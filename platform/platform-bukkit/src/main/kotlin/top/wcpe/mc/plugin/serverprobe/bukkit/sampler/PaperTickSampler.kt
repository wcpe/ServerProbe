package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.sampler.ServerTickSampler

/**
 * Paper 系采样器(source = [TickSampleSource.PAPER_API])。
 *
 * 数据全部经 [TickReflection] 反射 Paper 官方 API 获取(本模块编译期 Bukkit API 为纯 Spigot 口径,
 * 不含这些方法,故走反射):
 * - TPS:`Server#getTPS()` 返回 [1m, 5m, 15m];
 * - MSPT 均值:`Server#getAverageTickTime()`(毫秒);
 * - MSPT p95/p99:`Server#getTickTimes()`(最近 tick 纳秒数组)排序后取分位。
 *
 * 优雅降级:任一项反射不可用或瞬时取不到值时,对应字段为 null(N/A),其余字段照常给出;
 * 当 `getTickTimes()` 不可用(理论上 Paper 都有,留作兜底)时,p95/p99 退化为 null。
 */
class PaperTickSampler : ServerTickSampler {

    override val source: TickSampleSource = TickSampleSource.PAPER_API

    /**
     * 采样当前 tick 数据。
     *
     * @return Paper 路径采样结果;不可用项为 null。
     */
    override fun sample(): TickSample {
        val tps = TickReflection.paperTps()
        val tickTimes = TickReflection.paperTickTimes()
        // getTickTimes 可用则用其算分位;否则分位降级为 null,均值仍取 getAverageTickTime
        val avgMs: Double?
        val p95Ms: Double?
        val p99Ms: Double?
        if (tickTimes != null && tickTimes.isNotEmpty()) {
            // 复制后就地排序,避免改动平台返回的底层数组。
            // 理论冗余兜底——Paper 下 getAverageTickTime 与 getTickTimes 同时可用,
            // ?: 之后的数组均值分支仅在极端/非常规实现下才会触达,保留作极端防御。
            avgMs = TickReflection.paperAverageTickTimeMs() ?: Percentiles.avgMs(tickTimes)
            p95Ms = Percentiles.percentileMsInPlace(tickTimes.copyOf(), P95)
            p99Ms = Percentiles.percentileMsInPlace(tickTimes.copyOf(), P99)
        } else {
            avgMs = TickReflection.paperAverageTickTimeMs()
            p95Ms = null
            p99Ms = null
        }
        return TickSample(
            tps1m = tps?.getOrNull(TpsArrayIndex.M1),
            tps5m = tps?.getOrNull(TpsArrayIndex.M5),
            tps15m = tps?.getOrNull(TpsArrayIndex.M15),
            msptAvg = avgMs,
            msptP95 = p95Ms,
            msptP99 = p99Ms,
            source = source
        )
    }

    private companion object {

        /** p95 分位。 */
        private const val P95 = 0.95

        /** p99 分位。 */
        private const val P99 = 0.99
    }
}
