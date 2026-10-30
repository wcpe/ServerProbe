package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * [FrameWriter] 单元测试（写出路径 + 降级处置）。
 *
 * ## 为什么单独为它写测试
 * `bridge_status` 的计数是运维判断链路的**唯一入口**，而"哪种情况算失败、算到哪个计数里"
 * 全在 [FrameWriter]：连着时写、没连接时可靠事件落盘、可丢事件直接丢、写失败时按优先级降级。
 * 这些分支写错不会抛异常，只会让某个计数永远为 0——看板上表现为"链路很健康"，
 * 而事件实际上大段丢失。所以每一条都要直接钉住，而不是交给上层的集成测试间接覆盖。
 *
 * 用例全部**同步调用** [FrameWriter.writeGroup]/[writeControl] 后立刻断言：
 * 本类没有自己的线程，写出发生在调用线程上，因此不需要 latch 也不需要 sleep。
 */
class FrameWriterTest {

    /**
     * 未连接时可靠事件**转落盘**并计入 `spilled`，不得计入拒绝。
     *
     * 这是可靠通道的核心承诺：没连接不等于丢事件，先落盘等回放。
     */
    @Test
    fun `未连接时可靠事件转落盘`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)

        FrameWriter(AtomicReference<OutboundSink?>(null), spill, counters).writeGroup(listOf(event("evt-1", RELIABLE)))

        assertEquals(1, counters.spilled(), "落盘成功应计 spilled")
        assertEquals(0, counters.reliableRejected(), "已落盘的可靠事件不算被拒")
        assertEquals(1, spill.pending(), "事件应真的进了落盘缓冲")
    }

    /**
     * 未连接、落盘通道也不可用（`spill == null`）时，可靠事件计入 `reliableRejected`。
     *
     * 关键在"不得混进 `bestEffortDropped`"：`reliableRejected` 的语义就是
     * "可靠事件因队列满且落盘失败被拒"，记到可丢计数会让可靠事件丢失在运维看板上彻底看不见。
     */
    @Test
    fun `未连接且无落盘通道时可靠事件计入reliableRejected`() {
        val counters = SenderCounters()

        FrameWriter(AtomicReference<OutboundSink?>(null), null, counters).writeGroup(listOf(event("evt-1", RELIABLE)))

        assertEquals(1, counters.reliableRejected(), "可靠事件丢失必须计入 reliableRejected")
        assertEquals(0, counters.bestEffortDropped(), "不得把可靠事件丢失混进可丢计数")
        assertEquals(0, counters.spilled(), "没有落盘通道就不能计 spilled")
    }

    /**
     * 未连接时可丢事件**即使落盘通道可用也直接丢弃**并计数。
     *
     * 分级语义的分界线就在这里：若可丢事件也去落盘，一次断连就会用玩家 join/quit 采样
     * 把落盘文件（进而可靠事件的回放空间）全部挤满。
     */
    @Test
    fun `未连接时可丢事件直接丢弃不占落盘`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)

        FrameWriter(AtomicReference<OutboundSink?>(null), spill, counters).writeGroup(listOf(event("evt-1", BEST_EFFORT)))

        assertEquals(1, counters.bestEffortDropped(), "可丢事件丢弃应计 bestEffortDropped")
        assertEquals(0, counters.spilled(), "可丢事件不得落盘")
        assertEquals(0, spill.pending(), "可丢事件不得占用落盘缓冲")
    }

    /** 未连接时命令回执计入回执拒绝（回执既不落盘也不可丢）。 */
    @Test
    fun `未连接时命令回执计入回执拒绝`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)

        FrameWriter(AtomicReference<OutboundSink?>(null), spill, counters)
            .writeGroup(listOf(event("receipt-1", COMMAND_RESULT)))

        assertEquals(1, counters.commandResultRejected(), "回执未写出应计入 commandResultRejected")
        assertEquals(0, counters.reliableRejected(), "回执不得混进可靠事件拒绝计数")
        assertEquals(0, spill.pending(), "回执不落盘")
    }

    /** 已连接时正常写出：帧进到连接上，且所有失败/丢弃计数均不动。 */
    @Test
    fun `已连接时正常写出`() {
        val counters = SenderCounters()
        val sink = RecordingFrameSink()

        FrameWriter(AtomicReference<OutboundSink?>(sink), null, counters)
            .writeGroup(listOf(event("evt-1", RELIABLE)))

        assertEquals(listOf("evt-1"), sink.written, "帧应原样写给连接")
        assertEquals(0, counters.writeFailures(), "正常写出不得计写失败")
        assertEquals(0, counters.spilled(), "正常写出不得计落盘")
        assertEquals(0, counters.reliableRejected(), "正常写出不得计拒绝")
    }

    /**
     * 写失败（IOException）且落盘可用时：计写失败，并把可靠事件转落盘。
     *
     * 这是"连接半死但没断"的常见形态：不落盘的实现会让这段事件彻底消失。
     */
    @Test
    fun `写失败且落盘可用时可靠事件转落盘`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)
        val sink = FailingSink(failOn = "evt-1")

        FrameWriter(AtomicReference<OutboundSink?>(sink), spill, counters)
            .writeGroup(listOf(event("evt-1", RELIABLE)))

        assertEquals(1, counters.writeFailures(), "写失败应计 writeFailures")
        assertEquals(1, counters.spilled(), "写失败后可靠事件应转落盘")
        assertEquals(0, counters.reliableRejected(), "已落盘就不算被拒")
        assertEquals(1, spill.pending(), "事件应真的进了落盘缓冲")
    }

    /**
     * 写失败且无落盘可用时：可靠事件计入 `reliableRejected`（而非可丢计数）。
     *
     * 这里钉住"落盘结果必须被检查"：曾出现过无条件记 `spilled` 的写法，
     * 于是事件实际已丢失，监控却显示"已落盘、等回放"。
     */
    @Test
    fun `写失败且无落盘通道时可靠事件计入reliableRejected`() {
        val counters = SenderCounters()
        val sink = FailingSink(failOn = "evt-1")

        FrameWriter(AtomicReference<OutboundSink?>(sink), null, counters)
            .writeGroup(listOf(event("evt-1", RELIABLE)))

        assertEquals(1, counters.writeFailures(), "写失败应计 writeFailures")
        assertEquals(1, counters.reliableRejected(), "落盘不可用时可靠事件丢失必须计入 reliableRejected")
        assertEquals(0, counters.spilled(), "落盘未成功就不能记 spilled")
        assertEquals(0, counters.bestEffortDropped(), "不得混进可丢计数")
    }

    /**
     * 写失败时的可丢事件**只计写失败**，不额外计丢弃。
     *
     * 可丢事件本来就是"丢了也无所谓"，再计一次丢弃会让 `bestEffortDropped`
     * 同时表达"队列满"和"网络坏"两种不同故障，看板上再也分不开。
     */
    @Test
    fun `写失败时可丢事件只计写失败`() {
        val counters = SenderCounters()
        val sink = FailingSink(failOn = "evt-1")

        FrameWriter(AtomicReference<OutboundSink?>(sink), null, counters)
            .writeGroup(listOf(event("evt-1", BEST_EFFORT)))

        assertEquals(1, counters.writeFailures(), "写失败应计 writeFailures")
        assertEquals(0, counters.bestEffortDropped(), "写失败不应额外计可丢丢弃")
        assertEquals(0, counters.reliableRejected(), "可丢事件不得进可靠拒绝计数")
    }

    /** 控制帧未连接时直接返回拒绝，且**不计写失败**（没连接不是写失败）。 */
    @Test
    fun `控制帧未连接时直接拒绝`() {
        val counters = SenderCounters()

        val outcome = FrameWriter(AtomicReference<OutboundSink?>(null), null, counters)
            .writeControl(control("ping-1"))

        assertEquals(SubmitOutcome.REJECTED, outcome, "未连接时控制帧应返回 REJECTED")
        assertEquals(0, counters.writeFailures(), "没连接不是写失败")
        assertEquals(0, counters.reliableRejected(), "控制帧不进任何队列计数")
    }

    /** 控制帧已连接时旁路直接写出（不入队、不参与分片组）。 */
    @Test
    fun `控制帧已连接时旁路写出`() {
        val counters = SenderCounters()
        val sink = RecordingFrameSink()

        val outcome = FrameWriter(AtomicReference<OutboundSink?>(sink), null, counters)
            .writeControl(control("ping-1"))

        assertEquals(SubmitOutcome.QUEUED, outcome, "写出成功应返回 QUEUED")
        assertEquals(listOf("ping-1"), sink.written, "控制帧应直接写出")
        assertEquals(0, counters.writeFailures(), "正常写出不得计写失败")
    }

    /** 控制帧写失败时计写失败并返回拒绝（异常不放行给发送线程）。 */
    @Test
    fun `控制帧写失败时计写失败并返回拒绝`() {
        val counters = SenderCounters()
        val sink = FailingSink(failOn = "ping-1")

        val outcome = FrameWriter(AtomicReference<OutboundSink?>(sink), null, counters)
            .writeControl(control("ping-1"))

        assertEquals(SubmitOutcome.REJECTED, outcome, "写失败应返回 REJECTED 而不是抛异常")
        assertEquals(1, counters.writeFailures(), "写失败应计 writeFailures")
    }

    /**
     * 未连接时的**分片组**按可丢性计一次拒绝，且**不落盘**。
     *
     * 落盘格式不保存 `groupId`/`seq`/`total`，回放会得到一个既非原帧、也无法被对端重组的
     * "孤儿 chunk"。所以分片组写不出去时只能整体放弃——这条断言防止有人"顺手"给它加落盘。
     */
    @Test
    fun `未连接时整组分片不落盘而按可丢性计一次`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)

        FrameWriter(AtomicReference<OutboundSink?>(null), spill, counters)
            .writeGroup(chunkGroup("grp-1", 3, RELIABLE))

        assertEquals(1, counters.reliableRejected(), "整组放弃应计一次拒绝（不是每片一次）")
        assertEquals(0, counters.spilled(), "分片组绝不能落盘")
        assertEquals(0, spill.pending(), "分片组不得占用落盘缓冲")
    }

    /** 未连接时可丢优先级的整组同样只计可丢丢弃，不进可靠计数。 */
    @Test
    fun `未连接时可丢优先级的整组计入可丢丢弃`() {
        val counters = SenderCounters()

        FrameWriter(AtomicReference<OutboundSink?>(null), null, counters)
            .writeGroup(chunkGroup("grp-1", 3, BEST_EFFORT))

        assertEquals(1, counters.bestEffortDropped(), "整组放弃计一次可丢丢弃")
        assertEquals(0, counters.reliableRejected(), "不得进可靠拒绝计数")
    }

    /**
     * 分片组写到一半失败：计写失败 + 整体放弃**剩余片**（不重传、不落盘）。
     *
     * 对端会收到半组，由"新 hello 清空分组状态"兜底（规格 §3.5）；探针不重传。
     */
    @Test
    fun `分片组中途写失败后放弃剩余片`() {
        val counters = SenderCounters()
        val sink = FailingSink(failOn = "grp-1#1")

        FrameWriter(AtomicReference<OutboundSink?>(sink), null, counters)
            .writeGroup(chunkGroup("grp-1", 4, RELIABLE))

        assertEquals(listOf("grp-1#0", "grp-1#1"), sink.attempts, "失败后不得继续尝试剩余片")
        assertEquals(1, counters.writeFailures(), "写失败应计 writeFailures")
        assertEquals(1, counters.reliableRejected(), "整组放弃应计一次拒绝")
    }

    /** 已连接时分片组整组连续写出、顺序与 `seq` 一致。 */
    @Test
    fun `已连接时整组分片连续写出`() {
        val counters = SenderCounters()
        val sink = RecordingFrameSink()

        FrameWriter(AtomicReference<OutboundSink?>(sink), null, counters)
            .writeGroup(chunkGroup("grp-1", 3, RELIABLE))

        assertEquals(listOf("grp-1#0", "grp-1#1", "grp-1#2"), sink.written, "同组片应按 seq 连续写出")
        assertEquals(0, counters.writeFailures(), "正常写出不得计写失败")
    }

    /**
     * `total == 1` 的**分片**帧仍走分片组路径（不享受单帧的落盘语义）。
     *
     * 单帧判定看的是 `groupId == null` 而不是 `total == 1`：哪怕只有一片，
     * 落盘回放后它也是个没有分片元数据的孤儿 chunk，对端永远重组不出来。
     */
    @Test
    fun `单片分片帧也不落盘`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)
        val single = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "chunk",
            domain = "demo",
            payload = "grp-1#0",
            priority = RELIABLE,
            groupId = "grp-1",
            seq = 0,
            total = 1,
        )

        FrameWriter(AtomicReference<OutboundSink?>(null), spill, counters).writeGroup(listOf(single))

        assertEquals(0, counters.spilled(), "分片帧即使只有一片也不得落盘")
        assertEquals(0, spill.pending(), "不得占用落盘缓冲")
        assertEquals(1, counters.reliableRejected(), "应按可丢性计一次拒绝")
    }

    /** 落盘通道存在但帧是未分片的单帧时，走单帧路径（落盘语义生效）。 */
    @Test
    fun `未分片单帧走单帧路径`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)
        val sink = RecordingFrameSink()

        FrameWriter(AtomicReference<OutboundSink?>(sink), spill, counters)
            .writeGroup(listOf(event("evt-1", RELIABLE)))

        assertEquals(listOf("evt-1"), sink.written, "单帧应直接写出")
        assertEquals(0, counters.spilled(), "写出成功不落盘")
    }

    /**
     * 写出成功才推进落盘提交位点：回放取出的那个实例被成功写出后，未确认数归零。
     *
     * 这是“至少一次”语义的不可缺失的一半：只写不提交 → 每次重连都从同一位置重放；
     * 提交得太早（未写出就推位点）→ 崩溃时那段事件永久丢失。
     * `onSent` 靠**引用相等**认记录，所以这里必须把 [EventSpillStore.next] 返回的**同一个实例**
     * 交给 writer，而不是新造一个内容相同的帧。
     */
    @Test
    fun `回放实例写出成功后推进落盘提交位点`(@TempDir dir: File) {
        val counters = SenderCounters()
        val spill = spill(dir)
        spill.load()
        spill.append(event("evt-1", RELIABLE))
        spill.flushForTest()
        assertEquals(1, spill.pending(), "前提:已有一条未确认记录")

        val replayed = spill.next()
        assertNotNull(replayed, "落盘的可靠事件应可回放")

        FrameWriter(AtomicReference<OutboundSink?>(RecordingFrameSink()), spill, counters)
            .writeGroup(listOf(replayed!!))

        assertEquals(0, spill.pending(), "写出成功后应确认该记录（提交位点推进）")
        assertEquals(0, counters.spilled(), "这是回放重发，不是新落盘")
        assertEquals(0, counters.writeFailures(), "写出成功不得计写失败")
    }

    /** 造一条未分片的业务事件帧。 */
    private fun event(payload: String, priority: OutboundFrame.Priority): OutboundFrame = OutboundFrame(
        type = OutboundFrame.TYPE_EVENT,
        event = "demo_change",
        domain = "demo",
        payload = payload,
        priority = priority,
    )

    /** 造一条控制帧（ping）。 */
    private fun control(payload: String): OutboundFrame = OutboundFrame(
        type = "ping",
        payload = payload,
        priority = OutboundFrame.Priority.CONTROL,
    )

    /**
     * 造一个 [count] 片的分片组：负载形如 `grp-1#0`，便于断言写出顺序。
     *
     * @param groupId 分组标识（非 null 即视为分片帧）。
     * @param count 片数（`total`）。
     * @param priority 统一优先级。
     */
    private fun chunkGroup(
        groupId: String,
        count: Int,
        priority: OutboundFrame.Priority,
    ): List<OutboundFrame> = (0 until count).map { seq ->
        OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "chunk",
            domain = "demo",
            payload = "$groupId#$seq",
            priority = priority,
            groupId = groupId,
            seq = seq,
            total = count,
        )
    }

    /** 指向临时目录的落盘存储（[append] 只做内存投递，不需要真实写盘即可观察 `pending`）。 */
    private fun spill(dir: File): EventSpillStore = EventSpillStore(
        LineSettings(spillEnabled = true, spillDir = dir.absolutePath),
    )

    private companion object {
        val RELIABLE: OutboundFrame.Priority = OutboundFrame.Priority.RELIABLE_EVENT
        val BEST_EFFORT: OutboundFrame.Priority = OutboundFrame.Priority.BEST_EFFORT_EVENT
        val COMMAND_RESULT: OutboundFrame.Priority = OutboundFrame.Priority.COMMAND_RESULT
    }
}

/** 只记录写出的假连接：把"写出成功"与"写了什么"都留在测试里可断言。 */
private class RecordingFrameSink : OutboundSink {

    /** 已成功写出的负载（按写出顺序）。 */
    val written = ArrayList<String>()

    override fun sendText(text: String): Boolean {
        written.add(text)
        return true
    }
}

/**
 * 指定条负载开始抛 `IOException` 的假连接，用于模拟"连接已坏"。
 *
 * 失败发生在 `sendText` 内部（而不是返回 false）：这正是生产路径上写超时/断线的表现形式，
 * `FrameWriter` 靠捕获 `IOException` 走降级分支。
 */
private class FailingSink(private val failOn: String) : OutboundSink {

    /** 每一次被调用的负载（含抛异常的本次），用于断言"失败后放弃剩余片"。 */
    val attempts = ArrayList<String>()

    override fun sendText(text: String): Boolean {
        attempts.add(text)
        if (text == failOn) throw IOException("模拟写失败")
        return true
    }
}
