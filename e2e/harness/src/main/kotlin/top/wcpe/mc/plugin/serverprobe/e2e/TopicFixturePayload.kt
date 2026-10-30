package top.wcpe.mc.plugin.serverprobe.e2e

/**
 * 事件主题订阅 E2E两侧共用的**确定性**判据。
 *
 * ## 为什么两侧必须用同一份常量
 * 服务端（业务 Provider）按这些常量发出事件，fixture 侧按同一份常量断言"哪条该上行、哪条不该"。
 * 若两侧各自手写字符串，任何一处笔误都会让断言变成"比较两个不同错误值"——看似跑通、实则没校验。
 * 与 [ChunkFixturePayload] 采用同一套做法。
 *
 * ## 判据来源
 * `docs/specs/event-topic-subscription.md` §5：精确订阅 `activity.12345` 后，`activity.12345` 上行、
 * `activity.99999` 不上行；另有 §3.5「未声明 topic 的业务事件仍放行」与「被滤镜丢弃仍返回 true」。
 * 因此这里固定三条事件的主题与去重键，三条都走 `emitReliable`（不可丢），
 * 使"返回 true"只能来自"已受理"，不会与 best-effort 的丢弃语义混淆。
 */
internal object TopicFixturePayload {

    /** 业务域：与 harness 的 e2e Provider 注册域一致。 */
    const val DOMAIN = "e2e"

    /** 业务事件名：`<domain>_change`（与线上业务事件命名约定一致）。由 [DOMAIN] 推导，避免两处漂移。 */
    val BUSINESS_EVENT = "${DOMAIN}_change"

    /** Provider 动作名：一次性发出下面三条可靠事件。 */
    const val ACTION_EMIT_TOPIC = "emit-topic"

    /** fixture 下发的订阅表达式（规格 §5「精确订阅」）。 */
    const val SUBSCRIBE_EXPRESSION = "activity.12345"

    /** 命中订阅的主题：必须上行。与 [SUBSCRIBE_EXPRESSION] 同值才构成"精确命中"。 */
    const val HIT_TOPIC = "activity.12345"

    /** 未被订阅覆盖的主题：**一条都不许上行**。 */
    const val MISS_TOPIC = "activity.99999"

    /** 命中事件的去重键（可靠通道要求非空，规格 §3.3）。 */
    const val HIT_DEDUP_KEY = "e2e-topic-hit"

    /** 未命中事件的去重键。 */
    const val MISS_DEDUP_KEY = "e2e-topic-miss"

    /** 未声明 topic 事件的去重键。 */
    const val UNTOPICED_DEDUP_KEY = "e2e-topic-untopiced"

    /** 业务 payload 里自行声明主题的字段名：用于核对"帧上 topic"与"payload 内主题"同源同值。 */
    const val DECLARED_FIELD = "declared"

    /** 业务 payload 里标记本条事件用途的字段名。 */
    const val PURPOSE_FIELD = "purpose"

    const val PURPOSE_HIT = "hit"
    const val PURPOSE_MISS = "miss"
    const val PURPOSE_UNTOPICED = "untopiced"

    /** 受理结果的文本形式：三条事件的布尔值都必须是它。 */
    const val ACCEPTED = "true"

    /**
     * Provider 回执 output 的期望值：三条 `emitReliable` 全部返回 true。
     *
     * 其中 `miss=true` 是规格 §3.5 的关键语义——被主题滤镜判定为"无需上送"仍算**已受理**，
     * 因此"未命中事件没收到"不是通道失败或丢弃造成的。
     */
    const val EXPECTED_EMIT_OUTPUT = "hit=$ACCEPTED;miss=$ACCEPTED;untopiced=$ACCEPTED"

    /** 订阅回执 `output` 与 `bridge_status.topics.expressions` 的期望 JSON 文本（规格 §3.7）。 */
    const val EXPECTED_TOPICS_JSON = "[\"$SUBSCRIBE_EXPRESSION\"]"
}
