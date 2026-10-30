package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.frameWithChunkCount
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BridgeKernel] 单元测试（线路治理）。
 *
 * 覆盖最核心的不变量：**读线程绝不阻塞、绝不抛**。
 * 全部用 latch 断言而非墙钟阈值——墙钟断言在 CI 上会 flaky，且不能证明"谁没等谁"。
 *
 * 解析器注入为纯逻辑实现：默认解析器依赖运行期 nightconfig，而它在 core 的裸单测类路径中不存在
 * （见 `JsonFacadeTest` 的既有说明）。被测对象是**调度逻辑**，不是 JSON 库。
 */
class BridgeKernelTest {

    /**
     * 测试用解析器：把 `action|domain|requestId` 形式的明文当命令，
     * 避免引入 JSON 依赖，同时保留字段语义。
     */
    private val textParser: (String) -> BridgeCommand? = { raw ->
        val parts = raw.split('|')
        when {
            parts.size >= 3 -> BridgeCommand(
                action = parts[0],
                target = "",
                reason = "",
                requestId = parts[2],
                domain = parts[1],
                payload = "",
            )
            parts.size == 2 -> BridgeCommand(parts[0], "", "", "req-1", parts[1], "")
            parts.size == 1 && parts[0].isNotEmpty() -> BridgeCommand(parts[0], "", "", "req-1", "", "")
            else -> null
        }
    }

    /** 构造一条命令帧(纯文本形式,由 [textParser] 解析)。 */
    private fun commandJson(action: String, domain: String = "demo", requestId: String = "req-1"): String =
        "$action|$domain|$requestId"

    /** 构造测试用内核。 */
    private fun newKernel(
        settings: top.wcpe.mc.plugin.serverprobe.core.config.LineSettings =
            top.wcpe.mc.plugin.serverprobe.core.config.LineSettings(),
        chunker: FrameChunker = FrameChunker(ChunkSettings()),
    ): Triple<BridgeKernel, FrameSender, BridgePools> {
        val pools = BridgePools(settings)
        val sender = FrameSender(settings, null, chunker)
        return Triple(BridgeKernel(sender, pools, textParser, ::encodeReceipt), sender, pools)
    }

    /** 测试用回执编码器：不依赖 JSON 库，只拼出可断言的明文。 */
    private fun encodeReceipt(requestId: String, result: BridgeCommandResult): String =
        "receipt|$requestId|${result.success}|${result.output}|${result.error}"

    /** 可控制阻塞时机的路由。 */
    private class BlockingRoute(
        override val order: Int = 500,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : InboundRoute {
        override fun claims(command: BridgeCommand): Boolean = true

        override fun handle(command: BridgeCommand): BridgeCommandResult {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return BridgeCommandResult.ok("done")
        }
    }

    /**
     * 读线程不被 Provider 阻塞：这是本次重构的核心目标。
     *
     * 断言方式：让路由的 handle 卡在 latch A 上，然后**在 A 仍未释放时**投递第二条命令。
     * 若内核在读线程同步等待 handle，第二条命令永远进不来（onFrame 不返回），
     * 测试会超时失败；若内核只投递不等待，onFrame 立即返回、第二条命令可正常进入。
     */
    @Test
    fun `onFrame 不等待路由执行`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (kernel, sender, pools) = newKernel()
        kernel.addRoute(BlockingRoute(entered = entered, release = release))

        try {
            // 第一条:进入 handle 并卡住
            kernel.onFrame(commandJson("slow", "demo", "req-1"))
            assertTrue(entered.await(2, TimeUnit.SECONDS), "路由应已开始执行")

            // 第二条:必须在第一条仍卡住时就能被处理完毕(即 onFrame 立即返回)
            val secondCallReturned = CountDownLatch(1)
            Thread {
                kernel.onFrame(commandJson("fast", "demo", "req-2"))
                secondCallReturned.countDown()
            }.start()
            assertTrue(
                secondCallReturned.await(2, TimeUnit.SECONDS),
                "onFrame 不得等待前一条命令的执行(否则第二条永远进不来)",
            )
            assertEquals(1, release.count, "第一条此时仍应卡住——证明第二条不是靠它结束才通过的")
        } finally {
            release.countDown()
            pools.shutdownNow()
            sender.shutdown()
        }
    }

    /** 池饱和时就地回失败回执,不抛异常、不打断读循环。 */
    @Test
    fun `池饱和时就地降级且不抛`() {
        val (kernel, sender, pools) =
            newKernel(top.wcpe.mc.plugin.serverprobe.core.config.LineSettings(cmdPoolSize = 1, cmdQueueCapacity = 1))
        val blocked = CountDownLatch(1)
        kernel.addRoute(BlockingRoute(entered = CountDownLatch(1), release = blocked))

        try {
            // 远超池容量的命令:全部应被受理(要么执行、要么就地降级),绝不抛
            repeat(20) { index ->
                kernel.onFrame(commandJson("cmd-$index", "demo", "req-$index"))
            }
            val status = kernel.status()
            assertEquals(20, status.inboundFrames, "所有帧都应被计入")
            assertEquals(0, status.parseErrors, "饱和不是解析错误")
            assertTrue(status.saturated > 0, "应有命令因池饱和被就地降级(实际=${status.saturated})")
        } finally {
            blocked.countDown()
            pools.shutdownNow()
            sender.shutdown()
        }
    }

    /** 无路由命中时也应返回失败回执,而不是静默丢弃。 */
    @Test
    fun `无路由命中回失败回执`() {
        val (kernel, sender, pools) = newKernel()
        kernel.addRoute(object : InboundRoute {
            override val order: Int = 100
            override fun claims(command: BridgeCommand): Boolean = false
            override fun handle(command: BridgeCommand): BridgeCommandResult = BridgeCommandResult.ok()
        })

        kernel.onFrame(commandJson("unknown", "demo", "req-x"))
        val status = kernel.status()
        assertEquals(1, status.inboundFrames)
        assertEquals(0, status.parseErrors, "无命中不算解析错误")
        pools.shutdownNow()
        sender.shutdown()
    }

    /** 畸形帧不得抛异常、不得中断内核。 */
    @Test
    fun `畸形帧被忽略且不抛`() {
        val (kernel, sender, pools) = newKernel()

        kernel.onFrame("not-json")
        kernel.onFrame("""{"type":"command"}""")
        kernel.onFrame("")

        val status = kernel.status()
        assertEquals(3, status.inboundFrames)
        assertTrue(status.parseErrors >= 1, "非 JSON 帧应计入解析错误")
        pools.shutdownNow()
        sender.shutdown()
    }

    /** 路由按 order 升序匹配:低序号先命中并短路。 */
    @Test
    fun `路由按 order 匹配`() {
        val (kernel, sender, pools) = newKernel()
        val firstHits = AtomicInteger(0)
        val secondHits = AtomicInteger(0)

        kernel.addRoute(object : InboundRoute {
            override val order: Int = 100
            override fun claims(command: BridgeCommand): Boolean = true
            override fun handle(command: BridgeCommand): BridgeCommandResult {
                firstHits.incrementAndGet()
                return BridgeCommandResult.ok("first")
            }
        })
        kernel.addRoute(object : InboundRoute {
            override val order: Int = 500
            override fun claims(command: BridgeCommand): Boolean = true
            override fun handle(command: BridgeCommand): BridgeCommandResult {
                secondHits.incrementAndGet()
                return BridgeCommandResult.ok("second")
            }
        })

        kernel.onFrame(commandJson("any", "demo"))
        // 等池执行
        awaitCondition { firstHits.get() == 1 || secondHits.get() == 1 }
        assertEquals(1, firstHits.get(), "应先命中 order 更小的路由")
        assertEquals(0, secondHits.get(), "命中后应短路后续路由")
        pools.shutdownNow()
        sender.shutdown()
    }

    /**
     * `TOO_LARGE` 回执链路：命令池用**原命令的 requestId** 重写一条失败回执，且不递归。
     *
     * 切分发生在发送器内、那里拿不到 `BridgeCommandResult`，只能如实返回 `TOO_LARGE`；
     * 能拿到 `requestId` 的层是内核，故由这里重写成 `fail("回执过大无法分片传输")` 再送一次。
     * 该失败回执只有几十字节，因此**不会再触发分片**——这正是"不递归"的根据。
     */
    @Test
    fun `回执超大时用requestId重写失败回执`() {
        // 单组上限压到 1：任何需要 ≥2 片的回执都无法分片 → TOO_LARGE。
        val chunker = FrameChunker(ChunkSettings(enabled = true, maxFrameBytes = 512, maxChunksPerGroup = 1))
        val (kernel, sender, pools) = newKernel(LineSettings(receiptOfferBudgetMs = 0), chunker)
        val written = Collections.synchronizedList(ArrayList<String>())
        sender.attach(RecordingSink(written))
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()
        kernel.addRoute(bigResultRoute(BIG_OUTPUT_CHARS))

        try {
            kernel.onFrame(commandJson("big", "demo", "req-big"))
            awaitCondition { written.isNotEmpty() }

            assertEquals(1, written.size, "只应写出一条回执（重写的那条），不得递归产生更多")
            assertTrue(written[0].contains("req-big"), "失败回执应带原命令的 requestId，实际=${written[0]}")
            assertTrue(written[0].contains(TOO_LARGE_REASON), "应回写明确的超限原因，实际=${written[0]}")
            assertTrue(
                written[0].toByteArray(Charsets.UTF_8).size < 512,
                "失败回执应远小于阈值（否则会再次分片而递归），实际 ${written[0].length} 字符",
            )
            assertEquals(1, sender.status().commandResultRejected, "超限并入既有回执拒绝计数")
        } finally {
            pools.shutdownNow()
            sender.shutdown()
        }
    }

    /**
     * `REJECTED` 回执链路：通道饱和时用 `requestId` 重写成 `fail("回执通道饱和")` 并送出。
     *
     * 这里让大回执需要约十几片、而回执队列只有 2 帧容量 → 整组预留失败 → `REJECTED`；
     * 重写的失败回执只有 1 片，能挤进容量，因此**这一次能被端上看到**。
     */
    @Test
    fun `回执通道饱和时用requestId重写失败回执`() {
        val chunker = FrameChunker(ChunkSettings(enabled = true, maxFrameBytes = 512, maxChunksPerGroup = 64))
        val (kernel, sender, pools) = newKernel(
            LineSettings(receiptCapacity = 2, receiptOfferBudgetMs = 0),
            chunker,
        )
        val written = Collections.synchronizedList(ArrayList<String>())
        sender.attach(RecordingSink(written))
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()
        kernel.addRoute(bigResultRoute(BIG_OUTPUT_CHARS))

        try {
            kernel.onFrame(commandJson("big", "demo", "req-sat"))
            awaitCondition { written.isNotEmpty() }

            assertEquals(1, written.size, "原大回执整组放弃，只应有重写的失败回执被写出")
            assertTrue(written[0].contains("req-sat"), "失败回执应带原 requestId，实际=${written[0]}")
            assertTrue(written[0].contains(SATURATED_REASON), "应回写饱和原因，实际=${written[0]}")
            assertEquals(1, sender.status().commandResultRejected, "饱和并入既有回执拒绝计数")
        } finally {
            pools.shutdownNow()
            sender.shutdown()
        }
    }

    /**
     * 失败回执自身再被拒：只计数 + WARN，**绝不重试**（否则一次饱和会变成无限循环）。
     *
     * 断言计数正好 +2（原回执 1 次 + 重写的失败回执 1 次）：若是 3 或更多，
     * 就说明"为失败回执再生一条失败回执"的递归发生了。
     */
    @Test
    fun `失败回执再被拒也只计数一次`() {
        val chunker = FrameChunker(ChunkSettings(enabled = true, maxFrameBytes = 512, maxChunksPerGroup = 1))
        val (kernel, sender, pools) = newKernel(LineSettings(receiptCapacity = 1, receiptOfferBudgetMs = 0), chunker)
        // 先把唯一的容量占掉，使"重写的失败回执"也无处可去。
        sender.offerReceipt(
            OutboundFrame(
                type = OutboundFrame.TYPE_EVENT,
                event = OutboundFrame.TYPE_COMMAND_RESULT,
                payload = "occupied",
                priority = OutboundFrame.Priority.COMMAND_RESULT,
                requestId = "req-other",
            ),
        )
        assertEquals(1, sender.status().commandResultQueue, "前提:回执队列已无空位")
        kernel.addRoute(bigResultRoute(BIG_OUTPUT_CHARS))

        try {
            kernel.onFrame(commandJson("big", "demo", "req-full"))
            awaitCondition { sender.status().commandResultRejected >= 2 }

            assertEquals(2, sender.status().commandResultRejected, "应正好两次拒绝（原回执 + 重写的失败回执），不得递归")
        } finally {
            pools.shutdownNow()
            sender.shutdown()
        }
    }

    /** 没有 `requestId` 可回写（纯事件路径）：只计数 + WARN 一次，不重试。 */
    @Test
    fun `无requestId时只计数不回写`() {
        val chunker = FrameChunker(ChunkSettings(enabled = true, maxFrameBytes = 512, maxChunksPerGroup = 1))
        val (kernel, sender, pools) = newKernel(LineSettings(receiptOfferBudgetMs = 0), chunker)
        val written = Collections.synchronizedList(ArrayList<String>())
        sender.attach(RecordingSink(written))
        kernel.addRoute(bigResultRoute(BIG_OUTPUT_CHARS))

        try {
            // requestId 为空串：没有可回写的关联标识。
            kernel.onFrame(commandJson("big", "demo", ""))
            awaitCondition { sender.status().commandResultRejected >= 1 }

            assertEquals(1, sender.status().commandResultRejected, "只应计一次（不重试、不生成失败回执）")
            assertEquals(0, written.size, "没有 requestId 时不得凭空写一条失败回执")
        } finally {
            pools.shutdownNow()
            sender.shutdown()
        }
    }

    /** 返回超大成功结果的测试路由。 */
    private fun bigResultRoute(outputChars: Int): InboundRoute = object : InboundRoute {
        override val order: Int = 500
        override fun claims(command: BridgeCommand): Boolean = true
        override fun handle(command: BridgeCommand): BridgeCommandResult =
            BridgeCommandResult.ok("x".repeat(outputChars))
    }

    /** 轮询等待条件成立(仅用于等待异步执行完成,不作为性能断言)。 */
    private fun awaitCondition(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private companion object {
        /** 回写原因常量（与 `BridgeKernel` 的内部常量同值；测试用字面量断言，避免为测试放宽可见性）。 */
        const val TOO_LARGE_REASON = "回执过大无法分片传输"

        /** 同上。 */
        const val SATURATED_REASON = "回执通道饱和"

        /** 构造"超大回执"用的输出字符数：远超 512 字节阈值，必然需要多片。 */
        const val BIG_OUTPUT_CHARS = 4096
    }
}

/**
 * 只记录写出内容的假连接（内核侧用例用它断言"端上到底收到了什么"）。
 */
private class RecordingSink(private val written: MutableList<String>) : OutboundSink {
    override fun sendText(text: String): Boolean {
        written.add(text)
        return true
    }
}

