package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandRegistry
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneIdentity
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration

/**
 * [ManagedConfigService] 的模式装配单测（FR-32 / FR-33）。
 *
 * 守的是**独立模式零回归**这条硬约束：
 * - 独立模式：`config_push` 不注册（指令自然回落到平台执行器并按既有语义降级），`config_state` 不起线程；
 * - 托管模式：`config_push` 注册为内置动作，经同一分发中心可被派发，且载荷残缺时回**中文**失败回执
 *   （而不是把异常抛进桥的读循环）。
 *
 * 装配判定抽在 [ManagedConfigService.install] 上，故这里不需要起线程、连网络或伪造整条 IOC 容器。
 */
class ManagedConfigServiceTest {

    private lateinit var registry: BridgeCommandRegistry

    private lateinit var service: ManagedConfigService

    @BeforeEach
    fun setUp() {
        ProbeConfig.conf = testConfiguration(mapOf("bridge.instance" to "instance-9"))
        ControlPlaneRuntime.reset()
        ManagedConfigState.reset()
        ManagedConfigStore.clear()
        registry = BridgeCommandRegistry()
        service = ManagedConfigService()
        service.commandRegistry = registry
        // 回执帧会读实例标识；未连接状态下 sendUplink 只返回 NotConnected，不会真的发帧
        service.bridge = BridgeClient()
    }

    @AfterEach
    fun tearDown() {
        ProbeConfig.conf = testConfiguration()
        ControlPlaneRuntime.reset()
        ManagedConfigState.reset()
        ManagedConfigStore.clear()
    }

    /** 独立模式：**不注册** `config_push`（这是"零回归"的开关点）。 */
    @Test
    fun `独立模式不注册 config_push`() {
        assertFalse(service.install(ProbeMode.STANDALONE), "独立模式不应启用托管链路")
        assertNull(registry.dispatchCoreAction(pushCommand("""{"configVersion":1,"baseVersion":0}""")), "动作不应被注册")
    }

    /** 托管模式：`config_push` 注册为内置动作，可经分发中心派发。 */
    @Test
    fun `托管模式注册 config_push`() {
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity())

        assertTrue(service.install(ProbeMode.MANAGED), "托管模式应启用托管链路")
        assertNotNull(
            registry.dispatchCoreAction(pushCommand("""{"configVersion":1,"baseVersion":0,"values":{}}""")),
            "动作应已注册并可派发",
        )
    }

    /** 载荷残缺：回中文失败回执，绝不让异常冒到桥的读循环。 */
    @Test
    fun `载荷残缺时回中文失败回执`() {
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity())
        service.install(ProbeMode.MANAGED)

        val result = registry.dispatchCoreAction(pushCommand("""{"baseVersion":0}"""))

        assertEquals(false, result?.success)
        assertTrue(result?.error?.contains("configVersion") == true, "回执应点明缺什么，实际:${result?.error}")
    }

    /** 载荷不是 JSON：同样只回失败回执，不抛。 */
    @Test
    fun `载荷非 JSON 时回失败回执`() {
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity())
        service.install(ProbeMode.MANAGED)

        val result = registry.dispatchCoreAction(pushCommand("不是 JSON"))

        assertEquals(false, result?.success)
        assertTrue(result?.error?.contains("不可解析") == true, "实际:${result?.error}")
    }

    /** 构造一条 `config_push` 指令。 */
    private fun pushCommand(payload: String): BridgeCommand = BridgeCommand(
        action = "config_push",
        target = "",
        reason = "",
        requestId = "req-cfg",
        domain = "",
        payload = payload,
    )

    /** 一份最小可用身份（只为把运行模式置为托管）。 */
    private fun identity(): ControlPlaneIdentity = ControlPlaneIdentity(
        schemaVersion = 1,
        provider = "any",
        endpoint = "ws://cp.example/agent",
        instanceId = "instance-9",
        credential = "opaque",
        capabilities = emptyList(),
        createdAt = "2026-10-19T03:12:44Z",
    )
}
