package top.wcpe.mc.plugin.serverprobe.core.bridge

/**
 * 一次上行帧发送的结果。
 *
 * 把"桥断开"与"发送失败"分成两种语义是刻意的：
 * - [NotConnected]：探针此刻没有桥连接（独立模式未开桥、或正在重连）。周期指标推送**静默跳过**，
 *   因为这是协议规格 §3.6 R1 已确认接受的取舍（桥断即无指标），不算故障、不该刷告警；
 * - [Failed]：连接在、但这一帧没发出去（写锁忙/写超时/连接异常）。这属于需要告警的失败，
 *   由调用方按"错误变化才告警"的节奏打 WARN。
 */
internal sealed class UplinkOutcome {

    /**
     * 已交给发送队列（**不代表已经写进 socket**）。
     *
     * 发送器有自己的写线程与背压策略：这里只表示“帧被接纳”，是否真正落到连接上由发送器内部计数。
     * 回执文案因此不应承诺“已发送成功”。被滤镜丢弃（`FILTERED_OUT`）同样归入本分支：
     * 帧被接纳，但根本不会上送。
     */
    internal object Sent : UplinkOutcome()

    /** 当前没有桥连接；指标自然停止推送（绝不补发旧值）。 */
    internal object NotConnected : UplinkOutcome()

    /**
     * 发送失败。
     *
     * @property reason 中文失败原因（用于"错误变化才告警"的比对与日志）。
     */
    internal data class Failed(val reason: String) : UplinkOutcome()
}
