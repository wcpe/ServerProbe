package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.sampler.ServerTickSampler

/**
 * 无全局 tick 数据采样器(source = [TickSampleSource.UNAVAILABLE])。
 *
 * 用于 Folia:其调度为 per-region,不存在全局 TPS/MSPT 概念,且相关全局 API 已被移除。
 * 本实现不调用任何被移除的全局 API,[sample] 直接返回六个数值字段全为 null 的 [TickSample],
 * 由呈现层据 source 标注 N/A。
 *
 * per-region 明细将于 M2 以纯增字段补充(见 [TickSample] KDoc)。
 */
class UnavailableTickSampler : ServerTickSampler {

    override fun getSource(): TickSampleSource = TickSampleSource.UNAVAILABLE

    /**
     * 返回全 N/A 的采样结果。
     *
     * @return 六个数值字段均为 null 的 [TickSample]。
     */
    override fun sample(): TickSample = TickSample.builder()
        .tps1m(null)
        .tps5m(null)
        .tps15m(null)
        .msptAvg(null)
        .msptP95(null)
        .msptP99(null)
        .source(source)
        .build()
}
