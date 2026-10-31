package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration

/**
 * `metrics-push-interval-ms` 取值单测（FR-34，协议规格 §3.3 / §3.5）。
 *
 * 锁定两件事：默认 5000ms；低于物理下限 1000ms 时按 1000 生效（防止把反向 WS 打满）。
 */
class MetricsPushIntervalTest {

    @BeforeEach
    fun setUp() {
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
    }

    @AfterEach
    fun tearDown() {
        ProbeConfig.conf = testConfiguration()
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
    }

    /** 配置缺项时取默认 5000ms。 */
    @Test
    fun `配置缺项时取默认 5000`() {
        ProbeConfig.conf = testConfiguration()

        assertEquals(5_000L, ProbeConfig.metricsPushIntervalMs())
    }

    /** 配置显式值时按配置值生效（托管面热调即走这条路径）。 */
    @Test
    fun `配置值优先`() {
        ProbeConfig.conf = testConfiguration(mapOf("metrics-push-interval-ms" to 15_000L))

        assertEquals(15_000L, ProbeConfig.metricsPushIntervalMs())
    }

    /** 低于下限（1000ms）时兜底到 1000ms：绝不把反向 WS 打满。 */
    @Test
    fun `低于下限时按下限生效`() {
        ProbeConfig.conf = testConfiguration(mapOf("metrics-push-interval-ms" to 1L))

        assertEquals(1_000L, ProbeConfig.metricsPushIntervalMs())
    }
}
