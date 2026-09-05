package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Paper 采样器 MSPT 混合兜底的纯函数测试。
 *
 * 1.16 之前的 Paper 缺 `getAverageTickTime`/`getTickTimes`(真机 1.12.2 已证):
 * 原生 MSPT 缺失时回退共享自建直方图估算,TPS 仍走官方 API。
 */
class PaperTickSamplerMsptFallbackTest {

    @Test
    fun `原生 MSPT 可用时原样返回不消费直方图`() {
        val histogram = MsptHistogram(capacity = 10)
        val resolved = PaperTickSampler.resolveMspt(12.5, 13.5, 14.5, histogram)
        assertEquals(Triple(12.5, 13.5, 14.5), resolved)
    }

    @Test
    fun `原生 MSPT 缺失且回退直方图无样本时保持 N_A`() {
        val histogram = MsptHistogram(capacity = 10)
        val resolved = PaperTickSampler.resolveMspt(null, null, null, histogram)
        assertNull(resolved.first)
        assertNull(resolved.second)
        assertNull(resolved.third)
    }

    @Test
    fun `原生 MSPT 缺失时回退直方图均值与分位`() {
        val histogram = MsptHistogram(capacity = 100)
        repeat(20) { histogram.add((50_000_000L * (it + 1))) }
        val resolved = PaperTickSampler.resolveMspt(null, null, null, histogram)
        val (avg, p95, p99) = resolved
        org.junit.jupiter.api.Assertions.assertNotNull(avg)
        org.junit.jupiter.api.Assertions.assertNotNull(p95)
        org.junit.jupiter.api.Assertions.assertNotNull(p99)
    }

    @Test
    fun `无直方图时原生缺失保持 N_A`() {
        val resolved = PaperTickSampler.resolveMspt(null, null, null, null)
        assertNull(resolved.first)
    }
}
