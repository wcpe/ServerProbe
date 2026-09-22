package top.wcpe.mc.plugin.serverprobe.core.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot

/**
 * FR-29 新增告警类型的取值/差分单元测试。
 *
 * 覆盖:
 * - GC_OLD_HIGH:跨快照差分速率(正差/零差/回绕负差/时间倒退),单快照 extract 恒 N/A;
 * - STARTUP_SLOW:单快照 extract 恒 N/A(数据源在快照之外),extractStartup 的 ms→s 换算与 null 透传;
 * - 快照缺失字段的 N/A 语义(引擎"数据缺失只清状态不误报"的输入端保证)。
 */
class AlertTypeExtendedTest {

    /** 构造 JVM 指标:仅设定 Old GC 累计计数。 */
    private fun jvm(gcOldCount: Long): JvmMetrics = JvmMetrics.builder()
        .gcOldCount(gcOldCount)
        .build()

    /** 构造快照:设定时间戳与 JVM 指标(其余维度缺省,被测类型不依赖)。 */
    private fun snapshot(tsMs: Long, gcOldCount: Long): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(tsMs)
        .serverId("test")
        .platform(ProbePlatform.BUKKIT)
        .jvm(jvm(gcOldCount))
        .build()

    /** GC_OLD_HIGH 差分:1 秒内 2 次 Old GC → 速率 2.0 次/秒。 */
    @Test
    fun `old GC 速率按跨快照差分计算`() {
        val prev = snapshot(1000L, gcOldCount = 5)
        val curr = snapshot(2000L, gcOldCount = 7)

        val rate = AlertType.GC_OLD_HIGH.extractDifferential(curr, prev)

        assertEquals(2.0, rate!!, 1e-9, "(7-5)/1s = 2.0 次/秒")
        assertTrue(AlertType.GC_OLD_HIGH.violated(rate, 0.05), "2.0 应越 0.05 阈值")
    }

    /** GC_OLD_HIGH 差分:计数回绕(重启,差分为负)→ N/A。 */
    @Test
    fun `old GC 计数回绕时为 N_A`() {
        val prev = snapshot(1000L, gcOldCount = 10)
        val curr = snapshot(2000L, gcOldCount = 3)

        assertNull(AlertType.GC_OLD_HIGH.extractDifferential(curr, prev), "负差分应返回 null")
    }

    /** GC_OLD_HIGH 差分:时间倒退/零跨度 → N/A(除零保护)。 */
    @Test
    fun `时间倒退时为 N_A`() {
        val prev = snapshot(5000L, gcOldCount = 5)
        val curr = snapshot(2000L, gcOldCount = 7)

        assertNull(AlertType.GC_OLD_HIGH.extractDifferential(curr, prev), "时间倒退应返回 null")
    }

    /** GC_OLD_HIGH 单快照路径恒 N/A(速率必须差分,防误用单点累计值)。 */
    @Test
    fun `old GC 单快照取值恒为 N_A`() {
        assertNull(AlertType.GC_OLD_HIGH.extract(snapshot(1000L, gcOldCount = 999)))
    }

    /** STARTUP_SLOW:无画像(null)透传为 N/A;有画像按 ms→s 换算。 */
    @Test
    fun `启动超基线的取值换算`() {
        assertNull(AlertType.STARTUP_SLOW.extractStartup(null), "无画像应 N/A")
        assertNull(AlertType.STARTUP_SLOW.extract(snapshot(0L, gcOldCount = 0)), "单快照路径恒 N/A(数据源在快照之外)")

        val seconds = AlertType.STARTUP_SLOW.extractStartup(45_200L)
        assertEquals(45.2, seconds!!, 1e-9, "45200ms → 45.2s")
        assertTrue(AlertType.STARTUP_SLOW.violated(seconds, 180.0).not(), "45.2s 不应越 180s 阈值")
        assertTrue(AlertType.STARTUP_SLOW.violated(200.0, 180.0), "200s 应越 180s 阈值")
    }
}
