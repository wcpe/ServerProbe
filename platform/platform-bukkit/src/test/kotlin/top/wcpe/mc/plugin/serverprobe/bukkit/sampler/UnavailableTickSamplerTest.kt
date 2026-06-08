package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource

/**
 * [UnavailableTickSampler] 单元测试。
 *
 * Folia 路径采样器不依赖任何平台 API,可直接构造验证其契约:source 恒为 [TickSampleSource.UNAVAILABLE],
 * 且 [UnavailableTickSampler.sample] 六个数值字段全为 null(N/A)。
 */
class UnavailableTickSamplerTest {

    /** source 应固定为 UNAVAILABLE。 */
    @Test
    fun `source 为 UNAVAILABLE`() {
        assertEquals(TickSampleSource.UNAVAILABLE, UnavailableTickSampler().source, "source 应为 UNAVAILABLE")
    }

    /** sample() 六个数值字段应全为 null,且 source 一致。 */
    @Test
    fun `sample 六个数值字段全为 null`() {
        val sample = UnavailableTickSampler().sample()
        assertNull(sample.tps1m, "tps1m 应为 null")
        assertNull(sample.tps5m, "tps5m 应为 null")
        assertNull(sample.tps15m, "tps15m 应为 null")
        assertNull(sample.msptAvg, "msptAvg 应为 null")
        assertNull(sample.msptP95, "msptP95 应为 null")
        assertNull(sample.msptP99, "msptP99 应为 null")
        assertEquals(TickSampleSource.UNAVAILABLE, sample.source, "采样结果 source 应为 UNAVAILABLE")
    }
}
