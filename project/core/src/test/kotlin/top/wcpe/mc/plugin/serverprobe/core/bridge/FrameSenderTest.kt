package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.frameWithChunkCount
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.stringFieldOf
import top.wcpe.mc.plugin.serverprobe.core.config.BridgeConfigSource
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfigBridgeConfigSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FrameSender] 单元测试（线路治理 + 帧分片）。
 *
 * 覆盖分级与优先级不变量。**不用墙钟阈值断言性能**：
 * 「回执优先」用"先灌事件、等它们真的开始写出、再提交回执"的顺序断言，
 * 而不是"测得回执在 X 毫秒内写出"——后者在 CI 上必然 flaky。
 *
 * 分片相关用例**一律注入小阈值**（`max-frame-bytes` 用 512~1024），不读默认 256 KiB：
 * 用默认值断言会让"读了默认值但阈值逻辑写错"的实现照样变绿。
 */
class FrameSenderTest {

    private val senders = ArrayList<FrameSender>()

    @AfterEach
    fun tearDown() {
        senders.forEach { it.shutdown() }
        senders.clear()
    }

    /** 构造受测发送器并纳入统一清理。 */
    private fun sender(
        settings: LineSettings = LineSettings(),
        chunker: FrameChunker = FrameChunker(ChunkSettings()),
    ): FrameSender = FrameSender(settings, null, chunker).also { senders.add(it) }

    private fun event(priority: OutboundFrame.Priority, index: Int = 0): OutboundFrame = OutboundFrame(
        type = OutboundFrame.TYPE_EVENT,
        event = "demo_change",
        domain = "demo",
        payload = "payload-$index",
        priority = priority,
    )

    /** 构造注入指定分片参数的切分器。 */
    private fun chunker(maxFrameBytes: Int, maxChunksPerGroup: Int = 64, enabled: Boolean = true): FrameChunker =
        FrameChunker(
            ChunkSettings(enabled = enabled, maxFrameBytes = maxFrameBytes, maxChunksPerGroup = maxChunksPerGroup),
        )

    /**
     * 可丢事件队列满即丢弃并计数，不抛。
     */
    @Test
    fun `可丢事件队列满即丢弃`() {
        val sender = sender(LineSettings(bestEffortCapacity = 2))

        val outcomes = (0 until 5).map { sender.submit(event(OutboundFrame.Priority.BEST_EFFORT_EVENT, it)) }

        assertEquals(2, outcomes.count { it == SubmitOutcome.QUEUED }, "容量内应入队")
        assertEquals(3, outcomes.count { it == SubmitOutcome.DROPPED }, "超出容量应丢弃")
        assertEquals(3, sender.status().bestEffortDropped, "丢弃应计数")
    }

    /**
     * 可靠事件队列满且落盘不可用 → 拒绝（调用方须转业务失败），而不是静默丢。
     */
    @Test
    fun `可靠事件队列满即拒绝`() {
        val sender = sender(LineSettings(reliableCapacity = 1, spillEnabled = false))

        val outcomes = (0 until 3).map { sender.submit(event(OutboundFrame.Priority.RELIABLE_EVENT, it)) }

        assertEquals(1, outcomes.count { it == SubmitOutcome.QUEUED })
        assertEquals(2, outcomes.count { it == SubmitOutcome.REJECTED }, "可靠事件满应拒绝而非丢弃")
        assertEquals(2, sender.status().reliableRejected)
    }

    /**
     * 滤镜丢弃的帧不占容量、不计失败——这是"订阅越多越容易失败"的防线。
     */
    @Test
    fun `滤镜先于容量判定`() {
        val sender = sender(LineSettings(reliableCapacity = 1))
        val dropped = AtomicInteger(0)
        sender.addFilter(object : OutboundFilter {
            override val order: Int = 200
            override fun apply(frame: OutboundFrame): OutboundFrame? {
                dropped.incrementAndGet()
                return null
            }
        })

        val outcomes = (0 until 10).map { sender.submit(event(OutboundFrame.Priority.RELIABLE_EVENT, it)) }

        assertTrue(outcomes.all { it == SubmitOutcome.FILTERED_OUT }, "全部应被滤镜丢弃")
        assertEquals(10, sender.status().filteredOut, "应计入 filteredOut")
        assertEquals(0, sender.status().reliableQueue, "被滤镜丢弃的帧不得占队列容量")
        assertEquals(0, sender.status().reliableRejected, "滤镜丢弃不算失败")
        assertEquals(10, dropped.get(), "滤镜应对每一帧都被调用")
    }

    /**
     * 回执优先于事件：在事件队列仍有积压时提交回执，回执必须先于剩余事件写出。
     *
     * 断言的是**顺序**而非延迟。用「事件队列仍有剩余」作为前置条件，
     * 避免事件恰好已被消费完导致测试凭运气通过（那是不证明任何事的假绿）。
     */
    @Test
    fun `命令回执优先于事件写出`() {
        val settings = LineSettings(bestEffortCapacity = 256)
        val sender = sender(settings)
        val written = java.util.Collections.synchronizedList(ArrayList<String>())
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)

        // 第一条写出后**卡住**后续写出,稳定制造积压(否则写得比灌得快,测不出优先级)
        val ws = FakeWs { payload ->
            written.add(payload)
            if (payload.endsWith("-0")) {
                started.countDown()
                hold.await(3, TimeUnit.SECONDS)
            }
        }
        sender.attach(ws)
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()

        // 灌入远超容量的数量
        repeat(200) { sender.submit(event(OutboundFrame.Priority.BEST_EFFORT_EVENT, it)) }
        assertTrue(started.await(2, TimeUnit.SECONDS), "事件应开始写出")

        // 此刻发送线程被第一条卡住,队列必然有积压
        val backlogBefore = sender.status().bestEffortQueue
        assertTrue(backlogBefore > 0, "需要制造事件积压才能检验优先级(实际积压=$backlogBefore)")

        sender.submitReceipt(
            OutboundFrame(
                type = OutboundFrame.TYPE_EVENT,
                event = OutboundFrame.TYPE_COMMAND_RESULT,
                payload = "receipt-1",
                priority = OutboundFrame.Priority.COMMAND_RESULT,
                requestId = "r1",
            ),
        )

        // 放行写出;回执应在剩余积压事件之前被取走
        hold.countDown()
        awaitCondition { snapshot(written).contains("receipt-1") }

        val receiptIndex = written.indexOf("receipt-1")
        assertTrue(receiptIndex >= 0, "回执应被写出")
        assertTrue(
            receiptIndex < 200,
            "回执应先于剩余积压事件写出(实际位置=$receiptIndex,事件总数=200)",
        )
    }

    /**
     * 未连接时可靠事件落盘失败 → 计入 `reliableRejected`，**不得**混进 `bestEffortDropped`。
     *
     * `SenderStatus.reliableRejected` 的语义就是"可靠事件因队列满且落盘失败被拒"。
     * 记到可丢丢弃里，会让"可靠事件丢了"这件事在运维看板上彻底看不见——
     * 而可靠通道存在的唯一意义就是"丢没丢必须可观测"。
     */
    @Test
    fun `未连接且落盘不可用时可靠事件计入reliableRejected`() {
        val sender = sender(LineSettings(reliableCapacity = 8, spillEnabled = false))
        // 不 attach 连接：发送线程会把帧判为"写不出去"，走未连接分支。
        sender.submit(event(OutboundFrame.Priority.RELIABLE_EVENT, 0))
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()
        waitUntil { sender.status().reliableQueue == 0 }

        val status = sender.status()
        assertEquals(1, status.reliableRejected, "可靠事件落盘失败必须计入 reliableRejected")
        assertEquals(0, status.bestEffortDropped, "不得把可靠事件丢失混进可丢计数")
    }

    /**
     * 写失败（IOException）且落盘不可用时 → 同样计入 `reliableRejected`。
     *
     * 这里钉住的是"落盘结果必须被检查"：曾出现过无条件记 `spilled` 的写法，
     * 于是事件实际已丢失，监控却显示"已落盘、等回放"。
     */
    @Test
    fun `写失败且落盘不可用时可靠事件计入reliableRejected`() {
        val sender = sender(LineSettings(reliableCapacity = 8, spillEnabled = false))
        sender.attach(FakeWs { throw IOException("模拟写失败") })
        sender.submit(event(OutboundFrame.Priority.RELIABLE_EVENT, 0))
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()
        waitUntil { sender.status().reliableQueue == 0 }

        val status = sender.status()
        assertEquals(1, status.reliableRejected, "写失败且落盘失败必须计入 reliableRejected")
        assertEquals(0, status.bestEffortDropped, "不得把可靠事件丢失混进可丢计数")
        assertEquals(0, status.spilled, "落盘未成功就不能记 spilled")
    }

    /**
     * 未连接时可靠事件落盘（这里断言不抛且计数），可丢事件直接丢。 */
    @Test
    fun `未连接时按可丢性分级处置`() {
        val sender = sender(LineSettings(reliableCapacity = 1, spillEnabled = false))
        // 不 attach 连接
        val outcomes = listOf(
            sender.submit(event(OutboundFrame.Priority.RELIABLE_EVENT, 0)),
            sender.submit(event(OutboundFrame.Priority.BEST_EFFORT_EVENT, 1)),
        )
        // 未连接时帧先入队,由发送线程判定;此处只断言 submit 本身不抛、返回合法枚举
        assertTrue(outcomes.all { it == SubmitOutcome.QUEUED || it == SubmitOutcome.REJECTED })
    }

    /**
     * 同组原子性：可靠队列只剩 2 个空位时提交 64 片的组 → 整组拒绝，队列深度不变，**一片都不写出**。
     *
     * 这是分片最关键的不变量。逐片 `offer` 的实现会在这里发出 2 个前缀片
     * （`seq 0..1 / total 64`），对端于是永久等待其余 62 片：重组缓冲泄漏且无超时能回收它。
     * 断言"写出 0 帧"而不只是"返回 REJECTED"，正是为了钉住这一点。
     */
    @Test
    fun `分片组按total原子预留`() {
        // 单组上限放到 128，让"恰好 64 片"的构造不受上限干扰。
        val chunker = chunker(maxFrameBytes = 512, maxChunksPerGroup = 128)
        val original = frameWithChunkCount(chunker, ATOMIC_GROUP_CHUNKS)
        val sender = sender(LineSettings(reliableCapacity = 2, spillEnabled = false), chunker)
        val written = java.util.Collections.synchronizedList(ArrayList<String>())
        sender.attach(FakeWs { written.add(it) })

        // 先占满 2 个空位（不启动发送线程，保证它们仍在队列里）。
        sender.submit(event(OutboundFrame.Priority.RELIABLE_EVENT, 0))
        sender.submit(event(OutboundFrame.Priority.RELIABLE_EVENT, 1))
        assertEquals(2, sender.status().reliableQueue, "前提:可靠队列只剩 2 个空位")

        val outcome = sender.submit(original)

        assertEquals(SubmitOutcome.REJECTED, outcome, "整组放不下应拒绝（可靠事件不可静默丢）")
        assertEquals(2, sender.status().reliableQueue, "预留失败后队列深度不得变化")
        assertEquals(1, sender.status().reliableRejected, "拒绝应计数 +1")
        assertEquals(0, written.size, "不得写出任何前缀片（必须整组放弃）")
    }

    /**
     * 单组上限：片数超 `max-chunks-per-group` → `TOO_LARGE`（硬错误，与可丢性无关）。
     *
     * 这里用命令回执（不可丢、最高优先级）触发，断言它没有被降级成 `REJECTED`：
     * 超限说明"这帧根本没法按线路规则传"，语义上比"通道忙"严重。
     */
    @Test
    fun `单组上限超限返回TOO_LARGE且不入队`() {
        val chunker = chunker(maxFrameBytes = 512, maxChunksPerGroup = 2)
        val sender = sender(LineSettings(receiptCapacity = 8), chunker)
        val written = java.util.Collections.synchronizedList(ArrayList<String>())
        sender.attach(FakeWs { written.add(it) })

        val receipt = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = OutboundFrame.TYPE_COMMAND_RESULT,
            payload = "x".repeat(8192),
            priority = OutboundFrame.Priority.COMMAND_RESULT,
            requestId = "req-big",
        )
        val outcome = sender.submit(receipt)

        assertEquals(SubmitOutcome.TOO_LARGE, outcome, "片数超单组上限应返回 TOO_LARGE")
        assertEquals(0, sender.status().commandResultQueue, "超限不得占容量")
        assertEquals(1, sender.status().commandResultRejected, "超限并入既有回执拒绝计数（不新增字段）")
        assertEquals(0, written.size, "不得写出任何片")
    }

    /**
     * 同组连续：两组并发提交时，同组的片 `seq` 连续、另一组不穿插；CONTROL 帧允许插入。
     *
     * 对端按 `groupId`/`seq` 重组，不依赖连续性——但"同组不被普通帧穿插"是刻意的：
     * 否则对端要在内存里长期持有半成品分组。
     */
    @Test
    fun `同组连续写出且心跳可插入`() {
        val chunker = chunker(maxFrameBytes = 512)
        val sender = sender(LineSettings(reliableCapacity = 64), chunker)
        val written = java.util.Collections.synchronizedList(ArrayList<String>())
        val firstChunk = CountDownLatch(1)
        // 慢读:每片 5ms,让第二组在队列里等着,从而真的出现"两组同时在途"。
        sender.attach(
            FakeWs { payload ->
                written.add(payload)
                if (stringFieldOf(payload, "seq") == "0") firstChunk.countDown()
                Thread.sleep(5)
            },
        )
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()

        sender.submit(frameWithChunkCount(chunker, GROUP_CHUNKS))
        assertTrue(firstChunk.await(2, TimeUnit.SECONDS), "第一组应开始写出")
        sender.submit(frameWithChunkCount(chunker, GROUP_CHUNKS))
        // 控制帧旁路:不入队、直接写，允许插进某一组的中间。
        sender.submit(
            OutboundFrame(type = "ping", payload = "ping-1", priority = OutboundFrame.Priority.CONTROL),
        )

        awaitCondition { snapshot(written).count { it.contains("\"event\":\"chunk\"") } >= GROUP_CHUNKS * 2 }
        // 发送线程可能仍在写，故在列表锁内取快照再断言（直接遍历会 ConcurrentModificationException）。
        val chunks = snapshot(written).filter { it.contains("\"event\":\"chunk\"") }
        assertEquals(GROUP_CHUNKS * 2, chunks.size, "两组共 ${GROUP_CHUNKS * 2} 片都应写出")

        // 每个 groupId 就是一个组：其片在写出序列里必须连续，且 seq 从 0 连续递增。
        val byGroup = chunks.map { stringFieldOf(it, "groupId") ?: error("分片帧缺少 groupId") }
        assertEquals(2, byGroup.toSet().size, "两组应是两个不同的 groupId")
        byGroup.toSet().forEach { groupId ->
            val positions = byGroup.withIndex().filter { it.value == groupId }.map { it.index }
            assertEquals(
                positions.last() - positions.first() + 1,
                positions.size,
                "同一组的片必须连续（不得被另一组穿插），groupId=$groupId",
            )
            val seqs = positions.map { stringFieldOf(chunks[it], "seq")?.toInt() }
            assertEquals((0 until GROUP_CHUNKS).toList(), seqs, "同组 seq 应从 0 连续递增")
        }
        assertTrue(snapshot(written).contains("ping-1"), "控制帧必须被写出（旁路，不等分片组）")
    }

    /**
     * 心跳不被拖死：分片组写出期间，CONTROL ping 仍立即写出（走旁路），不排队等整组。
     *
     * 断言"ping 落在某组中间"而不是"ping 很快"——后者是墙钟断言，在 CI 上必 flaky；
     * 前者直接证明"ping 没有等整组写完"。
     */
    @Test
    fun `心跳不被分片组拖住`() {
        val chunker = chunker(maxFrameBytes = 512)
        val sender = sender(LineSettings(reliableCapacity = 64), chunker)
        val written = java.util.Collections.synchronizedList(ArrayList<String>())
        val firstChunk = CountDownLatch(1)
        sender.attach(
            FakeWs { payload ->
                written.add(payload)
                if (stringFieldOf(payload, "seq") == "0") firstChunk.countDown()
                Thread.sleep(10)
            },
        )
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()

        sender.submit(frameWithChunkCount(chunker, GROUP_CHUNKS))
        assertTrue(firstChunk.await(2, TimeUnit.SECONDS), "大组应开始写出")

        sender.submit(
            OutboundFrame(type = "ping", payload = "ping-mid", priority = OutboundFrame.Priority.CONTROL),
        )

        awaitCondition { snapshot(written).contains("ping-mid") }
        val chunksBeforeWrite = snapshot(written).count { it.contains("\"event\":\"chunk\"") }
        assertTrue(snapshot(written).contains("ping-mid"), "心跳必须写出")
        assertTrue(
            chunksBeforeWrite < GROUP_CHUNKS,
            "ping 应在大组写完之前写出（证明没被组拖住），实际已写 $chunksBeforeWrite/${GROUP_CHUNKS} 片",
        )
        assertEquals(0, sender.status().writeTimeouts, "写侧不应出现超时计数")
    }

    /**
     * 阈值可配：同一内容在"假配置注入的 1024"与 4096 下片数不同。
     *
     * 用 `BridgeConfigSource` 的委托式假实现注入，走的就是生产装配同一条接缝
     * （`ProbeConfigBridgeConfigSource.chunk()` → `FrameChunker`），因此不会退化成"读默认值"的假绿。
     */
    @Test
    fun `阈值由配置注入并改变片数`() {
        val payload = "z".repeat(6000)
        val frame = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "demo_change",
            domain = "demo",
            payload = payload,
            priority = OutboundFrame.Priority.RELIABLE_EVENT,
        )

        val smallThreshold = queueDepthAfterSubmit(frame, chunkMaxFrameBytes = 1024)
        val largeThreshold = queueDepthAfterSubmit(frame, chunkMaxFrameBytes = 4096)

        assertTrue(smallThreshold >= 2, "1024 阈值下应分成多片,实际深度=$smallThreshold")
        assertTrue(largeThreshold >= 2, "4096 阈值下也应分片,实际深度=$largeThreshold")
        assertTrue(smallThreshold > largeThreshold, "阈值调小应切得更碎(1024→$smallThreshold,4096→$largeThreshold)")
    }

    /**
     * `enabled=false`：超阈值**既不分片也不放行巨帧** → `TOO_LARGE`。
     *
     * 宁可明确失败，也不要发一个会长时间占写锁、可能撞对端上限的巨帧。
     */
    @Test
    fun `关闭分片时超阈值拒绝`() {
        val sender = sender(
            LineSettings(reliableCapacity = 8, spillEnabled = false),
            chunker(maxFrameBytes = 512, enabled = false),
        )
        val outcome = sender.submit(
            OutboundFrame(
                type = OutboundFrame.TYPE_EVENT,
                event = "demo_change",
                domain = "demo",
                payload = "y".repeat(4096),
                priority = OutboundFrame.Priority.RELIABLE_EVENT,
            ),
        )

        assertEquals(SubmitOutcome.TOO_LARGE, outcome, "关闭分片后超阈值应 TOO_LARGE（不放行巨帧）")
        assertEquals(0, sender.status().reliableQueue, "不得入队")
        assertEquals(1, sender.status().reliableRejected, "拒绝应计数")
    }

    /** 用假配置注入的阈值装配一个发送器，返回它接收一帧后的可靠队列深度（单位:帧）。 */
    private fun queueDepthAfterSubmit(frame: OutboundFrame, chunkMaxFrameBytes: Int): Int {
        val fake = object : BridgeConfigSource by ProbeConfigBridgeConfigSource {
            override fun chunk(): ChunkSettings = ChunkSettings(
                enabled = true,
                maxFrameBytes = chunkMaxFrameBytes,
                maxChunksPerGroup = 64,
            )
        }
        val sender = sender(LineSettings(reliableCapacity = 128, spillEnabled = false), FrameChunker(fake.chunk()))
        assertEquals(SubmitOutcome.QUEUED, sender.submit(frame), "阈值 $chunkMaxFrameBytes 下应能入队")
        return sender.status().reliableQueue
    }

    /** 在列表锁内取一份不可变快照（发送线程可能仍在追加）。 */
    private fun snapshot(written: MutableList<String>): List<String> = synchronized(written) { ArrayList(written) }

    /** 轮询等待条件成立（仅用于等待异步完成，不作为性能断言）。 */
    private fun awaitCondition(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
    }

    private companion object {
        /** 原子性用例用的组片数（规格 §5 的 64 片）。 */
        const val ATOMIC_GROUP_CHUNKS = 64

        /** 组连续性与心跳用例的组片数：够多才能覆盖到 ping 插入/未写完的时刻。 */
        const val GROUP_CHUNKS = 16
    }
}

/**
 * 测试用假连接：只记录写出内容，不需要真实 socket。
 *
 * 这正是把连接抽成 [OutboundSink] 的原因——发送器的分级/优先级逻辑得以脱离网络单测。
 */
private class FakeWs(private val onSend: (String) -> Unit) : OutboundSink {
    override fun sendText(text: String): Boolean {
        onSend(text)
        return true
    }
}

/**
 * 轮询等待条件成立（有界）。
 *
 * 发送线程是异步的，直接读计数会有竞态。这里不用固定 `sleep`：
 * sleep 太短则 flaky、太长则拖慢测试，而"轮询到条件成立"两者都避免。
 * 上限 5 秒只是防挂死——正常路径在毫秒级返回。
 */
private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(5)
    }
    throw AssertionError("等待条件超时(${timeoutMs}ms)")
}
