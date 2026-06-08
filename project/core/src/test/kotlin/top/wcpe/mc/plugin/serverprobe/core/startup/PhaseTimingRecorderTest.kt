package top.wcpe.mc.plugin.serverprobe.core.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.StartupPhase

/**
 * [PhaseTimingRecorder.computeTimings] 单元测试。
 *
 * 仅针对抽出的纯函数(喂 `Map<StartupPhase, Long>` 出 `List<PhaseTiming>`)验证累计算法,
 * 不触发 TabooLib 生命周期打点,可直接喂样本映射断言。
 * 覆盖:相对最早记录点的累计语义、按 ordinal 升序、缺段(只到部分阶段)、空映射、最早段恒为 0。
 */
class PhaseTimingRecorderTest {

    /** 一毫秒的纳秒数(构造样本用)。 */
    private val nanosPerMilli = 1_000_000L

    /**
     * 完整五段:各阶段相对最早记录点(CONST)的累计耗时,且按 ordinal 升序。
     * CONST=0ms、INIT=10ms、LOAD=30ms、ENABLE=60ms、ACTIVE=100ms。
     */
    @Test
    fun `完整分段按最早记录点累计且升序`() {
        val base = 1_000_000_000L
        val timings = PhaseTimingRecorder.computeTimings(
            mapOf(
                StartupPhase.CONST to base,
                StartupPhase.INIT to base + 10 * nanosPerMilli,
                StartupPhase.LOAD to base + 30 * nanosPerMilli,
                StartupPhase.ENABLE to base + 60 * nanosPerMilli,
                StartupPhase.ACTIVE to base + 100 * nanosPerMilli
            )
        )
        assertEquals(
            listOf(
                StartupPhase.CONST to 0L,
                StartupPhase.INIT to 10L,
                StartupPhase.LOAD to 30L,
                StartupPhase.ENABLE to 60L,
                StartupPhase.ACTIVE to 100L
            ),
            timings.map { it.phase to it.durationMs },
            "应为相对 CONST 的累计耗时且按 ordinal 升序"
        )
    }

    /**
     * 仅到部分阶段(尚未 ACTIVE):只产出已存在的阶段,最早段(CONST)恒为 0。
     */
    @Test
    fun `缺段时仅产出已打点阶段`() {
        val base = 500_000_000L
        val timings = PhaseTimingRecorder.computeTimings(
            mapOf(
                StartupPhase.CONST to base,
                StartupPhase.INIT to base + 5 * nanosPerMilli,
                StartupPhase.LOAD to base + 20 * nanosPerMilli
            )
        )
        assertEquals(
            listOf(
                StartupPhase.CONST to 0L,
                StartupPhase.INIT to 5L,
                StartupPhase.LOAD to 20L
            ),
            timings.map { it.phase to it.durationMs },
            "缺 ENABLE/ACTIVE 时只产出 CONST/INIT/LOAD"
        )
    }

    /**
     * 基准取映射中的最小 nanoTime:即便传入顺序乱序、且最早阶段非 CONST,最早记录点仍恒为 0。
     */
    @Test
    fun `基准取最早记录点而非固定 CONST`() {
        val base = 2_000_000_000L
        // 故意只给 INIT 与 ENABLE,且乱序传入;最早记录点为 INIT
        val timings = PhaseTimingRecorder.computeTimings(
            mapOf(
                StartupPhase.ENABLE to base + 40 * nanosPerMilli,
                StartupPhase.INIT to base
            )
        )
        assertEquals(
            listOf(
                StartupPhase.INIT to 0L,
                StartupPhase.ENABLE to 40L
            ),
            timings.map { it.phase to it.durationMs },
            "最早记录点(此处为 INIT)应为 0,且输出按 ordinal 升序"
        )
    }

    /** 空映射:返回空列表。 */
    @Test
    fun `空映射返回空`() {
        assertTrue(PhaseTimingRecorder.computeTimings(emptyMap()).isEmpty(), "空映射应返回空列表")
    }
}
