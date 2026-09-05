package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * Folia 已观测 region 的世界级汇总。
 *
 * 分位与均值均基于全部 region 的实际 tick 样本合并计算，因此天然按样本数加权。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class ObservedRegionWorldMetrics {
    /** 世界名称。 */
    String worldName;
    /** 当前仍在保留窗口内的已观测 region 数量。 */
    int activeRegions;
    /** 各已观测 region 最近玩家数之和。 */
    int playerCount;
    /** 合并后的真实 tick 样本数。 */
    long sampleCount;
    /** 合并样本的 TPS 平均值。 */
    Double tpsAvg;
    /** 合并样本的 TPS p95。 */
    Double tpsP95;
    /** 合并样本的 TPS p99。 */
    Double tpsP99;
    /** 合并样本的 MSPT 平均值。 */
    Double msptAvg;
    /** 合并样本的 MSPT p95。 */
    Double msptP95;
    /** 合并样本的 MSPT p99。 */
    Double msptP99;
}
