package top.wcpe.mc.plugin.serverprobe.core.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile

/**
 * [SlowPluginRanking] 单元测试：慢插件榜的"择优来源 + Top-N 截断"口径（FR-25 真机验收修正）。
 *
 * 背景：命令与 Prometheus 出口此前各自实现取源，挂 agent 时同一实例上两处给出的插件耗时不同
 * （面板用日志解析近似值、命令用 agent 实测值），且指标侧未截断会把全部插件写成 label。
 */
class SlowPluginRankingTest {

    @Test
    fun `未挂 agent 时取日志解析榜`() {
        val profile = baseProfile(pluginTimings = timings("A" to 1000L, "B" to 500L))

        val top = SlowPluginRanking.top(profile, topN = 5)

        assertEquals(listOf("A", "B"), top.map { it.name })
    }

    @Test
    fun `挂 agent 时优先实测值而非日志解析近似值`() {
        val profile = baseProfile(pluginTimings = timings("A" to 1000L, "B" to 500L))
            .toBuilder()
            .agentAttached(true)
            .agentPluginEnableTimings(timings("A" to 843L, "B" to 20L))
            .build()

        val top = SlowPluginRanking.top(profile, topN = 5)

        assertEquals(listOf(843L, 20L), top.map { it.enableMs }, "应取 agent 实测值")
    }

    @Test
    fun `Incision 生效时优先其精确值`() {
        val profile = baseProfile(pluginTimings = timings("A" to 1000L))
            .toBuilder()
            .agentAttached(true)
            .agentPluginEnableTimings(timings("A" to 843L))
            .incisionActive(true)
            .incisionPluginEnableTimings(timings("A" to 700L))
            .build()

        val top = SlowPluginRanking.top(profile, topN = 5)

        assertEquals(listOf(700L), top.map { it.enableMs }, "Incision 精确值应压过 agent 与日志解析")
    }

    @Test
    fun `声明生效但实测榜为空时回退日志解析`() {
        val profile = baseProfile(pluginTimings = timings("A" to 1000L))
            .toBuilder()
            .agentAttached(true)
            .agentPluginEnableTimings(emptyList())
            .build()

        val top = SlowPluginRanking.top(profile, topN = 5)

        assertEquals(listOf("A"), top.map { it.name }, "空列表视为无数据,不得把空榜当成有值")
    }

    @Test
    fun `按启用耗时降序截断到榜单条数`() {
        val profile = baseProfile(pluginTimings = timings("A" to 100L, "B" to 900L, "C" to 500L))

        val top = SlowPluginRanking.top(profile, topN = 2)

        assertEquals(listOf("B", "C"), top.map { it.name }, "应取最慢的两条")
    }

    @Test
    fun `榜单条数为非正数时返回空`() {
        val profile = baseProfile(pluginTimings = timings("A" to 100L))

        assertTrue(SlowPluginRanking.top(profile, topN = 0).isEmpty())
    }

    /** 构造最小画像：仅逐插件日志解析榜有意义，其余字段占位。 */
    private fun baseProfile(pluginTimings: List<PluginTiming>): StartupProfile = StartupProfile.builder()
        .schemaVersion(4)
        .serverId("srv-1")
        .platform(ProbePlatform.BUKKIT)
        .jvmStartTimeMs(0L)
        .totalMs(1000L)
        .createdAtMs(0L)
        .pluginTimings(pluginTimings)
        .build()

    /** 按 名字 → 耗时 构造逐插件耗时列表。 */
    private fun timings(vararg pairs: Pair<String, Long>): List<PluginTiming> = pairs.map { (name, ms) ->
        PluginTiming.builder().name(name).enableMs(ms).build()
    }
}
