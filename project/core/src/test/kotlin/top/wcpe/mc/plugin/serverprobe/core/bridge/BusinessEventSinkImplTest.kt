package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.EmitReceipt
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.BridgeEventPublisher
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicFilter
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicSubscriptionRegistry
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BusinessEventSinkImpl] 单元测试（事件主题订阅规格 §5：滤镜先于容量 / 可靠通道去重键 / 永不抛）。
 *
 * 用真实 [FrameSender] + 真实 [TopicFilter]，只把 JSON 序列化替换为纯文本：
 * core 的单测类路径没有 nightconfig，而这里要验证的是**分级与顺序**，不是 JSON 库。
 */
class BusinessEventSinkImplTest {

    private val senders = ArrayList<FrameSender>()

    @AfterEach
    fun tearDown() {
        senders.forEach { it.shutdown() }
        senders.clear()
    }

    /** 记录通过滤镜链的帧（用于断言"端上看到什么"与"主题是否同源"）。 */
    private class FrameRecorder : OutboundFilter {

        val frames: MutableList<OutboundFrame> = Collections.synchronizedList(ArrayList())

        override val order: Int = RECORDER_ORDER

        override fun apply(frame: OutboundFrame): OutboundFrame {
            frames.add(frame)
            return frame
        }
    }

    /** 不依赖 JSON 库的序列化器：按 `k=v` 顺序拼接，足以断言字段是否写入与是否同源。 */
    private val plainEncode: (Any?) -> String = { value ->
        (value as Map<*, *>).entries.joinToString("|") { "${it.key}=${it.value}" }
    }

    /** 组装：注册表 → 发送器（含主题滤镜与记录滤镜）→ 发布器 → 上报实现。 */
    private fun fixture(
        settings: LineSettings = LineSettings(),
        registry: TopicSubscriptionRegistry = TopicSubscriptionRegistry(),
    ): Fixture {
        val sender = FrameSender(settings, null)
        senders.add(sender)
        sender.addFilter(TopicFilter(registry))
        val recorder = FrameRecorder()
        sender.addFilter(recorder)
        val sink = BusinessEventSinkImpl()
        sink.publisherOverride = BridgeEventPublisher(sender = { sender }, instance = { INSTANCE_ID }, encode = plainEncode)
        return Fixture(sink, sender, registry, recorder)
    }

    private class Fixture(
        val sink: BusinessEventSinkImpl,
        val sender: FrameSender,
        val registry: TopicSubscriptionRegistry,
        val recorder: FrameRecorder,
    )

    /** `emit` 走可丢通道。 */
    @Test
    fun `emit按可丢通道受理`() {
        val fixture = fixture(LineSettings(bestEffortCapacity = 8, reliableCapacity = 8))

        val accepted = fixture.sink.emit("activity", "k1", "activity.1", mapOf("progress" to "80"))

        assertTrue(accepted, "可丢事件应受理")
        assertEquals(1, fixture.sender.status().bestEffortQueue, "应进入可丢队列")
        assertEquals(0, fixture.sender.status().reliableQueue, "不得占用可靠队列")
    }

    /** `emitReliable` 走可靠通道。 */
    @Test
    fun `emitReliable按可靠通道受理`() {
        val fixture = fixture(LineSettings(bestEffortCapacity = 8, reliableCapacity = 8))

        val accepted = fixture.sink.emitReliable("activity", "k1", "activity.1", mapOf("settled" to "true"))

        assertTrue(accepted, "可靠事件应受理")
        assertEquals(1, fixture.sender.status().reliableQueue, "应进入可靠队列")
        assertEquals(0, fixture.sender.status().bestEffortQueue, "不得占用可丢队列")
    }

    /**
     * `emitReliable` 空 dedupKey → false + WARN **一次**，且**不降级为可丢**。
     *
     * 断言 `bestEffortQueue == 0` 是关键：若实现把空 dedupKey 的事件悄悄丢进可丢队列，
     * 调用方会把"其实完全没有回放兜底"的事件当成可靠事件。
     */
    @Test
    fun `emitReliable空dedupKey拒绝且只告警一次`() {
        val fixture = fixture(LineSettings(bestEffortCapacity = 8, reliableCapacity = 8))
        val warnings = AtomicInteger(0)
        fixture.sink.warn = { warnings.incrementAndGet() }

        val first = fixture.sink.emitReliable("activity", "", "activity.1", emptyMap())
        val second = fixture.sink.emitReliable("activity", null, "activity.1", emptyMap())

        assertFalse(first, "空 dedupKey 应拒绝")
        assertFalse(second, "null dedupKey 应拒绝")
        assertEquals(1, warnings.get(), "只应告警一次（避免被事件量放大成日志风暴）")
        assertEquals(0, fixture.sender.status().reliableQueue, "不得进入可靠队列")
        assertEquals(0, fixture.sender.status().bestEffortQueue, "不得静默降级为可丢")
        assertEquals(0, fixture.recorder.frames.size, "被拒绝的事件不得产生任何上行帧")
    }

    /** `emit` 无 dedupKey 是合法的：可丢通道不保证去重。 */
    @Test
    fun `emit空dedupKey正常受理`() {
        val fixture = fixture(LineSettings(bestEffortCapacity = 8))
        val warnings = AtomicInteger(0)
        fixture.sink.warn = { warnings.incrementAndGet() }

        val accepted = fixture.sink.emit("activity", "", "activity.1", emptyMap())

        assertTrue(accepted, "可丢通道允许空 dedupKey")
        assertEquals(1, fixture.sender.status().bestEffortQueue, "应按可丢受理")
        assertEquals(0, warnings.get(), "可丢通道缺 dedupKey 不应告警")
    }

    /**
     * **滤镜先于容量**（本功能最重要的正确性约束）。
     *
     * 容量压到极小（可靠队列 1、可丢队列 1、无落盘），灌 1000 条不匹配订阅的 `emitReliable`：
     * 若滤镜被挪到容量判定之后，第 2 条起就会撞满容量并 `REJECTED`，监控特征正好是
     * `topics.count`↑ + `reliableRejected`↑ 而 `filteredOut` 恒 0。
     */
    @Test
    fun `滤镜先于容量时1000条不匹配事件全受理`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345"))
        val fixture = fixture(
            LineSettings(reliableCapacity = 1, bestEffortCapacity = 1, spillEnabled = false),
            registry,
        )

        val accepted = (0 until FILTER_STRESS_COUNT).map {
            fixture.sink.emitReliable("activity", "k-$it", "activity.99999", mapOf("i" to "$it"))
        }

        assertTrue(accepted.all { it }, "被滤镜判定为无需上送 = 已受理，1000 次必须全部 true")
        assertEquals(FILTER_STRESS_COUNT.toLong(), fixture.sender.status().filteredOut, "filteredOut 应等于投递量")
        assertEquals(0, fixture.sender.status().reliableQueue, "被滤镜丢弃的帧不得占可靠队列容量")
        assertEquals(0, fixture.sender.status().reliableRejected, "滤镜丢弃不算失败")
        assertEquals(0, fixture.recorder.frames.size, "无需上送的帧不得产生上行帧")
    }

    /** 同上，可丢入口的语义一致：被滤镜丢弃也算已受理。 */
    @Test
    fun `订阅不匹配时可丢事件也返回true`() {
        val registry = TopicSubscriptionRegistry()
        registry.replace(setOf("activity.12345"))
        val fixture = fixture(LineSettings(bestEffortCapacity = 1), registry)

        val accepted = (0 until 10).map { fixture.sink.emit("activity", null, "activity.99999", emptyMap()) }

        assertTrue(accepted.all { it }, "可丢事件被滤镜丢弃也应返回 true")
        assertEquals(10L, fixture.sender.status().filteredOut, "filteredOut 应等于投递量")
        assertEquals(0, fixture.sender.status().bestEffortQueue, "不得占容量")
    }

    /** 可靠队列满且落盘不可用 → `REJECTED` → 返回 false（调用方须转业务失败）。 */
    @Test
    fun `可靠通道满且无法落盘时返回false`() {
        val fixture = fixture(LineSettings(reliableCapacity = 1, spillEnabled = false))

        val first = fixture.sink.emitReliable("activity", "k1", "activity.1", emptyMap())
        val second = fixture.sink.emitReliable("activity", "k2", "activity.1", emptyMap())

        assertTrue(first, "第一条应入队")
        assertFalse(second, "队列满且无法落盘应返回 false")
        assertEquals(1, fixture.sender.status().reliableRejected, "应计入 reliableRejected")
        assertEquals(1, fixture.sender.status().reliableQueue, "队列深度不应变化")
    }

    /**
     * **永不抛**：发布器抛出 `RejectedExecutionException` 时也只返回 false。
     *
     * 用抛异常的发送器供应器模拟"投递被池拒绝"——异常一旦冒到业务线程，
     * 调用方就被迫在一个本不该存在的失败模式上写处理逻辑。
     */
    @Test
    fun `发布器抛出时返回false且不外抛`() {
        val sink = BusinessEventSinkImpl()
        sink.publisherOverride = BridgeEventPublisher(
            sender = { throw RejectedExecutionException("模拟池拒绝") },
            instance = { INSTANCE_ID },
            encode = plainEncode,
        )

        val reliable = sink.emitReliable("activity", "k1", "activity.1", emptyMap())
        val bestEffort = sink.emit("activity", null, "activity.1", emptyMap())

        assertFalse(reliable, "异常应被消化为 false")
        assertFalse(bestEffort, "异常应被消化为 false")
    }

    /** 桥未启动（未接线发布器）时两个入口一律 false，不抛。 */
    @Test
    fun `未接线时返回false且不抛`() {
        val sink = BusinessEventSinkImpl()

        assertFalse(sink.emit("activity", null, "activity.1", emptyMap()), "未接线应返回 false")
        assertFalse(sink.emitReliable("activity", "k1", "activity.1", emptyMap()), "未接线应返回 false")
    }

    /** 空域视为无效，直接 false（不构造帧、不计数）。 */
    @Test
    fun `空域返回false`() {
        val fixture = fixture()

        assertFalse(fixture.sink.emit("", null, "activity.1", emptyMap()), "空域应无效")
        assertFalse(fixture.sink.emitReliable("", "k1", "activity.1", emptyMap()), "空域应无效")
        assertEquals(0, fixture.recorder.frames.size, "空域不得产生上行帧")
    }

    /**
     * **主题同源**：payload 里的 `topic` 与帧字段必须来自同一个值。
     *
     * 滤镜读帧字段、对端读 payload；两者不一致时"为什么这条没上送"将无法解释。
     */
    @Test
    fun `主题在帧与payload中同源`() {
        val fixture = fixture()

        fixture.sink.emitReliable("activity", "k1", "activity.12345", mapOf("progress" to "80"))

        val frame = fixture.recorder.frames.single()
        assertEquals("activity.12345", frame.topic, "帧字段应带主题")
        assertTrue(frame.payload.contains("|topic=activity.12345|"), "payload 应带同一主题，实际=${frame.payload}")
        assertEquals("activity", frame.domain, "业务域应保留")
    }

    /** 未声明主题时 payload 里不出现 `topic` 字段（缺失 = 非主题事件）。 */
    @Test
    fun `未声明主题时payload不含topic字段`() {
        val fixture = fixture()

        fixture.sink.emitReliable("activity", "k1", null, emptyMap())

        val frame = fixture.recorder.frames.single()
        assertNull(frame.topic, "帧字段应为 null")
        assertFalse(frame.payload.contains("|topic="), "payload 不应出现 topic 字段，实际=${frame.payload}")
    }

    // ────────────── 强语义入口（带原因的受理凭据） ──────────────

    /** 可丢事件受理后，凭据不带序号（可丢事件不占序列）。 */
    @Test
    fun `emitReceipt可丢事件受理且无序号`() {
        val fixture = fixture(LineSettings(bestEffortCapacity = 8, reliableCapacity = 8))

        val receipt = fixture.sink.emitReceipt("activity", "k1", "activity.1", mapOf("progress" to "80"))

        assertTrue(receipt.isAccepted, "可丢事件应受理")
        assertEquals(EmitReceipt.Reason.ACCEPTED, receipt.reason)
        assertEquals(0L, receipt.seq, "可丢事件不分配序号")
    }

    /**
     * 可靠事件受理后，凭据回传探针分配的序号。
     *
     * <p>这是业务侧做「本地水位线」的唯一依据：拿不到序号就只能去查 `bridge_status`，
     * 而那是另一个跨线程调用的成本。</p>
     */
    @Test
    fun `emitReliableReceipt回传分配的序号`() {
        val fixture = fixture(LineSettings(bestEffortCapacity = 8, reliableCapacity = 8))

        val receipt = fixture.sink.emitReliableReceipt("activity", "k1", "activity.1", mapOf("settled" to "true"))

        assertTrue(receipt.isAccepted, "可靠事件应受理")
        assertTrue(receipt.seq > 0L, "可靠事件必须带序号，实际=${receipt.seq}")
        assertEquals(1L, receipt.seq, "全新分配器的首个序号为 1")
    }

    /**
     * 关键区分：空 dedupKey 是「调用方 bug」，**不是**可重试的饱和。
     *
     * <p>若这里报 `REJECTED_SATURATED`，回灌循环会无限重试一个永远不会成功的事件。</p>
     */
    @Test
    fun `emitReliableReceipt空dedupKey报不可重试的原因`() {
        val fixture = fixture(LineSettings(bestEffortCapacity = 8, reliableCapacity = 8))
        fixture.sink.warn = { }

        val receipt = fixture.sink.emitReliableReceipt("activity", "", "activity.1", emptyMap())

        assertFalse(receipt.isAccepted)
        assertEquals(EmitReceipt.Reason.MISSING_DEDUP_KEY, receipt.reason)
        assertFalse(receipt.isRetryable, "缺去重键重试一万次也不会成功")
        assertEquals(0, fixture.sender.status().reliableQueue, "不得进入可靠队列")
        assertEquals(0, fixture.sender.status().bestEffortQueue, "不得静默降级为可丢")
    }

    /** 空域报「域非法」，同样不可重试。 */
    @Test
    fun `emitReceipt空域报域非法`() {
        val fixture = fixture()

        val receipt = fixture.sink.emitReceipt("", "k1", null, emptyMap())

        assertFalse(receipt.isAccepted)
        assertEquals(EmitReceipt.Reason.INVALID_DOMAIN, receipt.reason)
        assertFalse(receipt.isRetryable)
    }

    /** 通道饱和才报可重试的饱和。 */
    @Test
    fun `emitReliableReceipt通道满时报可重试的饱和`() {
        // 容量 1、无关盘，第二条必被拒。
        val fixture = fixture(LineSettings(bestEffortCapacity = 1, reliableCapacity = 1, spillEnabled = false))
        fixture.sink.emitReliable("activity", "k1", null, emptyMap())

        val receipt = fixture.sink.emitReliableReceipt("activity", "k2", null, emptyMap())

        assertFalse(receipt.isAccepted)
        assertEquals(EmitReceipt.Reason.REJECTED_SATURATED, receipt.reason)
        assertTrue(receipt.isRetryable, "饱和是唯一值得重试的原因")
    }

    /** 未接线（探针未就绪）时报不可用的探针，而不是饱和。 */
    @Test
    fun `emitReceipt未接线时报探针不可用`() {
        val sink = BusinessEventSinkImpl()

        val receipt = sink.emitReceipt("activity", "k1", null, emptyMap())

        assertFalse(receipt.isAccepted)
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, receipt.reason)
        assertFalse(receipt.isRetryable, "探针未就绪时重试无意义")
    }

    private companion object {
        /** 记录滤镜序位：排在主题滤镜之后。 */
        const val RECORDER_ORDER = 999

        /** 实例标识（payload 里的 `instance`）。 */
        const val INSTANCE_ID = "s1"

        /** 滤镜先于容量的压测条数（规格 §5 指定 1000）。 */
        const val FILTER_STRESS_COUNT = 1000
    }
}
