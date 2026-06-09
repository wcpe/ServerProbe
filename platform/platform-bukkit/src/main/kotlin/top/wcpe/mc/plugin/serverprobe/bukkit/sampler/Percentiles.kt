package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

/**
 * 纳秒样本的分位/均值统计纯函数集合。
 *
 * 被 [MsptHistogram](自建窗口)与 Paper 路径(`getTickTimes()` 数组)共用,
 * 集中收口分位算法,避免在两处复制粘贴(规范第 10 条第 5 项)。全部为无副作用纯函数。
 *
 * 所有输入为单 tick 耗时(纳秒),输出统一换算为毫秒(MSPT 习惯单位)。
 *
 * **与 core 的 `aggregator.Percentiles` 区分**:core 另有一份 `DoubleArray`(已是毫秒)版本,
 * 用于跨快照对"周期代表值"再求分位。两者算法同形但输入类型/单位/数据口径不同,且 core 不依赖平台模块,
 * 故各存一份(详见 core 版 KDoc)。本版专用于平台采样器的纳秒逐 tick 样本。
 */
object Percentiles {

    /** 纳秒到毫秒换算因子。 */
    private const val NANOS_PER_MS = 1_000_000.0

    /**
     * 计算样本平均值(毫秒)。
     *
     * @param samples 单 tick 耗时样本(纳秒);**不会**被本方法修改。
     * @return 平均值(毫秒);样本为空时返回 null。
     */
    fun avgMs(samples: LongArray): Double? {
        if (samples.isEmpty()) {
            return null
        }
        var sum = 0L
        for (nanos in samples) {
            sum += nanos
        }
        return (sum.toDouble() / samples.size) / NANOS_PER_MS
    }

    /**
     * 按"最近秩"法计算指定分位(毫秒)。
     *
     * 对升序样本取第 ceil(p × n) 个(1 基),无插值,实现简单且满足运维诊断精度。
     *
     * 注意:为避免重复排序,本方法**会就地排序**传入数组;调用方若需保留原顺序应自行传入副本。
     *
     * @param samples 单 tick 耗时样本(纳秒);**会被就地升序排序**。
     * @param percentile 分位值,取值区间 (0.0, 1.0]。
     * @return 对应分位值(毫秒);样本为空时返回 null。
     */
    fun percentileMsInPlace(samples: LongArray, percentile: Double): Double? {
        if (samples.isEmpty()) {
            return null
        }
        samples.sort()
        val rank = Math.ceil(percentile * samples.size).toInt()
        val index = (rank - 1).coerceIn(0, samples.size - 1)
        return samples[index].toDouble() / NANOS_PER_MS
    }
}
