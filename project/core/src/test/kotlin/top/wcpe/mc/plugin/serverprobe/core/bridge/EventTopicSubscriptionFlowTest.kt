package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicFilter
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicSubscriptionRegistry
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import top.wcpe.mc.plugin.serverprobe.core.config.SubscribeSettings

/**
 * 订阅指令 → 滤镜 → 上行集合的**组合**验收（事件主题订阅规格 §5「清空订阅」「上限不静默截断」）。
 *
 * 这两条不能只断言注册表：判据写的是"**fixture 观测到的上行集合**"。只有把
 * `core.subscribe` 的回执语义与真实滤镜放行结果串起来，才能证明"没有部分生效"。
 */
class EventTopicSubscriptionFlowTest {

    private val senders = ArrayList<FrameSender>()

    @AfterEach
    fun tearDown() {
        senders.forEach { it.shutdown() }
        senders.clear()
    }

    /** 记录实际通过滤镜链的主题（即"端上会看到什么"）。 */
    private class TopicRecorder : OutboundFilter {

        val topics: MutableList<String?> = ArrayList()

        override val order: Int = RECORDER_ORDER

        override fun apply(frame: OutboundFrame): OutboundFrame {
            topics.add(frame.topic)
            return frame
        }
    }

    /** 测试用 payload 解析器：逗号分隔。 */
    private val decode: (String) -> List<String>? = { payload ->
        if (payload.isEmpty()) emptyList() else payload.split(',')
    }

    private class Flow(
        val registry: TopicSubscriptionRegistry,
        val route: SubscribeRoute,
        val sender: FrameSender,
        val recorder: TopicRecorder,
    )

    /** 组装：注册表 → 发送器（主题滤镜 + 记录滤镜）→ 订阅路由。 */
    private fun flow(maxTopics: Int = 8): Flow {
        val registry = TopicSubscriptionRegistry { SubscribeSettings(maxTopics = maxTopics) }
        val sender = FrameSender(LineSettings(reliableCapacity = 32), null)
        senders.add(sender)
        sender.addFilter(TopicFilter(registry))
        val recorder = TopicRecorder()
        sender.addFilter(recorder)
        val route = SubscribeRoute(registry, decode, { it.joinToString(",", "[", "]") })
        return Flow(registry, route, sender, recorder)
    }

    /** 上报一条带主题的业务事件，返回实际是否上行（未上行不算失败）。 */
    private fun Flow.emit(topic: String): Boolean =
        sender.submit(
            OutboundFrame(
                type = OutboundFrame.TYPE_EVENT,
                event = "activity_change",
                domain = "activity",
                payload = "p",
                priority = OutboundFrame.Priority.RELIABLE_EVENT,
                topic = topic,
            ),
        ) == SubmitOutcome.QUEUED

    /** 订阅命令。 */
    private fun command(action: String, payload: String): BridgeCommand =
        BridgeCommand(action = action, target = "", reason = "", requestId = "req-1", domain = "core", payload = payload)

    /**
     * 清空订阅：`topics: []` 后 `count == 0`，且三个不同话题**全部上行**。
     *
     * 关键是后半句：只断言 `count == 0` 无法区分"恢复全量上报"与"订阅了空集所以什么都不发"。
     */
    @Test
    fun `清空订阅后三个话题全部上行`() {
        val flow = flow()
        flow.route.handle(command(ACTION_SUBSCRIBE, "activity.12345"))
        assertFalse(flow.emit("activity.99999"), "前提：订阅生效时无关话题不上行")
        flow.recorder.topics.clear()

        val cleared = flow.route.handle(command(ACTION_SUBSCRIBE, ""))

        assertTrue(cleared.success, "清空应成功")
        assertEquals("[]", cleared.output, "output 应为空数组")
        assertEquals(0, flow.registry.snapshot().size, "清空后 count 必须为 0（表示全量上报）")
        val uploaded = listOf("activity.1", "activity.2", "activity.3").filter { flow.emit(it) }
        assertEquals(
            listOf("activity.1", "activity.2", "activity.3"),
            uploaded,
            "清空订阅后三个不同话题必须全部上行",
        )
        assertEquals(uploaded, flow.recorder.topics, "实际通过滤镜链的集合应与之一致")
    }

    /**
     * 超上限被拒后：回执失败、`count` 保持变更前的值，且**上行集合与变更前完全一致**。
     *
     * 后者是"没有部分生效"的判据：若实现偷偷应用了前两条，`a.1`/`a.2` 就会开始被过滤/放行，
     * 上行集合随之变化。
     */
    @Test
    fun `超上限被拒后上行集合与变更前一致`() {
        val flow = flow(maxTopics = 2)
        flow.route.handle(command(ACTION_SUBSCRIBE, "activity.12345"))

        val topics = listOf("activity.12345", "activity.99999", "economy.1")
        val before = topics.map { it to flow.emit(it) }
        val countBefore = flow.registry.snapshot().size

        val rejected = flow.route.handle(command(ACTION_SUBSCRIBE, "activity.1,activity.2,activity.3"))

        assertFalse(rejected.success, "超上限应失败")
        assertTrue(rejected.error.contains("max-topics=2"), "error 应含上限值，实际=${rejected.error}")
        assertEquals(countBefore, flow.registry.snapshot().size, "count 必须保持变更前的值")
        val after = topics.map { it to flow.emit(it) }
        assertEquals(before, after, "上行集合必须与变更前完全一致（证明没有部分生效）")
    }

    /** 精确订阅：命中上行、未命中不上行，回执 `output` 与注册表一致。 */
    @Test
    fun `精确订阅只放行同名主题`() {
        val flow = flow()

        val result = flow.route.handle(command(ACTION_SUBSCRIBE, "activity.12345"))

        assertTrue(result.success, "订阅应成功")
        assertEquals("[activity.12345]", result.output, "回执应回显生效集合")
        assertEquals(result.output, "[${flow.registry.snapshot().joinToString(",")}]", "回执与注册表必须同值")
        assertTrue(flow.emit("activity.12345"), "匹配主题应上行")
        assertFalse(flow.emit("activity.99999"), "不匹配主题不应上行")
    }

    /** 退订后不再过滤：幂等移除，且上行恢复。 */
    @Test
    fun `退订后恢复全量上行`() {
        val flow = flow()
        flow.route.handle(command(ACTION_SUBSCRIBE, "activity.12345"))

        val removed = flow.route.handle(command(ACTION_UNSUBSCRIBE, "activity.12345"))
        val again = flow.route.handle(command(ACTION_UNSUBSCRIBE, "not-exists.1"))

        assertTrue(removed.success, "退订应成功")
        assertTrue(again.success, "退订不存在的表达式幂等不报错")
        assertTrue(flow.emit("activity.99999"), "退订后应恢复全量上行")
    }

    private companion object {
        const val ACTION_SUBSCRIBE = "subscribe"
        const val ACTION_UNSUBSCRIBE = "unsubscribe"
        const val RECORDER_ORDER = 999
    }
}
