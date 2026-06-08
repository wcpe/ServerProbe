package top.wcpe.mc.plugin.serverprobe.api.model

import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform

/**
 * 启动画像:一次启动的结构化耗时报告(FR1)。
 *
 * 汇总端到端总时长、各生命周期分段、慢插件榜、世界加载耗时与 JVM 参数快照,
 * 用于"开服慢"定位及与上次/基线对比。每次启动落盘为一份 JSON(详见 PRD §9)。
 *
 * @property schemaVersion 落盘格式版本号,当前(M1)= 1;用于格式演进与向后兼容。
 * @property serverId 实例标识(自动生成或配置覆盖的 server-name)。
 *  实现保证恒有值:配置未指定时自动生成实例 ID(故为非空)。
 * @property platform 启动来源平台。
 * @property mcVersion Minecraft 版本(如 "1.21.4");代理端为其对应版本标识。
 * @property jvmStartTimeMs JVM 启动时刻(epoch 毫秒)。
 * @property totalMs 端到端启动总时长(毫秒)。
 * @property phaseTimings 各启动分段耗时明细。
 * @property pluginTimings 各插件 onEnable 耗时明细(慢插件榜数据源)。
 * @property worldTimings 各世界加载耗时明细。
 * @property jvmArgs JVM 启动参数快照。
 * @property createdAtMs 本画像生成时刻(epoch 毫秒)。
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
    val createdAtMs: Long
)
