package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigStore
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testJsonObject

/**
 * 下行 `command` 帧的**分发语义**单测。
 *
 * 解析映射与分发出 [BridgeKernel] + [GovernanceRoute] 承担，本测试直接驱动同一套生产路径
 * （帧对象 → [BridgeKernel.toCommand] → [GovernanceRoute]），只把"投池 + 发送器"换成同步调用，
 * 因此断言的是真实分发语义而不是替身。
 *
 * 回执帧的编码（[BridgeKernel.encodeCommandResult]）依赖运行期 JSON 后端，故不在此断言帧形态；
 * 那部分由 [BridgeKernelTest] 用注入编码器覆盖。这里只断言命令侧语义与能力位。
 */
class BridgeClientCommandTest {

    private lateinit var client: BridgeClient

    private lateinit var route: GovernanceRoute

    @BeforeEach
    fun setUp() {
        // 回执与实例标识都会读配置，故先给一个稳定替身
        ProbeConfig.conf = testConfiguration(mapOf("bridge.instance" to "instance-7"))
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
        client = BridgeClient()
        client.commandRegistry = BridgeCommandRegistry()
        route = GovernanceRoute(
            registry = { client.commandRegistry.handler },
            coreAction = client.commandRegistry::dispatchCoreAction,
        )
    }

    @AfterEach
    fun tearDown() {
        ProbeConfig.conf = testConfiguration()
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
    }

    /** `metrics_now` 走内置动作分发，且 requestId 原样带入回执通道。 */
    @Test
    fun `metrics_now 回执带回同一 requestId`() {
        var handledAction = ""
        client.commandRegistry.registerCoreAction("metrics_now") { command ->
            handledAction = command.action
            BridgeCommandResult.ok("已推送一帧 metric_snapshot(sequence=1)")
        }

        val command = commandOf(commandFrame(action = "metrics_now", requestId = "req-42"))
        val result = route.handle(command)

        assertEquals("metrics_now", handledAction, "内置动作应被分发执行")
        assertEquals("req-42", command.requestId, "requestId 必须原样带入回执,Worker 据此匹配等待者")
        assertTrue(result.success)
        assertTrue(result.output.contains("metric_snapshot"))
    }

    /** `metrics_now` 失败时同样带回 requestId，并带中文失败原因。 */
    @Test
    fun `metrics_now 失败仍带回 requestId`() {
        client.commandRegistry.registerCoreAction("metrics_now") { BridgeCommandResult.fail("桥未连接") }

        val command = commandOf(commandFrame(action = "metrics_now", requestId = "req-43"))
        val result = route.handle(command)

        assertEquals("req-43", command.requestId)
        assertFalse(result.success)
        assertEquals("桥未连接", result.error)
    }

    /** 未注册内置动作的治理指令回落到平台执行器；未注册执行器时按既有语义降级。 */
    @Test
    fun `未知动作回落平台治理执行器`() {
        val command = commandOf(commandFrame(action = "kick", requestId = "req-44"))
        val result = route.handle(command)

        assertEquals("req-44", command.requestId)
        assertFalse(result.success, "平台未注册执行器时应降级为失败回执")
    }

    /** hello 帧声明 `metric_push` 能力位（协议规格 §3.6），且既有字段语义不变。 */
    @Test
    fun `hello 声明 metric_push 能力位`() {
        val hello = BridgeFrames.hello("instance-7", "bukkit")

        assertEquals("hello", hello["type"])
        assertEquals("instance-7", hello["instance"])
        assertTrue(hello.containsKey("platform"), "既有字段不得缺失")
        assertTrue(hello.containsKey("version"), "既有字段不得缺失")
        @Suppress("UNCHECKED_CAST")
        val capabilities = hello["capabilities"] as List<String>
        assertTrue(capabilities.contains("metric_push"), "必须声明指标推送能力位")
        assertEquals(listOf("metric_push"), capabilities, "独立模式能力位只有指标推送")
        assertFalse(capabilities.contains("metrics_push"), "能力位名称以协议规格为准:metric_push")
    }

    /**
     * 托管模式才声明 `config_push` / `config_state`。
     *
     * 能力位是控制面"能不能对这台机器下发配置"的依据，独立探针不注册该动作也不上报状态，
     * 若照样声明就是谎报能力。
     */
    @Test
    fun `托管模式 hello 追加配置类能力位`() {
        val hello = BridgeFrames.hello("instance-7", "bukkit", managed = true)

        @Suppress("UNCHECKED_CAST")
        val capabilities = hello["capabilities"] as List<String>
        assertEquals(listOf("metric_push", "config_push", "config_state"), capabilities)
    }

    /**
     * 协议规格 §3.4.1 的帧形态：结构化载荷是**嵌套对象**（`payload`），不是 `payloadJson` 字符串。
     *
     * 这条路径此前只支持 `payloadJson`，漏掉对象形态会让 `config_push` 拿到空载荷——
     * 表现为"载荷为空"的拒绝，且没有任何编译期提示。
     */
    @Test
    fun `嵌套 payload 对象也能传递给动作`() {
        var received = ""
        client.commandRegistry.registerCoreAction("config_push") { command ->
            received = command.payload
            BridgeCommandResult.ok()
        }

        route.handle(
            commandOf(
                testJsonObject(
                    mapOf(
                        "type" to "command",
                        "action" to "config_push",
                        "requestId" to "req-45",
                        "payload" to mapOf("configVersion" to 7L, "baseVersion" to 6L, "values" to mapOf("debug" to true)),
                    ),
                ),
            ),
        )

        assertTrue(received.contains("\"configVersion\":7"), "动作应拿到编码后的载荷,实际:$received")
        assertTrue(received.contains("\"debug\":true"), "嵌套取值不得丢失,实际:$received")
    }

    /** `payloadJson`（既有业务命令约定）优先，且不受新增的 `payload` 分支影响。 */
    @Test
    fun `payloadJson 形态仍按原样传递`() {
        var received = ""
        client.commandRegistry.registerCoreAction("config_push") { command ->
            received = command.payload
            BridgeCommandResult.ok()
        }

        route.handle(
            commandOf(
                testJsonObject(
                    mapOf(
                        "type" to "command",
                        "action" to "config_push",
                        "requestId" to "req-46",
                        "payloadJson" to """{"configVersion":8,"baseVersion":7}""",
                    ),
                ),
            ),
        )

        assertEquals("""{"configVersion":8,"baseVersion":7}""", received, "payloadJson 应原样透传")
    }

    /** 帧对象 → 命令载体（纯逻辑映射，不依赖运行期 JSON 解析器）。 */
    private fun commandOf(frame: JsonObject): BridgeCommand =
        requireNotNull(BridgeKernel.toCommand(frame)) { "帧应能被解析为命令:$frame" }

    /** 构造一条最小 `command` 帧（含 requestId）。 */
    private fun commandFrame(action: String, requestId: String) = testJsonObject(
        mapOf(
            "type" to "command",
            "action" to action,
            "requestId" to requestId,
            "target" to "",
            "reason" to "",
            "payload" to emptyMap<String, Any?>(),
        ),
    )
}
