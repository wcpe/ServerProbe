package top.wcpe.mc.plugin.serverprobe.core.aggregator

import top.wcpe.mc.plugin.serverprobe.api.model.AggregatedMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.core.buffer.MetricSnapshotBuffer
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 指标聚合器(FR3.3):对近期历史中的若干份 [MetricSnapshot] 做跨快照统计。
 *
 * 职责单一——只做"取窗口 + 聚合计算",不采集、不落盘、不依赖任何平台 API。
 * 取数经注入的 [MetricSnapshotBuffer](内存近期历史),计算委托给本类的**纯函数** [aggregate]
 * (输入快照列表、输出 [AggregatedMetrics]),从而把核心算法与 IOC/缓冲解耦,便于直接单测。
 *
 * 聚合口径详见 [AggregatedMetrics] 各字段 KDoc;分位/均值算法见 [Percentiles]。
 */
@Service
class MetricAggregator {

    /** 近期历史缓冲;聚合时按窗口取最近若干份快照。 */
    @Inject
    lateinit var buffer: MetricSnapshotBuffer

    /**
     * 对最近 [windowSize] 份快照做聚合。
     *
     * 取 [MetricSnapshotBuffer.recent] 后委托纯函数 [aggregate];窗口为空时返回
     * [windowSampleCount] 为 0、其余字段均 null 的结果(而非 null),呈现层据此统一降级。
     *
     * @param windowSize 聚合窗口大小;非正时 [MetricSnapshotBuffer.recent] 返回空,得到空聚合结果。
     * @return 聚合结果(恒非 null;窗口为空时各统计项为 null)。
     */
    fun aggregate(windowSize: Int): AggregatedMetrics = aggregate(buffer.recent(windowSize))

    /**
     * 跨快照聚合的核心纯函数(无副作用、不依赖注入,便于单测)。
     *
     * 输入快照列表的顺序无关:速率计算按 [MetricSnapshot.timestampMs] 自行定位窗口两端
     * (最新 = 时间戳最大者,最旧 = 最小者),不假设列表为新→旧或旧→新。
     *
     * @param snapshots 参与聚合的快照列表(可空集);通常为近期历史的一个窗口切片。
     * @return 聚合结果;空列表得到 [windowSampleCount]=0、其余字段为 null 的结果。
     */
    fun aggregate(snapshots: List<MetricSnapshot>): AggregatedMetrics {
        if (snapshots.isEmpty()) {
            return EMPTY
        }
        val tpsAvg = Percentiles.average(collectTpsSamples(snapshots))
        val msptP95 = Percentiles.percentile(collectMsptSamples(snapshots) { it.msptP95 }, P95)
        val msptP99 = Percentiles.percentile(collectMsptSamples(snapshots) { it.msptP99 }, P99)
        val gcRates = computeGcRates(snapshots)
        return AggregatedMetrics(
            windowSampleCount = snapshots.size,
            tpsAvg = tpsAvg,
            msptP95 = msptP95,
            msptP99 = msptP99,
            gcYoungRatePerSec = gcRates.youngCountRate,
            gcOldRatePerSec = gcRates.oldCountRate,
            gcYoungTimeRatePerSec = gcRates.youngTimeRate,
            gcOldTimeRatePerSec = gcRates.oldTimeRate
        )
    }

    /**
     * 收集窗口内各快照的 1 分钟 TPS 样本(仅取 `server.tick.tps1m` 非 null 者)。
     *
     * @param snapshots 窗口内快照。
     * @return TPS 样本数组(可能为空,代表窗口内无任一快照含 TPS)。
     */
    private fun collectTpsSamples(snapshots: List<MetricSnapshot>): DoubleArray =
        snapshots.mapNotNull { it.server?.tick?.tps1m }.toDoubleArray()

    /**
     * 收集窗口内各快照的某项 MSPT 代表值(由 [selector] 选 p95 或 p99,仅取非 null 者)。
     *
     * @param snapshots 窗口内快照。
     * @param selector 从 tick 采样中取目标分位字段(p95/p99)。
     * @return MSPT 代表值样本数组(可能为空)。
     */
    private inline fun collectMsptSamples(
        snapshots: List<MetricSnapshot>,
        selector: (top.wcpe.mc.plugin.serverprobe.api.model.TickSample) -> Double?
    ): DoubleArray = snapshots.mapNotNull { snapshot -> snapshot.server?.tick?.let(selector) }.toDoubleArray()

    /**
     * 计算窗口两端的 GC 速率(次/秒、毫秒/秒)。
     *
     * 取窗口内 [MetricSnapshot.timestampMs] 最旧与最新两份:GC 计数为单调累计量,以
     * `(最新累计 − 最旧累计) ÷ 时间差(秒)` 得平均速率。以下情况整体记为不可计算(各速率字段 null):
     * - 样本不足 2 份(无法差分);
     * - 时间差 ≤ 0(同一时刻或时钟异常);
     * 单项差分为负(跨进程重启导致累计回绕)时,**仅该项**记为 null,其余项仍照常给出。
     *
     * @param snapshots 窗口内快照(已确保非空)。
     * @return 四项 GC 速率(任一不可计算项为 null)。
     */
    private fun computeGcRates(snapshots: List<MetricSnapshot>): GcRates {
        if (snapshots.size < 2) {
            return GcRates.EMPTY
        }
        val oldest = snapshots.minByOrNull { it.timestampMs } ?: return GcRates.EMPTY
        val newest = snapshots.maxByOrNull { it.timestampMs } ?: return GcRates.EMPTY
        val elapsedSeconds = (newest.timestampMs - oldest.timestampMs) / MILLIS_PER_SECOND
        if (elapsedSeconds <= 0.0) {
            return GcRates.EMPTY
        }
        val from = oldest.jvm
        val to = newest.jvm
        return GcRates(
            youngCountRate = ratePerSecond(from.gcYoungCount, to.gcYoungCount, elapsedSeconds),
            oldCountRate = ratePerSecond(from.gcOldCount, to.gcOldCount, elapsedSeconds),
            youngTimeRate = ratePerSecond(from.gcYoungTimeMs, to.gcYoungTimeMs, elapsedSeconds),
            oldTimeRate = ratePerSecond(from.gcOldTimeMs, to.gcOldTimeMs, elapsedSeconds)
        )
    }

    /**
     * 单调累计量的两端差分速率:`(to − from) ÷ 秒`。
     *
     * 差分为负(重启回绕)时返回 null,避免给出无意义的负速率。
     *
     * @param from 起点(较旧)累计值。
     * @param to 终点(较新)累计值。
     * @param elapsedSeconds 时间跨度(秒,调用方已保证为正)。
     * @return 速率(每秒);差分为负时为 null。
     */
    private fun ratePerSecond(from: Long, to: Long, elapsedSeconds: Double): Double? {
        val delta = to - from
        if (delta < 0) {
            return null
        }
        return delta / elapsedSeconds
    }

    /**
     * GC 四项速率的内部载体(仅本类计算中转用)。
     *
     * @property youngCountRate 年轻代 GC 次数速率(次/秒)。
     * @property oldCountRate 老年代 GC 次数速率(次/秒)。
     * @property youngTimeRate 年轻代 GC 耗时速率(毫秒/秒)。
     * @property oldTimeRate 老年代 GC 耗时速率(毫秒/秒)。
     */
    private data class GcRates(
        val youngCountRate: Double?,
        val oldCountRate: Double?,
        val youngTimeRate: Double?,
        val oldTimeRate: Double?
    ) {
        companion object {
            /** 不可计算(样本不足/时间差非正)时的全 null 速率。 */
            val EMPTY = GcRates(null, null, null, null)
        }
    }

    private companion object {

        /** p95 分位常量。 */
        private const val P95 = 0.95

        /** p99 分位常量。 */
        private const val P99 = 0.99

        /** 一秒的毫秒数(用于时间差换算为秒)。 */
        private const val MILLIS_PER_SECOND = 1000.0

        /** 空窗口聚合结果:份数为 0,其余统计项均 null。 */
        private val EMPTY = AggregatedMetrics(
            windowSampleCount = 0,
            tpsAvg = null,
            msptP95 = null,
            msptP99 = null,
            gcYoungRatePerSec = null,
            gcOldRatePerSec = null,
            gcYoungTimeRatePerSec = null,
            gcOldTimeRatePerSec = null
        )
    }
}
