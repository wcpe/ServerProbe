package top.wcpe.mc.plugin.serverprobe.core.startup

import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.ManagementFactory

/**
 * 启动画像装配器(FR1)。
 *
 * 将各来源的启动耗时数据组装为一份结构化 [StartupProfile]:分段耗时取自 [PhaseTimingRecorder],
 * JVM 启动参数与启动时刻取自 [java.lang.management.RuntimeMXBean],其余(总时长、慢插件榜、世界耗时)
 * 由平台监听器测得后传入。本装配器不依赖任何平台 API,故落位于 core。
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
     * @param pluginTimings 各插件 onEnable 耗时明细(慢插件榜数据源)。
     * @param worldTimings 各世界加载耗时明细。
     * @return 组装完成的启动画像。
     */
    fun build(
        mcVersion: String,
        platform: ProbePlatform,
        serverId: String,
        totalMs: Long,
        pluginTimings: List<PluginTiming>,
        worldTimings: List<WorldTiming>
    ): StartupProfile {
        val runtimeBean = ManagementFactory.getRuntimeMXBean()
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
            createdAtMs = System.currentTimeMillis()
        )
    }

    private companion object {

        /** 落盘/画像格式版本号,M1 固定为 1。 */
        private const val SCHEMA_VERSION = 1
    }
}
