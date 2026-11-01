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

    /**
     * 独立模式 hello 不声明托管能力位：指标上行属托管能力（不推就不能声明）。
     *
     * 这里的断言直接跑在 [BridgeFrames.hello] 上——它在生产里就是 `BridgeClient.helloJson` 的唯一真源，
     * 所以改坏能力位或漏写 `managed` 字段，本用例会红（不再是“只验证死代码”）。
     */
    @Test
    fun `独立模式 hello 不声明能力位`() {
        val hello = BridgeFrames.hello("instance-7", "bukkit")

        assertEquals("hello", hello["type"])
        assertEquals("instance-7", hello["instance"])
        assertEquals("bukkit", hello["platform"])
        assertEquals(BridgeCapabilities.VERSION, hello["version"], "版本号取线路治理的真源，不得各处各写一份")
        assertEquals(false, hello["managed"], "managed 字段必须如实反映运行模式")
        @Suppress("UNCHECKED_CAST")
        val capabilities = hello["capabilities"] as List<String>
        assertTrue(capabilities.isEmpty(), "独立模式不推指标、不下发配置、不上报状态，实际:$capabilities")
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

        assertEquals(true, hello["managed"])
        @Suppress("UNCHECKED_CAST")
        val capabilities = hello["capabilities"] as List<String>
        assertEquals(listOf("metric_push", "config_push", "config_state"), capabilities)
    }

    /** 线路治理登记的能力位（分片/主题/健康等）与托管能力位合并去重，且订阅表原样透传。 */
    @Test
    fun `hello 合并治理能力位并透传订阅`() {
        val hello = BridgeFrames.hello(
            instance = "instance-7",
            platform = "bungee",
            managed = true,
            extras = BridgeFrames.HelloExtras(
                extraCapabilities = listOf("chunk", "metric_push"),
                subscriptions = listOf("topic:player_chat"),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val capabilities = hello["capabilities"] as List<String>
        assertEquals(listOf("chunk", "metric_push", "config_push", "config_state"), capabilities, "应合并并去重，不得丢登记项")
        @Suppress("UNCHECKED_CAST")
        val subscriptions = hello["subscriptions"] as List<String>
        assertEquals(listOf("topic:player_chat"), subscriptions)
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
