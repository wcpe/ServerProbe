package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 单个插件的库下载/加载耗时(FR1,启动 agent 早期数据)。
 *
 * 库下载常是"首次启动慢"的隐形大头(远程拉取 Maven 依赖),仅当挂载启动 agent
 * (`-javaagent:plugins/ServerProbe.jar`)时才能在 `LibraryLoader#createLoader` 处测得;
 * 未挂载 agent 时无此数据(详见 [StartupProfile.libraryTimings])。
 *
 * @property name 插件名称。
 * @property loadMs 该插件库下载/加载耗时(毫秒)。
 */
data class LibraryTiming(
    val name: String,
    val loadMs: Long
)
