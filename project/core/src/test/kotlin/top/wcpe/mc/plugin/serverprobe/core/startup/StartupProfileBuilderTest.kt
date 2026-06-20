package top.wcpe.mc.plugin.serverprobe.core.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.FoldedStack
import top.wcpe.mc.plugin.serverprobe.api.model.HttpCall
import top.wcpe.mc.plugin.serverprobe.api.model.LibraryTiming
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StackHotspot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupItemTiming
import top.wcpe.mc.plugin.serverprobe.api.model.ThreadStackProfile
import top.wcpe.mc.plugin.serverprobe.api.model.TimelineEvent
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming
import top.wcpe.mc.plugin.serverprobe.core.agent.AgentStartupData

/**
 * [StartupProfileBuilder] 单元测试。
 *
 * [StartupProfileBuilder.build] 仅依赖 `java.lang.management.*` 与 [PhaseTimingRecorder],无 [top.wcpe.taboolib.ioc.annotation.Inject]
 * 字段,可直接实例化、不经 IOC 容器验证。断言聚焦"装配结果各字段对齐 api 契约"。
 */
class StartupProfileBuilderTest {

    /** build() 各字段应对齐 api 契约。 */
    @Test
    fun `build 各字段对齐契约`() {
        val pluginTimings = listOf(
            PluginTiming.builder().name("Alpha").enableMs(1200L).build(),
            PluginTiming.builder().name("Beta").enableMs(800L).build()
        )
        val worldTimings = listOf(
            WorldTiming.builder().name("world").loadMs(0L).build(),
            WorldTiming.builder().name("world_nether").loadMs(0L).build()
        )

        val profile = StartupProfileBuilder().build(
            mcVersion = "1.21.4",
            platform = ProbePlatform.BUKKIT,
            serverId = "test-server",
            totalMs = 12_345L,
            pluginTimings = pluginTimings,
            worldTimings = worldTimings
        )

        // schemaVersion:M5 起为 3
        assertEquals(3, profile.schemaVersion, "schemaVersion 应为 3")

        // 入参透传
        assertEquals("test-server", profile.serverId, "serverId 应透传")
        assertEquals(ProbePlatform.BUKKIT, profile.platform, "platform 应透传")
        assertEquals("1.21.4", profile.mcVersion, "mcVersion 应透传")
        assertEquals(12_345L, profile.totalMs, "totalMs 应透传")
        assertEquals(pluginTimings, profile.pluginTimings, "pluginTimings 应透传")
        assertEquals(worldTimings, profile.worldTimings, "worldTimings 应透传")

        // 取自运行时 JVM 的字段
        assertNotNull(profile.jvmArgs, "jvmArgs 不应为 null")
        assertTrue(profile.jvmStartTimeMs > 0, "jvmStartTimeMs 应大于 0")
        assertTrue(profile.createdAtMs > 0, "createdAtMs 应大于 0")

        // phaseTimings 取自 PhaseTimingRecorder
        assertNotNull(profile.phaseTimings, "phaseTimings 不应为 null")
        assertEquals(
            PhaseTimingRecorder.phaseTimings(),
            profile.phaseTimings,
            "phaseTimings 应来自 PhaseTimingRecorder"
        )

        // 未传 agentData(默认 null):agent 增强字段全部降级为默认(向后兼容,等同旧档)
        assertFalse(profile.agentAttached, "未传 agentData 时 agentAttached 应为 false")
        assertNull(profile.premainNanos, "未挂载时 premainNanos 应为 null")
        assertNull(profile.agentPluginLoadTimings, "未挂载时 agentPluginLoadTimings 应为 null")
        assertNull(profile.agentPluginEnableTimings, "未挂载时 agentPluginEnableTimings 应为 null")
        assertNull(profile.libraryTimings, "未挂载时 libraryTimings 应为 null")
        assertNull(profile.mainThreadHotspots, "未挂载时 mainThreadHotspots 应为 null")
        assertNull(profile.timelineEvents, "未挂载时 timelineEvents 应为 null")
        assertNull(profile.threadStacks, "未挂载时 threadStacks 应为 null")
        assertNull(profile.configTimings, "未挂载时 configTimings 应为 null")
        assertNull(profile.eventTimings, "未挂载时 eventTimings 应为 null")
        assertNull(profile.commandTimings, "未挂载时 commandTimings 应为 null")
        assertNull(profile.sampleIntervalMs, "未挂载时 sampleIntervalMs 应为 null")
        assertNull(profile.httpCalls, "未挂载时 httpCalls 应为 null")
    }

    /** agent 已挂载时,各 agent 增强字段(含 M5 时间线/折叠栈/配置·事件·命令)应从 [AgentStartupData] 透传填入画像。 */
    @Test
    fun `build agent 挂载时增强字段透传`() {
        val agentLoad = listOf(PluginTiming.builder().name("Alpha").enableMs(50L).build())
        val agentEnable = listOf(
            PluginTiming.builder().name("Alpha").enableMs(1200L).build(),
            PluginTiming.builder().name("Beta").enableMs(800L).build()
        )
        val libraries = listOf(LibraryTiming.builder().name("Alpha").loadMs(3000L).build())
        val hotspots = listOf(
            StackHotspot.builder().frame("a.b.C#m").sampleCount(120L).build(),
            StackHotspot.builder().frame("d.E#n").sampleCount(88L).build()
        )
        val timeline = listOf(TimelineEvent.builder().type("enable").name("Alpha").startNanos(1_000L).endNanos(2_000L).build())
        val threadStacks = listOf(
            ThreadStackProfile.builder()
                .threadName("Server thread")
                .stacks(listOf(FoldedStack.builder().frames(listOf("a.A#x", "b.B#y")).sampleCount(42L).build()))
                .build()
        )
        val configs = listOf(StartupItemTiming.builder().name("config.yml").costMs(30L).build())
        val events = listOf(StartupItemTiming.builder().name("Alpha").costMs(15L).build())
        val commands = listOf(StartupItemTiming.builder().name("give").costMs(5L).build())
        val httpCalls = listOf(
            HttpCall.builder()
                .seq(1L).startRelNanos(1000L).durationMs(137000L).method("GET").responseCode(200)
                .error(false).host("maven.x").url("https://maven.x/a.xml").headers(emptyList())
                .callerFrames(listOf("corelib.RuntimeEnv#load")).loaderHashes(listOf(42)).plugin("CoreLib")
                .build()
        )
        val agentData = AgentStartupData(
            attached = true,
            premainNanos = 987_654L,
            jvmStartTimeMs = 1_700_000_000_000L,
            loadTimings = agentLoad,
            enableTimings = agentEnable,
            libraryTimings = libraries,
            hotspots = hotspots,
            timelineEvents = timeline,
            threadStacks = threadStacks,
            configTimings = configs,
            eventTimings = events,
            commandTimings = commands,
            sampleIntervalMs = 10L,
            httpCalls = httpCalls
        )

        val profile = StartupProfileBuilder().build(
            mcVersion = "1.21.4",
            platform = ProbePlatform.BUKKIT,
            serverId = "test-server",
            totalMs = 12_345L,
            pluginTimings = emptyList(),
            worldTimings = emptyList(),
            agentData = agentData
        )

        assertTrue(profile.agentAttached, "agent 挂载时 agentAttached 应为 true")
        assertEquals(987_654L, profile.premainNanos, "premainNanos 应透传")
        assertEquals(agentLoad, profile.agentPluginLoadTimings, "agentPluginLoadTimings 应透传")
        assertEquals(agentEnable, profile.agentPluginEnableTimings, "agentPluginEnableTimings 应透传")
        assertEquals(libraries, profile.libraryTimings, "libraryTimings 应透传")
        assertEquals(hotspots, profile.mainThreadHotspots, "mainThreadHotspots 应透传")
        // M5 非空透传:时间线 / 折叠栈 / 配置·事件·命令
        assertEquals(timeline, profile.timelineEvents, "timelineEvents 应非空透传")
        assertEquals(threadStacks, profile.threadStacks, "threadStacks 应非空透传")
        assertEquals(configs, profile.configTimings, "configTimings 应非空透传")
        assertEquals(events, profile.eventTimings, "eventTimings 应非空透传")
        assertEquals(commands, profile.commandTimings, "commandTimings 应非空透传")
        assertEquals(10L, profile.sampleIntervalMs, "sampleIntervalMs 应透传")
        assertEquals(httpCalls, profile.httpCalls, "httpCalls 应非空透传")
    }

    /** agent 数据 attached=false 时,即使传入也应按未挂载降级。 */
    @Test
    fun `build agent 未挂载数据降级为默认`() {
        val profile = StartupProfileBuilder().build(
            mcVersion = "1.21.4",
            platform = ProbePlatform.BUKKIT,
            serverId = "test-server",
            totalMs = 1L,
            pluginTimings = emptyList(),
            worldTimings = emptyList(),
            agentData = AgentStartupData.notAttached()
        )

        assertFalse(profile.agentAttached, "attached=false 的数据应使 agentAttached 为 false")
        assertNull(profile.premainNanos, "未挂载降级时 premainNanos 应为 null")
        assertNull(profile.libraryTimings, "未挂载降级时 libraryTimings 应为 null")
        assertNull(profile.mainThreadHotspots, "未挂载降级时 mainThreadHotspots 应为 null")
        assertNull(profile.threadStacks, "未挂载降级时 threadStacks 应为 null")
    }
}
