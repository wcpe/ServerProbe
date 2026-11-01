package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCapabilities
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandRegistry
import top.wcpe.mc.plugin.serverprobe.core.bridge.MetricPushService
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigService
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpBridgeService
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * “stop 先到、装配后到”的行为契约（三个托管服务各一条）。
 *
 * 旧实现的共同病根：`stop()` 与装配走的是两把钥匙（或 stop 根本不拿锁）——
 * stop 先把标志置上后，装配线程仍会起线程、注册动作、声明能力位，
 * 得到“插件已停服却还在周期上报 / 指令仍指向已释放执行体”的残留。
 *
 * 这里不断言实现细节，只断言两件可观测的事：
 * 1. stop 先到 → 装配不注册动作、不起线程；
 * 2. 正常装配后 stop → 动作与能力位都被摘除（否则 `core.mcp` / `config_push` 仍可被分发到已停的执行体）。
 */
class ServiceStopRaceTest {

    @BeforeEach
    fun setUp() {
        ControlPlaneRuntime.reset()
        // 能力位是进程级单例：上一个用例可能已声明过，先摘干净才能断言“停后不存在”
        BridgeCapabilities.removeCapability(BridgeCapabilities.CAP_MCP_BRIDGE)
        // 配置是全局单例：给一份空配置，避免上一个用例的键泄漏进来
        ProbeConfig.conf = testConfiguration()
    }

    @AfterEach
    fun tearDown() {
        ControlPlaneRuntime.reset()
        BridgeCapabilities.removeCapability(BridgeCapabilities.CAP_MCP_BRIDGE)
    }

    // ── 指标推送 ──

    /** 正常装配：`metrics_now` 被注册，推送线程起来；stop 后两者都消失。 */
    @Test
    fun `指标推送停止后注销动作并停掉线程`() {
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, null)
        val registry = BridgeCommandRegistry()
        val service = MetricPushService()
        service.commandRegistry = registry
        service.modeService = ControlPlaneModeService()
        val booted = CountDownLatch(1)
        service.modeReadiness = { booted.countDown() }

        service.start()
        assertTrue(booted.await(WAIT_MS, TimeUnit.MILLISECONDS), "装配线程应当启动")
        assertTrue(
            awaitCondition { registry.dispatchCoreAction(command(METRICS_NOW)) != null },
            "托管模式下应当注册 metrics_now",
        )

        service.stop()

        assertNull(registry.dispatchCoreAction(command(METRICS_NOW)), "stop 后不得再分发 metrics_now")
        assertFalse(threadAlive(METRIC_PUSH_THREAD), "stop 后不得留下推送线程")
    }

    /** stop 先到：装配不得注册动作、不得起线程。 */
    @Test
    fun `指标推送的 stop 先到时不再装配`() {
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, null)
        val registry = BridgeCommandRegistry()
        val service = MetricPushService()
        service.commandRegistry = registry
        service.modeService = ControlPlaneModeService()
        val booted = CountDownLatch(1)
        service.modeReadiness = { booted.countDown() }

        service.stop()
        service.start()

        assertTrue(booted.await(WAIT_MS, TimeUnit.MILLISECONDS), "装配线程应当启动（否则本用例无意义）")
        assertTrue(awaitCondition { !threadAlive(METRIC_PUSH_THREAD) }, "stop 先到后不得起推送线程")
        assertNull(registry.dispatchCoreAction(command(METRICS_NOW)), "stop 先到后不得注册 metrics_now")
    }

    // ── 托管配置 ──

    /** 装配后 stop：`config_push` 被注销（否则停服后指令仍会落到已停的服务上）。 */
    @Test
    fun `配置服务停止后注销 config_push`() {
        val registry = BridgeCommandRegistry()
        val service = ManagedConfigService()
        service.commandRegistry = registry

        assertTrue(service.install(ProbeMode.MANAGED), "托管模式下应装配成功")
        assertNotNull(registry.dispatchCoreAction(command(CONFIG_PUSH)), "装配后应当注册 config_push")

        service.stop()

        assertNull(registry.dispatchCoreAction(command(CONFIG_PUSH)), "stop 后不得再分发 config_push")
    }

    /** stop 先到：不得注册 `config_push`，也不得起上报线程。 */
    @Test
    fun `配置服务的 stop 先到时不再装配`() {
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, null)
        val registry = BridgeCommandRegistry()
        val service = ManagedConfigService()
        service.commandRegistry = registry
        service.modeService = ControlPlaneModeService()
        val booted = CountDownLatch(1)
        service.modeReadiness = { booted.countDown() }

        service.stop()
        service.start()

        assertTrue(booted.await(WAIT_MS, TimeUnit.MILLISECONDS), "装配线程应当启动（否则本用例无意义）")
        assertTrue(awaitCondition { !threadAlive(CONFIG_STATE_THREAD) }, "stop 先到后不得起上报线程")
        assertNull(registry.dispatchCoreAction(command(CONFIG_PUSH)), "stop 先到后不得注册 config_push")
    }

    // ── MCP 经桥 ──

    /** 挂载后 stop：动作与能力位一起摘除（否则控制面会继续调一个已释放的工具面）。 */
    @Test
    fun `MCP 挂载后停止会摘掉动作与能力位`() {
        val registry = BridgeCommandRegistry()
        val service = McpBridgeService()
        service.commandRegistry = registry

        assertTrue(service.mount { BridgeCommandResult.ok("ok") }, "未停止时挂载应当成功")
        assertTrue(BridgeCapabilities.snapshot().contains(BridgeCapabilities.CAP_MCP_BRIDGE), "挂载后应声明能力位")
        assertNotNull(registry.dispatchCoreAction(command(MCP_ACTION)), "挂载后应当能分发 core.mcp")

        service.stop()

        assertNull(registry.dispatchCoreAction(command(MCP_ACTION)), "stop 后不得再分发 core.mcp")
        assertFalse(
            BridgeCapabilities.snapshot().contains(BridgeCapabilities.CAP_MCP_BRIDGE),
            "stop 后不得再声明 mcp_bridge 能力位",
        )
    }

    /** stop 先到：挂载直接放弃，一样都不注册。 */
    @Test
    fun `MCP 的 stop 先到时挂载直接放弃`() {
        val registry = BridgeCommandRegistry()
        val service = McpBridgeService()
        service.commandRegistry = registry

        service.stop()

        assertFalse(service.mount { BridgeCommandResult.ok("ok") }, "stop 先到时挂载必须放弃")
        assertNull(registry.dispatchCoreAction(command(MCP_ACTION)), "stop 先到后不得注册 core.mcp")
        assertFalse(
            BridgeCapabilities.snapshot().contains(BridgeCapabilities.CAP_MCP_BRIDGE),
            "stop 先到后不得声明能力位",
        )
    }

    /** 构造一条只用于分发的命令（执行体会失败，但“能不能被分发”才是本类要验的）。 */
    private fun command(action: String): BridgeCommand = BridgeCommand(action, "", "", "req-$action")

    /** 有界等待条件成立；超时返回 false，让用例明确报错而不是隐式通过。 */
    private fun awaitCondition(timeoutMs: Long = WAIT_MS, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private fun threadAlive(name: String): Boolean =
        Thread.getAllStackTraces().keys.any { it.isAlive && it.name == name }

    private companion object {
        private const val WAIT_MS = 2_000L
        private const val POLL_MS = 20L
        private const val METRICS_NOW = "metrics_now"
        private const val CONFIG_PUSH = "config_push"
        private const val MCP_ACTION = "mcp"
        private const val METRIC_PUSH_THREAD = "ServerProbe-MetricPush"
        private const val CONFIG_STATE_THREAD = "ServerProbe-ConfigState"
    }
}
