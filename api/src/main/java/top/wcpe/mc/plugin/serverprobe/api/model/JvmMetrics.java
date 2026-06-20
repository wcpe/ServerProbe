package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * JVM 运行时指标快照(FR2.1)。
 *
 * 全部来源于 {@code java.lang.management.*} 的各类 MXBean,全版本 + 全平台通用,
 * 是探针最稳定的指标层。本对象为某一时刻的取样结果,字段全部不可变。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class JvmMetrics {
    /** 堆已使用字节数。 */
    long heapUsedBytes;
    /** 堆已提交字节数。 */
    long heapCommittedBytes;
    /** 堆最大字节数;无上限时为 -1。 */
    long heapMaxBytes;
    /** 非堆已使用字节数。 */
    long nonHeapUsedBytes;
    /** 非堆已提交字节数。 */
    long nonHeapCommittedBytes;
    /** 非堆最大字节数;无上限时为 -1。 */
    long nonHeapMaxBytes;
    /** 各内存池明细。 */
    java.util.List<MemoryPoolMetric> memoryPools;
    /** 年轻代 GC 累计次数(按收集器名归类的派生聚合值)。 */
    long gcYoungCount;
    /** 年轻代 GC 累计耗时(毫秒,按收集器名归类的派生聚合值)。 */
    long gcYoungTimeMs;
    /** 老年代 GC 累计次数(按收集器名归类的派生聚合值)。 */
    long gcOldCount;
    /** 老年代 GC 累计耗时(毫秒,按收集器名归类的派生聚合值)。 */
    long gcOldTimeMs;
    /**
     * 各 GC 收集器的原始明细(按 {@code GarbageCollectorMXBean} 名称);
     * 上方 {@code gcYoung*}/{@code gcOld*} 即由本明细按收集器名归类聚合而来,供 PRD §9 历史与快速展示。
     */
    java.util.List<GcCollectorMetric> gcCollectors;
    /** 当前活动线程数。 */
    int threadCount;
    /** 当前守护线程数。 */
    int daemonThreadCount;
    /** 峰值线程数。 */
    int peakThreadCount;
    /** 死锁线程数(0 表示无死锁)。 */
    int deadlockedThreadCount;
    /** 当前已加载类数量。 */
    int loadedClassCount;
    /** 累计已加载类数量(含已卸载)。 */
    long totalLoadedClassCount;
    /** 当前进程 CPU 占用率(0.0–1.0);为 -1.0 表示当前 JDK 不提供该指标。 */
    double processCpuLoad;
    /** 系统整体 CPU 占用率(0.0–1.0);为 -1.0 表示当前 JDK 不提供该指标。 */
    double systemCpuLoad;
    /** JVM 运行时长(毫秒)。 */
    long uptimeMs;
    /** JVM 启动时刻(epoch 毫秒)。 */
    long startTimeMs;
    /** JVM 启动参数列表。 */
    java.util.List<String> jvmArgs;
}
