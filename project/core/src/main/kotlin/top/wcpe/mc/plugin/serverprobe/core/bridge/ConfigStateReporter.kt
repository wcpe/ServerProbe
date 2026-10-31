package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigRedaction
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigState
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigStore
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import java.util.Locale

/**
 * `config_state` 周期上报的执行体（FR-33，协议规格 §3.4.3）。
 *
 * 与线程、IOC 装配刻意解耦（同 [MetricPusher] 的做法），只依赖三个函数式入参，
 * 故可被单测直接驱动：字段含义、`connection` 四态判定与"从不抛"的语义都能逐一断言。
 *
 * ## cacheAgeSeconds 的语义
 * "距上次**成功应用**的时间"，而不是"缓存文件的 mtime"：托管模式下真正决定行为的是内存里那一代值，
 * 它可能是刚刚下发的（0s），也可能是启动时从缓存装载的（很大）。超阈值如何处理**不在探针**——
 * 规格明确"如实上报，由对端判定"，探针不自作主张把旧值判成失效（那会造成"探针自己把自己降级"）。
 *
 * ## 从不抛
 * 读状态 / 编码 / 上行任一步异常都只降级：状态上报是观测手段，绝不能成为事故源。
 *
 * @property instance 当前上行实例标识（与 hello / 指标帧同源）。
 * @property connection 当前桥连接态（四态，见 [BridgeConnectionState]）。
 * @property send 上行一帧帧树；生产侧编码为 JSON 并写入桥连接。
 * @property clockSeconds 当前时刻（epoch 秒）；仅供单测固定时间。
 */
internal class ConfigStateReporter(
    private val instance: () -> String,
    private val connection: () -> BridgeConnectionState,
    private val send: (Map<String, Any?>) -> UplinkOutcome,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    /**
     * 上报一次当前托管状态。
     *
     * @return 上行结果；桥未连接时返回 [UplinkOutcome.NotConnected]（调用方不视为失败）。
     */
    fun reportOnce(): UplinkOutcome {
        val now = clockSeconds()
        val frame = BridgeFrames.configState(instance(), now * MILLIS_PER_SECOND, payload(now))
        return send(frame)
    }

    /** 组装 `payload`（字段顺序与协议规格 §3.4.3 一致，便于逐字段比对）。 */
    private fun payload(nowSeconds: Long): Map<String, Any?> = linkedMapOf(
        "configVersion" to ManagedConfigState.configVersion(),
        "effective" to ManagedConfigRedaction.redact(ManagedConfigStore.currentValues()),
        "cacheAgeSeconds" to cacheAgeSeconds(nowSeconds),
        "mode" to modeName(),
        "connection" to connection().wireName,
        "capabilities" to BridgeFrames.capabilities(ControlPlaneRuntime.isManaged()),
        "lastError" to ManagedConfigState.lastError(),
    )

    /**
     * 距上次成功应用的秒数。
     *
     * 从未成功应用过（既无下发也无可用缓存）时返回 null——**不返回 0**：0 会被对端解读为
     * "刚刚生效过"，属于伪造健康状态；null 才是"无基线"的诚实表达。
     */
    private fun cacheAgeSeconds(nowSeconds: Long): Long? {
        val appliedAt = ManagedConfigState.lastAppliedAtSeconds() ?: return null
        return (nowSeconds - appliedAt).coerceAtLeast(0L)
    }

    /** 运行模式的线上取值（`standalone` / `managed`）。 */
    private fun modeName(): String = ControlPlaneRuntime.mode().name.lowercase(Locale.ROOT)

    private companion object {

        /** 毫秒/秒换算。 */
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
