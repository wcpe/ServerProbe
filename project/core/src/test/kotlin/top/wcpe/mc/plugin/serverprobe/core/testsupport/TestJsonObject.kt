package top.wcpe.mc.plugin.serverprobe.core.testsupport

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject

/**
 * Map 支撑的只读 [JsonObject] 替身。
 *
 * 单测 classpath 里没有运行期 JSON 后端（默认实现依赖 TabooLib 内嵌的 nightconfig），故下行帧在单测中
 * 直接用本替身构造。语义与生产实现一致：取不到键时回落调用方给出的默认值。
 *
 * @param values 键 → 值；嵌套对象请再传一个 [testJsonObject] 或直接用 Map（[getObject] 会按需包装）。
 * @return 只读 JSON 对象视图。
 */
internal fun testJsonObject(values: Map<String, Any?>): JsonObject = MapJsonObject(values)

/** [testJsonObject] 的实现。 */
private class MapJsonObject(private val values: Map<String, Any?>) : JsonObject {

    override fun getRaw(key: String): Any? = values[key]

    override fun getString(key: String, default: String): String = values[key]?.toString() ?: default

    override fun getInt(key: String, default: Int): Int = (values[key] as? Number)?.toInt() ?: default

    override fun getLong(key: String, default: Long): Long = (values[key] as? Number)?.toLong() ?: default

    override fun getDouble(key: String, default: Double): Double = (values[key] as? Number)?.toDouble() ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    @Suppress("UNCHECKED_CAST")
    override fun getStringList(key: String): List<String> = (values[key] as? List<*>)?.map { it.toString() } ?: emptyList()

    override fun contains(key: String): Boolean = values.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun getObject(key: String): JsonObject? = (values[key] as? Map<String, Any?>)?.let(::MapJsonObject)

    /** 替身本就以 Map 承载，直接给出防御性拷贝（嵌套 section 在替身里就以 Map 表达）。 */
    override fun toMap(): Map<String, Any?> = LinkedHashMap(values)
}
