package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 单个 GC 收集器的原始累计指标。
 *
 * 对应 JVM 中的一个垃圾回收器,数据来源于 {@code java.lang.management.GarbageCollectorMXBean},
 * 字段为该收集器自 JVM 启动以来的累计值。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class GcCollectorMetric {
    /** 收集器名称(由 JVM 提供,如 "G1 Young Generation"、"G1 Old Generation")。 */
    String name;
    /** 累计回收次数。 */
    long collectionCount;
    /** 累计回收耗时(毫秒)。 */
    long collectionTimeMs;
}
