package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.sampler.ServerTickSampler

/**
 * 老版本 / 纯 CraftBukkit 采样器(source = [TickSampleSource.NMS_RECENT_TPS])。
 *
 * 用于无 Paper `getTPS()` 的服务端:
 * - TPS:经 [TickReflection] 反射读取 NMS `MinecraftServer#recentTps`([1m, 5m, 15m]);
 * - MSPT:平台无现成数据,改由共享 [MsptHistogram](由 [TickClock] 逐 tick 喂入间隔样本)推算
 *   均值与 p95/p99。
 *
 * 优雅降级:recentTps 反射瞬时失败时三项 TPS 为 null;直方图尚无样本时 MSPT 三项为 null。
 *
 * @property histogram 由 [TickClock] 驱动的共享 MSPT 直方图。
 */
class LegacyTickSampler(
    private val histogram: MsptHistogram
) : ServerTickSampler {

    override val source: TickSampleSource = TickSampleSource.NMS_RECENT_TPS

    /**
     * 采样当前 tick 数据。
     *
     * @return Legacy 路径采样结果;不可用项为 null。
     */
    override fun sample(): TickSample {
        val tps = TickReflection.nmsRecentTps()
        return TickSample(
            tps1m = tps?.getOrNull(TpsArrayIndex.M1),
            tps5m = tps?.getOrNull(TpsArrayIndex.M5),
            tps15m = tps?.getOrNull(TpsArrayIndex.M15),
            msptAvg = histogram.avgMs(),
            msptP95 = histogram.p95Ms(),
            msptP99 = histogram.p99Ms(),
            source = source
        )
    }
}
