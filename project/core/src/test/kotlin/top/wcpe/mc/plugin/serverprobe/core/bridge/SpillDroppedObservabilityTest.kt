package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.io.File

/**
 * 「回灌时静默丢数据必须可观测」的护栏。
 *
 * ## 被钉住的事实
 * `EventSpillStore.append` **恒返回 true**：落盘内存缓冲满时它会丢弃**最旧**的一条并计数，
 * 而不是拒绝新的。于是调用方 `emitReliable` 收到的是「已受理」，
 * 而实际有一条更早的事件被丢了。若不把这个计数暴露到 `bridge_status`，
 * 大批量回灌（一次查询捞出数万条历史账单）造成的数据缺口在运维侧完全不可见，
 * 只能等对端条数对不上才发现。
 */
class SpillDroppedObservabilityTest {

    /** 落盘缓冲溢出必须计入 `spillDropped`，且**不**混进 `reliableRejected`。 */
    @Test
    fun `落盘缓冲溢出的丢弃计入spillDropped而不混入reliableRejected`(@TempDir dir: File) {
        val settings = LineSettings(
            reliableCapacity = 1,
            spillEnabled = true,
            spillDir = dir.absolutePath,
        )
        val store = EventSpillStore(settings)
        store.load()
        val sender = FrameSender(settings, store, FrameChunker(ChunkSettings()))
        try {
            // 不 attach 连接：发送线程不会消费，帧会一路「队列满 → 落盘 → 落盘缓冲也满」。
            // 多造 32 条越过落盘缓冲上限（MAX_PENDING = 4096）。
            val overflow = 32
            val total = MAX_PENDING_FOR_TEST + overflow
            repeat(total) { index ->
                sender.submit(
                    OutboundFrame(
                        type = OutboundFrame.TYPE_EVENT,
                        event = "bill_change",
                        domain = "economy",
                        payload = """{"ledgerId":"L$index"}""",
                        priority = OutboundFrame.Priority.RELIABLE_EVENT,
                    ),
                )
            }

            val status = sender.status()
            // 精确值取决于"多少条还能留在队列里"（reliableCapacity=1 → 恰有 1 条不进落盘），
            // 因此断言"绝大多数溢出都被记录"而不是钉死 32：本用例要证明的是**溢出可观测**，
            // 不是队列与落盘之间的分摊比例（那由别的用例负责）。
            assertTrue(
                status.spillDropped >= overflow - 1L,
                "落盘缓冲溢出应计入 spillDropped，实际=${status.spillDropped}（应至少 ${overflow - 1}）",
            )
            assertEquals(
                0,
                status.reliableRejected,
                "溢出发生在已受理之后，不应算成「拒绝受理」（否则调用方会重复回灌直接丢的那批）",
            )
        } finally {
            sender.shutdown()
            runCatching { store.shutdown() }
        }
    }

    /** 未启用落盘时该计数恒为 0（不能出现「没有落盘却报丢弃」的假信号）。 */
    @Test
    fun `未启用落盘时spillDropped恒为零`() {
        val settings = LineSettings(reliableCapacity = 1, spillEnabled = false)
        val sender = FrameSender(settings, null, FrameChunker(ChunkSettings()))
        try {
            repeat(16) {
                sender.submit(
                    OutboundFrame(
                        type = OutboundFrame.TYPE_EVENT,
                        event = "bill_change",
                        domain = "economy",
                        payload = """{"ledgerId":"L$it"}""",
                        priority = OutboundFrame.Priority.RELIABLE_EVENT,
                    ),
                )
            }
            assertEquals(0, sender.status().spillDropped, "无落盘时不应报丢弃")
        } finally {
            sender.shutdown()
        }
    }

    private companion object {
        /** 与 `EventSpillStore.MAX_PENDING` 对齐（该常量是私有的，这里复述其值并注释来源）。 */
        const val MAX_PENDING_FOR_TEST = 4_096
    }
}
