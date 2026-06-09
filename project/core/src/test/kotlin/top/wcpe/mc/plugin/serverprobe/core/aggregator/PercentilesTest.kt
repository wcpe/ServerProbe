package top.wcpe.mc.plugin.serverprobe.core.aggregator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [Percentiles] 单元测试(core 跨快照聚合用的 Double/毫秒分位与均值)。
 *
 * 为纯函数,不依赖任何平台/框架,可直接调用验证。覆盖:固定样本的均值与最近秩分位、
 * 空数组返回 null、单样本、p=1.0 取最大值,以及"不修改入参数组"的纯函数语义。
 */
class PercentilesTest {

    /** 浮点断言容差。 */
    private val delta = 1e-9

    /** 空数组:均值与分位均返回 null。 */
    @Test
    fun `空数组均返回 null`() {
        assertNull(Percentiles.average(DoubleArray(0)), "空数组均值应为 null")
        assertNull(Percentiles.percentile(DoubleArray(0), 0.95), "空数组分位应为 null")
    }

    /** 固定样本均值应为算术平均。 */
    @Test
    fun `固定样本均值正确`() {
        // 10、20、30 → 均值 20
        val samples = doubleArrayOf(10.0, 20.0, 30.0)
        assertEquals(20.0, Percentiles.average(samples)!!, delta, "均值应为 20")
    }

    /**
     * 1..100 共 100 个样本,按最近秩:
     * p95 = ceil(0.95×100)=95 → 第 95 个(升序)= 95;
     * p99 = ceil(0.99×100)=99 → 第 99 个 = 99。
     */
    @Test
    fun `固定样本分位按最近秩正确`() {
        val samples = DoubleArray(100) { (it + 1).toDouble() }
        assertEquals(95.0, Percentiles.percentile(samples, 0.95)!!, delta, "p95 应为 95")
        assertEquals(99.0, Percentiles.percentile(samples, 0.99)!!, delta, "p99 应为 99")
    }

    /** 单样本:均值与任意分位均等于该样本。 */
    @Test
    fun `单样本均值与分位相等`() {
        val samples = doubleArrayOf(42.0)
        assertEquals(42.0, Percentiles.average(samples)!!, delta, "单样本均值应为 42")
        assertEquals(42.0, Percentiles.percentile(samples, 0.95)!!, delta, "单样本 p95 应为 42")
    }

    /** p=1.0 应取最大值(最近秩 ceil(1.0×n)=n,即末位)。 */
    @Test
    fun `p 为一取最大值`() {
        val samples = doubleArrayOf(3.0, 1.0, 2.0)
        assertEquals(3.0, Percentiles.percentile(samples, 1.0)!!, delta, "p=1.0 应为最大值 3")
    }

    /** 纯函数语义:分位计算不得修改入参数组顺序。 */
    @Test
    fun `分位不修改入参数组`() {
        val samples = doubleArrayOf(3.0, 1.0, 2.0)
        Percentiles.percentile(samples, 0.5)
        assertEquals(
            listOf(3.0, 1.0, 2.0),
            samples.toList(),
            "percentile 应在副本上排序,不污染入参顺序"
        )
    }
}
