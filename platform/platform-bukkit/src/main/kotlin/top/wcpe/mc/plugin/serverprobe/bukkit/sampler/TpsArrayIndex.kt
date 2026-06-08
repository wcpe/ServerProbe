package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

/**
 * TPS 数组下标约定。
 *
 * Paper `getTPS()` 与 NMS `recentTps` 均以长度 3 的 double 数组返回 [1m, 5m, 15m]。
 * 下标含义在两条采样路径间一致,集中常量化避免魔法值与重复定义(规范第 10 条第 5/6 项)。
 */
internal object TpsArrayIndex {

    /** 最近 1 分钟平均 TPS 下标。 */
    const val M1 = 0

    /** 最近 5 分钟平均 TPS 下标。 */
    const val M5 = 1

    /** 最近 15 分钟平均 TPS 下标。 */
    const val M15 = 2
}
