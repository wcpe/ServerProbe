package top.wcpe.mc.plugin.serverprobe.core.registry

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.collector.ProxyMetricsCollector
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics

/** 探针注册中心的代理采集器生命周期测试。 */
class ProbeRegistryTest {

    /** 平台卸载后已注销的代理采集器不得继续暴露给编排层。 */
    @Test
    fun `注销代理采集器后不再公开`() {
        val registry = ProbeRegistry()
        val collector = object : ProxyMetricsCollector {
            override fun collect(): ProxyMetrics = ProxyMetrics.builder().build()
        }

        registry.register(collector)
        registry.unregister(collector)

        assertTrue(registry.proxyCollectors.isEmpty())
    }
}
