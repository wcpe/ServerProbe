package top.wcpe.mc.plugin.serverprobe.core.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming

/**
 * [StartupProfileBuilder] 单元测试。
 *
 * [StartupProfileBuilder.build] 仅依赖 `java.lang.management.*` 与 [PhaseTimingRecorder],无 [top.wcpe.taboolib.ioc.annotation.Inject]
 * 字段,可直接实例化、不经 IOC 容器验证。断言聚焦"装配结果各字段对齐 api 契约":固定值、入参透传、
 * 取自运行时 JVM 的不变量、分段来自 recorder。
 */
class StartupProfileBuilderTest {

    /** build() 各字段应对齐 api 契约。 */
    @Test
    fun `build 各字段对齐契约`() {
        val pluginTimings = listOf(PluginTiming("Alpha", 1200L), PluginTiming("Beta", 800L))
        val worldTimings = listOf(WorldTiming("world", 0L), WorldTiming("world_nether", 0L))

        val profile = StartupProfileBuilder().build(
            mcVersion = "1.21.4",
            platform = ProbePlatform.BUKKIT,
            serverId = "test-server",
            totalMs = 12_345L,
            pluginTimings = pluginTimings,
            worldTimings = worldTimings
        )

        // schemaVersion:M1 固定为 1
        assertEquals(1, profile.schemaVersion, "schemaVersion 应固定为 1")

        // 入参透传:serverId/platform/mcVersion/totalMs/pluginTimings/worldTimings 原样回填
        assertEquals("test-server", profile.serverId, "serverId 应透传")
        assertEquals(ProbePlatform.BUKKIT, profile.platform, "platform 应透传")
        assertEquals("1.21.4", profile.mcVersion, "mcVersion 应透传")
        assertEquals(12_345L, profile.totalMs, "totalMs 应透传")
        assertEquals(pluginTimings, profile.pluginTimings, "pluginTimings 应透传")
        assertEquals(worldTimings, profile.worldTimings, "worldTimings 应透传")

        // 取自运行时 JVM 的字段:jvmArgs 恒非 null;jvmStartTimeMs 为绝对时间戳应为正
        assertNotNull(profile.jvmArgs, "jvmArgs 不应为 null")
        assertTrue(profile.jvmStartTimeMs > 0, "jvmStartTimeMs 应大于 0")

        // createdAtMs 为生成时刻(epoch 毫秒)应为正
        assertTrue(profile.createdAtMs > 0, "createdAtMs 应大于 0")

        // phaseTimings 取自 PhaseTimingRecorder:非 null,且等于 recorder 当前快照(运行时实际打点值,不约束具体取值)。
        // 列表静态类型即 List<PhaseTiming>,与 recorder 快照相等已同时锁定"来源 + 元素类型"。
        assertNotNull(profile.phaseTimings, "phaseTimings 不应为 null")
        assertEquals(
            PhaseTimingRecorder.phaseTimings(),
            profile.phaseTimings,
            "phaseTimings 应来自 PhaseTimingRecorder"
        )
    }
}
