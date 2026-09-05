package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * Folia 已观测 region 的真实 tick 指标。
 *
 * 仅包含采样期内含在线玩家的 region。region 合并、拆分或中心坐标变化时会生成新的
 * {@link #regionSequence}，避免把不同的运行实体混入同一条时间序列。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class ObservedRegionMetrics {
    /** 世界名称。 */
    String worldName;
    /** Folia 运行期分配的 region id，仅在当前服务器进程内有效。 */
    long foliaRegionId;
    /** 本次进程内单调递增的观测 region 序号。 */
    long regionSequence;
    /** 恒为 true，明确本条仅代表已观测 region，不代表世界全部 region。 */
    boolean observed;
    /** region 当前中心区块 X 坐标。 */
    int centerChunkX;
    /** region 当前中心区块 Z 坐标。 */
    int centerChunkZ;
    /** 最近一次真实 tick 时所在 region 的在线玩家数。 */
    int playerCount;
    /** 窗口内已完成真实 tick 的样本数。 */
    long sampleCount;
    /** 窗口内 TPS 平均值；首个 tick 尚无间隔时可为 null。 */
    Double tpsAvg;
    /** 窗口内 TPS p95；无有效 TPS 间隔时为 null。 */
    Double tpsP95;
    /** 窗口内 TPS p99；无有效 TPS 间隔时为 null。 */
    Double tpsP99;
    /** 窗口内 MSPT 平均值。 */
    Double msptAvg;
    /** 窗口内 MSPT p95。 */
    Double msptP95;
    /** 窗口内 MSPT p99。 */
    Double msptP99;
    /** 最近一次真实 tick 完成时刻（epoch 毫秒）。 */
    long lastSeenMs;
}
