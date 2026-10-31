package top.wcpe.mc.plugin.serverprobe.core.control

import top.wcpe.mc.plugin.serverprobe.core.json.JsonText

/**
 * 身份文件（`agent-identity.json`）的 JSON 编解码。
 *
 * ## 为什么手写而不走 [top.wcpe.mc.plugin.serverprobe.core.json.Json] 门面
 * ADR-14 把全项目 JSON 收口到 `Json` 门面，默认后端是 TabooLib 运行期的 nightconfig。本文件是 ADR-14
 * 允许的**有据例外**（与火焰图查看器数据同类）：
 *
 * 1. **单文档、定形结构**：只有 7 个固定字段，一读一写，没有动态键、没有嵌套业务对象，用不上通用树解析；
 * 2. **凭证零变形**：`credential` 必须原样往返（不透明），自有编解码可保证不被类型推断/格式化改写；
 * 3. **半写防御可测**：nightconfig 在裸单测类路径不可用（运行期被重定位），若走门面则"截断文件视为不存在"
 *    这条**安全关键**路径无法被单元测试覆盖；手写实现让该路径可测。
 *
 * 写入仍是本文件自有的定形拼接（字段顺序固定、凭证原样）；读取复用
 * [JsonText.parseObject] 这一份严格读取器——它与本文件原来的私有实现逐字节等价，抽出的目的只是让
 * 托管配置缓存与下发载荷这些同样"损坏即降级"的路径共享同一实现（见 `JsonText` 的说明）。
 *
 * ## 严格性
 * 只接受单个 JSON 对象（顶层必须是对象）；非法结构、超深嵌套、非对象顶层一律判为不可用，
 * 由调用方降级独立模式。解析不做任何"尽力修复"——半写文件绝不能被猜成半个身份。
 */
internal object IdentityJson {

    /** 序列化为紧凑单行 JSON，字段顺序与协议规格 §3.2 的示例一致。 */
    fun encode(identity: ControlPlaneIdentity): String = buildString {
        append('{')
        appendQuoted(KEY_SCHEMA_VERSION).append(':').append(identity.schemaVersion)
        append(',').appendQuoted(KEY_PROVIDER).append(':').appendQuoted(identity.provider)
        append(',').appendQuoted(KEY_ENDPOINT).append(':').appendQuoted(identity.endpoint)
        append(',').appendQuoted(KEY_INSTANCE_ID).append(':').appendQuoted(identity.instanceId)
        append(',').appendQuoted(KEY_CREDENTIAL).append(':').appendQuoted(identity.credential)
        append(',').appendQuoted(KEY_CAPABILITIES).append(':').append('[')
        identity.capabilities.forEachIndexed { index, capability ->
            if (index > 0) append(',')
            appendQuoted(capability)
        }
        append(']')
        append(',').appendQuoted(KEY_CREATED_AT).append(':').appendQuoted(identity.createdAt)
        append('}')
    }

    /**
     * 解析身份文件文本。
     *
     * @param text 文件全文。
     * @return [IdentityLoadResult.Loaded] 或 [IdentityLoadResult.Unusable]（不会返回 [IdentityLoadResult.Absent]）。
     */
    fun decode(text: String): IdentityLoadResult {
        val fields = runCatching { JsonText.parseObject(text) }.getOrNull()
            ?: return IdentityLoadResult.Unusable(IdentityUnusableReason.MALFORMED, "JSON 结构非法或文件被截断")
        return toIdentity(fields)
    }

    /** 把已解析的键值映射为身份对象，并做版本与必填字段校验。 */
    private fun toIdentity(fields: Map<String, Any?>): IdentityLoadResult {
        val version = (fields[KEY_SCHEMA_VERSION] as? Long)?.toInt()
            ?: return IdentityLoadResult.Unusable(IdentityUnusableReason.MALFORMED, "缺少 $KEY_SCHEMA_VERSION 或类型不是整数")
        if (version != ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION) {
            return IdentityLoadResult.Unusable(
                IdentityUnusableReason.UNKNOWN_SCHEMA_VERSION,
                "读到 schemaVersion=$version,探针只支持 ${ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION}",
            )
        }
        val required = listOf(KEY_PROVIDER, KEY_ENDPOINT, KEY_INSTANCE_ID, KEY_CREDENTIAL)
        val values = HashMap<String, String>(required.size)
        for (key in required) {
            val value = fields[key] as? String
            if (value.isNullOrBlank()) {
                return IdentityLoadResult.Unusable(IdentityUnusableReason.MISSING_FIELD, "缺少必要字段 $key 或其为空白")
            }
            values[key] = value
        }
        return IdentityLoadResult.Loaded(
            ControlPlaneIdentity(
                schemaVersion = version,
                provider = values.getValue(KEY_PROVIDER),
                endpoint = values.getValue(KEY_ENDPOINT),
                instanceId = values.getValue(KEY_INSTANCE_ID),
                credential = values.getValue(KEY_CREDENTIAL),
                // capabilities 缺失即"对端未声明"——按规格只用基础帧,不视为损坏
                capabilities = (fields[KEY_CAPABILITIES] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                // createdAt 是描述性字段,缺失不影响可用性(兼容对端裁剪)
                createdAt = fields[KEY_CREATED_AT] as? String ?: "",
            ),
        )
    }

    /** 按 JSON 规范转义后追加一个字符串字面量。 */
    private fun StringBuilder.appendQuoted(value: String): StringBuilder {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        return append('"')
    }

    /** 字段名常量（与协议规格 §3.2 完全一致，不得改写）。 */
    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_INSTANCE_ID = "instanceId"
    private const val KEY_CREDENTIAL = "credential"
    private const val KEY_CAPABILITIES = "capabilities"
    private const val KEY_CREATED_AT = "createdAt"
}
