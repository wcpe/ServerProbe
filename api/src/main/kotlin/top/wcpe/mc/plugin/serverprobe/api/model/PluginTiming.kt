package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 单个插件的 onEnable 耗时(FR1.2,慢插件榜数据项)。
 *
 * @property name 插件名称。
 * @property enableMs 该插件 onEnable 耗时(毫秒)。
 */
data class PluginTiming(
    val name: String,
    val enableMs: Long
)
