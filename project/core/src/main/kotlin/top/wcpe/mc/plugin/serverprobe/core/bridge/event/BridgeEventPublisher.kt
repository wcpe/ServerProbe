package top.wcpe.mc.plugin.serverprobe.core.bridge.event

import top.wcpe.mc.plugin.serverprobe.core.bridge.FrameSender
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame
import top.wcpe.mc.plugin.serverprobe.core.bridge.SubmitOutcome
import top.wcpe.mc.plugin.serverprobe.core.json.Json

/**
 * 基于 [FrameSender] 的业务事件发布器。
 *
 * ## 主题同源（不可分叉）
 * `topic` 只算一次（`effectiveTopic`），随后**同时**写入 payload 与 [OutboundFrame.topic]。
 * 滤镜读的是后者、对端看的是前者，两者不一致时过滤行为将无法解释，因此这里刻意不让两个
 * 写入口各自取值。
 *
 * ## 可丢性不推断
 * 优先级完全来自调用方传入的 [EventPolicy]，本类不看 `domain`、不看 `event` 名。
 *
 * ## 结果映射（规格 §3.5）
 * - 已入队 / 已落盘 → `true`；
 * - **被主题滤镜判定为无需上送 → `true`**（已受理，只是无需送达）；
 * - 可丢帧被丢、可靠帧被拒、超大无法分片 → `false`。
 *
 * ## 永不抛
 * 供业务线程调用：拼帧、序列化、提交的任何异常（含 `RejectedExecutionException`）
 * 一律消化为 `false`，绝不让异常冒进业务代码。
 *
 * ## 的位点透传
 * `seq` / `seqEpoch` 同时写进**帧字段**与 **payload**：
 * 前者供本地滤镜（主题订阅）与落盘关联，后者是对端真正读到的地方——
 * 帧在写 socket 时只序列化 `payload`（见 [FrameWriter]），帧字段不出网络。
 * 两者取值必须同源（与 `topic` 同理，见上文"主题同源"）。
 *
 * @param sender 当前发送器；桥未启动时为 null（此时返回 false）。
 * @param instance 实例标识供应器（每帧现取，重连后无需重建）。
 * @param encode JSON 序列化器；默认走 [Json] 门面，单测注入纯文本实现以脱离 nightconfig。
 */
class BridgeEventPublisher(
    private val sender: () -> FrameSender?,
    private val instance: () -> String,
    private val encode: (Any?) -> String = Json::encode,
) : BusinessEventPublisher {

    override fun publish(
        domain: String,
        dedupKey: String,
        topic: String?,
        fields: Map<String, String>,
        policy: EventPolicy,
        position: EventPosition,
    ): Boolean {
        // 用 try/catch 而非 runCatching：后者会为每次调用构造 Result 对象，
        // 而本方法是业务方最高频的调用（每条约 17µs 里 Result 虽小，但没有理由付）。
        return try {
            val frameSender = sender() ?: return false
            // 主题一次算出，payload 与帧字段共用同一个值（见类注释"主题同源"）。
            val effectiveTopic = topic?.takeIf { it.isNotEmpty() }
            // 事件名同样只算一次：它要写入帧字段与 payload 两处，
            // 重复拼 `"${domain}_change"` 等于每次多一次字符串分配。
            val eventName = eventName(domain)
            val frame = OutboundFrame(
                type = OutboundFrame.TYPE_EVENT,
                event = eventName,
                domain = domain,
                payload = encode(
                    payloadOf(EventEnvelope(domain, eventName, dedupKey, effectiveTopic, fields), position),
                ),
                priority = policy.priority,
                topic = effectiveTopic,
                // 位点同时写入帧字段（本地可读）与 payload（对端可读），两者同源。
                eventSeq = position.seq,
                seqEpoch = position.epoch,
            )
            frameSender.submit(frame).accepted()
        } catch (_: Throwable) {
            // 契约：永不抛。任何异常（含 RejectedExecutionException）都降级为 false。
            false
        }
    }

    /** 拼 payload 所需的固定字段（收成一个对象，避免 `payloadOf` 的参数表越滚越长）。 */
    private class EventEnvelope(
        val domain: String,
        val eventName: String,
        val dedupKey: String,
        val topic: String?,
        val fields: Map<String, String>,
    )

    /**
     * 构造事件 payload；`topic` 仅在真正声明时出现（缺失 = 非主题事件）。
     *
     * `seq` / `seqEpoch` 同理：**只在有值时才出现**。可丢事件没有位点，
     * 写一个 `seq=0` 会让下游误以为"这条事件是第 0 条"而不是"这条事件没有位点"。
     */
    private fun payloadOf(envelope: EventEnvelope, position: EventPosition): Map<String, Any?> {
        val payload = LinkedHashMap<String, Any?>(PAYLOAD_SLOTS)
        payload["type"] = OutboundFrame.TYPE_EVENT
        payload["event"] = envelope.eventName
        payload["instance"] = instance()
        payload["timestamp"] = System.currentTimeMillis()
        payload["domain"] = envelope.domain
        payload["dedupKey"] = envelope.dedupKey
        if (envelope.topic != null) payload["topic"] = envelope.topic
        if (position.present) {
            payload["seq"] = position.seq
            payload["seqEpoch"] = position.epoch
        }
        payload["data"] = withoutEmptyValues(envelope.fields)
        return payload
    }

    /**
     * 去掉空值字段；**没有空值时原样返回入参**。
     *
     * 原先无条件 `fields.filterValues { it.isNotEmpty() }`：即使全部非空也会新建 LinkedHashMap
     * 并逐个 put——而"字段都有值"才是常态。这里先扫一遍，一律非空就直接复用原 Map（零分配）。
     */
    private fun withoutEmptyValues(fields: Map<String, String>): Map<String, String> {
        var hasEmpty = false
        for (value in fields.values) {
            if (value.isEmpty()) {
                hasEmpty = true
                break
            }
        }
        if (!hasEmpty) return fields
        val filtered = LinkedHashMap<String, String>(fields.size)
        for ((key, value) in fields) {
            if (value.isNotEmpty()) filtered[key] = value
        }
        return filtered
    }

    /** 事件名沿用既有约定：`<domain>_change`。 */
    private fun eventName(domain: String): String = "${domain}_change"

    /** 提交结果 → 是否已受理。`FILTERED_OUT` **不算失败**。 */
    private fun SubmitOutcome.accepted(): Boolean = when (this) {
        SubmitOutcome.QUEUED, SubmitOutcome.FILTERED_OUT -> true
        SubmitOutcome.DROPPED, SubmitOutcome.REJECTED, SubmitOutcome.TOO_LARGE -> false
    }

    private companion object {
        /**
         * payload 的槽位数：type / event / instance / timestamp / domain / dedupKey / data = 7，
         * 声明 topic 时为 8，带位点（seq + seqEpoch）时为 10。按上界预分配，
         * 避免 LinkedHashMap 在扩容时重建整条链。
         */
        const val PAYLOAD_SLOTS = 10
    }
}
