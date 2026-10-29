package top.wcpe.mc.plugin.serverprobe.core.bridge.health

/**
 * 健康状态文本的脱敏与限长。
 *
 * ## 为什么需要
 * `health().getReason()` 与 dispatch 失败原因都来自**第三方代码**：它们可能把 JDBC URL、
 * HTTP 请求头或回调地址整段塞进 reason，而这段文本会进回执、进日志、进面板。
 * 不脱敏等于把凭据交给每一个能读 `core.business_status` 的人。
 *
 * ## 规则（本 spec 自持，core 内无既有实现）
 * 1. `token=<值>` / `password=<值>` / `authorization: <值>` 形态 → 值替换为 `***`；
 * 2. 含 URL 的文本先去掉 query（凭据通常在 query 里）；
 * 3. 最后按上限截断并补 `…`（单字符省略号，不占多字节）。
 *
 * ## 顺序不可交换
 * 必须先脱敏、后截断：反过来会把 `token=` 截掉一半，剩下的部分谁都认不出，
 * 却仍然泄漏了凭据的开头几位。截断**计数按字符**（`String.length`）而非字节，
 * 因为回执是 JSON 文本，长度约束针对字符数。
 */
object HealthTextSanitizer {

    /** 凭据键名 → 值替换目标。含 `=` 与 `:` 两种分隔形态。 */
    private val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
        // token=abc123 / token = abc123（值到空白、& 或引号为止）
        Regex("(?i)\\b(token)\\s*=\\s*[^\\s&\"'<>]+") to "${'$'}1=***",
        // password=abc123
        Regex("(?i)\\b(password)\\s*=\\s*[^\\s&\"'<>]+") to "${'$'}1=***",
        // authorization: Bearer abc123（值到行尾或引号为止，允许空格）
        Regex("(?i)\\b(authorization)\\s*:\\s*[^\"'<>\\r\\n]+") to "${'$'}1: ***",
        // 常见别名：secret / api_key / apikey / access_token
        Regex("(?i)\\b(secret|api[_-]?key|access[_-]?token)\\s*=\\s*[^\\s&\"'<>]+") to "${'$'}1=***",
    )

    /** 形如 `scheme://host/path?query` 的 URL 片段；只保留到 `?` 之前。 */
    private val URL_WITH_QUERY = Regex("(?i)\\b[a-z][a-z0-9+.\\-]*://[^\\s\"'<>]*\\?[^\\s\"'<>]*")

    /**
     * 脱敏并按上限截断。
     *
     * @param raw 原始文本；null 视为空串
     * @param maxChars 字符上限；`<= 0` 表示不截断（仅脱敏）
     * @return 可直接展示/落库的文本，永不为 null
     */
    fun sanitize(raw: String?, maxChars: Int): String {
        if (raw == null || raw.isEmpty()) return ""
        var text = stripUrlQuery(raw)
        SECRET_PATTERNS.forEach { (pattern, replacement) -> text = pattern.replace(text, replacement) }
        return truncate(text, maxChars)
    }

    /**
     * 仅按上限截断（用于结构化 `detailJson`：它可能是 JSON，改写其中内容会破坏结构）。
     *
     * @param raw 原始文本；null 视为空串
     * @param maxChars 字符上限；`<= 0` 表示不截断
     * @return 截断后的文本，永不为 null
     */
    fun truncateOnly(raw: String?, maxChars: Int): String = truncate(raw ?: "", maxChars)

    /**
     * 去掉文本中 URL 的 query 部分。
     *
     * 逐段处理而非整串替换：一条 reason 里可能嵌了多个 URL（如"回调 A 失败，回退到 B"），
     * 只处理第一个会把后面的凭据原样留下。
     */
    private fun stripUrlQuery(text: String): String =
        URL_WITH_QUERY.replace(text) { match ->
            val value = match.value
            val cut = value.indexOf('?')
            if (cut < 0) value else value.substring(0, cut)
        }

    /** 按字符上限截断并补省略号；已在上限内则原样返回。 */
    private fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        // 留出省略号的位置，保证结果总长不超过上限。
        return text.substring(0, maxChars - 1) + ELLIPSIS
    }

    /** 截断省略号（单字符，避免把长度算成多字节）。 */
    private const val ELLIPSIS = "…"
}
