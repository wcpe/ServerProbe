package top.wcpe.mc.plugin.serverprobe.e2e.load

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 直方图的正确性验证。
 *
 * ## 为什么这些用例非写不可
 *
 * 分位数实现最容易出的两类错，都是"看上去能跑、数字却是错的"：
 *
 * 1. **桶映射错位**：把 1ms 映射到 100µs 的桶里，于是"链路已经堵了"被报成健康。
 *    这种错不会抛异常，只会让压测结论完全失真。所以每个数量级都得拿一个
 *    已知值进去、把分位数读出来对。
 * 2. **分位偏置**：取桶中点还是上界、rank 用 ceil 还是 floor，都会让 p99 系统性地偏低。
 *    这里用"全部样本都是同一个值"的极端输入验证：此时任何合理的实现都必须原样返回该值。
 *
 * 基线正确之后，才谈得上用它去判断真实链路。
 */
class LatencyHistogramTest {

    /** 全部样本同一值 → 四分位全等于该值（允许桶上界的近似）。 */
    @Test
    fun `单值输入下所有分位等于该值`() {
        val histogram = LatencyHistogram()
        repeat(1000) { histogram.record(1_500_000) } // 1.5 ms

        val snapshot = histogram.snapshot()
        assertEquals(1000, snapshot.count)
        assertTrue(snapshot.p50Millis() in 1.5..1.51, "p50 应为 1.5ms 附近，实际=${snapshot.p50Millis()}")
        assertTrue(snapshot.p99Millis() in 1.5..1.51, "p99 应为 1.5ms 附近，实际=${snapshot.p99Millis()}")
    }

    /** 微秒级小值（线性区）必须精确，不能被数量级桶粗化掉。 */
    @Test
    fun `微秒级值保持精确`() {
        val histogram = LatencyHistogram()
        repeat(100) { histogram.record(42_000) } // 42 µs

        val snapshot = histogram.snapshot()
        assertEquals(42_000, snapshot.minNanos, "min 应精确为 42µs")
        assertEquals(42_000, snapshot.maxNanos, "max 应精确为 42µs")
        assertTrue(snapshot.p50Millis() in 0.042..0.043, "p50 应≈42µs，实际=${snapshot.p50Millis()}")
    }

    /** 跨数量级（µs → ms）都能正确分位，不串档。 */
    @Test
    fun `跨数量级分位不串档`() {
        val histogram = LatencyHistogram()
        // 9900 个 100µs + 100 个 5ms → p50 在 100µs 区，p999 跳到 5ms 区
        repeat(9900) { histogram.record(100_000) }
        repeat(100) { histogram.record(5_000_000) }

        val snapshot = histogram.snapshot()
        assertTrue(snapshot.p50Millis() in 0.1..0.11, "p50 应≈100µs，实际=${snapshot.p50Millis()}")
        assertTrue(snapshot.p999Millis() >= 5.0, "p999 必须落入 5ms 区，实际=${snapshot.p999Millis()}")
        assertEquals(5_000_000, snapshot.maxNanos, "max 应精确为 5ms")
    }

    /**
     * 长尾必须被报出：1% 的样本很慢时，**p999** 必须反映它。
     *
     * ## 为何用 p999 而不是 p99（这里踩过一次坑）
     *
     * 首版写成“1% 长尾 → 用 p99 验证”，结果 p99 真的是 50µs。当时以为实现错了，
     * 实际是**期望值错**：10000 个样本中 9900 个是 50µs，`rank(p99)=ceil(0.99*10000)=9900`，
     * 累加到第 9900 个恰好仍是 50µs —— 那 100 个慢请求属于**前 1%**，在 p99 之后。
     *
     * 这个细节值得留在测试里：分位数的 rank 边界极易错。写错期望值会让
     * “实现正确”被当成“实现有 bug”，反过来也可能让真 bug 被错误期望掩盖。
     *
     * 同时验证 p99.9 能捕获它，而 p50 仍在小值区——两侧都对才算度量有效。
     */
    @Test
    fun `百分之一的长尾能被 p999 捕获`() {
        val histogram = LatencyHistogram()
        repeat(9900) { histogram.record(50_000) } // 50 µs，占 99%
        repeat(100) { histogram.record(20_000_000) } // 20 ms，占 1%（top 1%）

        val snapshot = histogram.snapshot()
        assertEquals(10_000, snapshot.count)
        assertTrue(snapshot.p50Millis() < 0.1, "p50 应仍在小值区，实际=${snapshot.p50Millis()}")
        assertTrue(
            snapshot.p99Millis() < 1.0,
            "p99 仍应落在 50µs 区（rank=9900 恰好是最后一个快样本），实际=${snapshot.p99Millis()}",
        )
        assertTrue(
            snapshot.p999Millis() >= 20.0,
            "p999 必须反映 1% 的长尾（20ms），实际=${snapshot.p999Millis()}，若报成小值则度量失效",
        )
    }

    /** 超过 maxTrackedMillis 的样本计入溢出，不得拉低分位。 */
    @Test
    fun `超出跟踪范围的样本不被静默丢弃`() {
        val histogram = LatencyHistogram(maxTrackedMillis = 10)
        repeat(100) { histogram.record(1_000_000) } // 1ms
        repeat(1) { histogram.record(500_000_000) } // 500ms，超出 10ms 范围

        val snapshot = histogram.snapshot()
        assertEquals(101, snapshot.count, "溢出样本也必须计入总数")
        assertEquals(500_000_000, snapshot.maxNanos, "max 必须是真实的 500ms")
    }

    /** 负值归零而不丢样本（时钟回退等异常输入）。 */
    @Test
    fun `负值归零但仍计数`() {
        val histogram = LatencyHistogram()
        histogram.record(-1000)
        histogram.record(2000)

        val snapshot = histogram.snapshot()
        assertEquals(2, snapshot.count)
        assertEquals(0, snapshot.minNanos)
    }

    /** 空直方图返回 EMPTY，不抛。 */
    @Test
    fun `空直方图返回空快照`() {
        val snapshot = LatencyHistogram().snapshot()
        assertEquals(0, snapshot.count)
        assertEquals(0, snapshot.p99Nanos)
    }

    /**
     * 并发写入不丢计数。
     *
     * 压测时 N 个线程会同时 record()；这里用多线程验证计数精确，
     * 避免"用无锁实现但真的丢了样本"这种最难发现的问题。
     */
    @Test
    fun `多线程写入计数精确`() {
        val histogram = LatencyHistogram()
        val threads = 8
        val perThread = 10_000
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val tasks = (1..threads).map {
                java.util.concurrent.Callable {
                    repeat(perThread) { histogram.record(1_000) }
                }
            }
            pool.invokeAll(tasks)
        } finally {
            pool.shutdown()
        }
        assertEquals((threads * perThread).toLong(), histogram.snapshot().count, "并发写入不得丢计数")
    }
}
