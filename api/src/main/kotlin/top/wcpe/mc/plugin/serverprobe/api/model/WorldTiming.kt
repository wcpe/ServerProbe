package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 单个世界的加载耗时(FR1.3)。
 *
 * @property name 世界名称。
 * @property loadMs 该世界加载(含 spawn-chunk 预加载)耗时(毫秒)。
 */
data class WorldTiming(
    val name: String,
    val loadMs: Long
)
