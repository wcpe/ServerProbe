package top.wcpe.mc.plugin.serverprobe.core.aggregator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample

/**
 * [MetricAggregator] 核心纯函数 [MetricAggregator.aggregate](接收快照列表)单元测试。
 *
 * 纯函数不依赖注入的 [top.wcpe.mc.plugin.serverprobe.core.buffer.MetricSnapshotBuffer],
 * 故直接 `new MetricAggregator()` 调用 `aggregate(List)` 验证,无需 IOC 容器。
 *
 * 覆盖:
 * - tpsAvg:窗口内各快照 tps1m 的均值,且自动跳过 null;
 * - msptP95/P99:对"各周期代表值"按最近秩取分位;
 * - GC 速率:两端单调累计差分 ÷ 时间差(秒);
 * - 边界:空窗口(count=0、全 null)、单样本(GC 速率 null)、重启回绕(差分为负 → null)、
 *   时间差为 0(GC 速率 null)。
 */
class MetricAggregatorTest {

    /** 浮点断言容差。 */
    private val delta = 1e-9

    /** 被测聚合器:纯函数路径不触碰 buffer,直接实例化即可。 */
    private val aggregator = MetricAggregator()

    /** 空窗口:份数为 0,所有统计项均 null。 */
    @Test
    fun `空窗口各项为 null`() {
        val result = aggregator.aggregate(emptyList())
        assertEquals(0, result.windowSampleCount, "空窗口份数应为 0")
        assertNull(result.tpsAvg, "空窗口 tpsAvg 应为 null")
        assertNull(result.msptP95, "空窗口 msptP95 应为 null")
        assertNull(result.msptP99, "空窗口 msptP99 应为 null")
        assertNull(result.gcYoungRatePerSec, "空窗口 GC 速率应为 null")
        assertNull(result.gcOldRatePerSec, "空窗口 GC 速率应为 null")
    }

    /** tpsAvg:多份快照 tps1m 求均值,且跳过 tps1m 为 null 的快照。 */
    @Test
    fun `tpsAvg 求均值并跳过 null`() {
        val snapshots = listOf(
            snapshot(timestampMs = 0, tps1m = 18.0),
            snapshot(timestampMs = 5_000, tps1m = 20.0),
            // tps1m 为 null(如 Folia)→ 不计入均值
            snapshot(timestampMs = 10_000, tps1m = null)
        )
        val result = aggregator.aggregate(snapshots)
        // (18 + 20) / 2 = 19.0
        assertEquals(19.0, result.tpsAvg!!, delta, "tpsAvg 应为非 null 样本均值 19.0")
        assertEquals(3, result.windowSampleCount, "份数应为全部 3 份(含被跳过 TPS 的那份)")
    }

    /**
     * msptP95/P99:对各快照的"周期代表值"按最近秩取分位。
     * p95(p95 字段)样本 = 1..100 → 第 95 个 = 95;p99 样本同构 → 第 99 个 = 99。
     */
    @Test
    fun `mspt 分位对周期代表值取最近秩`() {
        val snapshots = (1..100).map { i ->
            snapshot(
                timestampMs = i.toLong() * 1_000,
                tps1m = 20.0,
                msptP95 = i.toDouble(),
                msptP99 = i.toDouble()
            )
        }
        val result = aggregator.aggregate(snapshots)
        assertEquals(95.0, result.msptP95!!, delta, "msptP95 应为周期代表值的 p95 = 95")
        assertEquals(99.0, result.msptP99!!, delta, "msptP99 应为周期代表值的 p99 = 99")
    }

    /**
     * GC 速率:窗口两端差分 ÷ 时间差(秒)。
     * young 100→160(Δ60)/10s = 6.0;old 10→15(Δ5)/10s = 0.5;
     * youngTime 200→260(Δ60)/10s = 6.0;oldTime 50→60(Δ10)/10s = 1.0。
     * 列表故意乱序传入,验证按 timestampMs 自行定位两端。
     */
    @Test
    fun `GC 速率按两端差分除以秒`() {
        val snapshots = listOf(
            // 中间样本(乱序,不影响两端定位)
            snapshot(timestampMs = 5_000, tps1m = 20.0, gcYoungCount = 130, gcOldCount = 12, gcYoungTimeMs = 230, gcOldTimeMs = 55),
            // 最旧
            snapshot(timestampMs = 0, tps1m = 20.0, gcYoungCount = 100, gcOldCount = 10, gcYoungTimeMs = 200, gcOldTimeMs = 50),
            // 最新
            snapshot(timestampMs = 10_000, tps1m = 20.0, gcYoungCount = 160, gcOldCount = 15, gcYoungTimeMs = 260, gcOldTimeMs = 60)
        )
        val result = aggregator.aggregate(snapshots)
        assertEquals(6.0, result.gcYoungRatePerSec!!, delta, "young 次数速率应为 6.0/s")
        assertEquals(0.5, result.gcOldRatePerSec!!, delta, "old 次数速率应为 0.5/s")
        assertEquals(6.0, result.gcYoungTimeRatePerSec!!, delta, "young 耗时速率应为 6.0ms/s")
        assertEquals(1.0, result.gcOldTimeRatePerSec!!, delta, "old 耗时速率应为 1.0ms/s")
    }

    /** 单样本:无法差分,GC 速率全为 null;但份数与 TPS 仍可给出。 */
    @Test
    fun `单样本 GC 速率为 null`() {
        val result = aggregator.aggregate(listOf(snapshot(timestampMs = 0, tps1m = 20.0)))
        assertEquals(1, result.windowSampleCount, "份数应为 1")
        assertEquals(20.0, result.tpsAvg!!, delta, "单样本 tpsAvg 应为该样本值")
        assertNull(result.gcYoungRatePerSec, "单样本无法差分,young 速率应为 null")
        assertNull(result.gcOldRatePerSec, "单样本无法差分,old 速率应为 null")
        assertNull(result.gcYoungTimeRatePerSec, "单样本无法差分,young 耗时速率应为 null")
        assertNull(result.gcOldTimeRatePerSec, "单样本无法差分,old 耗时速率应为 null")
    }

    /** 重启回绕:最新累计值小于最旧(差分为负)→ 对应 GC 速率项为 null。 */
    @Test
    fun `重启回绕差分为负则速率为 null`() {
        val snapshots = listOf(
            // 最旧:young 累计已很高
            snapshot(timestampMs = 0, tps1m = 20.0, gcYoungCount = 1000, gcYoungTimeMs = 5000),
            // 最新:重启后归零重新累计 → 差分为负
            snapshot(timestampMs = 10_000, tps1m = 20.0, gcYoungCount = 30, gcYoungTimeMs = 100)
        )
        val result = aggregator.aggregate(snapshots)
        assertNull(result.gcYoungRatePerSec, "回绕(差分为负)时 young 次数速率应为 null")
        assertNull(result.gcYoungTimeRatePerSec, "回绕(差分为负)时 young 耗时速率应为 null")
    }

    /** 时间差为 0(两端时间戳相同):无法换算速率 → GC 速率全为 null。 */
    @Test
    fun `时间差为零则 GC 速率为 null`() {
        val snapshots = listOf(
            snapshot(timestampMs = 1_000, tps1m = 20.0, gcYoungCount = 100),
            snapshot(timestampMs = 1_000, tps1m = 20.0, gcYoungCount = 200)
        )
        val result = aggregator.aggregate(snapshots)
        assertNull(result.gcYoungRatePerSec, "时间差为 0 时 GC 速率应为 null")
        assertNull(result.gcOldRatePerSec, "时间差为 0 时 GC 速率应为 null")
    }

    /**
     * 构造一份测试用 [MetricSnapshot]:仅暴露聚合关心的字段,其余以稳定占位填充。
     *
     * @param timestampMs 采样时刻(用于 GC 速率两端定位)。
     * @param tps1m 1 分钟 TPS(null 表示该快照无 TPS)。
     * @param msptP95 MSPT p95 周期代表值。
     * @param msptP99 MSPT p99 周期代表值。
     * @param gcYoungCount/gcOldCount/gcYoungTimeMs/gcOldTimeMs GC 单调累计量。
     */
    private fun snapshot(
        timestampMs: Long,
        tps1m: Double?,
        msptP95: Double? = 1.0,
        msptP99: Double? = 1.0,
        gcYoungCount: Long = 0,
        gcOldCount: Long = 0,
        gcYoungTimeMs: Long = 0,
        gcOldTimeMs: Long = 0
    ): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(timestampMs)
        .serverId("test")
        .platform(ProbePlatform.BUKKIT)
        .jvm(jvm(gcYoungCount, gcOldCount, gcYoungTimeMs, gcOldTimeMs))
        .server(
            ServerMetrics.builder()
                .tick(
                    TickSample.builder()
                        .tps1m(tps1m)
                        .tps5m(null)
                        .tps15m(null)
                        .msptAvg(null)
                        .msptP95(msptP95)
                        .msptP99(msptP99)
                        .source(TickSampleSource.SELF_SAMPLING)
                        .build()
                )
                .onlinePlayers(0)
                .maxPlayers(20)
                .uptimeMs(0)
                .build()
        )
        .build()

    /**
     * 构造一份测试用 [JvmMetrics]:仅 GC 四项累计量有意义,其余字段以稳定占位填充。
     */
    private fun jvm(
        gcYoungCount: Long,
        gcOldCount: Long,
        gcYoungTimeMs: Long,
        gcOldTimeMs: Long
    ): JvmMetrics = JvmMetrics.builder()
        .heapUsedBytes(0)
        .heapCommittedBytes(0)
        .heapMaxBytes(-1)
        .nonHeapUsedBytes(0)
        .nonHeapCommittedBytes(0)
        .nonHeapMaxBytes(-1)
        .memoryPools(emptyList())
        .gcYoungCount(gcYoungCount)
        .gcYoungTimeMs(gcYoungTimeMs)
        .gcOldCount(gcOldCount)
        .gcOldTimeMs(gcOldTimeMs)
        .gcCollectors(emptyList())
        .threadCount(0)
        .daemonThreadCount(0)
        .peakThreadCount(0)
        .deadlockedThreadCount(0)
        .loadedClassCount(0)
        .totalLoadedClassCount(0)
        .processCpuLoad(-1.0)
        .systemCpuLoad(-1.0)
        .uptimeMs(0)
        .startTimeMs(0)
        .jvmArgs(emptyList())
        .build()
}
