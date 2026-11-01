package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.BridgeTarget
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration

/**
 * 桥客户端启动期的两个行为契约。
 *
 * 1. **启用线程不阻塞**：`@PostEnable` 跑在主线程，旧实现里 `doStart()` 会同步等模式判定（上限 5s），
 *    失败回滚再叠 4s —— 直接违反“主线程禁止阻塞 IO/长等待”的红线。
 * 2. **托管目标能自愈**：旧实现只算一次，判定超时拿到 null 就“定论”下去；托管部署里 `bridge.*`
 *    已被自动迁移清空，于是整场会话不连控制面且无重试入口。
 */
class BridgeClientStartupTest {

    @BeforeEach
    fun setUp() {
        ProbeConfig.conf = testConfiguration(mapOf("bridge.enabled" to false))
        ControlPlaneRuntime.reset()
    }

    @AfterEach
    fun tearDown() {
        ControlPlaneRuntime.reset()
    }

    /** 未注入判定服务时（最坏情况：永远拿不到结论），`start()` 也必须立刻返回。 */
    @Test
    fun `start 不在启用线程等模式判定`() {
        val client = BridgeClient()

        val startedAt = System.nanoTime()
        client.start()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            elapsedMs < FAST_RETURN_MS,
            "start() 必须立即返回（实际 ${elapsedMs}ms），模式判定与装配都在引导线程上",
        )
    }

    /** 判定晚于启动落地时，后续取目标必须能补上（而不是把 null 定论到底）。 */
    @Test
    fun `托管目标晚到时能自愈`() {
        val client = BridgeClient()
        client.managedTargetProvider = { null }
        assertNull(client.currentManagedTarget(), "判定未落地时不得凭空造出托管目标")

        val target = BridgeTarget("ws://127.0.0.1:9/ws", "opaque-credential", "instance-heal", "e2e")
        client.managedTargetProvider = { target }

        assertEquals("instance-heal", client.currentManagedTarget()?.instanceId, "判定落地后应能补取到目标")
        assertEquals(
            "ws://127.0.0.1:9/ws",
            client.currentManagedTarget()?.endpoint,
            "补取到的目标应被缓存且保持稳定",
        )
    }

    /**
     * 停机先到：`stop()` 不得因“running 还没置 true”就早早退场。
     *
     * 装配已下移到引导线程，旧实现在“启用后立刻停机”时会让 stop 直接返回，而引导线程醒来后
     * 照样建池起线程——停机之后仍有四条线程在跑，序号落盘/落盘 flush 全被跳过。
     */
    @Test
    fun `停机先到时不残留桥线程`() {
        val client = BridgeClient()

        client.start()
        client.stop()

        val deadline = System.nanoTime() + STOP_SETTLE_MS * 1_000_000
        while (System.nanoTime() < deadline && bridgeThreadAlive()) {
            Thread.sleep(SETTLE_POLL_MS)
        }

        assertFalse(bridgeThreadAlive(), "停机后不得残留 ServerProbe-Bridge 线程（否则序号/落盘收尾全被跳过）")
    }

    /** 当前是否有存活的桥引导/工作线程。 */
    private fun bridgeThreadAlive(): Boolean = Thread.getAllStackTraces().keys.any { worker ->
        worker.isAlive && worker.name.startsWith(BRIDGE_THREAD_PREFIX)
    }

    private companion object {
        /** `start()` 的返回上限：留足线程创建开销，但远小于旧实现的 5s 判定等待。 */
        private const val FAST_RETURN_MS = 500L

        /** 等引导线程收手的上限（毫秒）。 */
        private const val STOP_SETTLE_MS = 2_000L

        /** 等待轮询间隔（毫秒）。 */
        private const val SETTLE_POLL_MS = 20L

        /** 桥线程名前缀（引导线程与工作线程共用）。 */
        private const val BRIDGE_THREAD_PREFIX = "ServerProbe-Bridge"
    }
}
