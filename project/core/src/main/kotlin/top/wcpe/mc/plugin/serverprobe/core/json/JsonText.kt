package top.wcpe.mc.plugin.serverprobe.core.json

/**
 * 极简**严格** JSON 文本读写（零第三方依赖、不依赖 TabooLib 运行期）。
 *
 * ## 为什么它不是 ADR-14 的普通调用点
 * ADR-14 把全项目 JSON 收口到 [Json] 门面，默认后端是 TabooLib 运行期的 nightconfig。本文件的定位与
 * `IdentityJson` 相同，是**有据例外**：
 *
 * 1. **半写/损坏防御必须可被单测覆盖**：nightconfig 在裸单测类路径不可用（运行期被重定位），
 *    任何"截断文件 → 视为不存在"这类**安全/健壮性关键**路径若只能走门面，就只能靠真机验证。
 *    托管配置缓存（`managed-cache.json`）与 `config_push` 载荷都属此类。
 * 2. **单文档、定形结构**：一读一写，不需要通用对象映射，用不上反射式反序列化。
 * 3. **字节可控**：自有实现保证键顺序与数值形态稳定，便于人工比对与逐字节回归。
 *
 * 因此 **本文件只被两类调用点使用**：身份/缓存/下发载荷的严格解析（生产），以及单测里替换
 * [Json.codec] 用的适配器（测试）。帧编码等其它路径仍走 [Json] 门面。
 *
 * ## 严格性
 * 只接受标准 JSON：对象键必须带双引号，不接受注释、单引号、尾随逗号。解析失败一律抛
 * [IllegalArgumentException]，由调用方按各自语义降级（缓存 → 无缓存；载荷 → 中文拒绝原因）。
 */
internal object JsonText {

    /** 最大嵌套深度：这几类文档都是浅结构，超深只可能是构造出来的坏数据，直接判非法。 */
    private const val MAX_DEPTH = 8

    /**
     * 解析一份 JSON 文档，顶层必须是对象。
     *
     * @param text JSON 文本。
     * @return 键值映射（值为 Map / List / String / Long / Double / Boolean / null）。
     * @throws IllegalArgumentException 结构非法或顶层不是对象。
     */
    fun parseObject(text: String): Map<String, Any?> = Reader(text).readDocument()

    /**
     * 把 Map / List / 标量编码为紧凑单行 JSON。
     *
     * @param value 待编码值。
     * @return JSON 文本。
     */
    fun encode(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    /** 递归追加一个 JSON 值。 */
    private fun append(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is Map<*, *> -> appendObject(builder, value)
            is Iterable<*> -> appendArray(builder, value)
            is Boolean, is Int, is Long, is Short, is Byte -> builder.append(value.toString())
            is Double -> builder.append(numberText(value))
            is Float -> builder.append(numberText(value.toDouble()))
            is String -> appendQuoted(builder, value)
            // 其余类型（枚举等）按字符串写出：缓存/载荷里不会出现，出现时也宁可留下可读文本而非抛错
            else -> appendQuoted(builder, value.toString())
        }
    }

    /** 追加对象：键保持原顺序，键必须是字符串形式。 */
    private fun appendObject(builder: StringBuilder, value: Map<*, *>) {
        builder.append('{')
        var first = true
        value.forEach { (key, item) ->
            if (!first) builder.append(',')
            first = false
            appendQuoted(builder, key.toString())
            builder.append(':')
            append(builder, item)
        }
        builder.append('}')
    }

    /** 追加数组。 */
    private fun appendArray(builder: StringBuilder, value: Iterable<*>) {
        builder.append('[')
        var first = true
        value.forEach { item ->
            if (!first) builder.append(',')
            first = false
            append(builder, item)
        }
        builder.append(']')
    }

    /** 浮点文本：去掉 `NaN`/`Infinity` 等非 JSON 形态（写出去会让控制面解析失败）。 */
    private fun numberText(value: Double): String = when {
        value.isNaN() -> "null"
        value == Double.POSITIVE_INFINITY -> "null"
        value == Double.NEGATIVE_INFINITY -> "null"
        else -> value.toString()
    }

    /** 按 JSON 规范转义后追加一个字符串字面量。 */
    private fun appendQuoted(builder: StringBuilder, value: String) {
        builder.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (ch < ' ') builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
            }
        }
        builder.append('"')
    }

    /**
     * 极简严格 JSON 读取器：只负责"文本 → 键值映射"。
     *
     * 支持对象、数组、字符串（含转义）、整数/小数、布尔、null；其余（注释、单引号、尾随逗号）一律非法。
     */
    private class Reader(private val text: String) {

        /** 当前读取位置。 */
        private var pos = 0

        /** 读取整份文档：顶层必须是对象，且对象之后不得再有内容。 */
        fun readDocument(): Map<String, Any?> {
            skipWhitespace()
            val root = readValue(0)
            skipWhitespace()
            require(pos == text.length) { "对象后存在多余内容" }
            require(root is Map<*, *>) { "顶层不是 JSON 对象" }
            @Suppress("UNCHECKED_CAST")
            return root as Map<String, Any?>
        }

        /** 读取一个值，按首字符分派。 */
        private fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "嵌套过深" }
            skipWhitespace()
            require(pos < text.length) { "意外结束" }
            return when (val ch = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(ch == '-' || ch.isDigit()) { "非法字符 '$ch'" }
                    readNumber()
                }
            }
        }

        /** 读取对象；键必须带双引号。 */
        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("对象内缺少逗号")
                }
            }
        }

        /** 读取数组。 */
        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("数组内缺少逗号")
                }
            }
        }

        /** 读取字符串字面量并还原转义。 */
        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                require(pos < text.length) { "字符串未闭合" }
                when (val ch = text[pos++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    else -> {
                        require(ch >= ' ') { "字符串内出现未转义控制字符" }
                        builder.append(ch)
                    }
                }
            }
        }

        /** 读取反斜杠后的转义序列。 */
        private fun readEscape(): Char {
            require(pos < text.length) { "转义序列未完成" }
            return when (val ch = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> throw IllegalArgumentException("非法转义 \\$ch")
            }
        }

        /** 读取 `\uXXXX` 转义（仅基本多文种平面）。 */
        private fun readUnicodeEscape(): Char {
            require(pos + UNICODE_ESCAPE_LENGTH <= text.length) { "\\u 转义不足 4 位" }
            val hex = text.substring(pos, pos + UNICODE_ESCAPE_LENGTH)
            pos += UNICODE_ESCAPE_LENGTH
            return hex.toInt(HEX_RADIX).toChar()
        }

        /** 读取数字：含小数点或指数按 Double，否则按 Long（版本号与整型字段依赖 Long）。 */
        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in NUMERIC_EXTRA_CHARS)) pos++
            val literal = text.substring(start, pos)
            require(literal.isNotEmpty() && literal != "-") { "非法数字" }
            return if (literal.any { it == '.' || it == 'e' || it == 'E' }) {
                literal.toDoubleOrNull() ?: throw IllegalArgumentException("非法小数")
            } else {
                literal.toLongOrNull() ?: throw IllegalArgumentException("非法整数")
            }
        }

        /** 读取 true / false / null 字面量。 */
        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, pos)) { "非法字面量" }
            pos += literal.length
            return value
        }

        /** 断言当前位置为 [expected] 并前进。 */
        private fun expect(expected: Char) {
            require(peek() == expected) { "期望 '$expected'" }
            pos++
        }

        /** 取当前字符（越界返回 NUL，交由调用方的 require 处理）。 */
        private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        /** 跳过 JSON 空白（空格、制表符、换行、回车）。 */
        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }

    /** `\uXXXX` 的十六进制位数。 */
    private const val UNICODE_ESCAPE_LENGTH = 4

    /** 十六进制基数。 */
    private const val HEX_RADIX = 16

    /** 数字中除数字字符外允许出现的字符（符号、小数点、指数）。 */
    private const val NUMERIC_EXTRA_CHARS = "+-.eE"
}
