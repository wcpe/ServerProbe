package top.wcpe.mc.plugin.serverprobe.bukkit.folia

import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionWorldMetrics
import top.wcpe.mc.plugin.serverprobe.core.aggregator.Percentiles
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Folia 真实 region tick 的有界观测窗口。 */
class ObservedRegionTracker(
    private val sampleCapacity: Int = DEFAULT_SAMPLE_CAPACITY,
    private val expireAfterMs: Long = DEFAULT_EXPIRE_AFTER_MS
) {

    private val nextSequence = AtomicLong()
    private val regions = ConcurrentHashMap<RegionKey, RegionWindow>()

    init {
        require(sampleCapacity > 0) { "region 样本容量必须为正数" }
        require(expireAfterMs > 0) { "region 过期时间必须为正数" }
    }

    /** 在真实 region tick 开始事件中登记一个采样。 */
    fun onTickStart(context: FoliaRegionContext, startedNanos: Long, nowMs: Long) {
        if (context.playerCount <= 0) {
            regions[RegionKey(context)]?.pause()
            return
        }
        windowFor(context).start(context.playerCount, startedNanos, nowMs)
    }

    /** 在真实 region tick 结束事件中完成一个采样。 */
    fun onTickEnd(context: FoliaRegionContext, durationMs: Double, nowMs: Long) {
        if (durationMs < 0.0) {
            return
        }
        regions[RegionKey(context)]?.finish(durationMs, nowMs)
    }

    /** 返回尚未过期的 region 明细及每世界加权汇总。 */
    fun snapshot(nowMs: Long): ObservedRegionSnapshot {
        val active = activeSamples(nowMs)
        return ObservedRegionSnapshot(
            regions = active.map { it.metrics }.sortedWith(REGION_ORDER),
            worlds = active.groupBy { it.metrics.worldName }.map(::worldSnapshot).sortedBy { it.worldName }
        )
    }

    private fun windowFor(context: FoliaRegionContext): RegionWindow = regions.compute(RegionKey(context)) { _, current ->
        current ?: RegionWindow(
            context,
            nextSequence.incrementAndGet(),
            sampleCapacity,
            expireAfterMs
        )
    }!!

    private fun activeSamples(nowMs: Long): List<RegionSample> = regions.entries.mapNotNull { entry ->
        entry.value.snapshot(nowMs)?.also { sample ->
            if (sample.expired) {
                regions.remove(entry.key, entry.value)
            }
        }?.takeUnless { it.expired }
    }

    private fun worldSnapshot(entry: Map.Entry<String, List<RegionSample>>): ObservedRegionWorldMetrics {
        val samples = entry.value
        return ObservedRegionWorldMetrics.builder()
            .worldName(entry.key)
            .activeRegions(samples.size)
            .playerCount(samples.sumOf { it.metrics.playerCount })
            .sampleCount(samples.sumOf { it.metrics.sampleCount })
            .tpsStats(samples.flatMap { it.tpsSamples.asList() }.toDoubleArray())
            .msptStats(samples.flatMap { it.msptSamples.asList() }.toDoubleArray())
            .build()
    }

    private fun ObservedRegionWorldMetrics.ObservedRegionWorldMetricsBuilder.tpsStats(samples: DoubleArray) = apply {
        tpsAvg(Percentiles.average(samples))
        tpsP95(Percentiles.percentile(samples, P95))
        tpsP99(Percentiles.percentile(samples, P99))
    }

    private fun ObservedRegionWorldMetrics.ObservedRegionWorldMetricsBuilder.msptStats(samples: DoubleArray) = apply {
        msptAvg(Percentiles.average(samples))
        msptP95(Percentiles.percentile(samples, P95))
        msptP99(Percentiles.percentile(samples, P99))
    }

    private companion object {
        private const val DEFAULT_SAMPLE_CAPACITY = 600
        private const val DEFAULT_EXPIRE_AFTER_MS = 60_000L
        private const val P95 = 0.95
        private const val P99 = 0.99
        private val REGION_ORDER = compareBy<ObservedRegionMetrics> { it.worldName }
            .thenBy { it.centerChunkX }
            .thenBy { it.centerChunkZ }
            .thenBy { it.regionSequence }
    }
}

/** Folia 内部当前 region 的最小快照，不暴露到公共 API。 */
data class FoliaRegionContext(
    val internalId: Long,
    val worldName: String,
    val centerChunkX: Int,
    val centerChunkZ: Int,
    val playerCount: Int
)

/** 同一内部 region 的坐标变更必须产生新序列，旧序列由过期策略清理。 */
private data class RegionKey(
    val internalId: Long,
    val worldName: String,
    val centerChunkX: Int,
    val centerChunkZ: Int
) {

    constructor(context: FoliaRegionContext) : this(
        context.internalId,
        context.worldName,
        context.centerChunkX,
        context.centerChunkZ
    )
}

/** 已观测 region 的内部快照，携带原始窗口以生成世界级加权指标。 */
data class ObservedRegionSnapshot(
    val regions: List<ObservedRegionMetrics>,
    val worlds: List<ObservedRegionWorldMetrics>
)

private class RegionWindow(
    private val context: FoliaRegionContext,
    private val sequence: Long,
    sampleCapacity: Int,
    private val expireAfterMs: Long
) {

    private val msptSamples = DoubleWindow(sampleCapacity)
    private val tpsSamples = DoubleWindow(sampleCapacity)
    private var pendingStartNanos: Long? = null
    private var previousStartNanos: Long? = null
    private var playerCount = 0
    private var sampleCount = 0L
    private var lastSeenMs = 0L

    @Synchronized
    fun start(currentPlayers: Int, startedNanos: Long, nowMs: Long) {
        pendingStartNanos = startedNanos
        playerCount = currentPlayers
        lastSeenMs = nowMs
    }

    @Synchronized
    fun pause() {
        pendingStartNanos = null
        previousStartNanos = null
    }

    @Synchronized
    fun finish(durationMs: Double, nowMs: Long) {
        val startedNanos = pendingStartNanos ?: return
        pendingStartNanos = null
        msptSamples.add(durationMs)
        previousStartNanos?.let { addTps(startedNanos - it) }
        previousStartNanos = startedNanos
        sampleCount++
        lastSeenMs = nowMs
    }

    @Synchronized
    fun snapshot(nowMs: Long): RegionSample? {
        if (sampleCount == 0L) {
            return null
        }
        val expired = nowMs - lastSeenMs > expireAfterMs
        return RegionSample(metrics(), tpsSamples.snapshot(), msptSamples.snapshot(), expired)
    }

    private fun addTps(intervalNanos: Long) {
        if (intervalNanos > 0) {
            tpsSamples.add(minOf(MAX_TPS, NANOS_PER_SECOND.toDouble() / intervalNanos))
        }
    }

    private fun metrics(): ObservedRegionMetrics = ObservedRegionMetrics.builder()
        .worldName(context.worldName)
        .foliaRegionId(context.internalId)
        .regionSequence(sequence)
        .observed(true)
        .centerChunkX(context.centerChunkX)
        .centerChunkZ(context.centerChunkZ)
        .playerCount(playerCount)
        .sampleCount(sampleCount)
        .tpsAvg(Percentiles.average(tpsSamples.snapshot()))
        .tpsP95(Percentiles.percentile(tpsSamples.snapshot(), P95))
        .tpsP99(Percentiles.percentile(tpsSamples.snapshot(), P99))
        .msptAvg(Percentiles.average(msptSamples.snapshot()))
        .msptP95(Percentiles.percentile(msptSamples.snapshot(), P95))
        .msptP99(Percentiles.percentile(msptSamples.snapshot(), P99))
        .lastSeenMs(lastSeenMs)
        .build()

    private companion object {
        private const val MAX_TPS = 20.0
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val P95 = 0.95
        private const val P99 = 0.99
    }
}

private data class RegionSample(
    val metrics: ObservedRegionMetrics,
    val tpsSamples: DoubleArray,
    val msptSamples: DoubleArray,
    val expired: Boolean
)

/** 在事件线程中以固定内存保存数值样本。 */
private class DoubleWindow(private val capacity: Int) {

    private val values = DoubleArray(capacity)
    private var nextIndex = 0
    private var size = 0

    fun add(value: Double) {
        values[nextIndex] = value
        nextIndex = (nextIndex + 1) % capacity
        size = minOf(capacity, size + 1)
    }

    fun snapshot(): DoubleArray {
        if (size < capacity) {
            return values.copyOf(size)
        }
        return values.copyOfRange(nextIndex, capacity) + values.copyOfRange(0, nextIndex)
    }
}
