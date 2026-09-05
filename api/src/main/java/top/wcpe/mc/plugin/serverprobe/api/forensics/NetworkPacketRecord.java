package top.wcpe.mc.plugin.serverprobe.api.forensics;

/**
 * 一条可供同进程受信任调用方读取的网络包取证记录。
 *
 * 完整 IP 与载荷仅通过 FR8 只读 API 和已鉴权 Web 查询返回，Prometheus 不得使用本模型的敏感字段。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class NetworkPacketRecord {
    /** 数据库自增标识，同时作为同一时间戳内的稳定游标顺序。 */
    long id;
    /** 捕获时刻（epoch 毫秒）。 */
    long capturedAtMs;
    /** 相对当前服务器进程的传输方向。 */
    PacketDirection direction;
    /** 已关联玩家 UUID；未知时为 null。 */
    String playerUuid;
    /** 已关联玩家名称；未知时为 null。 */
    String playerName;
    /** 完整对端 IP；未知时为 null。 */
    String ip;
    /** 规范化后的包类型。 */
    String packetType;
    /** Plugin Message 频道；非插件消息时为 null。 */
    String channel;
    /** 原始完整包长度。 */
    int originalLength;
    /** 原始完整包的 SHA-256 小写十六进制摘要。 */
    String payloadSha256;
    /** 白名单命中的载荷 Base64；未捕获时为 null。 */
    String payloadBase64;
    /** 是否实际保存了白名单载荷。 */
    boolean payloadCaptured;
    /** 已保存载荷是否因上限被截断。 */
    boolean payloadTruncated;

    /** 供 Kotlin 与 JavaBean 消费方统一读取载荷保存标志。 */
    public boolean getPayloadCaptured() {
        return payloadCaptured;
    }

    /** 供 Kotlin 与 JavaBean 消费方统一读取截断标志。 */
    public boolean getPayloadTruncated() {
        return payloadTruncated;
    }
}
