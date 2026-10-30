package top.wcpe.mc.plugin.serverprobe.core.bridge.topic

import top.wcpe.mc.plugin.serverprobe.core.config.SubscribeSettings
import java.util.Collections

/**
 * 订阅变更结果：被拒的表达式逐条带原因，**绝不静默截断**。
 *
 * 布尔访问器在 Java 侧暴露为 `getApplied()`（Kotlin `val` 的默认访问器即 `get` 前缀），
 * 符合 ADR-13「值对象布尔访问器一律 `get` 前缀」，故不引入 `isXxx()` 形式。
 *
 * @property applied 是否整批应用成功。
 * @property effective 生效后的全量订阅（成功即新集合，失败即**变更前**的集合）。
 * @property rejected 被拒明细；成功时为空。
 */
class SubscriptionApplyResult(
    val applied: Boolean,
    val effective: Set<String>,
    val rejected: List<Rejection>,
) {

    /**
     * 单条被拒明细。
     *
     * @property expression 被拒的表达式原文。
     * @property reason 人可读原因（含上限值等可核对信息）。
     */
    class Rejection(val expression: String, val reason: String) {

        override fun toString(): String = "$expression（$reason）"
    }
}

/**
 * 主题订阅注册表（事件主题订阅规格 §3.6）。
 *
 * ## 为什么是"整批原子"
 * 订阅意图是一组：JM 说"这两个主题"时，只生效一个会让"我订阅了却收不到"极难诊断。
 * 因此任何一条不合格（超上限、超长、空、含空白）都**整批拒绝**，当前生效集合保持不变，
 * 回执里逐条列出被拒原因。同理不接受"截断到上限"——静默截断会让 JM 以为全部订阅成功。
 *
 * ## 并发模型
 * 写（`replace`/`add`/`clear`）在锁内完成，读（`hasSubscriptions`/`matchesAny`/`snapshot`）
 * 只读一个 `@Volatile` 不可变集合的引用。**读路径无锁无阻塞**，因为 [TopicFilter] 跑在
 * 事件发送路径上；锁只用于让"校验 + 提交"成为一个不可分割的动作。
 *
 * ## 上限来源
 * 用供应器 [settings] 而非构造期快照：`bridge.subscribe.*` 重载后无需重建注册表即可生效，
 * 单测也可注入极小上限来验证整批拒绝。
 *
 * @param settings 订阅参数供应器（数量上限、单表达式长度上限）。
 */
class TopicSubscriptionRegistry(
    private val settings: () -> SubscribeSettings = { SubscribeSettings() },
) {

    /** 当前生效集合（不可变、按字典序），整体替换以保证读侧不撕裂。 */
    @Volatile
    private var current: Set<String> = emptySet()

    /**
     * 原子替换订阅集合；空集合表示「无订阅」（全量上报）。
     *
     * @return 应用结果；`applied=false` 时 [SubscriptionApplyResult.effective] 为**变更前**集合。
     */
    fun replace(topics: Set<String>): SubscriptionApplyResult = applyTo(base = emptySet(), incoming = topics)

    /**
     * 增量添加；同样整批原子，拒绝时当前生效集合不变。
     *
     * 已存在的表达式视为幂等（不重复计数、不报错）。
     */
    fun add(topics: Set<String>): SubscriptionApplyResult = applyTo(base = current, incoming = topics)

    /** 清空订阅，恢复全量上报；幂等。 */
    fun clear() {
        current = emptySet()
    }

    /** 当前是否有订阅；false 表示全量上报（向后兼容）。 */
    fun hasSubscriptions(): Boolean = current.isNotEmpty()

    /**
     * 主题是否被任一订阅表达式覆盖。
     *
     * 无订阅时返回 false——调用方（[TopicFilter]）必须先判 [hasSubscriptions]，
     * 不能把"无订阅"当成"什么都不匹配"。
     */
    fun matchesAny(topic: String): Boolean {
        val expressions = current
        if (expressions.isEmpty() || topic.isEmpty()) return false
        return expressions.any { TopicMatcher.matches(it, topic) }
    }

    /** 当前生效集合的不可变快照（按字典序、已去重）。 */
    fun snapshot(): Set<String> = current

    /**
     * 校验并入账。
     *
     * @param base 变更前的集合（`replace` 为空集）。
     * @param incoming 本次提交的表达式（可能含重复、可能与 [base] 重叠）。
     */
    private fun applyTo(base: Set<String>, incoming: Set<String>): SubscriptionApplyResult {
        val limits = settings()
        // 去重并丢弃已生效项：回执明细按 JM 提交顺序列出，便于逐条对账。
        val candidates = incoming.filter { it !in base }.distinct()
        val rejected = candidates.mapNotNull { expression ->
            invalidReason(expression, limits)?.let { SubscriptionApplyResult.Rejection(expression, it) }
        }
        if (rejected.isNotEmpty()) return rejectedResult(rejected)

        val overflow = countOverflow(base.size, candidates, limits.maxTopics)
        if (overflow.isNotEmpty()) return rejectedResult(overflow)

        val committed = immutableSorted(base + candidates)
        current = committed
        return SubscriptionApplyResult(applied = true, effective = committed, rejected = emptyList())
    }

    /**
     * 数量上限检查：以**应用后的集合大小**为准（不是入参条数），否则 `add` 可以无限堆叠。
     *
     * 返回"放不下的那些"逐条明细（从第 `max-topics+1` 条起），整批仍然拒绝。
     */
    private fun countOverflow(baseSize: Int, candidates: List<String>, maxTopics: Int): List<SubscriptionApplyResult.Rejection> {
        val room = (maxTopics - baseSize).coerceAtLeast(0)
        if (candidates.size <= room) return emptyList()
        val reason = "超出订阅数量上限(max-topics=$maxTopics)"
        return candidates.drop(room).map { SubscriptionApplyResult.Rejection(it, reason) }
    }

    /** 单条表达式的非法原因；合法返回 null。 */
    private fun invalidReason(expression: String, limits: SubscribeSettings): String? = when {
        expression.isEmpty() -> "表达式为空"
        expression.any { it.isWhitespace() } -> "表达式含空白字符"
        expression.length > limits.maxExpressionChars ->
            "超过单个表达式长度上限(max-expression-chars=${limits.maxExpressionChars}):实际 ${expression.length} 字符"
        else -> null
    }

    /** 构造拒绝结果：`effective` 保持变更前状态（绝不部分应用）。 */
    private fun rejectedResult(rejected: List<SubscriptionApplyResult.Rejection>): SubscriptionApplyResult =
        SubscriptionApplyResult(applied = false, effective = current, rejected = rejected)

    /** 去重且按字典序冻结，同时屏蔽外部修改。 */
    private fun immutableSorted(topics: Set<String>): Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(topics.sorted()))
}
