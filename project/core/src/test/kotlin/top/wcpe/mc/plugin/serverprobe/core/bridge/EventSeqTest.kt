package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.BridgeEventPublisher
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.io.File
import java.util.Collections

/**
 * 可靠事件序号的验收测试（spec §6 的 5 条 + 持久化续接）。
 *
 * ## 为什么用真实 `FrameSender` + 记录滤镜
 * 序号必须出现在**帧**里才算真的透传。用一个只记录帧的滤镜，就能同时断言
 * `OutboundFrame.eventSeq`（本地可读）与 `payload` 里的 `seq`（对端可读）——两者不同源是本功能最易犯的错。
 *
 * ## 为什么 JSON 编码器要替换
 * core 的单测类路径没有 nightconfig（`Json.encode` 会 `NoClassDefFoundError`）。
 * 记录滤镜抓到的 `payload` 是"纯文本序列化器"的输出，足以断言字段是否存在与取值。
 */
class EventSeqTest {

    private val senders = ArrayList<FrameSender>()

    @AfterEach
    fun tearDown() {
        senders.forEach { it.shutdown() }
        senders.clear()
    }

    // ------------------------------------------------------------ 严格递增无跳号

    /** 验收 1：连续 emit 100 条可靠事件 → seq 1..100 无跳号。 */
    @Test
    fun `连续100条可靠事件seq无跳号`() {
        val fixture = fixture()

        repeat(SEQ_COUNT) { fixture.sink.emitReliable("activity", "k-$it", null, mapOf("i" to "$it")) }

        val seqs = fixture.recorder.frames.map { it.eventSeq }
        assertEquals((1L..SEQ_COUNT.toLong()).toList(), seqs, "可靠事件 seq 必须从 1 连续递增")
    }

    /** 验收 2：可丢事件混入 → 不影响可靠事件 seq 连续性。 */
    @Test
    fun `可丢事件不占用seq序列`() {
        val fixture = fixture()

        repeat(SEQ_COUNT) { index ->
            // 交错的形态与真实场景一致：进度刷新（可丢）夹在结算（可靠）之间。
            fixture.sink.emit("activity", null, null, mapOf("progress" to "$index"))
            fixture.sink.emitReliable("activity", "k-$index", null, mapOf("settled" to "true"))
        }

        val reliable = fixture.recorder.frames.filter { it.priority == OutboundFrame.Priority.RELIABLE_EVENT }
        val bestEffort = fixture.recorder.frames.filter { it.priority == OutboundFrame.Priority.BEST_EFFORT_EVENT }

        assertEquals((1L..SEQ_COUNT.toLong()).toList(), reliable.map { it.eventSeq }, "可靠事件序列不得因可丢事件出现空洞")
        assertTrue(bestEffort.isNotEmpty(), "前置：应确实发出了可丢事件")
        assertTrue(bestEffort.all { it.eventSeq == 0L }, "可丢事件不得携带 seq（0 = 没有位点）")
    }

    /** **per-domain 独立**：不同域各有自己的序列，互不干扰（不是全局单调）。 */
    @Test
    fun `不同域seq各自独立递增`() {
        val fixture = fixture()

        repeat(3) {
            fixture.sink.emitReliable("activity", "a-$it", null, emptyMap())
            fixture.sink.emitReliable("economy", "e-$it", null, emptyMap())
        }

        val byDomain = fixture.recorder.frames.groupBy { it.domain }
        assertEquals(listOf(1L, 2L, 3L), byDomain.getValue("activity").map { it.eventSeq })
        assertEquals(listOf(1L, 2L, 3L), byDomain.getValue("economy").map { it.eventSeq }, "另一域应从 1 重新开始")
    }

    /** 验收 5：同一 dedupKey 重复 emit → 两条都有 seq（去重由下游做）。 */
    @Test
    fun `同dedupKey重复emit两条都有seq`() {
        val fixture = fixture()

        fixture.sink.emitReliable("activity", "same-key", null, mapOf("n" to "1"))
        fixture.sink.emitReliable("activity", "same-key", null, mapOf("n" to "2"))

        val seqs = fixture.recorder.frames.map { it.eventSeq }
        assertEquals(listOf(1L, 2L), seqs, "探针不做去重：两条都要分配 seq，由下游按 dedupKey 幂等")
    }

    // ---------------------------------------------------------------- seqEpoch

    /** `seqEpoch` 与 `seq` 一起出现在帧字段与 payload 里（spec §3.4 的帧格式）。 */
    @Test
    fun `seq与seqEpoch透传到帧与payload`() {
        val fixture = fixture()

        fixture.sink.emitReliable("activity", "k-1", "activity.1", mapOf("v" to "x"))

        val frame = fixture.recorder.frames.single()
        assertEquals(1L, frame.eventSeq, "帧字段应带 seq")
        assertTrue(frame.hasEventSeq, "应能判定该帧携带位点")
        assertTrue(frame.seqEpoch > 0L, "帧字段应带 seqEpoch")
        assertTrue(frame.payload.contains("|seq=1|"), "payload 应带同一 seq，实际=${frame.payload}")
        assertTrue(frame.payload.contains("|seqEpoch="), "payload 应带 seqEpoch，实际=${frame.payload}")
    }

    /** 可丢事件的 payload 里**不出现** `seq` 字段（缺失 = 无位点，而不是 seq=0）。 */
    @Test
    fun `可丢事件payload不含seq字段`() {
        val fixture = fixture()

        fixture.sink.emit("activity", null, null, mapOf("progress" to "50"))

        val frame = fixture.recorder.frames.single()
        assertEquals(0L, frame.eventSeq, "可丢事件的帧字段应为 0")
        assertFalse(frame.hasEventSeq, "不应被判定为携带位点")
        assertFalse(frame.payload.contains("|seq="), "可丢事件 payload 不得含 seq，实际=${frame.payload}")
        assertFalse(frame.payload.contains("|seqEpoch="), "可丢事件 payload 不得含 seqEpoch，实际=${frame.payload}")
    }

    // ------------------------------------------------------------ 重启续接（方案 B）

    /**
     * 验收 3：探针重启 → seq **续接**（spec §3.3 方案 B）。
     *
     * 用同一目录构造第二个分配器来模拟重启：它必须从 `max + 1` 继续，且 **epoch 不变**
     * （方案 B 的语义正是"序号可跨重启比对"，epoch 若变了下游就得全量对账，等于退回方案 A）。
     */
    @Test
    fun `重启后seq续接且epoch不变`(@TempDir dir: File) {
        val store = EventSeqStore(dir)
        val first = EventSeqAllocator(store) { FIXED_CLOCK }
        first.load()
        repeat(5) { first.next("activity") }
        first.shutdown()

        val epochBefore = first.currentEpoch()
        val second = EventSeqAllocator(EventSeqStore(dir)) { FIXED_CLOCK }
        second.load()

        assertEquals(epochBefore, second.currentEpoch(), "方案 B 下正常重启不应递增 epoch")
        assertEquals(6L, second.next("activity"), "重启后应从已分配的最大值 +1 继续")
        assertEquals(7L, second.next("activity"), "续接后仍应连续")
        assertEquals(1L, second.next("economy"), "未持久化的域仍从 1 开始")
    }

    /** 全新安装（无状态文件）→ 从 **1** 开始，epoch 不变（spec §3.2「起始 1」）。 */
    @Test
    fun `首次启动从1开始且epoch不变`(@TempDir dir: File) {
        val allocator = EventSeqAllocator(EventSeqStore(dir)) { FIXED_CLOCK }
        allocator.load()

        assertEquals(1L, allocator.next("activity"), "首次启动的 seq 必须从 1 开始")
        assertEquals(2L, allocator.next("activity"))
        assertEquals(EventSeqStore.INITIAL_EPOCH, allocator.currentEpoch(), "首次启动不改 epoch")
    }

    /** 持久化状态**损坏** → 从 `(当前时间戳毫秒 << 20)` 起跳，且 epoch 递增。 */
    @Test
    fun `持久化损坏时从时间戳起跳并递增epoch`(@TempDir dir: File) {
        val store = EventSeqStore(dir)
        // 内容不可解析（没有 epoch 行也没有合法的域名行）。
        File(store.filePath()).writeText("这不是合法内容\nactivity\tnot-a-number\n")
        val allocator = EventSeqAllocator(store) { FIXED_CLOCK }
        allocator.load()

        val seq = allocator.next("activity")
        val expectedBase = FIXED_CLOCK shl EventSeqAllocator.SHIFT_BITS

        assertEquals(expectedBase, seq, "持久化丢失时应从 (时间戳 << 20) 起跳")
        assertTrue(seq > 1L, "起跳值必须远大于 1，避免与历史值冲突")
        assertTrue(
            allocator.currentEpoch() > EventSeqStore.INITIAL_EPOCH,
            "序号语义重置时 epoch 必须递增（下游据此触发全量对账）",
        )
    }

    /** 持久化丢失后的起点**大于**任何合理的历史值（这是"不冲突"的直接证明）。 */
    @Test
    fun `时间戳起跳值高于历史区间`(@TempDir dir: File) {
        val store = EventSeqStore(dir)
        File(store.filePath()).writeText("坏内容\n")
        val allocator = EventSeqAllocator(store) { FIXED_CLOCK }
        allocator.load()

        val base = allocator.next("activity")

        // 历史值上限取一个足够宽的量级：每秒百万条连发一年 ≈ 3.2e13。
        assertTrue(base > 32_000_000_000_000L, "起跳值应远高于任何现实的历史序号，实际=$base")
    }

    /** 状态文件的行格式可被人工阅读，且**域名与数值都在**（可运维性）。 */
    @Test
    fun `状态文件内容可读且含域名与最大序号`(@TempDir dir: File) {
        val store = EventSeqStore(dir)
        val allocator = EventSeqAllocator(store) { FIXED_CLOCK }
        allocator.load()
        repeat(3) { allocator.next("activity") }
        allocator.shutdown()

        val text = File(store.filePath()).readText()

        assertTrue(text.contains("epoch"), "应含 epoch 行，实际=$text")
        assertTrue(text.contains("activity\t"), "应含域名，实际=$text")
        assertTrue(text.lines().any { it.startsWith("activity\t") && it.endsWith("\t3") }, "应记录该域最大序号，实际=$text")
    }

    /** 状态文件损坏 → 按"持久化丢失"处理并**照常分配**（绝不因此拒绝上报）。 */
    @Test
    fun `状态文件损坏时照常分配`(@TempDir dir: File) {
        val store = EventSeqStore(dir)
        File(store.filePath()).writeText("这不是合法内容\nactivity\tnot-a-number\neconomy\n")
        val allocator = EventSeqAllocator(store) { FIXED_CLOCK }

        allocator.load()
        val first = allocator.next("activity")

        assertTrue(first > 0L, "损坏的状态文件不得让分配失败")
    }

    /**
     * 损坏后**崩溃再启动**仍是时间戳起跳，绝不退回小序号。
     *
     * 这条回归用例针对一个真实缺陷：若丢失时立刻把（空的）状态写回去，文件就变成
     * "可读但域表为空"，下次启动会被判成正常状态 → 各域从 1 起跳，
     * 与刚刚发出去的 `1.8e18` 号段正面冲突，下游会看到序号倒退。
     *
     * 刻意**不调用 `shutdown()`**：正常停机会把真实域表写下来，那条路径本来就是安全的；
     * 要覆盖的是"起号之后进程直接崩了"这种形态。
     */
    @Test
    fun `损坏后崩溃重启仍不退回到小序号`(@TempDir dir: File) {
        File(EventSeqStore(dir).filePath()).writeText("这不是合法内容\n")
        val first = EventSeqAllocator(EventSeqStore(dir)) { FIXED_CLOCK }
        first.load()
        val firstSeq = first.next("activity")
        // 模拟崩溃：不 flush、不 shutdown，直接丢弃实例。

        val second = EventSeqAllocator(EventSeqStore(dir)) { FIXED_CLOCK }
        second.load()
        val secondSeq = second.next("activity")

        assertTrue(firstSeq > 1L, "前置：损坏态应从时间戳起跳，实际=$firstSeq")

        // 这条断言是本次修复的重点。
        //
        // 原先这里写的是 `secondSeq > 1L`——但时间戳起跳值恒约为 1.8e18，
        // 无论序号怎么倒退都不可能小于等于 1，所以那条断言**永远不会失败**，
        // 它声称守护的缺陷（写回空域表 → 下次从 1 起跳）根本没被抓住。
        //
        // 真正要断言的是“不得**倒退**”：第二次的起跳值不得低于第一次。
        // 若损坏态被错误地落盘成“可读且域表为空”，第二次就会从 1 起（或从小值），
        // 这里会立即失败。
        assertTrue(
            secondSeq >= firstSeq,
            "崩溃重启后序号不得倒退（第一次=$firstSeq，第二次=$secondSeq）——" +
                "若第二次变小，说明损坏态被错误地写成了可读状态",
        )

        // 并钉住“epoch 已离开初始值”：损坏态必须让下游知道这一段与历史不可比。
        //
        // 注意不能断言“第二次的 epoch 大于第一次”——实现有意在损坏时不落盘
        // （见 EventSeqAllocator 的 Corrupt 分支），于是两次启动都是 Corrupt，
        // 两次都定格在 INITIAL_EPOCH + 1。那是刻意设计（保持损坏态以避开“可读但域表为空”的陷阱），
        // 不是缺陷。这里只验“确实递增到了非初始值”。
        assertTrue(
            first.currentEpoch() > EventSeqStore.INITIAL_EPOCH,
            "损坏态应递增 seqEpoch（初始=${EventSeqStore.INITIAL_EPOCH}，实际=${first.currentEpoch()}）",
        )
        assertEquals(
            first.currentEpoch(),
            second.currentEpoch(),
            "损坏态不落盘是刻意的：两次启动都仍读到 Corrupt，epoch 因此相同",
        )

        // 真正该守的元数据前提：第一次起号后，域表里应已有记录（供 shutdown 写盘）。
        // 若这张表一直为空，正常停机也无法把状态恢复成可读，每次重启都要重新起跳。
        assertTrue(
            first.maxSeqOf("activity") > 0L,
            "取过号之后该域应有已分配位点，实际=${first.maxSeqOf("activity")}",
        )
    }

    // ------------------------------------------------------------ 补发保持原 seq

    /**
     * 验收 4：从 spill 补发 → **保持原 seq**。
     *
     * 端到端走真实路径：通道满 → 落盘（位点一起写入行）→ 重连回放 → 读出的帧仍是原 seq。
     */
    @Test
    fun `spill补发保持原seq`(@TempDir dir: File) {
        val settings = LineSettings(spillEnabled = true, spillDir = dir.absolutePath, reliableCapacity = 1)
        val store = EventSpillStore(settings).also { it.load() }
        val sink = BusinessEventSinkImpl()
        val sender = FrameSender(settings, store)
        senders.add(sender)
        val recorder = FrameRecorder()
        sender.addFilter(recorder)
        sink.publisherOverride = BridgeEventPublisher(sender = { sender }, instance = { INSTANCE_ID }, encode = PLAIN_ENCODE)

        // 队列容量 1：第 1 条入队，第 2 条撞满 → 落盘。
        sink.emitReliable("activity", "k-1", null, emptyMap())
        sink.emitReliable("activity", "k-2", null, emptyMap())
        store.flushForTest()

        val replayed = store.next()

        assertNotNull(replayed, "第 2 条应已落盘待回放")
        assertEquals(2L, replayed!!.eventSeq, "补发必须沿用原 seq，不得重新分配")
        assertTrue(replayed.seqEpoch > 0L, "补发的帧同样要带 seqEpoch")
        assertTrue(replayed.payload.contains("|seq=2|"), "补发帧的 payload 也应带原 seq，实际=${replayed.payload}")
    }

    /** 落盘行兼容之前的 6 字段格式（升级不该让待回放事件读不出来）。 */
    @Test
    fun `旧格式落盘行仍可回放`(@TempDir dir: File) {
        val settings = LineSettings(spillEnabled = true, spillDir = dir.absolutePath)
        // 旧行格式：type \t event \t domain \t topic \t requestId \t payload
        File(dir, "spill.jsonl").writeText("event\tactivity_change\tactivity\t\t\tpayload-body\n")
        val store = EventSpillStore(settings).also { it.load() }

        val frame = store.next()

        assertNotNull(frame, "旧格式行应仍可读出")
        assertEquals("payload-body", frame!!.payload, "旧格式的 payload 在第 6 列")
        assertEquals(0L, frame.eventSeq, "旧格式没有位点，应为 0")
    }

    // -------------------------------------------------------- 调用方指定 seq（补发入口）

    /** 调用方指定的 seq 照用，且**过去的号不推进**分配序列（否则新事件会撞进历史区间）。 */
    @Test
    fun `调用方指定seq不推进分配序列`() {
        val fixture = fixture()

        fixture.sink.emitReliable("activity", "k-1", null, emptyMap())
        // 回填一个**已经用过**的号：分配序列必须原地不动。
        fixture.sink.emitReliable("activity", "old-1", null, emptyMap(), 1L)
        fixture.sink.emitReliable("activity", "k-2", null, emptyMap())

        val seqs = fixture.recorder.frames.map { it.eventSeq }
        assertEquals(1L, seqs[0], "第 1 条自动分配")
        assertEquals(1L, seqs[1], "显式指定的号照用（下游按 dedupKey 幂等）")
        assertEquals(2L, seqs[2], "指定过去的号不得推进分配序列")
    }

    /** 指定值 `<= 0` 视为无效并返回 false（下游会把它当真实位点，不能放行）。 */
    @Test
    fun `指定非法seq被拒绝`() {
        val fixture = fixture()

        assertFalse(fixture.sink.emitReliable("activity", "k-1", null, emptyMap(), 0L), "seq=0 应拒绝")
        assertFalse(fixture.sink.emitReliable("activity", "k-2", null, emptyMap(), -5L), "负数应拒绝")
        assertTrue(fixture.recorder.frames.isEmpty(), "被拒绝的事件不得产生上行帧")
    }

    /** 指定**更大**的 seq 会抬高后续自动分配的起点（避免下游看到序号倒退）。 */
    @Test
    fun `指定更大seq抬高后续起点`() {
        val fixture = fixture()

        fixture.sink.emitReliable("activity", "old-1", null, emptyMap(), 100L)
        fixture.sink.emitReliable("activity", "k-1", null, emptyMap())

        val seqs = fixture.recorder.frames.map { it.eventSeq }
        assertEquals(100L, seqs[0])
        assertEquals(101L, seqs[1], "后续自动分配必须大于已用过的最大值")
    }

    // -------------------------------------------------------------------- 边界

    /** 探针未就绪时**不消耗**序号（否则每次失败的早期上报都在序列里留一个假空洞）。 */
    @Test
    fun `未接线时不消耗序号`() {
        val sink = BusinessEventSinkImpl()
        val allocator = EventSeqAllocator()
        sink.seqAllocatorOverride = allocator

        repeat(5) { assertFalse(sink.emitReliable("activity", "k-$it", null, emptyMap()), "未接线应返回 false") }

        assertEquals(0L, allocator.maxSeqOf("activity"), "未被受理的事件不得占用序号")
    }

    /** 空域不分配序号（拼错的域名不该在序列里留假缺口）。 */
    @Test
    fun `空域不分配序号`() {
        val fixture = fixture()

        assertFalse(fixture.sink.emitReliable("", "k-1", null, emptyMap()), "空域应无效")

        assertEquals(0L, fixture.allocator.maxSeqOf(""), "空域不得分配序号")
        assertTrue(fixture.recorder.frames.isEmpty(), "空域不得产生上行帧")
    }

    // -------------------------------------------------------------------- 测试设施

    private class FrameRecorder : OutboundFilter {
        val frames: MutableList<OutboundFrame> = Collections.synchronizedList(ArrayList())

        override val order: Int = RECORDER_ORDER

        override fun apply(frame: OutboundFrame): OutboundFrame {
            frames.add(frame)
            return frame
        }
    }

    private class Fixture(
        val sink: BusinessEventSinkImpl,
        val allocator: EventSeqAllocator,
        val recorder: FrameRecorder,
    )

    /** 组装：分配器 → 上报实现 → 真实发送器 + 记录滤镜。 */
    private fun fixture(settings: LineSettings = LineSettings(bestEffortCapacity = 4_096, reliableCapacity = 4_096)): Fixture {
        val sender = FrameSender(settings, null)
        senders.add(sender)
        val recorder = FrameRecorder()
        sender.addFilter(recorder)
        val allocator = EventSeqAllocator()
        val sink = BusinessEventSinkImpl()
        sink.seqAllocatorOverride = allocator
        sink.publisherOverride = BridgeEventPublisher(sender = { sender }, instance = { INSTANCE_ID }, encode = PLAIN_ENCODE)
        return Fixture(sink, allocator, recorder)
    }

    private companion object {
        const val RECORDER_ORDER = 999
        const val INSTANCE_ID = "s1"

        /** 验收条数（spec §6 第 1 条）。 */
        const val SEQ_COUNT = 100

        /** 假时钟：固定值便于精确断言 `时间戳 << 20`。 */
        const val FIXED_CLOCK = 1_730_000_000_000L

        /** 不依赖 JSON 库的序列化器：按 `k=v` 顺序拼接（足以断言字段是否存在与取值）。 */
        val PLAIN_ENCODE: (Any?) -> String = { value ->
            (value as Map<*, *>).entries.joinToString("|", prefix = "|") { "${it.key}=${it.value}" }
        }
    }
}
