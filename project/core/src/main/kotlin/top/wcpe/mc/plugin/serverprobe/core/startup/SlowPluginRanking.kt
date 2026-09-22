package top.wcpe.mc.plugin.serverprobe.core.startup

import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile

/**
 * 慢插件榜的取值与抽稀口径（FR-1 / FR-25 / FR-29 共用的唯一真源）。
 *
 * 画像里逐插件耗时**两套来源并存、精度不同**：agent（或 Incision）插桩实测的精确值，
 * 与 `logs/latest.log` 时间差解析的近似值。命令与 Prometheus 出口必须**同源同截**——
 * 否则同一实例上 `/metrics` 面板与 `/probe startup` 会给出不同数字（真机验收发现的 FR-25 不一致），
 * 且近似榜若在解析侧未截断，会把每个插件都写成一条 label，基数随插件数增长。
 *
 * 择优顺序：Incision 精确 > agent 实测 > 日志解析近似；空列表按"无数据"向下回退，不把空当成有值。
 */
object SlowPluginRanking {

    /**
     * 取慢插件榜：按上述来源择优后，按启用耗时降序截断到前 [topN] 条。
     *
     * @param profile 启动画像。
     * @param topN 榜单条数（调用方传 `ProbeConfig.startupTopN()`）；非正数视为不要榜单，返回空列表。
     * @return 慢插件榜；来源本身为空时为全空列表。
     */
    fun top(profile: StartupProfile, topN: Int): List<PluginTiming> {
        if (topN <= 0) {
            return emptyList()
        }
        return source(profile).sortedByDescending { it.enableMs }.take(topN)
    }

    /** 择优来源：Incision 精确 > agent 实测 > 日志解析近似（后两者精度更高，优先呈现）。 */
    private fun source(profile: StartupProfile): List<PluginTiming> {
        val incision = profile.incisionPluginEnableTimings
        if (profile.incisionActive && !incision.isNullOrEmpty()) {
            return incision
        }
        val agent = profile.agentPluginEnableTimings
        if (profile.agentAttached && !agent.isNullOrEmpty()) {
            return agent
        }
        return profile.pluginTimings
    }
}
