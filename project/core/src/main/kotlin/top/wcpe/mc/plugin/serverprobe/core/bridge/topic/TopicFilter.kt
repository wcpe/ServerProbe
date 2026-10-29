package top.wcpe.mc.plugin.serverprobe.core.bridge.topic

import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFilter
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame

/**
 * 主题过滤滤镜（事件主题订阅规格 §3.5）。
 *
 * ## 位置
 * 注册为 [OutboundFilter]（`order = 200`），由发送器在**容量判定之前**执行。
 * 顺序不可调换：若先做容量判定，订阅越多、无关事件越会先把可靠队列占满，
 * 于是 `emitReliable` 开始返回 false，调用方把一次"只是不关心"的噪音事件当成业务失败。
 * 监控上识别写反的特征是 `topics.count` 上升、`reliableRejected` 上升，而 `filteredOut` 恒为 0。
 *
 * ## 只读两个既有字段
 * 本滤镜只读 `OutboundFrame` 的 [OutboundFrame.topic] 与 [OutboundFrame.isBusinessDomain]，
 * 不新增字段、不改字段语义，也不看 `event` 名——避免将来新增 event 子类型时漏加白名单造成漏过滤。
 *
 * ## 关键决策：未声明 topic 的业务事件仍然放行
 * 若拦截，任何还没适配 topic 的 Provider 会**静默丢事件**，这比多传数据严重得多。
 * JM 想精确过滤时应让 Provider 声明 topic。
 *
 * ## 轻量
 * 全程仅字符串比较与一次集合遍历，跑在事件发送路径上，不得引入 IO 或锁等待。
 *
 * @param registry 订阅注册表（与 `core.subscribe`、`bridge_status.topics` 同一实例）。
 */
class TopicFilter(private val registry: TopicSubscriptionRegistry) : OutboundFilter {

    /** 执行顺序：主题过滤早于后续任何滤镜，且必在容量判定之前。 */
    override val order: Int = ORDER

    override fun apply(frame: OutboundFrame): OutboundFrame? {
        // ① 非业务域（玩家 / 系统 / 治理 core / 元域 jbis）一律不受主题过滤：
        //    否则订阅一开，治理回执与元查询会把自己过滤掉。
        if (!frame.isBusinessDomain) return frame
        // ② 无订阅 → 全量放行（向后兼容）。
        if (!registry.hasSubscriptions()) return frame
        // ③ 未声明 topic → 放行（防静默丢失，见类注释的关键决策）。
        val topic = frame.topic
        if (topic.isNullOrEmpty()) return frame
        // ④ 有订阅且声明了 topic → 只放行匹配的；null 表示无需上送，由发送器计 filteredOut。
        return if (registry.matchesAny(topic)) frame else null
    }

    companion object {
        /** 滤镜序位：主题过滤（权威分配见事件主题订阅规格 §3.5）。 */
        const val ORDER = 200
    }
}
