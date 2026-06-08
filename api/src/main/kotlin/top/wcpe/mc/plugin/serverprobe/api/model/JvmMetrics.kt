package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * JVM 运行时指标快照(FR2.1)。
 *
 * 全部来源于 `java.lang.management.*` 的各类 MXBean,全版本 + 全平台通用,
 * 是探针最稳定的指标层。本对象为某一时刻的取样结果,字段全部不可变。
 *
 * @property heapUsedBytes 堆已使用字节数。
 * @property heapCommittedBytes 堆已提交字节数。
 * @property heapMaxBytes 堆最大字节数;无上限时为 -1。
 * @property nonHeapUsedBytes 非堆已使用字节数。
 * @property nonHeapCommittedBytes 非堆已提交字节数。
 * @property nonHeapMaxBytes 非堆最大字节数;无上限时为 -1。
 * @property memoryPools 各内存池明细。
 * @property gcYoungCount 年轻代 GC 累计次数(按收集器名归类的派生聚合值)。
 * @property gcYoungTimeMs 年轻代 GC 累计耗时(毫秒,按收集器名归类的派生聚合值)。
 * @property gcOldCount 老年代 GC 累计次数(按收集器名归类的派生聚合值)。
 * @property gcOldTimeMs 老年代 GC 累计耗时(毫秒,按收集器名归类的派生聚合值)。
 * @property gcCollectors 各 GC 收集器的原始明细(按 `GarbageCollectorMXBean` 名称);
 *  上方 `gcYoung*`/`gcOld*` 即由本明细按收集器名归类聚合而来,供 PRD §9 历史与快速展示。
 * @property threadCount 当前活动线程数。
 * @property daemonThreadCount 当前守护线程数。
 * @property peakThreadCount 峰值线程数。
 * @property deadlockedThreadCount 死锁线程数(0 表示无死锁)。
 * @property loadedClassCount 当前已加载类数量。
 * @property totalLoadedClassCount 累计已加载类数量(含已卸载)。
 * @property processCpuLoad 当前进程 CPU 占用率(0.0–1.0);为 -1.0 表示当前 JDK 不提供该指标。
 * @property systemCpuLoad 系统整体 CPU 占用率(0.0–1.0);为 -1.0 表示当前 JDK 不提供该指标。
 * @property uptimeMs JVM 运行时长(毫秒)。
 * @property startTimeMs JVM 启动时刻(epoch 毫秒)。
 * @property jvmArgs JVM 启动参数列表。
 */
data class JvmMetrics(
    val heapUsedBytes: Long,
    val heapCommittedBytes: Long,
    val heapMaxBytes: Long,
    val nonHeapUsedBytes: Long,
    val nonHeapCommittedBytes: Long,
    val nonHeapMaxBytes: Long,
    val memoryPools: List<MemoryPoolMetric>,
    val gcYoungCount: Long,
    val gcYoungTimeMs: Long,
    val gcOldCount: Long,
    val gcOldTimeMs: Long,
    val gcCollectors: List<GcCollectorMetric>,
    val threadCount: Int,
    val daemonThreadCount: Int,
    val peakThreadCount: Int,
    val deadlockedThreadCount: Int,
    val loadedClassCount: Int,
    val totalLoadedClassCount: Long,
    val processCpuLoad: Double,
    val systemCpuLoad: Double,
    val uptimeMs: Long,
    val startTimeMs: Long,
    val jvmArgs: List<String>
)
