package top.wcpe.mc.plugin.serverprobe.core.bridge.topic

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.FrameSender
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFilter
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundSink
import top.wcpe.mc.plugin.serverprobe.core.bridge.SubmitOutcome
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.util.Collections

/**
 * [TopicFilter] 单元测试（事件主题订阅规格 §5：无订阅不过滤 / 精确 / 前缀 / 非业务事件 /
 * 未声明 topic 放行 / 滤镜丢弃不产生分片）。
 *
 * 全部经由真实 [FrameSender] 提交，而不是直接调 `filter.apply`：
 * 只有走提交路径才能证明"滤镜与容量判定的先后"和"被丢弃的帧不产生分片"这两件事。
 */
class TopicFilterTest {

    private val senders = ArrayList<FrameSender>()

    @AfterEach
    fun tearDown() {
        senders.forEach { it.shutdown() }
        senders.clear()
    }

    /** 构造受测发送器（注册主题滤镜 + 一个位于其后的记录滤镜）。 */
    private fun sender(
        registry: TopicSubscriptionRegistry,
        settings: LineSettings = LineSettings(),
        chunker: FrameChunker = FrameChunker(ChunkSettings()),
        recorded: MutableList<String?>? = null,
    ): FrameSender {
        val sender = FrameSender(settings, null, chunker)
        senders.add(sender)
        sender.addFilter(TopicFilter(registry))
        if (recorded != null) sender.addFilter(recorder(recorded))
        return sender
    }

    /** 记录"实际通过滤镜链的帧主题"——用它断言"端上到底收到什么"。 */
    private fun recorder(recorded: MutableList<String?>): OutboundFilter = object : OutboundFilter {
        override val order: Int = RECORDER_ORDER

        override fun apply(frame: OutboundFrame): OutboundFrame {
            recorded.add(frame.topic)
            return frame
        }
    }

    /** 构造一条业务事件帧。 */
    private fun business(topic: String?, payload: String = "p"): OutboundFrame = OutboundFrame(
        type = OutboundFrame.TYPE_EVENT,
        event = "activity_change",
        domain = "activity",
        payload = payload,
        priority = OutboundFrame.Priority.RELIABLE_EVENT,
        topic = topic,
    )

    /** 无订阅：三个不同 topic 的业务事件全部放行，且注册表确实为空（去循环论证）。 */
    @Test
    fun `无订阅时全量放行且注册表为空`() {
        val registry = TopicSubscriptionRegistry()
        val recorded = ArrayList<String?>()
        val sender = sender(registry, recorded = recorded)
        val topics = listOf("activity.1", "activity.2", "activity.3")

        topics.forEach { assertEquals(SubmitOutcome.QUEUED, sender.submit(business(it)), "无订阅时 $it 应入队") }

        assertEquals(topics, recorded, "应收到 3 条且 topic 全等")
        assertTrue(registry.snapshot().isEmpty(), "『无订阅』必须是确实没有订阅，而不是订阅了空集")
        assertEquals(0, sender.status().filteredOut, "无订阅时不应有任何过滤计数")
    }

    /** 精确订阅：只放行同名主题。 */
    @Test
    fun `精确订阅只放行匹配主题`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345"))
        val recorded = ArrayList<String?>()
        val sender = sender(registry, recorded = recorded)

        val hit = sender.submit(business("activity.12345"))
        val miss = sender.submit(business("activity.99999"))

        assertEquals(SubmitOutcome.QUEUED, hit, "匹配主题应入队")
        assertEquals(SubmitOutcome.FILTERED_OUT, miss, "不匹配主题应被滤镜丢弃")
        assertEquals(listOf("activity.12345"), recorded, "端上只应看到匹配主题")
        assertEquals(1, sender.status().filteredOut, "被丢弃应计入 filteredOut")
        assertEquals(0, sender.status().reliableRejected, "滤镜丢弃不算失败")
    }

    /** 前缀订阅：`activity.*` 放行同域下的一切（含层级子主题）。 */
    @Test
    fun `前缀订阅放行同域与层级子主题`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.*"))
        val recorded = ArrayList<String?>()
        val sender = sender(registry, recorded = recorded)

        sender.submit(business("activity.1"))
        sender.submit(business("activity.2"))
        sender.submit(business("activity.12345.sub"))
        sender.submit(business("economy.1"))

        assertEquals(
            listOf("activity.1", "activity.2", "activity.12345.sub"),
            recorded,
            "前缀订阅应放行同域与层级子主题，且不跨域",
        )
        assertEquals(1, sender.status().filteredOut, "只有跨域那条被丢弃")
    }

    /** 非业务事件（玩家 / 命令回执 / 治理 core / 元域 jbis）在订阅生效期间仍全部上行。 */
    @Test
    fun `非业务事件不受订阅影响`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345"))
        val sender = sender(registry)

        val connected = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "connected",
            payload = "{}",
            priority = OutboundFrame.Priority.BEST_EFFORT_EVENT,
        )
        val receipt = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = OutboundFrame.TYPE_COMMAND_RESULT,
            payload = "{}",
            priority = OutboundFrame.Priority.COMMAND_RESULT,
            requestId = "r1",
        )
        val governance = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "core_change",
            domain = "core",
            payload = "{}",
            priority = OutboundFrame.Priority.BEST_EFFORT_EVENT,
        )
        val jbis = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "jbis_change",
            domain = "jbis",
            payload = "{}",
            priority = OutboundFrame.Priority.BEST_EFFORT_EVENT,
        )

        assertEquals(SubmitOutcome.QUEUED, sender.submit(connected), "connected 必须仍上行")
        assertEquals(SubmitOutcome.QUEUED, sender.submit(receipt), "command_result 必须仍上行")
        assertEquals(SubmitOutcome.QUEUED, sender.submit(governance), "治理域不受主题过滤")
        assertEquals(SubmitOutcome.QUEUED, sender.submit(jbis), "元域不受主题过滤")
        assertEquals(0, sender.status().filteredOut, "非业务事件一条都不该被过滤")
    }

    /** 未声明 topic 的业务事件仍然放行（防静默丢失）。 */
    @Test
    fun `未声明主题的业务事件放行`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345"))
        val recorded = ArrayList<String?>()
        val sender = sender(registry, recorded = recorded)

        assertEquals(SubmitOutcome.QUEUED, sender.submit(business(null)), "缺 topic 应放行")
        assertEquals(SubmitOutcome.QUEUED, sender.submit(business("")), "空 topic 视同未声明，应放行")
        assertEquals(2, recorded.size, "两条都应通过滤镜链")
        assertEquals(0, sender.status().filteredOut, "未声明 topic 不算被过滤")
    }

    /**
     * 被滤镜丢弃的超大帧**不产生任何分片**：分片在滤镜之后，容量/上限判定根本看不到它。
     *
     * 构造：阈值 512 字节、单组上限 2 片、载荷 8192 字节——若它真的到达分片器，
     * 必然因片数超限变成 `TOO_LARGE` 并把计数并入 `reliableRejected`。
     * 断言 `FILTERED_OUT` 且 `reliableRejected == 0`，就证明"分片器未被触碰"。
     */
    @Test
    fun `滤镜丢弃不产生分片`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345"))
        val chunker = FrameChunker(ChunkSettings(enabled = true, maxFrameBytes = 512, maxChunksPerGroup = 2))
        val written = Collections.synchronizedList(ArrayList<String>())
        val sender = sender(registry, LineSettings(reliableCapacity = 8, spillEnabled = false), chunker)
        sender.attach(RecordingSink(written))
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()

        val outcome = sender.submit(business("activity.99999", payload = "x".repeat(8192)))

        assertEquals(SubmitOutcome.FILTERED_OUT, outcome, "不匹配订阅的超大帧应被滤镜丢弃，而不是被分片器拒绝")
        assertEquals(1, sender.status().filteredOut, "filteredOut 应 +1")
        assertEquals(0, sender.status().reliableRejected, "不得触碰分片上限（reliableRejected 应保持 0）")
        assertEquals(0, sender.status().reliableQueue, "不得占容量")
        assertEquals(0, written.count { it.contains("\"event\":\"chunk\"") }, "不得写出任何 chunk 帧")
    }

    /** 滤镜序位为 200（权威分配表）。 */
    @Test
    fun `滤镜序位为200`() {
        assertEquals(200, TopicFilter(TopicSubscriptionRegistry()).order, "主题滤镜必须注册在 order=200")
    }

    private companion object {
        /** 记录滤镜序位：排在主题滤镜之后，故它看到的都是"已经通过过滤"的帧。 */
        const val RECORDER_ORDER = 999
    }
}

/** 只记录写出内容的假连接（本文件私有，避免与其它测试文件的同名助手冲突）。 */
private class RecordingSink(private val written: MutableList<String>) : OutboundSink {
    override fun sendText(text: String): Boolean {
        written.add(text)
        return true
    }
}
