package top.wcpe.mc.plugin.serverprobe.core.json

import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type

/**
 * 默认 JSON 适配器实现：后端复用 TabooLib 运行时自带的 `Configuration`（nightconfig），零额外依赖（ADR-14）。
 *
 * 用 [Type.JSON_MINIMAL]（紧凑单行、不美化），与既有指标/启动画像落盘格式同源——
 * 故把落盘序列化收口到本适配器后输出字节不变、`schemaVersion` 兼容自然保持。
 *
 * ## 集合与映射为什么单独编码
 * `Configuration.serialize` 会对着对象所属类逐字段 `setAccessible` 反射。对 `listOf(...)` 这类
 * Java 集合（ArrayList/UnmodifiableList）反射的是 `java.util` 的私有字段，JDK 17+ 的强封装会直接抛
 * `InaccessibleObjectException` —— 表现为 `core.subscribe` 回执整条失败（回执 `output` 就是 JSON 数组）。
 * 集合本身就是 JSON 数组，按数组语义写出既不反射、也不需要 `Configuration`（section 只能装对象）。
 *
 * 映射（Map）同理必须自己写：`ConfigurationSection.set(String, Object)` 把键里的 `.` 当成**层级分隔符**，
 * 于是 `{"plugins/Foo/config.yml": {...}}` 会被写成 `{"plugins/Foo/config": {"yml": {...}}}`。
 * 这在的 `applied` 映射上直接违约（配置重载规格 §3.7 要求键就是路径原文）；
 * JSON 对象的键按定义是字面量，不带任何路径语义，所以映射也必须绕开 `Configuration`。
 * 其余类型（数据类 / 基元）保持原有路径，输出字节不变。
 */
object ConfigJsonCodec : JsonCodec {

    override fun encode(value: Any?): String = when (value) {
        null -> "null"
        // 映射按 JSON 对象语义写出（键字面化），见类注释：绝不能走 `set(String, ...)`。
        is Map<*, *> -> buildJsonObject(value).toString()
        // 集合/数组必须单独处理：serialize 是对**元素对象**逐字段反射，而 `listOf(...)` 返回的是
        // Java 集合（ArrayList/UnmodifiableList），反射它们会去 setAccessible(`java.util` 的私有字段)，
        // 在 JDK 17+ 的强封装下直接抛 InaccessibleObjectException（core.subscribe 的回执因此整条失败）。
        // 集合本就是 JSON 数组，直接按数组写出，既不反射也不经过 Configuration（section 只能装对象）。
        is Collection<*> -> buildJsonArray(value).toString()
        is Array<*> -> buildJsonArray(value.asList()).toString()
        else -> Configuration.serialize(value, Type.JSON_MINIMAL).toString()
    }

    override fun parse(json: String): JsonObject =
        ConfigJsonObject(Configuration.loadFromString(json, Type.JSON_MINIMAL))

    /**
     * 按 JSON 数组语义拼接集合：元素递归，嵌套集合仍走本路径，绝不反射集合自身。
     *
     * 预分配容量：默认 `StringBuilder()` 只有 16 字符，写一帧 250 字符的 JSON 要扩容 4~5 次，
     * 每次都整段拷贝——这是编码路径上最大的单项分配来源。
     */
    private fun buildJsonArray(values: Collection<*>): StringBuilder =
        StringBuilder(2 + values.size * ELEMENT_CAPACITY_HINT).also { builder ->
            builder.append('[')
            var first = true
            for (element in values) {
                if (!first) builder.append(',')
                first = false
                appendJsonValue(builder, element)
            }
            builder.append(']')
        }

    /**
     * 按 JSON 对象语义拼接映射：**键一律按字面写出**，值递归。
     *
     * 键用 [appendJsonString] 完整转义：映射键可能来自第三方（如配置重载的配置路径），
     * 含引号或反斜杠时若不转义会直接产出非法 JSON。
     */
    private fun buildJsonObject(values: Map<*, *>): StringBuilder =
        StringBuilder(2 + values.size * ENTRY_CAPACITY_HINT).also { builder ->
            builder.append('{')
            var first = true
            for ((key, value) in values) {
                if (!first) builder.append(',')
                first = false
                appendJsonString(builder, key.toString())
                builder.append(':')
                appendJsonValue(builder, value)
            }
            builder.append('}')
        }

    /**
     * 写出单个 JSON 值。
     *
     * 关键：集合里嵌套的 **Map 也必须走 [buildJsonObject] 路线**，不能退回
     * `Configuration.serialize`——那条路同样是按字段反射，对 `HashMap` 会去
     * `setAccessible` 内部字段，在 JDK 17+ 上一样抛 `InaccessibleObjectException`；
     * 而且映射经 `Configuration` 写入时键里的 `.` 会被当成层级分隔符（见类注释）。
     * 只有真正的数据类（无 Map/集合语义）才交给 serialize。
     */
    private fun appendJsonValue(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is String -> appendJsonString(builder, value)
            is Number, is Boolean -> builder.append(value.toString())
            is Map<*, *> -> builder.append(buildJsonObject(value))
            is Collection<*> -> builder.append(buildJsonArray(value))
            is Array<*> -> builder.append(buildJsonArray(value.asList()))
            else -> builder.append(Configuration.serialize(value, Type.JSON_MINIMAL).toString())
        }
    }

    /**
     * 写出 JSON 字符串字面量（含转义）。
     *
     * ## 快路径：整段直接 append
     * 绝大多数键值（域名字段、路径、数字串）不含任何需转义字符。这里先扫一遍找"首个需转义位置"，
     * 找不到就一次 `append(CharSequence)` 完事——避免逐字符走 6 分支 `when`。
     * 逐字符处理的代价在编码热路径上很可观：每个字符一次 `when` + 一次 `append(Char)`。
     *
     * ## 控制字符用查表，不用 String.format
     * `"\\u%04x".format(code)` 单次开销在微秒级（格式化器解析 + 分配），
     * 而这里只是写 6 个 ASCII 字符，用十六进制查表即可。
     */
    private fun appendJsonString(builder: StringBuilder, value: String) {
        builder.append('"')
        val length = value.length
        val firstEscape = indexOfFirstEscape(value)
        if (firstEscape < 0) {
            builder.append(value)
            builder.append('"')
            return
        }
        builder.append(value, 0, firstEscape)
        var index = firstEscape
        while (index < length) {
            val character = value[index]
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (character.code < MIN_PRINTABLE) {
                    appendUnicodeEscape(builder, character.code)
                } else {
                    builder.append(character)
                }
            }
            index++
        }
        builder.append('"')
    }

    /** 返回首个需要转义的字符下标；没有则返回 -1（多数字符串走这条路）。 */
    private fun indexOfFirstEscape(value: String): Int {
        val length = value.length
        var index = 0
        while (index < length) {
            val character = value[index]
            if (character == '"' || character == '\\' || character.code < MIN_PRINTABLE) return index
            index++
        }
        return -1
    }

    /**
     * 写 `\u00XX`。
     *
     * 不用 `String.format("%04x")`——它对每次调用都做格式化器查找与分配，
     * 在编码热路径上是纯亏损。这里直接拼 4 个十六进制字符。
     */
    private fun appendUnicodeEscape(builder: StringBuilder, code: Int) {
        builder.append("\\u")
        builder.append(HEX_DIGITS[(code shr 12) and 0xF])
        builder.append(HEX_DIGITS[(code shr 8) and 0xF])
        builder.append(HEX_DIGITS[(code shr 4) and 0xF])
        builder.append(HEX_DIGITS[code and 0xF])
    }

    /** 可打印字符下界：低于它的控制字符必须写成 `\u00XX`。 */
    private const val MIN_PRINTABLE = 0x20

    /** 十六进制数字表（替代 `String.format`）。 */
    private val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    /** 单个数组元素的容量预估：短字符串 + 逗号。 */
    private const val ELEMENT_CAPACITY_HINT = 16

    /** 单个键值对的容量预估：键 + 冒号 + 值 + 逗号。 */
    private const val ENTRY_CAPACITY_HINT = 32
}

/**
 * 把 TabooLib [ConfigurationSection] 包装为库无关的只读 [JsonObject]，按键取字段委托给底层 section。
 */
internal class ConfigJsonObject(private val section: ConfigurationSection) : JsonObject {

    override fun getRaw(key: String): Any? = section.get(key)

    override fun getString(key: String, default: String): String = section.getString(key) ?: default

    override fun getInt(key: String, default: Int): Int = section.getInt(key, default)

    override fun getLong(key: String, default: Long): Long = section.getLong(key, default)

    override fun getDouble(key: String, default: Double): Double = section.getDouble(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean = section.getBoolean(key, default)

    override fun getStringList(key: String): List<String> = section.getStringList(key)

    override fun contains(key: String): Boolean = section.contains(key)

    override fun getObject(key: String): JsonObject? =
        section.getConfigurationSection(key)?.let { ConfigJsonObject(it) }
}
