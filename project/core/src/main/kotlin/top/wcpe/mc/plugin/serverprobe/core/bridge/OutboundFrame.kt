package top.wcpe.mc.plugin.serverprobe.core.bridge

/**
 * 一条待发出的上行帧(线路治理地基,见 ADR `bridge-line-hardening` 规格)。
 *
 * 桥是**单条 WebSocket** 承载全部流量,故每一帧都必须携带足够信息让发送器做优先级、
 * 可丢性、主题过滤与分片判断,而不必回头查业务语义。
 *
 * ## 字段集是唯一权威
 * 后续功能(主题订阅、帧分片)只**读**本类字段,不各自新增字段;确需扩展时改本节并同步所有规格。
 *
 * ## 不可变
 * 构造后不再变更;滤镜链按需返回新实例,避免跨线程可见性问题。
 *
 * @property type 帧类型:`event` / `command_result` / `hello` / `ping`。
 * @property event event 子类型(`command_result` / `connected` / `<domain>_change` …);非 event 帧为空串。
 * @property domain 业务域;非业务帧为空串。
 * @property payload 已序列化的完整 JSON 文本。
 * @property priority 发送优先级,决定走高优先级队列还是可丢队列。
 * @property topic 主题(供主题订阅过滤);无主题为 null。
 * @property requestId 关联回执标识(分片超限时用于回写失败回执);无则为空串。
 * @property groupId 分片分组 ID;非分片为 null。
 * @property seq 分片序号,从 0 开始;非分片为 0。
 * @property total 分片总数;非分片为 1。
 * @property ofType 分片对应的原始 event 子类型;非分片为 null。
 * @property eventSeq 可靠事件的序号(**per-domain**);非可靠事件或可丢事件为 0。
 *   与 [seq] 无关:[seq] 是**分片**序号,本字段是**事件位点**。
 * @property seqEpoch 事件序号的 epoch;非可靠事件为 0。
 *   下游遇到 epoch 变化时知道需要触发对账。
 */
// 参数多是有意的：这是桥出站帧的**唯一权威字段集**（spec 明确要求单一真源），
// 拆成若干子结构只会让 FrameSender/分片/过滤三处各自拼装，反而失去"一处定义"的保证。
@Suppress("LongParameterList")
class OutboundFrame(
    val type: String,
    val event: String = "",
    val domain: String = "",
    val payload: String,
    val priority: Priority = Priority.BEST_EFFORT_EVENT,
    val topic: String? = null,
    val requestId: String = "",
    val groupId: String? = null,
    val seq: Int = 0,
    val total: Int = 1,
    val ofType: String? = null,
    val eventSeq: Long = 0L,
    val seqEpoch: Long = 0L,
) {

    /**
     * 是否业务域事件(供主题过滤判定)。
     *
     * 保留域(内建治理 `core`、元域 `jbis`)与空域都不算业务事件——它们不受主题订阅过滤,
     * 否则一旦订阅生效,治理回执与元查询就会自身被过滤掉。
     */
    val isBusinessDomain: Boolean
        get() = domain.isNotEmpty() && domain != RESERVED_CORE_DOMAIN && domain != RESERVED_JBIS_DOMAIN

    /** 是否分片帧。 */
    val isChunk: Boolean
        get() = groupId != null

    /** 是否为命令回执(发送器据此走高优先级队列)。 */
    val isCommandResult: Boolean
        get() = type == TYPE_COMMAND_RESULT || event == TYPE_COMMAND_RESULT

    /**
     * 是否携带可靠事件位点。
     *
     * 只有可靠事件会分配 `eventSeq`；可丢事件恒为 0，因此可以据此判断"这条事件该不该有 seq"。
     */
    val hasEventSeq: Boolean
        get() = eventSeq > 0L

    /**
     * 发送优先级。
     *
     * **可丢性由优先级唯一决定**([droppable]),不再保留独立的 `reliable` 字段——
     * 那样会出现 `CONTROL + reliable` 这类无意义组合,且判定要看两个字段。
     */
    enum class Priority {

        /** 控制帧(ping/pong/hello):不入队,走旁路直接写,避免被分片组拖住导致对端误判失联。 */
        CONTROL,

        /** 命令回执:最高队列优先级,绝不被事件淹没。 */
        COMMAND_RESULT,

        /** 不可丢事件(业务状态变更、结算):有界队列 + 落盘兜底。 */
        RELIABLE_EVENT,

        /** 可丢事件(玩家 join/quit/chat 采样):队列满即丢并计数。 */
        BEST_EFFORT_EVENT;

        /** 可丢性:仅 [BEST_EFFORT_EVENT] 可丢。 */
        val droppable: Boolean
            get() = this == BEST_EFFORT_EVENT
    }

    companion object {

        /** 帧类型:命令回执。 */
        const val TYPE_COMMAND_RESULT = "command_result"

        /** 帧类型:事件。 */
        const val TYPE_EVENT = "event"

        /** 帧类型:握手。 */
        const val TYPE_HELLO = "hello"

        /** 内建治理域:不属于业务域,不受主题订阅过滤。 */
        private const val RESERVED_CORE_DOMAIN = "core"

        /** JBIS 元域:同上。 */
        private const val RESERVED_JBIS_DOMAIN = "jbis"
    }
}
