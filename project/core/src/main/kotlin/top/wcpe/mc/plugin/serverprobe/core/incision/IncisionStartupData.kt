package top.wcpe.mc.plugin.serverprobe.core.incision

import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming

/**
 * Incision 织入链路在一次启动中的定格数据(FR7)。
 *
 * [enabled] 表示配置已请求采集;[active] 只在目标切点实际执行时为 true。
 * 二者分离使未命中有效切点时可诚实降级,且不会把空数据写入启动画像。
 */
data class IncisionStartupData(
    val enabled: Boolean,
    val active: Boolean,
    val pluginEnableTimings: List<PluginTiming>
) {

    companion object {

        /** 构造默认关闭的降级数据。 */
        fun disabled(): IncisionStartupData = IncisionStartupData(false, false, emptyList())
    }
}
