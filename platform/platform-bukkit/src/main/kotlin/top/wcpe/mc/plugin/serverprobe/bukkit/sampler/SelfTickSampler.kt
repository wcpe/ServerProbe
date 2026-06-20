package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.sampler.ServerTickSampler

/**
 * 自建采样兜底采样器(source = [TickSampleSource.SELF_SAMPLING])。
 *
 * 当既无 Paper API、又无法反射 NMS `recentTps` 时的最终兜底:
 * - TPS:完全自算——由 [TickClock] 记录的 tick 时间戳窗口推算 1m/5m/15m 平均 TPS;
 * - MSPT:同 Legacy,经共享 [MsptHistogram] 推算。
 *
 * 优雅降级:对应时间窗内尚无足够 tick 样本时,该项 TPS 为 null(刚启动时常见)。
 *
 * @property clock 提供 tick 时间戳窗口与 MSPT 直方图的 tick 时钟。
 */
class SelfTickSampler(
    private val clock: TickClock
) : ServerTickSampler {

    override fun getSource(): TickSampleSource = TickSampleSource.SELF_SAMPLING

    /**
     * 采样当前 tick 数据。
     *
     * @return Self 路径采样结果;不可用项为 null。
     */
    override fun sample(): TickSample {
        return TickSample.builder()
            .tps1m(clock.tpsOver(WINDOW_1M_SECONDS))
            .tps5m(clock.tpsOver(WINDOW_5M_SECONDS))
            .tps15m(clock.tpsOver(WINDOW_15M_SECONDS))
            .msptAvg(clock.histogram.avgMs())
            .msptP95(clock.histogram.p95Ms())
            .msptP99(clock.histogram.p99Ms())
            .source(source)
            .build()
    }

    private companion object {

        /** 1 分钟窗口(秒)。 */
        private const val WINDOW_1M_SECONDS = 60

        /** 5 分钟窗口(秒)。 */
        private const val WINDOW_5M_SECONDS = 300

        /** 15 分钟窗口(秒)。 */
        private const val WINDOW_15M_SECONDS = 900
    }
}
