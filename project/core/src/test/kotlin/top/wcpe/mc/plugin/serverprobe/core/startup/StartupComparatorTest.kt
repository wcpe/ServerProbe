package top.wcpe.mc.plugin.serverprobe.core.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile

/**
 * [StartupComparator] 单元测试。
 *
 * [StartupComparator] 为纯逻辑(仅依赖入参的 totalMs 与 pluginTimings,不读外部数据),可直接构造画像验证。
 * 覆盖:无 previous 返回固定"无基线"文案;有 previous 时摘要含总时长变化(±s 与 ±%)与最慢插件变化。
 */
class StartupComparatorTest {

    /** 无上一次画像应返回固定"无基线"文案。 */
    @Test
    fun `无 previous 返回无基线`() {
        val current = profile(totalMs = 10_000L, plugins = listOf(PluginTiming("Alpha", 500L)))

        assertEquals("无基线对比(首次记录)", StartupComparator.summary(current, null), "首次记录应返回无基线文案")
    }

    /** 有上一次画像:摘要应含总时长变化(带符号秒差与百分比)与最慢插件变化。 */
    @Test
    fun `有 previous 含总时长与慢插件变化`() {
        // 上次 10.0s,本次 11.0s:Δ=+1.0s,+10.0%
        val previous = profile(totalMs = 10_000L, plugins = listOf(PluginTiming("Alpha", 800L)))
        val current = profile(totalMs = 11_000L, plugins = listOf(PluginTiming("Alpha", 1_000L)))

        val summary = StartupComparator.summary(current, previous)

        assertTrue(summary.contains("10.0s"), "应含上次总时长 10.0s")
        assertTrue(summary.contains("11.0s"), "应含本次总时长 11.0s")
        assertTrue(summary.contains("+1.0s"), "应含带符号秒差 +1.0s")
        assertTrue(summary.contains("+10.0%"), "应含带符号百分比 +10.0%")
        assertTrue(summary.contains("Alpha"), "应含最慢插件名 Alpha")
    }

    /** 总时长下降时应给出负号秒差。 */
    @Test
    fun `总时长下降给出负号`() {
        val previous = profile(totalMs = 20_000L, plugins = emptyList())
        val current = profile(totalMs = 15_000L, plugins = emptyList())

        val summary = StartupComparator.summary(current, previous)

        assertTrue(summary.contains("-5.0s"), "总时长下降应含 -5.0s")
    }

    /**
     * 构造一份用于对比的启动画像;仅 totalMs 与 pluginTimings 影响对比结果,其余字段填占位值。
     *
     * @param totalMs 端到端总时长(毫秒)。
     * @param plugins 逐插件耗时。
     * @return 启动画像。
     */
    private fun profile(totalMs: Long, plugins: List<PluginTiming>): StartupProfile = StartupProfile(
        schemaVersion = 1,
        serverId = "test",
        platform = ProbePlatform.BUKKIT,
        mcVersion = "1.21.4",
        jvmStartTimeMs = 1L,
        totalMs = totalMs,
        phaseTimings = emptyList(),
        pluginTimings = plugins,
        worldTimings = emptyList(),
        jvmArgs = emptyList(),
        createdAtMs = 1L
    )
}
