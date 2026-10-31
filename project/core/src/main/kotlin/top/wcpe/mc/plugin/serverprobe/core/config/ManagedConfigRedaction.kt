package top.wcpe.mc.plugin.serverprobe.core.config

import java.util.Locale

/**
 * 托管生效值的**脱敏**(FR-33,协议规格 §3.4.3 的 `effective` 标注为"脱敏")。
 *
 * ## 边界
 * 托管配置面本身已不可能出现凭据：[ConfigTier] 把 `bridge` 整组与 `metrics` / `mcp` / `web` 的
 * 凭证键定为禁止远程档，那些键永远不会进入 [ManagedConfigStore]。仍要做一层脱敏，
 * 是因为**危险档里的"地址类"键也可能夹带密钥**（例如 `alert.channels.webhook.url` 的
 * `?token=...` 查询参数），一旦原样上报就等于把密钥抄进了控制面的库和前端页面。
 *
 * ## 两条规则，都可解释
 * 1. **按键名**：键路径的最后一段命中敏感词（token / secret / password / credential / api-key）时，
 *    整个值替换为 [MASK]。这是主规则，覆盖"值本身就是凭据"的情形。
 * 2. **按查询参数**：字符串值里形如 `?token=xxx` / `&secret=xxx` 的**参数值**遮掉，保留参数名与地址其余部分。
 *    这一条专门覆盖"地址里夹带密钥"（`alert.channels.webhook.url` 是最现实的例子）——
 *    只按键名判定会漏掉它（叶子键叫 `url`），而整条地址遮掉又会让控制面失去对账依据。
 *
 * 刻意**不**做"从任意字符串里识别密钥"的通用启发式：那会随机遮掉正常值（如 server-name），
 * 且无法解释给运维听。
 *
 * @see redact
 */
internal object ManagedConfigRedaction {

    /** 替换后的占位文本(与控制面展示约定一致)。 */
    const val MASK = "***"

    /** 敏感键名标记:命中即遮。 */
    private val SENSITIVE_MARKERS = listOf("token", "secret", "password", "passwd", "credential", "api-key", "apikey")

    /**
     * URL 查询串里的敏感参数:`?token=xxx` / `&secret=xxx` / `;api_key=xxx`。
     *
     * 只遮**参数值**,保留参数名与其余部分——控制面要据地址做对账,整条地址遮掉会让 `effective` 失去意义。
     */
    private val SENSITIVE_QUERY_PARAM = Regex(
        "(?i)([?&;](?:token|secret|password|passwd|credential|api[_-]?key)=)[^&#\\s]*",
    )

    /** 需要做查询串脱敏的字符串长度上限:配置里的地址都很短,超长串跳过以免正则开销失控。 */
    private const val MAX_SCAN_LENGTH = 2_048

    /**
     * 脱敏一份取值表(递归处理嵌套对象)。
     *
     * @param values 生效值。
     * @return 新表;敏感键的值被替换为 [MASK],其余原样(字符串会再做一次查询串脱敏)。
     */
    fun redact(values: Map<String, Any?>): Map<String, Any?> {
        val redacted = LinkedHashMap<String, Any?>(values.size)
        values.forEach { (key, value) -> redacted[key] = redactValue(key, value) }
        return redacted
    }

    /** 单个值:敏感键直接遮;嵌套对象递归;字符串做查询串脱敏;其余原样。 */
    private fun redactValue(key: String, value: Any?): Any? = when {
        isSensitive(key) -> MASK
        value is Map<*, *> -> {
            val nested = LinkedHashMap<String, Any?>(value.size)
            value.forEach { (childKey, childValue) -> nested[childKey.toString()] = redactValue(childKey.toString(), childValue) }
            nested
        }
        value is String -> redactQuerySecrets(value)
        else -> value
    }

    /** 把字符串里形如 `token=xxx` 的查询参数值遮掉。 */
    private fun redactQuerySecrets(value: String): String {
        if (value.length > MAX_SCAN_LENGTH || !value.contains('=')) {
            return value
        }
        return SENSITIVE_QUERY_PARAM.replace(value) { match -> match.groupValues[1] + MASK }
    }

    /** 键路径的最后一段是否命中敏感词(大小写不敏感,`-` / `_` 视为等价)。 */
    private fun isSensitive(key: String): Boolean {
        val leaf = key.substringAfterLast('.').lowercase(Locale.ROOT).replace('_', '-')
        return SENSITIVE_MARKERS.any { leaf.contains(it) }
    }
}
