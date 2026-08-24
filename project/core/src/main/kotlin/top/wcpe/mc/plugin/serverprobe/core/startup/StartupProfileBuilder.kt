package top.wcpe.mc.plugin.serverprobe.core.startup

import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming
import top.wcpe.mc.plugin.serverprobe.core.agent.AgentStartupData
import top.wcpe.mc.plugin.serverprobe.core.incision.IncisionStartupData
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.ManagementFactory

/** 组装启动画像所需的平台测量数据。 */
data class StartupProfileInput(
    val mcVersion: String,
    val platform: ProbePlatform,
    val serverId: String,
    val totalMs: Long,
    val pluginTimings: List<PluginTiming>,
    val worldTimings: List<WorldTiming>,
    val agentData: AgentStartupData? = null,
    val incisionData: IncisionStartupData = IncisionStartupData.disabled()
)

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
     * @param input 平台测得的基础耗时、可选 agent 与 Incision 数据。
     * @return 组装完成的启动画像。
     */
    fun build(input: StartupProfileInput): StartupProfile {
        val runtimeBean = ManagementFactory.getRuntimeMXBean()
        // agent 未挂载(null 或 attached=false):增强字段全 null,与旧档默认值一致
        val agentAttached = input.agentData?.attached == true
        return StartupProfile.builder()
            .schemaVersion(SCHEMA_VERSION)
            .serverId(input.serverId)
            .platform(input.platform)
            .mcVersion(input.mcVersion)
            .jvmStartTimeMs(runtimeBean.startTime)
            .totalMs(input.totalMs)
            .phaseTimings(PhaseTimingRecorder.phaseTimings())
            .pluginTimings(input.pluginTimings)
            .worldTimings(input.worldTimings)
            .jvmArgs(runtimeBean.inputArguments)
            .createdAtMs(System.currentTimeMillis())
            .agentAttached(agentAttached)
            .premainNanos(if (agentAttached) input.agentData?.premainNanos else null)
            .agentPluginLoadTimings(if (agentAttached) input.agentData?.loadTimings else null)
            .agentPluginEnableTimings(if (agentAttached) input.agentData?.enableTimings else null)
            .libraryTimings(if (agentAttached) input.agentData?.libraryTimings else null)
            .mainThreadHotspots(if (agentAttached) input.agentData?.hotspots else null)
            .timelineEvents(if (agentAttached) input.agentData?.timelineEvents else null)
            .threadStacks(if (agentAttached) input.agentData?.threadStacks else null)
            .configTimings(if (agentAttached) input.agentData?.configTimings else null)
            .eventTimings(if (agentAttached) input.agentData?.eventTimings else null)
            .commandTimings(if (agentAttached) input.agentData?.commandTimings else null)
            .sampleIntervalMs(if (agentAttached) input.agentData?.sampleIntervalMs else null)
            .httpCalls(if (agentAttached) input.agentData?.httpCalls else null)
            .incisionEnabled(input.incisionData.enabled)
            .incisionActive(input.incisionData.active)
            .incisionPluginEnableTimings(if (input.incisionData.active) input.incisionData.pluginEnableTimings else null)
            .build()
    }

    private companion object {

        /** 落盘/画像格式版本号,FR7 起为 4(M5 = 3,A3 = 2,M1 = 1)。 */
        private const val SCHEMA_VERSION = 4
    }
}
