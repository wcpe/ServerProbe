package top.wcpe.mc.plugin.serverprobe.velocity.collector

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import taboolib.common.platform.service.PlatformExecutor
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry

/** Velocity 采集器关闭时的注册中心清理测试。 */
class VelocityProxyCollectorLifecycleTest {

    /** 关闭采集器后，编排层不应再看到已经卸载的 Velocity 采集器。 */
    @Test
    fun `关闭时注销代理采集器`() {
        val registry = ProbeRegistry()
        val collector = VelocityProxyCollector().apply { this.registry = registry }
        registry.register(collector)

        collector.shutdown()

        assertTrue(registry.proxyCollectors.isEmpty())
    }

    /** 周期任务与本轮缓存必须随采集器一并释放。 */
    @Test
    fun `关闭时取消周期任务并清空后端缓存`() {
        val cache = mutableMapOf("lobby" to "reachable")
        val task = CancelRecordingTask()
        val lifecycle = VelocityCollectorLifecycle(cache)

        lifecycle.start(task)
        lifecycle.close()

        assertTrue(task.cancelled)
        assertTrue(cache.isEmpty())
    }

    private class CancelRecordingTask : PlatformExecutor.PlatformTask {

        var cancelled = false
            private set

        override fun cancel() {
            cancelled = true
        }
    }
}
