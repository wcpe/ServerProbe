package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 在线玩家 ping 分布的一个区间桶(FR2.4)。
 *
 * 描述 RTT 落在某区间内的在线玩家数,便于一眼看出网络质量分布。
 * 区间定义:[minMs, maxMs),maxMs 为 -1 表示无上限(兜底桶)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class PingBucket {
    /** 区间标签(如 `&lt;50ms`、`50-100ms`、`500ms+`),由采集侧按固定分桶规则生成。 */
    String label;
    /** 区间下限(毫秒,含)。 */
    int minMs;
    /** 区间上限(毫秒,不含);-1 表示无上限。 */
    int maxMs;
    /** 落在该区间的在线玩家数。 */
    int count;
}
