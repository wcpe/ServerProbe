package top.wcpe.mc.plugin.serverprobe.core.startup

import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming
import top.wcpe.mc.plugin.serverprobe.core.agent.AgentStartupData
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.ManagementFactory

/**
 * 启动画像装配器(FR1)。
 *
 * 将各来源的启动耗时数据组装为一份结构化 [StartupProfile]:分段耗时取自 [PhaseTimingRecorder],
 * JVM 启动参数与启动时刻取自 [java.lang.management.RuntimeMXBean],其余(总时长、慢插件榜、世界耗时)
 * 由平台监听器测得后传入;**启动 agent 早期数据**(A3)经 [AgentStartupData] 由平台监听器读出后传入,
 * 填入画像的 agent 增强字段。本装配器不依赖任何平台 API,故落位于 core。
 *
 * 作为 IOC [Service] 供平台监听器注入调用,自身无状态。
 */
@Service
class StartupProfileBuilder {

    /**
     * 组装一份启动画像。
     *
     * @param mcVersion Minecraft 版本(如 "1.21.4");代理端为其对应版本标识。
     * @param platform 启动来源平台。
     * @param serverId 实例标识。
     * @param totalMs 端到端启动总时长(毫秒)。
     * @param pluginTimings 各插件 onEnable 耗时明细(慢插件榜数据源,来自日志解析)。
     * @param worldTimings 各世界加载耗时明细。
     * @param agentData 启动 agent 早期数据;为 null 或其 [AgentStartupData.attached] 为 false 时,
     *  画像的 agent 增强字段全部填默认(未挂载降级)。
     * @return 组装完成的启动画像。
     */
    fun build(
        mcVersion: String,
        platform: ProbePlatform,
        serverId: String,
        totalMs: Long,
        pluginTimings: List<PluginTiming>,
        worldTimings: List<WorldTiming>,
        agentData: AgentStartupData? = null
    ): StartupProfile {
        val runtimeBean = ManagementFactory.getRuntimeMXBean()
        // agent 未挂载(null 或 attached=false):增强字段全 null,与旧档默认值一致
        val agentAttached = agentData?.attached == true
        return StartupProfile(
            schemaVersion = SCHEMA_VERSION,
            serverId = serverId,
            platform = platform,
            mcVersion = mcVersion,
            jvmStartTimeMs = runtimeBean.startTime,
            totalMs = totalMs,
            phaseTimings = PhaseTimingRecorder.phaseTimings(),
            pluginTimings = pluginTimings,
            worldTimings = worldTimings,
            jvmArgs = runtimeBean.inputArguments,
            createdAtMs = System.currentTimeMillis(),
            agentAttached = agentAttached,
            premainNanos = if (agentAttached) agentData?.premainNanos else null,
            agentPluginLoadTimings = if (agentAttached) agentData?.loadTimings else null,
            agentPluginEnableTimings = if (agentAttached) agentData?.enableTimings else null,
            libraryTimings = if (agentAttached) agentData?.libraryTimings else null,
            mainThreadHotspots = if (agentAttached) agentData?.hotspots else null,
            timelineEvents = if (agentAttached) agentData?.timelineEvents else null,
            threadStacks = if (agentAttached) agentData?.threadStacks else null,
            configTimings = if (agentAttached) agentData?.configTimings else null,
            eventTimings = if (agentAttached) agentData?.eventTimings else null,
            commandTimings = if (agentAttached) agentData?.commandTimings else null,
            sampleIntervalMs = if (agentAttached) agentData?.sampleIntervalMs else null,
            httpCalls = if (agentAttached) agentData?.httpCalls else null
        )
    }

    private companion object {

        /** 落盘/画像格式版本号,M5 起为 3(A3 = 2,M1 = 1)。 */
        private const val SCHEMA_VERSION = 3
    }
}
