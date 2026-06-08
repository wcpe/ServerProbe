package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 单个内存池的使用指标。
 *
 * 对应 JVM 内存管理中的一个内存池(如 Eden、Survivor、Old Gen、Metaspace 等),
 * 数据来源于 `java.lang.management.MemoryPoolMXBean`。
 *
 * @property name 内存池名称(由 JVM 提供,如 "G1 Eden Space")。
 * @property usedBytes 已使用字节数。
 * @property maxBytes 最大可用字节数;部分内存池无上限时为 -1。
 */
data class MemoryPoolMetric(
    val name: String,
    val usedBytes: Long,
    val maxBytes: Long
)
