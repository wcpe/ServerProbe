package top.wcpe.mc.plugin.serverprobe.api.forensics;

/**
 * 网络包取证的只读查询条件。
 *
 * 时间范围为必填项；实现会拒绝缺失、倒置范围和大于 100 条的单页请求，避免事故查询扩大为全表扫描。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class NetworkPacketQuery {
    /** 范围下界（epoch 毫秒，含），必填。 */
    Long sinceMs;
    /** 范围上界（epoch 毫秒，含），必填。 */
    Long untilMs;
    /** 方向过滤；null 表示不限。 */
    PacketDirection direction;
    /** 包类型精确过滤；null 表示不限。 */
    String packetType;
    /** 玩家 UUID 精确过滤；null 表示不限。 */
    String playerUuid;
    /** 玩家名称精确过滤；null 表示不限。 */
    String playerName;
    /** 完整 IP 精确过滤；null 表示不限。 */
    String ip;
    /** 上一页末条记录的捕获时间；首页为 null。 */
    Long cursorCapturedAtMs;
    /** 上一页末条记录的数据库标识；首页为 null。 */
    Long cursorId;
    /** 单页条数，范围为 1 至 100。 */
    int limit;
}
