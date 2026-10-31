package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 指标帧推送的执行体（协议规格 §3.4.5）：取快照 → 编码 → 交给上行通道。
 *
 * 与线程、IOC 装配刻意解耦，只依赖三个函数式入参，故可被单测直接驱动：
 * 帧内容、`sequence` 单调性、失败语义与"错误变化才告警"的节奏都能逐一断言。
 *
 * ## 绝不重发旧值
 * 快照为 null（探针尚无任何采样）或桥未连接时**不推帧**：协议规格 §3.6 R1 要求桥断时前端明确
 * 显示"指标不可用"，不得静默给旧值。宁可少一帧，也不把上一份快照当新的发出去。
 *
 * ## 失败告警去重
 * 连续失败只在"原因发生变化"时打 WARN（例如从写超时变成连接异常），同一原因不重复刷屏；
 * 恢复时打一次 INFO 清理状态。
 *
 * @property snapshots 取最新指标快照（生产为 `ProbeReadApi.latestSnapshot()`）。
 * @property instance 当前上行实例标识（用于 `dedupKey`）。
 * @property send 上行一帧帧树；生产侧在这里做 JSON 编码并写入桥连接。
 * @property clockSeconds 当前时刻（epoch 秒）；仅供单测固定时间。
 */
internal class MetricPusher(
    private val snapshots: () -> MetricSnapshot?,
    private val instance: () -> String,
    private val send: (Map<String, Any?>) -> UplinkOutcome,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    /** 进程内单调递增序号（协议规格 §3.4.4 的 `sequence`）。 */
    private val sequence = AtomicLong(0)

    /** 最近一次未被恢复的失败原因；null 表示当前无失败。 */
    private val failure = AtomicReference<String?>(null)

    /** 最近一次仍未恢复的失败原因（供 `metrics_now` 的失败回执取用）；无失败时为 null。 */
    val lastFailureReason: String?
        get() = failure.get()

    /**
     * 推一帧。
     *
     * @return 送达时返回本次 `sequence`；无快照 / 桥未连接 / 发送失败时返回 null。
     */
    fun pushOnce(): Long? {
        val snapshot = readSnapshot() ?: return null
        val seconds = clockSeconds()
        val current = sequence.incrementAndGet()
        val frame = MetricSnapshotFrame.of(instance(), seconds, current, snapshot)
        return when (val outcome = send(frame)) {
            UplinkOutcome.Sent -> {
                onSent()
                current
            }
            // 桥断开：指标自然停止推送，不告警（已接受的取舍），更不重发旧值
            UplinkOutcome.NotConnected -> null
            is UplinkOutcome.Failed -> {
                onFailed(outcome.reason)
                null
            }
        }
    }

    /** 取快照；读取异常一律降级为"无快照"，探针不因推送链路拖累宿主。 */
    // 取快照走的是既有采集管线，任何异常都只降级为不推帧，故广捕是有意为之
    @Suppress("TooGenericExceptionCaught")
    private fun readSnapshot(): MetricSnapshot? = try {
        snapshots()
    } catch (e: Exception) {
        onFailed("读取指标快照失败:${e.message}")
        null
    }

    /** 成功：清理失败状态，必要时提示一次恢复。 */
    private fun onSent() {
        val previous = failure.getAndSet(null)
        if (previous != null) {
            ProbeLogger.info("指标 WS 推送已恢复")
        }
    }

    /** 失败：仅在原因变化时 WARN，避免同一错误每周期刷屏。 */
    private fun onFailed(reason: String) {
        val previous = failure.getAndSet(reason)
        if (previous != reason) {
            ProbeLogger.warn("指标 WS 推送失败:$reason(同一原因不再重复告警)")
        }
    }
}
