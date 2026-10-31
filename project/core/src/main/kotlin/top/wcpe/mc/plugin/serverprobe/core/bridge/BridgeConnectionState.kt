package top.wcpe.mc.plugin.serverprobe.core.bridge

/**
 * 插件桥与控制面的连接态(FR-45,协议规格 §3.4.3 的 `connection` 四态)。
 *
 * ## 四态必须区分的理由
 * 排障时"连不上"和"凭据被拒"是两条完全不同的处置链路:前者等网络恢复即可,
 * 后者必须重新签发凭据——把两者混成一句"离线",控制面就会在派发配置时对着一台
 * 其实是 token 被吊销的机器反复重试。
 *
 * ## 与配置取值的关系
 * 四态**都不改变配置源**:托管模式下,连接态如何都继续用 [top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigStore]
 * 里的缓存值跑(规格 §3.7 的"不得降级到文件配置"),这里只负责如实上报供对端判定。
 */
enum class BridgeConnectionState(
    /** 上行的线上取值(协议规格 §3.4.3)。 */
    val wireName: String,
) {

    /** 已连上控制面,上行可用。 */
    ONLINE("online"),

    /** 网络侧不可达(连接失败/断开/读超时),探针用托管缓存继续跑。 */
    DEGRADED("degraded"),

    /** 控制面明确拒绝凭据(握手返回 401/403),**不得**降级到文件配置。 */
    UNAUTHORIZED("unauthorized"),

    /** 未连接(尚未发起连接、已停止,或判定尚未完成)。 */
    DISCONNECTED("disconnected"),
}
