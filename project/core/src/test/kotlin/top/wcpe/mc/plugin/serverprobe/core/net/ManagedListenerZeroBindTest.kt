package top.wcpe.mc.plugin.serverprobe.core.net

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigStore
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneIdentity
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneModeService
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerGate
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerLauncher
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpControlPlane
import top.wcpe.mc.plugin.serverprobe.core.prometheus.PrometheusExporter
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration
import top.wcpe.mc.plugin.serverprobe.core.web.WebPanelServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 托管模式**零监听端口**验收测试（FR-34，协议规格 §3.6）。
 *
 * 断言方式不是"看配置开关"，而是**证明 bind 没被调用**：`metrics` / `web` 两个监听面通过
 * [HttpListenerFactory] 注入记录型替身，托管模式下调用其真实 bind 入口后计数必须为 0；
 * `mcp` 的 bind 入口是 `McpControlPlane.enable()`，托管模式下必须直接拒绝且不进入运行态。
 *
 * 关键前提：本地 `config.yml` 里**故意留着 `metrics.enabled=true` / `web.enabled=true`**
 * （存量迁移只移除 `bridge` 段，这三个开关的遗留值仍在），以此证明关断依据是运行模式而非配置。
 *
 * 独立模式回归：同一入口在独立模式下必须照旧 bind 一次。
 */
class ManagedListenerZeroBindTest {

    /** 记录 bind 次数的监听器工厂替身；**刻意不执行 configure**，避免连带构造处理器依赖。 */
    private class RecordingListenerFactory : HttpListenerFactory {

        /** bind 调用次数。 */
        val calls = AtomicLong(0)

        /** 最近一次绑定地址。 */
        @Volatile
        var lastAddress: InetSocketAddress? = null

        override fun bind(address: InetSocketAddress, backlog: Int, configure: (HttpServer) -> Unit): LocalHttpListener {
            calls.incrementAndGet()
            lastAddress = address
            return object : LocalHttpListener {
                override fun stop() = Unit
            }
        }
    }

    @BeforeEach
    fun setUp() {
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
        // 遗留 true：模拟"迁移只移除 bridge 段"后的真实磁盘状态
        ProbeConfig.conf = testConfiguration(
            mapOf(
                "metrics.enabled" to true,
                "metrics.host" to "127.0.0.1",
                "metrics.port" to 9940,
                "metrics.allowed-ips" to listOf("127.0.0.1"),
                "web.enabled" to true,
                "web.host" to "127.0.0.1",
                "web.port" to 9950,
                "web.allowed-ips" to listOf("127.0.0.1"),
                "mcp.enabled" to true,
                "mcp.host" to "127.0.0.1",
                "mcp.port" to 9960,
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
        ProbeConfig.conf = testConfiguration()
    }

    /** `/metrics` 监听面：托管模式下 bind 一次都不许发生。 */
    @Test
    fun `托管模式下 metrics 监听面不 bind`() {
        val factory = RecordingListenerFactory()
        val exporter = PrometheusExporter()
        exporter.listenerFactory = factory

        managed()

        exporter.bindIfStandalone()

        assertEquals(0L, factory.calls.get(), "托管模式下 /metrics 不得 bind（即使 config.yml 留着 enabled=true）")
        exporter.stop()
    }

    /** Web 面板监听面：托管模式下 bind 一次都不许发生。 */
    @Test
    fun `托管模式下 web 监听面不 bind`() {
        val factory = RecordingListenerFactory()
        val panel = WebPanelServer()
        panel.listenerFactory = factory

        managed()

        panel.bindIfStandalone()

        assertEquals(0L, factory.calls.get(), "托管模式下 Web 面板不得 bind（即使 config.yml 留着 enabled=true）")
        panel.stop()
    }

    /** MCP 控制面：托管模式下开启被拒，且不进入运行态（bind 入口即 `enable()`）。 */
    @Test
    fun `托管模式下 MCP 控制面拒绝开启`() {
        val plane = McpControlPlane()

        managed()

        val outcome = plane.enable()

        assertFalse(outcome.success, "托管模式下 MCP 必须拒绝开启")
        // 精确对齐闸门文案：否则"启动失败（端口占用/取目录异常）"也会让 success=false，断言会变成空转
        assertEquals(
            "托管模式下不启动本地监听端口（零监听端口承诺），MCP 控制面已跳过。",
            outcome.message,
            "拒绝原因必须是托管模式闸门给出的，而不是其它启动失败",
        )
        assertFalse(plane.running, "拒绝后不得处于监听状态")
    }

    /**
     * 启动期路径（`@PostEnable`）同样是"等判定后再决定"，且**主线程不 bind**。
     *
     * 判定服务在此用真实实例（`bootstrap()` 在无平台环境下会降级并放行），放行后我们再发布托管模式，
     * 于是后台线程醒来时读到的就是 MANAGED——bind 必须仍然不发生。
     */
    @Test
    fun `启动期等待判定后仍不 bind`() {
        val factory = RecordingListenerFactory()
        val exporter = PrometheusExporter()
        exporter.listenerFactory = factory
        exporter.modeService = decidedModeService()

        managed()

        exporter.start()
        assertEquals(0L, factory.calls.get(), "start() 不得在调用线程上直接 bind")

        // 给后台线程足够时间走到闸门；期间计数必须恒为 0
        repeat(POLL_TIMES) {
            Thread.sleep(POLL_INTERVAL_MS)
            assertEquals(0L, factory.calls.get(), "后台线程也不得 bind")
        }
        exporter.stop()
    }

    /** 独立模式回归：同一 bind 入口照旧按配置起服（一次）。 */
    @Test
    fun `独立模式下两个监听面照旧 bind`() {
        val metricsFactory = RecordingListenerFactory()
        val exporter = PrometheusExporter()
        exporter.listenerFactory = metricsFactory

        val webFactory = RecordingListenerFactory()
        val panel = WebPanelServer()
        panel.listenerFactory = webFactory

        // 独立模式：闸门放行，行为与引入托管能力前一致
        ControlPlaneRuntime.reset()

        exporter.bindIfStandalone()
        panel.bindIfStandalone()

        assertEquals(1L, metricsFactory.calls.get(), "独立模式下 /metrics 应照旧 bind 一次")
        assertEquals(1L, webFactory.calls.get(), "独立模式下 Web 面板应照旧 bind 一次")
        assertEquals(9940, metricsFactory.lastAddress?.port, "绑定端口应取自 config.yml")
        assertEquals(9950, webFactory.lastAddress?.port, "绑定端口应取自 config.yml")

        exporter.stop()
        panel.stop()
    }

    /** 监听面启动器：在守护后台线程里先等判定，再执行 bind。 */
    @Test
    fun `启动器在后台线程等待判定后执行`() {
        val latch = CountDownLatch(1)
        val waitMs = AtomicLong(0)
        var executedThread: String? = null
        val launcher = LocalListenerLauncher(
            LocalListenerGate.Surface.METRICS,
            awaitDecision = { timeout -> waitMs.set(timeout); true },
        )

        val worker = launcher.launch {
            executedThread = Thread.currentThread().name
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "后台线程应在判定后执行 bind 动作")
        worker.join(5_000)
        assertTrue(worker.isDaemon, "监听面线程必须是守护线程")
        assertTrue(executedThread!!.startsWith("ServerProbe-Listener-"), "实际线程名:$executedThread")
        assertEquals(5_000L, waitMs.get(), "等待上限应与桥的启动等待同量级")
    }

    /** 发布托管模式（带最小合法身份，模拟身份文件驱动的运行态）。 */
    private fun managed() {
        ControlPlaneRuntime.publish(
            ProbeMode.MANAGED,
            ControlPlaneIdentity(
                schemaVersion = ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION,
                provider = "unknown",
                endpoint = "ws://127.0.0.1:9102/ws/plugin-bridge",
                instanceId = "instance-1",
                credential = "token",
                capabilities = emptyList(),
                createdAt = "2026-10-19T05:12:44Z",
            ),
        )
    }

    /** 已放行的模式判定服务（判定完成后返回，供启动期路径使用）。 */
    private fun decidedModeService(): ControlPlaneModeService = ControlPlaneModeService().also { service ->
        service.bootstrap()
        service.awaitReady(5_000)
    }

    private companion object {

        /** 轮询次数与间隔：够长以暴露真实的异步回归，又不拖慢测试。 */
        private const val POLL_TIMES = 20
        private const val POLL_INTERVAL_MS = 50L
    }
}
