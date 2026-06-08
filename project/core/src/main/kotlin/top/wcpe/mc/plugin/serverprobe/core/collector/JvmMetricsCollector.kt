package top.wcpe.mc.plugin.serverprobe.core.collector

import top.wcpe.mc.plugin.serverprobe.api.model.GcCollectorMetric
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MemoryPoolMetric
import top.wcpe.mc.plugin.serverprobe.core.jmx.JmxSupport
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import top.wcpe.mc.plugin.serverprobe.api.collector.JvmMetricsCollector as JvmMetricsCollectorApi

/**
 * JVM 指标采集器的通用实现(FR2.1)。
 *
 * 全部数据取自 `java.lang.management.*` 各类 MXBean,外加经 [JmxSupport] 反射读取的 CPU 指标,
 * 不依赖任何平台 API,故为全版本 + 全平台通用实现,落位于 core 模块。
 *
 * 生命周期:作为 IOC [Service] 由容器实例化并注入 [registry];[register] 在依赖注入完成后
 * (`@PostConstruct`)将自身登记到 [ProbeRegistry],供编排层发现。[collect] 本身不依赖 [registry],
 * 可独立调用(便于单测)。
 */
@Service
class JvmMetricsCollector : JvmMetricsCollectorApi {

    /** 组件注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: ProbeRegistry

    /**
     * 依赖注入完成后将本采集器登记到注册中心。
     *
     * 采用 `@PostConstruct` 而非构造期注册,确保 [registry] 已注入完毕再使用。
     */
    @PostConstruct
    fun register() {
        registry.register(this)
        ProbeLogger.debug("JVM 采集器已注册")
    }

    /**
     * 采集当前 JVM 指标快照。
     *
     * 依次取数:堆/非堆内存 → 内存池明细 → GC 明细及 young/old 派生 → 线程 → 类加载 → CPU → 运行时。
     *
     * @return 当前时刻的 [JvmMetrics]。
     */
    override fun collect(): JvmMetrics {
        val memoryBean = ManagementFactory.getMemoryMXBean()
        val heap = memoryBean.heapMemoryUsage
        val nonHeap = memoryBean.nonHeapMemoryUsage

        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gcCollectors = gcBeans.map { GcCollectorMetric(it.name, it.collectionCount, it.collectionTime) }
        val gcGenerational = deriveGenerationalGc(gcBeans)

        val threadBean = ManagementFactory.getThreadMXBean()
        val classLoadingBean = ManagementFactory.getClassLoadingMXBean()
        val runtimeBean = ManagementFactory.getRuntimeMXBean()

        return JvmMetrics(
            heapUsedBytes = heap.used,
            heapCommittedBytes = heap.committed,
            heapMaxBytes = heap.max,
            nonHeapUsedBytes = nonHeap.used,
            nonHeapCommittedBytes = nonHeap.committed,
            nonHeapMaxBytes = nonHeap.max,
            memoryPools = ManagementFactory.getMemoryPoolMXBeans().map { pool ->
                // usage 在部分内存池(如刚回收的池)可能为 null,以 -1 容错表达"无数据"
                val usage = pool.usage
                MemoryPoolMetric(pool.name, usage?.used ?: -1L, usage?.max ?: -1L)
            },
            gcYoungCount = gcGenerational.youngCount,
            gcYoungTimeMs = gcGenerational.youngTimeMs,
            gcOldCount = gcGenerational.oldCount,
            gcOldTimeMs = gcGenerational.oldTimeMs,
            gcCollectors = gcCollectors,
            threadCount = threadBean.threadCount,
            daemonThreadCount = threadBean.daemonThreadCount,
            peakThreadCount = threadBean.peakThreadCount,
            // 无死锁时 findDeadlockedThreads 返回 null,归一为 0
            deadlockedThreadCount = threadBean.findDeadlockedThreads()?.size ?: 0,
            loadedClassCount = classLoadingBean.loadedClassCount,
            totalLoadedClassCount = classLoadingBean.totalLoadedClassCount,
            processCpuLoad = JmxSupport.processCpuLoad(),
            systemCpuLoad = JmxSupport.systemCpuLoad(),
            uptimeMs = runtimeBean.uptime,
            startTimeMs = runtimeBean.startTime,
            jvmArgs = runtimeBean.inputArguments
        )
    }

    /**
     * 将各 GC 收集器按名称启发式归类为年轻代 / 老年代并累加 count 与 time。
     *
     * 归类依据收集器名称(转小写后)的关键词:
     * - 年轻代:命中 [YOUNG_KEYWORDS](`young`/`scavenge`/`copy`/`parnew`/`eden`)中任一关键词;
     * - 老年代:**非年轻代一律归老年代**——含传统老年代收集器(如 cms/marksweep/g1 old 等),
     *   以及无法识别的单代/并发回收器(如 ZGC、Shenandoah,视为全堆回收)。
     *
     * 注:young/old 为按收集器名归类的**启发式派生聚合值**,可能不覆盖未来新增收集器的命名。
     * 另需注意,对暴露多个 bean 的并发回收器(如 ZGC 的 `ZGC Cycles`/`ZGC Pauses`、Shenandoah 同理),
     * young/old 聚合可能重复计数,精确口径以 [JvmMetrics.gcCollectors] 明细为准。
     *
     * @param gcBeans 当前 JVM 的全部 GC 收集器 MXBean。
     * @return 按代归类聚合后的结果。
     */
    private fun deriveGenerationalGc(gcBeans: List<GarbageCollectorMXBean>): GenerationalGc {
        var youngCount = 0L
        var youngTimeMs = 0L
        var oldCount = 0L
        var oldTimeMs = 0L
        for (bean in gcBeans) {
            val name = bean.name.lowercase()
            if (YOUNG_KEYWORDS.any { name.contains(it) }) {
                youngCount += bean.collectionCount
                youngTimeMs += bean.collectionTime
            } else {
                // 老年代关键词,以及无法识别的单代/并发回收器,统一计入老年代(视为全堆回收)
                oldCount += bean.collectionCount
                oldTimeMs += bean.collectionTime
            }
        }
        return GenerationalGc(youngCount, youngTimeMs, oldCount, oldTimeMs)
    }

    /**
     * GC 按代归类的聚合结果(进程内部传递用)。
     *
     * @property youngCount 年轻代累计回收次数。
     * @property youngTimeMs 年轻代累计回收耗时(毫秒)。
     * @property oldCount 老年代累计回收次数。
     * @property oldTimeMs 老年代累计回收耗时(毫秒)。
     */
    private data class GenerationalGc(
        val youngCount: Long,
        val youngTimeMs: Long,
        val oldCount: Long,
        val oldTimeMs: Long
    )

    private companion object {

        /** 年轻代收集器名称关键词(小写)。 */
        private val YOUNG_KEYWORDS = listOf("young", "scavenge", "copy", "parnew", "eden")
    }
}
