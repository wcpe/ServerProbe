package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 启动期单次 hook 事件的时间线记录(M5,启动 agent 增强数据)。
 *
 * 与 [PluginTiming] / [LibraryTiming] 的"汇总耗时"不同,本模型记录每个被 hook 的方法调用的
 * **精确开始/结束时刻**(纳秒级,相对 premain),形成完整启动时间线,可据此生成火焰图与瀑布图。
 *
 * 仅当挂载启动 agent(`-javaagent:plugins/ServerProbe.jar`)时才有此数据
 * (详见 [StartupProfile.timelineEvents])。
 *
 * @property type hook 类型:enable/load/library/worldCreate/configLoad/eventRegister/commandRegister。
 * @property name 被 hook 的对象名(插件名/文件名/世界名/命令名)。
 * @property startNanos 事件开始时刻(相对 premain 的 `System.nanoTime()` 偏移,纳秒)。
 * @property endNanos 事件结束时刻(同上)。
 */
data class TimelineEvent(
    val type: String,
    val name: String,
    val startNanos: Long,
    val endNanos: Long
)
