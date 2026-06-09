package top.wcpe.mc.plugin.serverprobe.core.aggregator

import kotlin.math.ceil

/**
 * 毫秒样本的分位/均值统计纯函数集合(跨快照聚合用)。
 *
 * 被 [MetricAggregator] 用于对**已是毫秒单位的"周期代表值"**(如各快照的 `msptP95`、`tps1m`)
 * 做窗口内统计。全部为无副作用纯函数,便于直接单测。
 *
 * **为何与 platform-bukkit 的 `sampler.Percentiles` 各存一份(规范第 5/10 条说明)**:
 * 两者算法形态相同(均值、最近秩分位),但输入语义差异大,合并反而劣化两侧:
 * - 类型与单位:bukkit 版输入 `LongArray`(单 tick 耗时**纳秒**)并在内部换算为毫秒;
 *   本版输入 `DoubleArray` 且**已是毫秒**,不做任何单位换算。
 * - 数据口径:bukkit 版对"原始逐 tick 样本"求分位(采样器窗口内);本版对"各周期已算好的代表值"
 *   再求分位(跨快照),是不同抽象层级的统计。
 * - 模块边界:core 不得依赖任何平台模块(本版位于 core),无法复用 bukkit 版;强行下沉到公共模块
 *   需引入"是否换算单位/Long 还是 Double"的开关参数,徒增复杂度。
 * 故各保留一份,并在两处 KDoc 交叉说明,避免被误判为可消除的重复。
 */
object Percentiles {

    /**
     * 计算样本算术平均值。
     *
     * @param samples 毫秒样本(或任意已统一单位的 Double 样本);**不会**被本方法修改。
     * @return 平均值;样本为空时返回 null。
     */
    fun average(samples: DoubleArray): Double? {
        if (samples.isEmpty()) {
            return null
        }
        var sum = 0.0
        for (value in samples) {
            sum += value
        }
        return sum / samples.size
    }

    /**
     * 按"最近秩"法计算指定分位。
     *
     * 对升序样本取第 ceil(p × n) 个(1 基),无插值,与 platform-bukkit 的分位实现口径一致,
     * 满足运维诊断精度。本方法在内部副本上排序,**不修改**传入数组。
     *
     * @param samples 毫秒样本(或任意已统一单位的 Double 样本);**不会**被本方法修改。
     * @param p 分位值,取值区间 (0.0, 1.0]。
     * @return 对应分位值;样本为空时返回 null。
     */
    fun percentile(samples: DoubleArray, p: Double): Double? {
        if (samples.isEmpty()) {
            return null
        }
        // 复制后排序,保证不污染调用方数组(纯函数语义)
        val sorted = samples.copyOf()
        sorted.sort()
        val rank = ceil(p * sorted.size).toInt()
        val index = (rank - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
