package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [MsptHistogram] 单元测试。
 *
 * [MsptHistogram] 为纯逻辑(环形窗口 + 分位计算),不依赖 Bukkit/NMS,可直接构造验证。
 * 覆盖:空窗口返回 null、固定样本的均值与 p95/p99(最近秩)、容量滚动后旧样本被覆盖。
 */
class MsptHistogramTest {

    /** 纳秒/毫秒换算:1ms = 1_000_000ns,便于以毫秒意图构造样本。 */
    private val msToNanos = 1_000_000L

    /** 浮点断言容差(毫秒)。 */
    private val delta = 1e-9

    /** 空窗口下均值与分位均应返回 null。 */
    @Test
    fun `空窗口各项返回 null`() {
        val histogram = MsptHistogram(capacity = 10)
        assertNull(histogram.avgMs(), "空窗口均值应为 null")
        assertNull(histogram.p95Ms(), "空窗口 p95 应为 null")
        assertNull(histogram.p99Ms(), "空窗口 p99 应为 null")
    }

    /** 固定样本下均值应为算术平均(毫秒)。 */
    @Test
    fun `固定样本均值正确`() {
        val histogram = MsptHistogram(capacity = 10)
        // 10ms、20ms、30ms → 均值 20ms
        histogram.add(10 * msToNanos)
        histogram.add(20 * msToNanos)
        histogram.add(30 * msToNanos)
        assertEquals(20.0, histogram.avgMs()!!, delta, "均值应为 20ms")
    }

    /**
     * 1..100ms 共 100 个样本,按最近秩:
     * p95 = ceil(0.95×100)=95 → 第 95 个(升序)= 95ms;
     * p99 = ceil(0.99×100)=99 → 第 99 个 = 99ms。
     */
    @Test
    fun `固定样本 p95 p99 按最近秩正确`() {
        val histogram = MsptHistogram(capacity = 100)
        for (ms in 1..100) {
            histogram.add(ms.toLong() * msToNanos)
        }
        assertEquals(95.0, histogram.p95Ms()!!, delta, "p95 应为 95ms")
        assertEquals(99.0, histogram.p99Ms()!!, delta, "p99 应为 99ms")
    }

    /** 单样本时均值与各分位均等于该样本值。 */
    @Test
    fun `单样本均值与分位相等`() {
        val histogram = MsptHistogram(capacity = 5)
        histogram.add(42 * msToNanos)
        assertEquals(42.0, histogram.avgMs()!!, delta, "单样本均值应为 42ms")
        assertEquals(42.0, histogram.p95Ms()!!, delta, "单样本 p95 应为 42ms")
        assertEquals(42.0, histogram.p99Ms()!!, delta, "单样本 p99 应为 42ms")
    }

    /**
     * 容量滚动:容量 3,写入 4 个样本(10/20/30/40ms),
     * 最旧的 10ms 应被覆盖,窗口仅剩 20/30/40ms,均值 30ms。
     */
    @Test
    fun `超容量后旧样本被覆盖`() {
        val histogram = MsptHistogram(capacity = 3)
        histogram.add(10 * msToNanos)
        histogram.add(20 * msToNanos)
        histogram.add(30 * msToNanos)
        histogram.add(40 * msToNanos)
        // 窗口为 {20,30,40} → 均值 30ms,最大分位 40ms
        assertEquals(30.0, histogram.avgMs()!!, delta, "滚动后均值应为 30ms")
        assertEquals(40.0, histogram.p99Ms()!!, delta, "滚动后 p99 应为最新最大值 40ms")
    }

    /** 负样本应被忽略,不污染统计。 */
    @Test
    fun `负样本被忽略`() {
        val histogram = MsptHistogram(capacity = 5)
        histogram.add(-1)
        assertNull(histogram.avgMs(), "仅负样本时窗口应仍为空")
        histogram.add(10 * msToNanos)
        histogram.add(-5)
        assertEquals(10.0, histogram.avgMs()!!, delta, "负样本不应计入均值")
    }
}
