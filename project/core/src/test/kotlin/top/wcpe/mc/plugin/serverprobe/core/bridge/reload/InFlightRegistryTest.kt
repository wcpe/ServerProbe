package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [InFlightRegistry] 单元测试（配置重载规格 §3.5 + §5「在途」「卡死」「全量互斥」三行）。
 *
 * 时间全部由用例注入（[tryAcquire] 的 `nowMs`），因此卡死阈值是**确定性断言**，
 * 不靠 sleep 制造时序。
 */
class InFlightRegistryTest {

    /** 首次占用成功，重复占用返回 IN_FLIGHT。 */
    @Test
    fun `同路径第二次占用返回在途`() {
        val registry = InFlightRegistry(stuckAfterMs = 30_000L)

        assertEquals(AcquireResult.ACQUIRED, registry.tryAcquire(PATH, 1_000L))
        assertEquals(AcquireResult.IN_FLIGHT, registry.tryAcquire(PATH, 1_001L))
    }

    /** release 后可再次占用（幂等：重复 release 无副作用）。 */
    @Test
    fun `释放后可再次占用且释放幂等`() {
        val registry = InFlightRegistry(stuckAfterMs = 30_000L)
        registry.tryAcquire(PATH, 1_000L)

        registry.release(PATH)
        registry.release(PATH)

        assertEquals(AcquireResult.ACQUIRED, registry.tryAcquire(PATH, 2_000L))
    }

    /** 不同路径互不阻塞。 */
    @Test
    fun `不同路径互不阻塞`() {
        val registry = InFlightRegistry(stuckAfterMs = 30_000L)

        assertEquals(AcquireResult.ACQUIRED, registry.tryAcquire("plugins/A/config.yml", 1_000L))
        assertEquals(AcquireResult.ACQUIRED, registry.tryAcquire("plugins/B/config.yml", 1_000L))
    }

    /** 验收「卡死」：超过 stuck-after-ms 后返回 STUCK（不是 IN_FLIGHT）。 */
    @Test
    fun `超过卡死阈值返回STUCK`() {
        val registry = InFlightRegistry(stuckAfterMs = 1_000L)
        registry.tryAcquire(PATH, 10_000L)

        // 恰好等于阈值：仍算在途（边界是"超过"）。
        assertEquals(AcquireResult.IN_FLIGHT, registry.tryAcquire(PATH, 11_000L))
        assertEquals(AcquireResult.STUCK, registry.tryAcquire(PATH, 11_001L))
    }

    /** 快照带上已占用时长与卡死标记。 */
    @Test
    fun `快照带占用时长与卡死标记`() {
        val registry = InFlightRegistry(stuckAfterMs = 1_000L)
        registry.tryAcquire("plugins/B/config.yml", 1_000L)
        registry.tryAcquire("plugins/A/config.yml", 1_500L)

        val snapshot = registry.snapshot(2_000L)

        assertEquals(listOf("plugins/A/config.yml", "plugins/B/config.yml"), snapshot.map { it.path }, "按路径字典序")
        assertEquals(500L, snapshot[0].heldMs)
        assertEquals(1_000L, snapshot[1].heldMs)
        assertTrue(snapshot.none { it.stuck }, "都没超过阈值")
    }

    // ---------------------------------------------------------------- 全量哨兵互斥

    /** 验收「全量互斥」：全量在途时，任何单路径请求 → IN_FLIGHT。 */
    @Test
    fun `全量在途时单路径返回在途`() {
        val registry = InFlightRegistry(stuckAfterMs = 30_000L)
        assertEquals(AcquireResult.ACQUIRED, registry.tryAcquire(InFlightRegistry.FULL_RELOAD_KEY, 1_000L))

        assertEquals(AcquireResult.IN_FLIGHT, registry.tryAcquire(PATH, 1_001L))
        val blocker = registry.blockerOf(PATH, 1_001L)
        assertTrue(blocker != null && blocker.isFullReload(), "挡住单路径的应是全量哨兵")
    }

    /** 验收「全量互斥」：任何单路径在途时，全量请求 → IN_FLIGHT。 */
    @Test
    fun `单路径在途时全量返回在途`() {
        val registry = InFlightRegistry(stuckAfterMs = 30_000L)
        registry.tryAcquire(PATH, 1_000L)

        assertEquals(AcquireResult.IN_FLIGHT, registry.tryAcquire(InFlightRegistry.FULL_RELOAD_KEY, 1_001L))
        val blocker = registry.blockerOf(InFlightRegistry.FULL_RELOAD_KEY, 1_001L)
        assertEquals(PATH, blocker?.path, "挡住全量的应是那条单路径")
    }

    /** 全量自己重复占用也返回 IN_FLIGHT（不自我放行）。 */
    @Test
    fun `全量重复占用返回在途`() {
        val registry = InFlightRegistry(stuckAfterMs = 30_000L)
        registry.tryAcquire(InFlightRegistry.FULL_RELOAD_KEY, 1_000L)

        assertEquals(AcquireResult.IN_FLIGHT, registry.tryAcquire(InFlightRegistry.FULL_RELOAD_KEY, 1_001L))
    }

    /** 全量在途且已超阈值 → 单路径也得 STUCK（卡死不能靠全量掩盖）。 */
    @Test
    fun `全量卡死时单路径返回STUCK`() {
        val registry = InFlightRegistry(stuckAfterMs = 1_000L)
        registry.tryAcquire(InFlightRegistry.FULL_RELOAD_KEY, 10_000L)

        assertEquals(AcquireResult.STUCK, registry.tryAcquire(PATH, 20_000L))
    }

    /** 全部释放后无人挡路。 */
    @Test
    fun `释放全部后无阻塞`() {
        val registry = InFlightRegistry(stuckAfterMs = 30_000L)
        registry.tryAcquire(PATH, 1_000L)
        registry.release(PATH)

        assertNull(registry.blockerOf(PATH, 1_001L))
        assertTrue(registry.snapshot(1_001L).isEmpty())
    }

    private companion object {
        const val PATH = "plugins/Foo/config.yml"
    }
}
