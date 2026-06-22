package top.wcpe.mc.plugin.serverprobe.core.bridge

/**
 * 极简 JSON 工具(零第三方依赖,JDK 8;插件桥专用,见 [BridgeClient])。
 *
 * 探针约定不引第三方 JSON 库(与 PrometheusExporter / WebhookAlertChannel 一致)。插件桥的下行帧
 * (`command`)为**扁平字符串字段**对象(type/action/target/reason/requestId),无需通用解析器,
 * 故提供一个仅取顶层字符串字段值的提取器:扫描 `"key"` : `"value"`,支持基础转义反解。不处理嵌套对象/
 * 数组/数字/布尔——插件桥下行不需要(上行的结构化载荷由 Worker 侧 Go 解析,探针只发不收复杂结构)。
 */
object MiniJson {

    /**
     * 从扁平 JSON 文本中提取顶层字符串字段 [key] 的值;不存在或非字符串值返回空串。
     *
     * 仅识别形如 `"key":"value"` 的字符串字段(允许键值间空白),value 内的 `\"` / `\\` / `\n` 等转义按
     * JSON 规则反解。键名做精确匹配(带引号),避免子串误命中(如 key=server 命中 fromServer)。
     *
     * @param json JSON 文本。
     * @param key 顶层字段名。
     * @return 字段字符串值;缺失/非字符串时为空串。
     */
    // 按名查找键的线性扫描天然有多个跳出点(未命中返回空 / 命中返回值 / 跳过非字符串值续找),
    // 是惯用写法,拆分无益,故豁免循环跳转检查。
    @Suppress("LoopWithTooManyJumpStatements")
    fun getString(json: String, key: String): String {
        val needle = "\"$key\""
        var i = 0
        while (true) {
            val k = json.indexOf(needle, i)
            if (k < 0) return ""
            // 键后允许空白,随后必须是冒号
            var p = k + needle.length
            while (p < json.length && json[p].isWhitespace()) p++
            if (p >= json.length || json[p] != ':') {
                i = k + needle.length
                continue
            }
            p++
            while (p < json.length && json[p].isWhitespace()) p++
            if (p >= json.length || json[p] != '"') {
                // 非字符串值(数字/对象等):本提取器不处理,继续找下一处同名键(理论上罕见)
                i = p
                continue
            }
            return readJsonString(json, p)
        }
    }

    /**
     * 从位置 [start](指向起始双引号)读一个 JSON 字符串字面量,返回反解转义后的内容。
     *
     * @param s 文本。
     * @param start 起始双引号下标。
     * @return 字符串内容(已反解转义)。
     */
    private fun readJsonString(s: String, start: Int): String {
        val sb = StringBuilder()
        var p = start + 1
        while (p < s.length) {
            when (s[p]) {
                '"' -> return sb.toString()
                '\\' -> p = appendEscape(sb, s, p)
                else -> {
                    sb.append(s[p])
                    p++
                }
            }
        }
        return sb.toString()
    }

    /**
     * 反解从 [p](指向反斜杠)起的一个转义序列,追加到 [sb],返回消费后的新位置。
     * 反斜杠在串尾则返回串长以结束读取;`\uXXXX` 交 [appendUnicodeEscape] 处理。
     */
    private fun appendEscape(sb: StringBuilder, s: String, p: Int): Int {
        if (p + 1 >= s.length) return s.length // 反斜杠是最后字符:结束
        when (val e = s[p + 1]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'b' -> sb.append('\b')
            'f' -> sb.append('')
            'u' -> return appendUnicodeEscape(sb, s, p)
            else -> sb.append(e)
        }
        return p + 2
    }

    /**
     * 反解 `\uXXXX`:成功则追加字符并消费 6 个位置;失败(越界/非法 hex)仅消费 2 个、不追加(保持原逻辑)。
     */
    private fun appendUnicodeEscape(sb: StringBuilder, s: String, p: Int): Int {
        if (p + 5 < s.length) {
            val code = s.substring(p + 2, p + 6).toIntOrNull(16)
            if (code != null) {
                sb.append(code.toChar())
                return p + 6
            }
        }
        return p + 2
    }
}
