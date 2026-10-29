package top.wcpe.mc.plugin.serverprobe.core.bridge.manifest

import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import java.security.MessageDigest

/**
 * manifest 内容指纹（spec §3.1）。
 *
 * ## 为什么需要指纹
 * 面板要展示 N 个业务域的能力清单，而单域 manifest 可能有几 KB。若每次查询都全量传，
 * 「100 个域」就是几百 KB 的回执，还得每帧分片。指纹让下游先拿 16 位十六进制串比对：
 * **只有变化时才去拉全量**。
 *
 * ## 规范化是硬要求
 * 指纹必须**稳定**——同一份语义内容在任何调用顺序、任何 JVM 上都算出同一个值。
 * 因此序列化前做三件事（spec §3.1）：
 *
 * 1. **动作按名称排序**：Provider 的 manifest 是每次现算的，声明顺序可能来自
 *    `HashMap` 遍历或插件加载顺序；不排序会让指纹在内容未变时抖动。
 * 2. **对象键按字典序**：同上，`Map` 迭代顺序不是内容。
 *    注意 `errorCodes` 是**集合**（无顺序语义），也按字典序排；
 *    而 `actions` 排序后仍写成**数组**（它是有序列表，元素本身已按名排好）。
 * 3. **去除空白差异**：手写 JSON 拼装（不是序列化库），字段之间不带任何空格/换行，
 *    因此「谁写的是紧凑格式、谁写了缩进」不影响指纹。`inputSchema`/`outputSchema`
 *    这类**嵌套 JSON 文本**只去掉首尾空白后原样参与（理由见下）。
 *
 * ## 为什么自己拼 JSON 而不用序列化库
 * 两个理由：其一，core 的单测类路径没有 nightconfig（`Json.encode` 会 `NoClassDefFoundError`），
 * 而指纹逻辑必须能被单测直接覆盖；其二，**键序必须由本类唯一决定**，
 * 交给序列化库意味着键序跟着库的实现走，换库就换指纹。
 *
 * ## 为什么不解析 inputSchema/outputSchema
 * 它们是 Provider 给的 JSON Schema 文本，探针只透传（见 `BusinessProvider` 的说明）。
 * 解析它们等于替 Provider 判定「写得对不对」，而一个 schema 写坏的动作其执行路径往往仍然正常。
 * 代价是「同一 schema 的不同缩进写法算不同指纹」——这是可接受的：
 * 指纹只需要「内容变了就变」，宁可多报变化（下游多拉一次全量），也不能漏报（面板显示旧规格）。
 *
 * ## 稳定性保证
 * [MessageDigest.getInstance] 每次都取新实例（`MessageDigest` 非线程安全），
 * 本对象无可变状态，可在任意线程并发调用。
 */
object ManifestDigest {

    /** 指纹长度（十六进制字符数）。spec §3.1 定死 16 位，即 sha256 前 8 字节。 */
    const val DIGEST_CHARS = 16

    /**
     * 计算一份清单的内容指纹。
     *
     * @param manifest 已（必要时）降级后的清单；null 返回 null
     * @return 16 位小写十六进制指纹；manifest 为 null 时返回 null
     */
    fun of(manifest: DomainManifest?): String? {
        if (manifest == null) return null
        return runCatching { sha256Hex(canonicalJson(manifest)) }.getOrNull()
    }

    /**
     * 规范化 JSON 文本（**公开供单测直接断言键序与内容**，生产只用 [of]）。
     *
     * 形态是一个有固定键序的对象：
     * `{"actions":[...],"apiVersion":N,"attributes":{...},"description":"...","displayName":"...","domain":"..."}`
     * ——顶层键按字典序排列，与 [DomainManifest] 的字段声明顺序无关。
     *
     * @param manifest 清单
     * @return 紧凑 JSON（无空白差异）
     */
    fun canonicalJson(manifest: DomainManifest): String {
        val out = StringBuilder(CAPACITY_HINT)
        out.append('{')
        // ① actions：先按名称排序（内容语义），再逐个规范化。
        out.append("\"actions\":[")
        val sortedActions = manifest.actions
            .map { it ?: EMPTY_ACTION }
            .sortedWith(compareBy({ it.name }, { it.description }, { it.risk.name }))
        sortedActions.forEachIndexed { index, spec ->
            if (index > 0) out.append(',')
            appendAction(out, spec)
        }
        out.append(']')
        // ② apiVersion / attributes / description / displayName / domain：按字典序追加。
        out.append(",\"apiVersion\":").append(manifest.apiVersion)
        out.append(",\"attributes\":")
        appendAttributes(out, manifest.attributes)
        out.append(",\"description\":")
        appendString(out, manifest.description)
        out.append(",\"displayName\":")
        appendString(out, manifest.displayName)
        out.append(",\"domain\":")
        appendString(out, manifest.domain)
        out.append('}')
        return out.toString()
    }

    /**
     * 单个动作的规范化形态，键按字典序：
     * `description` / `errorCodes` / `idempotent` / `inputSchema` / `name` / `outputSchema` / `risk`。
     *
     * `risk` 取 [ActionSpec.getRisk]（生效值）而**不是** `getDeclaredRisk()`：
     * 后者是"是否声明过"的诊断信息，未声明时在规范化文本里会缺一个字段，
     * 让「显式写 WRITE」与「没写」算出不同指纹——而对下游而言两者行为完全一致，不该报变化。
     */
    private fun appendAction(out: StringBuilder, spec: ActionSpec) {
        out.append('{')
        out.append("\"description\":")
        appendString(out, spec.description)
        out.append(",\"errorCodes\":[")
        // errorCodes 是 Set（无顺序语义）：排序后写数组，避免 HashSet 迭代序污染指纹。
        spec.errorCodes.sorted().forEachIndexed { index, code ->
            if (index > 0) out.append(',')
            appendString(out, code)
        }
        out.append(']')
        out.append(",\"idempotent\":").append(spec.idempotent)
        out.append(",\"inputSchema\":")
        appendString(out, spec.inputSchema.trim())
        out.append(",\"name\":")
        appendString(out, spec.name)
        out.append(",\"outputSchema\":")
        appendString(out, spec.outputSchema.trim())
        out.append(",\"risk\":")
        appendString(out, spec.risk.name)
        out.append('}')
    }

    /** attributes 是 Map（无顺序语义）：键按字典序输出。 */
    private fun appendAttributes(out: StringBuilder, attributes: Map<String, String>) {
        out.append('{')
        attributes.entries
            .map { it.key to it.value }
            .sortedBy { it.first }
            .forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                appendString(out, key)
                out.append(':')
                appendString(out, value)
            }
        out.append('}')
    }

    /**
     * 追加一个 JSON 字符串字面量（自动转义）。
     *
     * 需要转义控制字符而非只做 `"`/`\`：schema 文本里可能出现换行，
     * 不转义就会把紧凑单行格式破坏成多行，进而让「去空白」形同虚设。
     */
    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                // 其余控制字符与 DEL 一并转义：它们既无文本意义，又会破坏单行格式。
                ch < ' ' || ch == '\u007F' -> out.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }

    /** sha256 → 前 [DIGEST_CHARS] 位十六进制小写。 */
    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.substring(0, DIGEST_CHARS)
    }

    /** 小写十六进制字母表。 */
    private val HEX = "0123456789abcdef".toCharArray()

    /** 清单里出现 null 动作时的占位（契约要求非 null，但外部实现可能违约）。 */
    private val EMPTY_ACTION: ActionSpec = ActionSpec.of("", "", null, false, null, null, null)

    /** 编码缓冲初始容量：典型 manifest 一两百字节，给个不会频繁扩容的起点。 */
    private const val CAPACITY_HINT = 256
}
