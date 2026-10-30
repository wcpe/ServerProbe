package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink
import top.wcpe.mc.plugin.serverprobe.api.bridge.EmitReceipt
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.BusinessEventPublisher
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.EventPolicy
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.EventPosition
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 业务事件上报实现（事件主题订阅规格 §3.2 / §3.3）。
 *
 * ## 只做策略判定，不碰线路
 * 本类决定两件事：
 * 1. 走哪个入口 → 哪种可丢性（[EventPolicy]，**由调用方选择的入口唯一决定**，不推断 domain 语义）；
 * 2. 可靠通道要求非空 `dedupKey`（spill 回放是"至少一次"，去重全靠 JM 按 `(domain, dedupKey)` 幂等）。
 * 拼帧与提交由 [BusinessEventPublisher] 完成，因此本类可脱离网络单测。
 *
 * ## 空 dedupKey 不降级
 * `emitReliable` 传空 `dedupKey` → 返回 `false` + **WARN 一次**，绝不静默降级为可丢：
 * 降级会让调用方误以为事件可靠，而它实际已经没有任何回放兜底保护。
 * 玩家事件（`player_join`/`chat`）没有业务幂等键，只能用 `emit`。
 *
 * ## 永不抛
 * 契约要求所有方法永不抛异常。内部任何异常（含发布器抛出的
 * `RejectedExecutionException`）都消化为 `false`——异常冒到业务线程会让调用方
 * 在业务逻辑里处理一个本不该存在的失败模式。
 *
 * ## 的 seq 分配边界
 * **只有 `emitReliable` 分配 seq**，`emit` 一个都不占：
 * - 可丢事件一旦被丢弃，若它已占过号，可靠序列里就会留下永久补不上的空洞，
 *   而"有缺口 = 真的漏了"正是下游唯一的完整性判据；
 * - 补发入口（带 seq 的重载）只把调用方给的号透传出去，**不推进**探针的分配序列。
 *
 * 分配是 `AtomicLong.getAndIncrement`（见 [EventSeqAllocator.next]），per-domain 无锁递增；
 * 落盘续接由同一实例负责，本类不碰 IO。
 */
@Service
class BusinessEventSinkImpl : BusinessEventSink {

    /**
     * 桥客户端：事件的实际发送出口（取当前发送器与实例标识）。
     *
     * 为什么注入具体类而不是接口：IoC 静态分析按 `@Service` **暴露的类型**注册 Bean——
     * 实现了接口的类只以该接口暴露（本类以 [BusinessEventSink] 暴露，给门面装配器用），
     * 而 [BridgeClient] 未实现任何接口，故以自身类型暴露、可直接注入。
     */
    @Inject
    lateinit var bridgeClient: BridgeClient

    /**
     * 发布器覆盖（供单测替换，不参与生产装配）。
     *
     * 生产路径由 [bridgeClient] 派生；单测用它注入一个只连真实 `FrameSender`、
     * 不碰运行期 JSON 库与真实连接的发布器。
     */
    @Volatile
    internal var publisherOverride: BusinessEventPublisher? = null

    /** 「可靠通道缺 dedupKey」的告警去重标记（只提示一次，避免被事件量放大成日志风暴）。 */
    private val missingDedupWarned = AtomicBoolean(false)

    /**
     * 事件序号分配器覆盖（供单测替换，不参与生产装配）。
     *
     * 生产路径取 [bridgeClient] 持有的分配器——**必须是同一个实例**，
     * 否则两处各自持有一份序列，序号会互相撞号。未注入桥时（单测直接构造本类）
     * 落到 [fallbackAllocator]：不持久化，从 1 起按域递增，测 seq 语义不需要磁盘。
     */
    @Volatile
    internal var seqAllocatorOverride: EventSeqAllocator? = null

    /** 无桥可依赖时的分配器（不持久化，见 [seqAllocatorOverride]）。 */
    private val fallbackAllocator = EventSeqAllocator()

    /** WARN 出口；单测可替换以断言"只告警一次"（生产用 [ProbeLogger]）。 */
    @Volatile
    internal var warn: (String) -> Unit = ProbeLogger::warn

    override fun emit(domain: String?, dedupKey: String?, topic: String?, fields: Map<String, String>?): Boolean =
        // 可丢事件**不分配** seq（spec §5）：分配了就会在可靠序列里留下永久空洞。
        publish(Outgoing(EventPolicy.BEST_EFFORT, domain, dedupKey, topic, fields), positionOf = null)

    override fun emitReliable(domain: String?, dedupKey: String?, topic: String?, fields: Map<String, String>?): Boolean {
        // 可靠通道只接受带非空 dedupKey 的业务事件：spill 回放的重发只能靠它去重。
        if (dedupKey.isNullOrEmpty()) {
            warnMissingDedup()
            return false
        }
        // 域无效时不必分配：否则一个拼错的域名会白占一个号，在序列里留下假缺口。
        if (domain.isNullOrEmpty()) return false
        // 分配推迟到"确认发布器可用"之后（见 [publish] 的 positionOf）：
        // 探针未就绪时一个号都不该被消耗，否则每次失败的早期上报都会在序列里留一个假空洞。
        return publish(Outgoing(EventPolicy.RELIABLE, domain, dedupKey, topic, fields)) {
            EventPosition(allocator().next(domain), allocator().currentEpoch())
        }
    }

    override fun emitReliable(
        domain: String?,
        dedupKey: String?,
        topic: String?,
        fields: Map<String, String>?,
        seq: Long,
    ): Boolean {
        if (dedupKey.isNullOrEmpty()) {
            warnMissingDedup()
            return false
        }
        if (domain.isNullOrEmpty()) return false
        // `<= 0` 与"未指定"无法区分，且下游会把它当成一个真实的位点，故直接拒绝。
        if (seq <= 0L) return false
        // 让分配器知道该域已经用到了这个号：否则后续自动分配会从更小的号继续，下游会看到序号倒退。
        // 注意"抬高上限"与"不推进序列"在这里并不矛盾——见 EventSeqAllocator.observe 的说明。
        allocator().observe(domain, seq)
        return publish(Outgoing(EventPolicy.RELIABLE, domain, dedupKey, topic, fields)) {
            EventPosition(seq, allocator().currentEpoch())
        }
    }

    /**
     * 强语义入口：可丢事件带原因的受理凭据。
     *
     * <p>与 [emit] 共用同一发送路径，只把“为什么没受理”与“是否值得重试”一并返回。</p>
     */
    override fun emitReceipt(
        domain: String?,
        dedupKey: String?,
        topic: String?,
        fields: Map<String, String>?,
    ): EmitReceipt =
        // 可丢事件**不分配** seq，故凭据里永远没有 seq（与 [emit] 的语义一致）。
        publishReceipt(Outgoing(EventPolicy.BEST_EFFORT, domain, dedupKey, topic, fields), positionOf = null)

    /**
     * 强语义入口：不可丢事件带原因与序号的受理凭据。
     *
     * <p>序号直接回传给调用方（它会同时随帧上送），业务侧可拿它做本地水位线。
     */
    override fun emitReliableReceipt(
        domain: String?,
        dedupKey: String?,
        topic: String?,
        fields: Map<String, String>?,
    ): EmitReceipt {
        if (dedupKey.isNullOrEmpty()) {
            warnMissingDedup()
            return EmitReceipt.rejected(EmitReceipt.Reason.MISSING_DEDUP_KEY)
        }
        if (domain.isNullOrEmpty()) {
            return EmitReceipt.rejected(EmitReceipt.Reason.INVALID_DOMAIN)
        }
        // 分配推迟到“确认发布器可用”之后（见 [publishReceipt] 的 positionOf）：
        // 探针未就绪时一个号都不该被消耗，否则每次失败的早期上报都会在序列里留一个假空洞。
        return publishReceipt(Outgoing(EventPolicy.RELIABLE, domain, dedupKey, topic, fields)) {
            EventPosition(allocator().next(domain), allocator().currentEpoch())
        }
    }

    /**
     * 一条待发出事件的请求（收口 `publish` 的参数表）。
     *
     * @property policy 可丢性（由调用方入口唯一决定）
     * @property domain 业务域
     * @property dedupKey 去重键
     * @property topic 主题；null/空串表示未声明
     * @property fields 结构化字段
     */
    private class Outgoing(
        val policy: EventPolicy,
        val domain: String?,
        val dedupKey: String?,
        val topic: String?,
        val fields: Map<String, String>?,
    )

    /**
     * 统一发送入口。
     *
     * @param outgoing 待发出事件
     * @param positionOf 位点供应器；null 表示这条事件**不携带位点**（可丢通道）。
     *   只在确认发布器可用之后才求值，因此"探针未就绪"不会消耗序号。
     * @return false 表示"未受理"：域无效、探针不可用、通道饱和，或空 dedupKey。
     */
    private fun publish(outgoing: Outgoing, positionOf: (() -> EventPosition)?): Boolean =
        publishReceipt(outgoing, positionOf).isAccepted

    /**
     * 统一发送入口（强语义版）：返回带原因的受理凭据。
     *
     * <p>与 [publish] 同一实现，只多了一个“为什么没受理”的出口。
     * 保留 [publish] 是因为既有调用点（含单测与基准）都在用布尔值，
     * 而强语义是本轮新增的能力，不应当强迫所有调用点改签名。</p>
     *
     * @param outgoing 待发出事件
     * @param positionOf 位点供应器；null 表示不携带位点
     * @return 受理凭据，永不返回 null；异常也消化为不可受理而绝不向业务线程抛出
     */
    private fun publishReceipt(outgoing: Outgoing, positionOf: (() -> EventPosition)?): EmitReceipt {
        val domain = outgoing.domain
        if (domain.isNullOrEmpty()) {
            return EmitReceipt.rejected(EmitReceipt.Reason.INVALID_DOMAIN)
        }
        val current = publisher()
            ?: return EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE)
        // 用 try/catch 而非 runCatching：后者每次调用都会构造 Result 对象，
        // 而本方法处在业务方最高频的上报路径上，没有理由为一个异常兜底付这笔分配。
        return try {
            val position = positionOf?.invoke() ?: EventPosition.NONE
            // `fields ?: emptyMap()` 只在真的传 null 时才产生分配（emptyMap 是单例，实际零成本）。
            val accepted = current.publish(
                domain,
                outgoing.dedupKey ?: "",
                outgoing.topic,
                outgoing.fields ?: emptyMap(),
                outgoing.policy,
                position,
            )
            if (accepted) {
                if (position.seq > 0L) {
                    EmitReceipt.accepted(position.seq, position.epoch)
                } else {
                    EmitReceipt.accepted()
                }
            } else {
                EmitReceipt.rejected(EmitReceipt.Reason.REJECTED_SATURATED)
            }
        } catch (_: Throwable) {
            // 契约：永不抛。任何异常都消化为不可受理，绝不冒进业务线程。
            EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE)
        }
    }

    /**
     * 取当前序号分配器：优先单测覆盖，其次桥持有的唯一实例，最后本地兜底。
     *
     * 生产必须命中第二条——分配器与 [BridgeClient] 共用同一实例，序号与落盘续接才是一份状态。
     */
    private fun allocator(): EventSeqAllocator {
        seqAllocatorOverride?.let { return it }
        if (!::bridgeClient.isInitialized) return fallbackAllocator
        return bridgeClient.seqAllocator
    }

    /** 取当前发布器；桥未启动（或未完成注入）时为 null。 */
    private fun publisher(): BusinessEventPublisher? {
        publisherOverride?.let { return it }
        if (!::bridgeClient.isInitialized) return null
        return bridgeClient.eventPublisher()
    }

    /** 可靠通道缺 dedupKey 时告警一次（去重，不随事件量刷屏）。 */
    private fun warnMissingDedup() {
        if (missingDedupWarned.compareAndSet(false, true)) {
            warn("可靠事件缺少 dedupKey,已拒绝且不降级为可丢(重复告警已抑制);请改用 emit 或补上业务幂等键")
        }
    }
}
