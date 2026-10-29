package top.wcpe.mc.plugin.serverprobe.core.bridge.event

/**
 * 可靠事件位点。
 *
 * 收成一个类型而不是两个裸 `long` 参数，有两个实质理由：
 * 1. **不可混淆**：`(seq, seqEpoch)` 相邻且同类型，传反了编译器不会拦——而反了以后
 *    下游的缺口判定会全错，且症状是"看起来一切正常但永远对不上账"。
 * 2. **可缺省**：可丢事件**没有**位点，用 [NONE] 表达比 `0L, 0L` 更难被误写成"第 0 条事件"。
 *
 * @property seq 该域的位点（per-domain，严格递增）；0 表示无位点
 * @property epoch 位点所属 epoch（见 `EventSeqAllocator` 的说明）；0 表示无位点
 */
data class EventPosition(val seq: Long, val epoch: Long) {

    /** 是否携带位点。 */
    val present: Boolean
        get() = seq > 0L

    companion object {

        /** 无位点（可丢事件 / 非事件帧）。 */
        val NONE = EventPosition(0L, 0L)
    }
}

/**
 * 业务事件的实际发送出口（内部接缝，不对外）。
 *
 * 抽成接口的唯一目的是**可测**：`BusinessEventSinkImpl` 只做策略判定（选哪个入口 → 哪种可丢性、
 * `dedupKey` 是否必需），真正的"拼帧 + 提交"由本接口的实现完成。
 * 这样 §5 的"滤镜先于容量"可以用真实 [top.wcpe.mc.plugin.serverprobe.core.bridge.FrameSender]
 * 跑 1000 次而不需要一条真实 WebSocket 连接。
 *
 * 实现须**永不抛、永不阻塞**（可能在业务线程与事件池线程上被调用）。
 */
interface BusinessEventPublisher {

    /**
     * 发出一条业务事件。
     *
     * @param domain 业务域（非空；调用方已校验）。
     * @param dedupKey 去重键；不可丢事件必非空，可丢事件可为空串。
     * @param topic 主题；null 或空串表示未声明主题。
     * @param fields 结构化字段。
     * @param policy 可丢性（由调用方入口决定，不可由本方法推断）。
     * @param position 可靠事件位点；可丢事件传 [EventPosition.NONE]，此时帧与 payload 都不带 `seq`。
     *   为什么把它做成**带默认值**的参数而不是另一个方法名：既有的调用点（含基准与单测）
     *   一行都不用改，而"位点"本就是这条事件的一个可选属性，不是另一种事件。
     * @return true=已受理（含"被主题滤镜判定为无需上送"）；false=通道饱和 / 探针不可用。
     */
    // 参数多是有意的：这 6 个字段就是一条业务事件的全部**协议要素**（域、去重键、主题、
    // 载荷、可丢性、位点），再包一层 DTO 只会让每个调用点先构造一个短命对象，
    // 而这是每帧都要走的热路径。位点带默认值，既有调用点一行都不用改。
    @Suppress("LongParameterList")
    fun publish(
        domain: String,
        dedupKey: String,
        topic: String?,
        fields: Map<String, String>,
        policy: EventPolicy,
        position: EventPosition = EventPosition.NONE,
    ): Boolean
}
