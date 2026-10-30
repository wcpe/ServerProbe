package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.util.concurrent.TimeUnit

/**
 * 停机与状态快照测试（线路治理）。
 *
 * 验证两条容易被忽略的运维性质：
 * - **停机不留线程**：池/发送器停止后不得有残留线程（否则插件热重载会不断累积）；
 * - **状态字段有真源**：`bridge_status` 的计数必须来自真实事件，不能是恒定占位。
 */
class BridgeShutdownAndStatusTest {

    /** 停机后不应残留 `serverprobe-*` 线程。 */
    @Test
    fun `停止后无残留工作线程`() {
        val settings = LineSettings()
        val pools = BridgePools(settings)
        val sender = FrameSender(settings, null)
        val before = probeThreads()

        // 触发一次执行,确保池线程被创建
        pools.submit(ExecutorKind.COMMAND) { }
        Thread { sender.runLoop() }.apply { isDaemon = true }.start()
        awaitCondition { probeThreads().isNotEmpty() }
        assertTrue(probeThreads().isNotEmpty(), "应已创建 serverprobe-* 线程(否则本测试无鉴别力)")

        sender.shutdown()
        pools.shutdownNow()
        awaitCondition { probeThreads().isEmpty() }

        val after = probeThreads()
        assertTrue(
            after.isEmpty(),
            "停机后不应残留工作线程,实际残留:${after.joinToString()}（停机前有 ${before.size} 个)）",
        )
    }

    /** 状态快照的计数来自真实事件。 */
    @Test
    fun `状态计数字段有真源`() {
        val settings = LineSettings(bestEffortCapacity = 1, reliableCapacity = 1, spillEnabled = false)
        val sender = FrameSender(settings, null)
        val pools = BridgePools(settings)
        val kernel = BridgeKernel(sender, pools, parse = { null }, encodeReceipt = { _, _ -> null })

        try {
            // 制造一次可丢丢弃 + 一次可靠拒绝
            repeat(3) { sender.submit(bestEffort(0)) }
            repeat(3) { sender.submit(reliable(0)) }
            // 制造一次解析失败
            kernel.onFrame("bad")
            // 制造一次入站超限
            kernel.onInboundTooLarge(payloadSkipped = true)

            val send = sender.status()
            val core = kernel.status()

            assertTrue(send.bestEffortDropped > 0, "可丢丢弃计数应 > 0,实际=${send.bestEffortDropped}")
            assertTrue(send.reliableRejected > 0, "可靠拒绝计数应 > 0,实际=${send.reliableRejected}")
            assertEquals(1, core.inboundFrames, "入站帧计数应来自真实帧")
            assertTrue(core.parseErrors > 0, "解析失败计数应 > 0")
            assertEquals(1, core.tooLarge, "超限计数应来自真实调用")
            assertEquals(0, send.commandResultRejected, "未发生回执拒绝时应为 0")
        } finally {
            sender.shutdown()
            pools.shutdownNow()
        }
    }

    /** 三个池的线程名前缀互不重叠(隔离可被观测)。 */
    @Test
    fun `三池线程名可区分`() {
        val settings = LineSettings(cmdPoolSize = 1, eventPoolSize = 1, reloadPoolSize = 1)
        val pools = BridgePools(settings)
        try {
            pools.submit(ExecutorKind.COMMAND) { Thread.sleep(300) }
            pools.submit(ExecutorKind.EVENT) { Thread.sleep(300) }
            pools.submit(ExecutorKind.RELOAD) { Thread.sleep(300) }
            awaitCondition {
                probeThreads().count { it.startsWith(BridgePools.THREAD_CMD) } >= 1 &&
                    probeThreads().count { it.startsWith(BridgePools.THREAD_EVENT) } >= 1 &&
                    probeThreads().count { it.startsWith(BridgePools.THREAD_RELOAD) } >= 1
            }
            val threads = probeThreads()
            assertEquals(1, threads.count { it.startsWith(BridgePools.THREAD_CMD) }, "命令池线程前缀")
            assertEquals(1, threads.count { it.startsWith(BridgePools.THREAD_EVENT) }, "事件池线程前缀")
            assertEquals(1, threads.count { it.startsWith(BridgePools.THREAD_RELOAD) }, "重载池线程前缀")
        } finally {
            pools.shutdownNow()
        }
    }

    private fun bestEffort(index: Int) = OutboundFrame(
        type = OutboundFrame.TYPE_EVENT,
        event = "demo_change",
        domain = "demo",
        payload = "e-$index",
        priority = OutboundFrame.Priority.BEST_EFFORT_EVENT,
    )

    private fun reliable(index: Int) = OutboundFrame(
        type = OutboundFrame.TYPE_EVENT,
        event = "demo_change",
        domain = "demo",
        payload = "r-$index",
        priority = OutboundFrame.Priority.RELIABLE_EVENT,
    )

    /** 当前存活的 serverprobe-* 线程名。 */
    private fun probeThreads(): List<String> = Thread.getAllStackTraces().keys
        .filter { it.isAlive }
        .map { it.name }
        .filter { it.startsWith(THREAD_PREFIX) }

    private fun awaitCondition(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    private companion object {
        /** 桥工作线程统一前缀。 */
        const val THREAD_PREFIX = "serverprobe-"
    }
}
