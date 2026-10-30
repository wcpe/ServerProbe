package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicSubscriptionRegistry
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings

/**
 * `bridge_status.topics` 与 `hello.subscriptions` 的同源验收（事件主题订阅规格 §3.7 / §3.8 / §5）。
 *
 * 两条可观测性要求必须一起成立：
 * - `count == 0` 表示**无订阅**（全量上报），不是"订阅了 0 个主题所以什么都不发"；
 * - `hello.subscriptions` 与 `bridge_status.topics.expressions` **同源同值**——JM 靠它
 *   在连上瞬间发现"探针仍在用旧订阅"。装配漏了 `setSubscriptionSource` 会让前者恒为空，
 *   因此这里断言"接线后两者逐项相等"。
 *
 * [BridgeCapabilities] 是进程内全局单例，故每个用例结束后解绑，避免污染其它测试。
 */
class BridgeStatusTopicsTest {

    private val senders = ArrayList<FrameSender>()
    private val pools = ArrayList<BridgePools>()

    @AfterEach
    fun tearDown() {
        BridgeCapabilities.setSubscriptionSource(null)
        senders.forEach { it.shutdown() }
        senders.clear()
        pools.forEach { it.shutdownNow() }
        pools.clear()
    }

    /** 捕获 `bridge_status` 的 payload 结构（不依赖 JSON 库）。 */
    private class PayloadCapture {
        @Volatile
        var payload: Map<String, Any?>? = null
    }

    /** 组装受测的 `bridge_status` 路由；订阅来源与 hello 指向**同一个注册表**。 */
    private fun statusRoute(
        registry: TopicSubscriptionRegistry?,
        capture: PayloadCapture,
    ): BridgeStatusRoute {
        val settings = LineSettings(spillEnabled = false)
        val sender = FrameSender(settings, null)
        senders.add(sender)
        val pools = BridgePools(settings)
        this.pools.add(pools)
        val kernel = BridgeKernel(sender, pools, parse = { null }, encodeReceipt = { _, _ -> null })
        val topics = if (registry == null) ({ emptyList() }) else ({ registry.snapshot().toList() })
        return BridgeStatusRoute(
            sender = sender,
            kernel = kernel,
            topics = topics,
            encode = { payload ->
                capture.payload = payload
                "ok"
            },
        ) { BridgeLinkSnapshot() }
    }

    /** 取 `topics` 子结构（`count` 与 `expressions`）。 */
    @Suppress("UNCHECKED_CAST")
    private fun topicsOf(capture: PayloadCapture): Map<String, Any?> =
        capture.payload?.get("topics") as? Map<String, Any?> ?: error("bridge_status 缺少 topics 字段")

    /** 无订阅时 `count == 0` 且 `expressions` 为空数组。 */
    @Test
    fun `无订阅时topics计数为零`() {
        val capture = PayloadCapture()
        val route = statusRoute(TopicSubscriptionRegistry(), capture)

        val result = route.handle(statusCommand())

        assertTrue(result.success, "bridge_status 应成功")
        assertEquals(0, topicsOf(capture)["count"], "无订阅时 count 必须为 0（表示全量上报）")
        assertEquals(emptyList<String>(), topicsOf(capture)["expressions"], "无订阅时 expressions 应为空数组")
    }

    /** `expressions` 按字典序，且与注册表快照逐项相等（去重由 `Set` 类型保证）。 */
    @Test
    fun `topics表达式与注册表逐项相等`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("b.1", "a.1"))
        val capture = PayloadCapture()

        statusRoute(registry, capture).handle(statusCommand())

        @Suppress("UNCHECKED_CAST")
        val expressions = topicsOf(capture)["expressions"] as List<String>
        assertEquals(listOf("a.1", "b.1"), expressions, "expressions 应按字典序")
        assertEquals(registry.snapshot().toList(), expressions, "expressions 必须与注册表快照逐项相等")
        assertEquals(2, topicsOf(capture)["count"], "count 应与 expressions 同源")
    }

    /** `hello.subscriptions` 接线后与注册表快照逐项相等。 */
    @Test
    fun `hello订阅与注册表逐项相等`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345", "activity.*"))
        BridgeCapabilities.setSubscriptionSource { registry.snapshot().toList() }

        assertEquals(
            registry.snapshot().toList(),
            BridgeCapabilities.subscriptions(),
            "hello.subscriptions 必须与 registry.snapshot() 逐项相等",
        )
    }

    /**
     * 未接线时为**空数组且不抛**：接线缺失必须可观测，而不是静默沿用旧订阅。
     */
    @Test
    fun `未接线时hello订阅为空且不抛`() {
        BridgeCapabilities.setSubscriptionSource(null)

        val subscriptions = BridgeCapabilities.subscriptions()

        assertTrue(subscriptions.isEmpty(), "未接线时 subscriptions 应为空数组")
    }

    /** 订阅来源自身抛异常时也只返回空数组（hello 拼装绝不能因此失败）。 */
    @Test
    fun `订阅来源抛异常时返回空数组`() {
        BridgeCapabilities.setSubscriptionSource { throw IllegalStateException("模拟取值失败") }

        assertTrue(BridgeCapabilities.subscriptions().isEmpty(), "取值失败应降级为空数组，绝不抛出")
    }

    /** `hello.subscriptions` 与 `bridge_status.topics.expressions` 同源同值。 */
    @Test
    fun `hello与bridge_status同源同值`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345", "activity.*"))
        // 装配接线：与 bridge_status 读同一份注册表快照。
        BridgeCapabilities.setSubscriptionSource { registry.snapshot().toList() }
        val capture = PayloadCapture()

        statusRoute(registry, capture).handle(statusCommand())

        @Suppress("UNCHECKED_CAST")
        val expressions = topicsOf(capture)["expressions"] as List<String>
        assertEquals(BridgeCapabilities.subscriptions(), expressions, "hello 与 bridge_status 必须同值")
    }

    /** 订阅能力值已登记（装配义务之一）。 */
    @Test
    fun `订阅能力值可登记`() {
        BridgeCapabilities.addCapability(BridgeCapabilities.CAP_TOPIC)

        assertTrue(BridgeCapabilities.snapshot().contains("topic"), "capabilities 应含 topic")
        assertEquals("topic", BridgeCapabilities.CAP_TOPIC, "能力值字面量由 bridge-reconnect 规格固定")
    }

    /** 构造一条 `core.bridge_status` 命令。 */
    private fun statusCommand(): BridgeCommand = BridgeCommand(
        action = BridgeStatusRoute.ACTION,
        target = "",
        reason = "",
        requestId = "req-status",
        domain = BridgeStatusRoute.DOMAIN_CORE,
        payload = "",
    )
}
