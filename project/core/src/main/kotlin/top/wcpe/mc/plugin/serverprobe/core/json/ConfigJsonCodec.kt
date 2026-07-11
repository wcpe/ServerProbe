package top.wcpe.mc.plugin.serverprobe.core.json

import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type

/**
 * 默认 JSON 适配器实现：后端复用 TabooLib 运行时自带的 `Configuration`（nightconfig），零额外依赖（ADR-14）。
 *
 * 用 [Type.JSON_MINIMAL]（紧凑单行、不美化），与既有指标/启动画像落盘格式同源——
 * 故把落盘序列化收口到本适配器后输出字节不变、`schemaVersion` 兼容自然保持。
 */
object ConfigJsonCodec : JsonCodec {

    override fun encode(value: Any?): String = when (value) {
        // 动态键值用「空 Configuration + 逐键 set」构造(serialize 是按字段反射、不适用于 Map);
        // 数据类等走 serialize 反射。两路都以 JSON_MINIMAL 紧凑单行输出。
        null -> "null"
        is Map<*, *> -> Configuration.empty(Type.JSON_MINIMAL).also { fillSection(it, value) }.toString()
        else -> Configuration.serialize(value, Type.JSON_MINIMAL).toString()
    }

    override fun parse(json: String): JsonObject =
        ConfigJsonObject(Configuration.loadFromString(json, Type.JSON_MINIMAL))

    /** 把 Map 逐键写入 section,嵌套 Map 递归建子 section,其余值直接 set。 */
    private fun fillSection(section: ConfigurationSection, map: Map<*, *>) {
        for ((k, v) in map) {
            val key = k.toString()
            if (v is Map<*, *>) {
                fillSection(section.createSection(key), v)
            } else {
                section.set(key, v)
            }
        }
    }
}

/**
 * 把 TabooLib [ConfigurationSection] 包装为库无关的只读 [JsonObject]，按键取字段委托给底层 section。
 */
internal class ConfigJsonObject(private val section: ConfigurationSection) : JsonObject {

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
