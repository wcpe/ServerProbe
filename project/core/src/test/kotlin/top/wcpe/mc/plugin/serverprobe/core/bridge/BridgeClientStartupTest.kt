package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

    private companion object {
        /** `start()` 的返回上限：留足线程创建开销，但远小于旧实现的 5s 判定等待。 */
        private const val FAST_RETURN_MS = 500L
    }
}
