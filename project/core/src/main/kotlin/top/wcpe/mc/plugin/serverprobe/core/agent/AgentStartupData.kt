package top.wcpe.mc.plugin.serverprobe.core.agent

import top.wcpe.mc.plugin.serverprobe.api.model.LibraryTiming
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StackHotspot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupItemTiming
import top.wcpe.mc.plugin.serverprobe.api.model.ThreadStackProfile
import top.wcpe.mc.plugin.serverprobe.api.model.TimelineEvent
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming

/**
 * 启动 agent 早期数据的 core 内部载体(A3/M5)。
 *
 * 由 [AgentDataReader.read] 经跨 ClassLoader 反射读出并解析得到,随后由
 * [top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileBuilder] 并入对外的
 * [top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile]。
 *
 * **未挂载语义**:agent 仅在启动加 `-javaagent:plugins/ServerProbe.jar` 时挂载;未挂载时由
 * [notAttached] 产出 [attached] = false、其余为零值/空列表的实例。
 *
 * 纯数据载体,不对外暴露(故置于 core,而非 api)。
 *
 * @property attached agent 是否挂载(反射成功且 premainNanos>0)。
 * @property premainNanos agent premain 时刻的 `System.nanoTime()`;未挂载时为 0。
 * @property jvmStartTimeMs agent 读取的 JVM 启动时刻(epoch 毫秒);未挂载时为 0。
 * @property loadTimings agent 实测的逐插件 onLoad 耗时;未挂载时为空列表。
 * @property enableTimings agent 实测的逐插件 onEnable 耗时;未挂载时为空列表。
 * @property libraryTimings 逐插件库下载/加载耗时;未挂载时为空列表。
 * @property hotspots 主线程栈采样热点(由 [threadStacks] 的主线程折叠栈派生,已按命中降序);未挂载时为空列表。
 * @property timelineEvents 启动期逐事件时间线(M5,相对 premain);未挂载时为空列表。
 * @property threadStacks 多线程折叠栈采样(M5,火焰图数据源);未挂载时为空列表。
 * @property worldTimings agent 实测的逐世界创建耗时(M5);未挂载时为空列表。
 * @property configTimings agent 实测的逐配置文件加载耗时(M5);未挂载时为空列表。
 * @property eventTimings agent 实测的逐插件事件注册耗时(M5);未挂载时为空列表。
 * @property commandTimings agent 实测的逐命令注册耗时(M5);未挂载时为空列表。
 */
data class AgentStartupData(
    val attached: Boolean,
    val premainNanos: Long,
    val jvmStartTimeMs: Long,
    val loadTimings: List<PluginTiming>,
    val enableTimings: List<PluginTiming>,
    val libraryTimings: List<LibraryTiming>,
    val hotspots: List<StackHotspot>,
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val threadStacks: List<ThreadStackProfile> = emptyList(),
    val worldTimings: List<WorldTiming> = emptyList(),
    val configTimings: List<StartupItemTiming> = emptyList(),
    val eventTimings: List<StartupItemTiming> = emptyList(),
    val commandTimings: List<StartupItemTiming> = emptyList()
) {

    companion object {

        /**
         * 构造"未挂载"降级实例:[attached] = false,其余为零值/空列表。
         *
         * agent 未挂载或读取失败时统一返回此实例,使装配侧无需做空判分支即可降级。
         *
         * @return 未挂载的降级数据。
         */
        fun notAttached(): AgentStartupData = AgentStartupData(
            attached = false,
            premainNanos = 0L,
            jvmStartTimeMs = 0L,
            loadTimings = emptyList(),
            enableTimings = emptyList(),
            libraryTimings = emptyList(),
            hotspots = emptyList(),
            timelineEvents = emptyList(),
            threadStacks = emptyList(),
            worldTimings = emptyList(),
            configTimings = emptyList(),
            eventTimings = emptyList(),
            commandTimings = emptyList()
        )
    }
}
