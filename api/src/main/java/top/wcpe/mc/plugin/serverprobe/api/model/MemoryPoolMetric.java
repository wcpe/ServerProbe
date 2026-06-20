package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 单个内存池的使用指标。
 *
 * 对应 JVM 内存管理中的一个内存池(如 Eden、Survivor、Old Gen、Metaspace 等),
 * 数据来源于 {@code java.lang.management.MemoryPoolMXBean}。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class MemoryPoolMetric {
    /** 内存池名称(由 JVM 提供,如 "G1 Eden Space")。 */
    String name;
    /** 已使用字节数。 */
    long usedBytes;
    /** 最大可用字节数;部分内存池无上限时为 -1。 */
    long maxBytes;
}
