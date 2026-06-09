package top.wcpe.mc.plugin.serverprobe.api.model

import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform

/**
 * 启动画像:一次启动的结构化耗时报告(FR1)。
 *
 * 汇总端到端总时长、各生命周期分段、慢插件榜、世界加载耗时与 JVM 参数快照,
 * 用于"开服慢"定位及与上次/基线对比。每次启动落盘为一份 JSON(详见 PRD §9)。
 *
 * **启动 agent 增强字段(A3,向后兼容)**:末尾的 [agentAttached]/[premainNanos]/
 * [agentPluginLoadTimings]/[agentPluginEnableTimings]/[libraryTimings]/[mainThreadHotspots]
 * 仅当 JVM 以 `-javaagent:plugins/ServerProbe.jar` 挂载启动 agent 时才有值;
 * **未挂载时全为默认**([agentAttached] = false,其余为 null)。
 * agent 在 system ClassLoader 中从字节码插桩与栈采样直接测得,其逐插件 load/enable 耗时
 * **精度高于**基于 `logs/latest.log` 时间差的日志解析([pluginTimings]);二者并存,展示侧择优。
 * 这些字段均带默认值,故旧档(无这些字段)反序列化时降级为默认值,不破坏既有落盘格式。
 *
 * @property schemaVersion 落盘格式版本号,A3 起 = 2(M1 = 1);用于格式演进与向后兼容。
 * @property serverId 实例标识(自动生成或配置覆盖的 server-name)。
 *  实现保证恒有值:配置未指定时自动生成实例 ID(故为非空)。
 * @property platform 启动来源平台。
 * @property mcVersion Minecraft 版本(如 "1.21.4");代理端为其对应版本标识。
 * @property jvmStartTimeMs JVM 启动时刻(epoch 毫秒)。
 * @property totalMs 端到端启动总时长(毫秒)。
 * @property phaseTimings 各启动分段耗时明细。
 * @property pluginTimings 各插件 onEnable 耗时明细(慢插件榜数据源;来自日志解析)。
 * @property worldTimings 各世界加载耗时明细。
 * @property jvmArgs JVM 启动参数快照。
 * @property createdAtMs 本画像生成时刻(epoch 毫秒)。
 * @property agentAttached 启动 agent 是否挂载;true 时下列 agent 字段方有意义。
 * @property premainNanos agent premain 执行时刻的 `System.nanoTime()`;未挂载时为 null。
 * @property agentPluginLoadTimings agent 实测的逐插件 onLoad 耗时(精度高于日志解析);未挂载时为 null。
 * @property agentPluginEnableTimings agent 实测的逐插件 onEnable 耗时(精度高于日志解析);未挂载时为 null。
 * @property libraryTimings 逐插件库下载/加载耗时("首次启动慢"隐形大头);未挂载时为 null。
 * @property mainThreadHotspots 启动期"Server thread"主线程栈采样热点榜;未挂载时为 null。
 */
data class StartupProfile(
    val schemaVersion: Int,
    val serverId: String,
    val platform: ProbePlatform,
    val mcVersion: String,
    val jvmStartTimeMs: Long,
    val totalMs: Long,
    val phaseTimings: List<PhaseTiming>,
    val pluginTimings: List<PluginTiming>,
    val worldTimings: List<WorldTiming>,
    val jvmArgs: List<String>,
    val createdAtMs: Long,
    val agentAttached: Boolean = false,
    val premainNanos: Long? = null,
    val agentPluginLoadTimings: List<PluginTiming>? = null,
    val agentPluginEnableTimings: List<PluginTiming>? = null,
    val libraryTimings: List<LibraryTiming>? = null,
    val mainThreadHotspots: List<StackHotspot>? = null
)
