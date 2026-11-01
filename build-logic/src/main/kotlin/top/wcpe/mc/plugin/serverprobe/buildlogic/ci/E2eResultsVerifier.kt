package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import java.io.File

/**
 * 校验 mc-testkit 的 E2E 结果文件是否全部 PASS、期望场景是否都有结果。
 *
 * 结果文件名形如 `<scenario>.properties`，`scenario` 是 kebab-case 场景名（与 harness 内
 * 判定 `MC_TESTKIT_E2E_SCENARIO` 的字面量一致，不由 Gradle 任务名推导）。
 *
 * 本对象是原先 `.github/workflows/e2e.yml` 内联脚本的等价实现：
 * 跳过注释与空行、按第一个等号切分、非 PASS 与「场景根本没产出结果文件」都判失败。
 */
object E2eResultsVerifier {

    /** 结果文件后缀。 */
    private const val SUFFIX = ".properties"

    /** 判定字段。 */
    private const val KEY_STATUS = "status"
    private const val VALUE_PASS = "PASS"

    /** 一次校验的输出与结论。 */
    data class Outcome(val lines: List<String>, val failed: Boolean)

    /**
     * 校验结果目录。
     *
     * @param resultsDir 结果目录（`build/mc-testkit/results`）；为 null 时视作目录不存在。
     * @param expected 期望出现的场景名。
     * @return 打印行与是否失败。
     */
    fun verify(resultsDir: File?, expected: List<String>): Outcome {
        val lines = ArrayList<String>()
        var failed = false
        val present = LinkedHashMap<String, Map<String, String>>()

        val files = resultsDir?.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
        files.forEach { file ->
            val text = file.readText()
            val props = parseProperties(text)
            val name = file.name.removeSuffix(SUFFIX)
            present[name] = props
            if (props[KEY_STATUS] == VALUE_PASS) {
                lines += "[PASS] $name"
            } else {
                lines += "[FAIL] $name -> status=${repr(props[KEY_STATUS])}"
                lines += text.trimEnd()
                failed = true
            }
        }

        // 清单没传时不能让校验形同虚设：空清单下没有一个场景会被要求出结果，
        // 「所有场景都没跑」与「全部通过」在结果上无法区分（旧的内联脚本这里会直接 KeyError）。
        if (expected.isEmpty()) {
            lines += "[错误] 期望场景清单为空：必须通过 -PexpectedScenarios 或环境变量 EXPECTED_SCENARIOS 给出"
            failed = true
        }

        // 场景根本没产出结果文件（如服务端未能启动）同样判失败，不能静默放过
        expected.filterNot { present.containsKey(it) }.forEach { scenario ->
            lines += "[缺失] 未找到结果文件: $scenario"
            failed = true
        }

        lines += "---"
        lines += "结果文件 ${present.size} 个，期望场景 ${expected.size} 个"
        return Outcome(lines, failed)
    }

    /**
     * 解析 java properties 风格的文本（与内联脚本同口径：跳过注释与空行，按第一个等号切分）。
     *
     * @param text 文件内容。
     * @return 键值表（后出现的同名键覆盖前者）。
     */
    fun parseProperties(text: String): Map<String, String> {
        val props = LinkedHashMap<String, String>()
        text.lines().forEach { rawLine ->
            val stripped = rawLine.trim()
            if (stripped.isEmpty() || stripped.startsWith("#") || !stripped.contains('=')) {
                return@forEach
            }
            val key = stripped.substringBefore('=').trim()
            val value = stripped.substringAfter('=').trim()
            props[key] = value
        }
        return props
    }

    /** 与原脚本 `%r` 语义对齐的展示：缺失为 `None`，否则带单引号。 */
    private fun repr(value: String?): String = if (value == null) "None" else "'$value'"
}
