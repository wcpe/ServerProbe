package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * 将 E2E 判定原子写入 mc-testkit 结果文件。
 *
 * 除原有的 `status` / `message` 外，结果文件还承载断言汇总：`assertions.total`、`assertions.passed`、
 * `assertions.failed`、`assertions.failed.names`。`status=PASS` 只由“逐项断言全部成立”得出，
 * 避免此前“场景跑完即通过”的假阳性。
 *
 * 计数语义（去重）：断言按名字登记，同名断言重复登记只保留最后一次（通过与否与 detail 都覆盖），
 * `assertions.total` 统计去重后的断言名数量，因此恒有 `total = passed + failed`。
 * 选择去重而非累加，是因为同一断言的重复登记（重试、多轮 fixture 轮询）代表同一个不变量，
 * 累加会让“1 条断言失败”在结果里放大成几十条，CI 无法据此定位实际破损的不变量。
 */
class ScenarioResultWriter(private val resultFile: File) {

    /**
     * 断言登记表，保持登记顺序，便于结果文件与日志稳定可比。
     *
     * 线程安全选择：fixture 线程与主线程都会登记断言，这里用私有锁 + LinkedHashMap 而不是 ConcurrentHashMap
     * —— 后者不保留迭代顺序（失败名列表会随哈希序抖动），而“同名覆盖 + 整体读快照”本身就需要一段原子区间，
     * synchronized 表达这种语义比逐操作原子更直白。
     */
    private val assertionLock = Any()
    private val assertions = LinkedHashMap<String, Assertion>()

    /**
     * 记录一条断言结果。
     *
     * @param name 断言名（简短、稳定、可被 CI 检索）
     * @param condition 是否为真
     * @param detail 失败时附带的实际值/上下文，可为空
     * @return condition 本身，便于调用处 `if (!assertTrue(...)) return`
     */
    fun assertTrue(name: String, condition: Boolean, detail: String = ""): Boolean {
        synchronized(assertionLock) { assertions[name] = Assertion(condition, detail) }
        return condition
    }

    /**
     * 语义糖：断言为假才通过。
     *
     * @return 断言是否成立（即 `!condition`），便于调用处 `if (!assertFalse(...)) return`
     */
    fun assertFalse(name: String, condition: Boolean, detail: String = ""): Boolean =
        assertTrue(name, !condition, detail)

    /**
     * 收尾：把所有断言汇总成 PASS/FAIL 并落盘。
     *
     * - 有失败 → `status=FAIL`，`message` 为给定文案 + 失败断言名（含 detail）
     * - 零断言且 [allowZeroAssertions] 为 false → `status=FAIL`
     * - 其余 → `status=PASS`，`message` 用给定文案
     *
     * ## 为什么零断言要判失败
     * “断言前缀写错”时证据键全会落进 details，一条断言也不会被登记——若零断言当 PASS，
     * 这种情形就会以“通过”收场，与“PASS 只由逐项断言全部成立得出”的承诺相反。
     * 因此默认要求至少一条断言；确实不登记断言的场景（事件驱动类）由调用方显式 [pass]。
     *
     * 多次调用幂等覆盖写，结果文件始终是最后一次调用的内容。
     *
     * @param message 通过时的文案（失败时会被拼接失败断言名）。
     * @param details 附加详情（不参与判定）。
     * @param allowZeroAssertions 是否允许“一条断言也没有”也算通过。
     */
    fun finish(message: String, details: Map<String, String> = emptyMap(), allowZeroAssertions: Boolean = false) {
        val snapshot = snapshot()
        val failed = failedNames(snapshot)
        when {
            failed.isNotEmpty() ->
                write(STATUS_FAIL, failureMessage(message, snapshot, failed), withSummary(details, snapshot, failed))
            snapshot.isEmpty() && !allowZeroAssertions ->
                write(STATUS_FAIL, zeroAssertionMessage(message), withSummary(details, snapshot, failed))
            else -> write(STATUS_PASS, message, withSummary(details, snapshot, failed))
        }
    }

    /**
     * 事件驱动场景的收尾：这类场景不登记逐项断言（判定在 fixture 的事件序列里）。
     *
     * 走该入口即显式声明“允许零断言”；有登记断言时行为与 [finish] 完全一致。
     */
    fun pass(message: String, details: Map<String, String>) = finish(message, details, allowZeroAssertions = true)

    /** 兼容既有调用点签名：无条件写 FAIL，断言汇总与附加详情（如取证判据）一并落盘。 */
    fun fail(message: String, details: Map<String, String> = emptyMap()) {
        val snapshot = snapshot()
        write(STATUS_FAIL, message, withSummary(details, snapshot, failedNames(snapshot)))
    }

    /** 断言表的原子快照，避免落盘过程中被并发登记打断。 */
    private fun snapshot(): Map<String, Assertion> = synchronized(assertionLock) { LinkedHashMap(assertions) }

    private fun failedNames(snapshot: Map<String, Assertion>): List<String> =
        snapshot.entries.filter { !it.value.passed }.map { it.key }

    /** 零断言时的失败文案：明确指向“断言前缀/判定未登记”，而不是让结果看起来像跑完了。 */
    private fun zeroAssertionMessage(message: String): String =
        "$message（失败断言: 未登记任何断言——判定逻辑或断言前缀可能写错，不允许零断言通过）"

    /** 失败文案列出失败断言名；detail 非空时附在括号里，便于直接看出实际值。 */
    private fun failureMessage(message: String, snapshot: Map<String, Assertion>, failed: List<String>): String {
        val described = failed.joinToString(", ") { name ->
            val detail = snapshot[name]?.detail.orEmpty()
            if (detail.isEmpty()) name else "$name($detail)"
        }
        return "$message（失败断言: $described）"
    }

    /** 汇总断言计数与失败名；写在调用方 details 之后，防止被同名键覆盖。 */
    private fun withSummary(
        details: Map<String, String>,
        snapshot: Map<String, Assertion>,
        failed: List<String>,
    ): Map<String, String> {
        val merged = LinkedHashMap(details)
        merged[KEY_ASSERTIONS_TOTAL] = snapshot.size.toString()
        merged[KEY_ASSERTIONS_PASSED] = (snapshot.size - failed.size).toString()
        merged[KEY_ASSERTIONS_FAILED] = failed.size.toString()
        merged[KEY_ASSERTIONS_FAILED_NAMES] = failed.joinToString(FAILED_NAME_SEPARATOR)
        return merged
    }

    private fun write(status: String, message: String, details: Map<String, String>) {
        resultFile.parentFile?.mkdirs()
        val temporary = File.createTempFile("result-", ".tmp", resultFile.parentFile)
        try {
            temporary.outputStream().use { output ->
                Properties().apply {
                    details.forEach { (key, value) -> setProperty(key, value) }
                    setProperty("status", status)
                    setProperty("message", message)
                    store(output, "mc-testkit E2E 结果")
                }
            }
            moveAtomically(temporary)
        } finally {
            // 正常路径上临时文件已被 move 走，这里只清理失败残留。
            temporary.delete()
        }
    }

    private fun moveAtomically(temporary: File) {
        try {
            Files.move(temporary.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 单条断言结果：是否成立，以及登记时附带的上下文。 */
    private class Assertion(val passed: Boolean, val detail: String)

    private companion object {
        private const val STATUS_PASS = "PASS"
        private const val STATUS_FAIL = "FAIL"
        private const val KEY_ASSERTIONS_TOTAL = "assertions.total"
        private const val KEY_ASSERTIONS_PASSED = "assertions.passed"
        private const val KEY_ASSERTIONS_FAILED = "assertions.failed"
        private const val KEY_ASSERTIONS_FAILED_NAMES = "assertions.failed.names"
        private const val FAILED_NAME_SEPARATOR = ","
    }
}
