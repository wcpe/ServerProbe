package top.wcpe.mc.plugin.serverprobe.core.bridge.event

import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame

/**
 * 事件可丢性策略（事件主题订阅规格 §3.3）。
 *
 * 由**调用方选择的入口**唯一决定，**绝不**从 `domain` / `event` 名推断：
 * 一张"按域名推断可丢"的规则表会让第三方 Provider 在无意识中丢掉结算事件。
 * 也因此本枚举只有两个取值——一旦选定不可逆，`emitReliable` 不会因队列压力降级为可丢，
 * `emit` 也不会升级为可靠。
 *
 * @property priority 对应的发送优先级（决定队列与失败后果）。
 * @property requiresDedupKey 是否强制要求非空 `dedupKey`（可靠通道靠它让 JM 幂等去重）。
 */
enum class EventPolicy(
    val priority: OutboundFrame.Priority,
    val requiresDedupKey: Boolean,
) {

    /** `emit`：可丢，允许用丢弃换吞吐。 */
    BEST_EFFORT(OutboundFrame.Priority.BEST_EFFORT_EVENT, requiresDedupKey = false),

    /** `emitReliable`：不可丢，必须带 dedupKey（spill 回放按它去重）。 */
    RELIABLE(OutboundFrame.Priority.RELIABLE_EVENT, requiresDedupKey = true),
}
