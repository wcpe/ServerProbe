package top.wcpe.mc.plugin.serverprobe.core.bridge.topic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.SubscribeSettings

/**
 * [TopicSubscriptionRegistry] 单元测试（事件主题订阅规格 §3.6 + §5「整批拒绝」「上限不静默截断」）。
 *
 * 核心不变量：**整批原子**——任何一条不合格都整批拒绝，当前生效集合保持不变，被拒明细逐条带原因。
 * 上限用注入的小值（`maxTopics = 2` / `maxExpressionChars = 8`）验证，
 * 不读默认 256/256：用默认值断言会让"读了默认值但上限逻辑写错"的实现照样变绿。
 */
class TopicSubscriptionRegistryTest {

    /** 构造注入小上限的注册表。 */
    private fun registry(maxTopics: Int = 8, maxExpressionChars: Int = 32): TopicSubscriptionRegistry =
        TopicSubscriptionRegistry { SubscribeSettings(maxTopics = maxTopics, maxExpressionChars = maxExpressionChars) }

    /** 初始状态：无订阅（`count == 0` 表示全量上报）。 */
    @Test
    fun `初始无订阅`() {
        val registry = registry()

        assertFalse(registry.hasSubscriptions(), "初始应无订阅")
        assertTrue(registry.snapshot().isEmpty(), "初始快照应为空集")
        assertFalse(registry.matchesAny("activity.1"), "无订阅时不得声称命中任何主题")
    }

    /** 替换订阅：生效集合按字典序排列（去重由 `Set` 入参保证）。 */
    @Test
    fun `替换订阅按字典序`() {
        val registry = registry()

        val result = registry.replace(setOf("b.1", "a.1"))

        assertTrue(result.applied, "合法表达式应整批应用")
        assertEquals(listOf("a.1", "b.1"), result.effective.toList(), "生效集合应按字典序去重")
        assertEquals(listOf("a.1", "b.1"), registry.snapshot().toList(), "注册表快照应与结果一致")
        assertTrue(registry.hasSubscriptions(), "应用后应有订阅")
        assertTrue(registry.matchesAny("a.1"), "应命中已订阅主题")
        assertFalse(registry.matchesAny("c.1"), "不得命中未订阅主题")
    }

    /** 清空：恢复全量上报且幂等。 */
    @Test
    fun `清空订阅幂等`() {
        val registry = registry()
        registry.replace(setOf("a.1"))

        registry.clear()
        registry.clear()

        assertFalse(registry.hasSubscriptions(), "清空后应无订阅")
        assertTrue(registry.snapshot().isEmpty(), "清空后快照应为空集")
    }

    /** 含空白 / 空串 → 拒绝，且**整批**不生效。 */
    @Test
    fun `空串与含空白表达式整批拒绝`() {
        val registry = registry()
        registry.replace(setOf("keep.1"))

        val blank = registry.replace(setOf("a.1", "bad topic"))
        val empty = registry.replace(setOf(""))

        assertFalse(blank.applied, "含空白的表达式应导致整批拒绝")
        assertEquals(1, blank.rejected.size, "应只列出真正越界的那一条")
        assertEquals("bad topic", blank.rejected[0].expression, "明细应带表达式原文")
        assertTrue(blank.rejected[0].reason.contains("空白"), "原因应说明含空白，实际=${blank.rejected[0].reason}")
        assertEquals(listOf("keep.1"), blank.effective.toList(), "被拒时生效集合必须保持变更前")
        assertFalse(empty.applied, "空串表达式应被拒")
        assertEquals(listOf("keep.1"), registry.snapshot().toList(), "两次被拒后注册表仍保持变更前")
    }

    /** 单表达式长度上限：超长拒绝，原因含上限值。 */
    @Test
    fun `表达式超长度上限被拒`() {
        val registry = registry(maxExpressionChars = 8)

        val result = registry.replace(setOf("aaaa.bbbb.cccc"))

        assertFalse(result.applied, "超长表达式应被拒")
        assertTrue(
            result.rejected[0].reason.contains("max-expression-chars=8"),
            "原因应含上限值，实际=${result.rejected[0].reason}",
        )
        assertTrue(registry.snapshot().isEmpty(), "被拒后不得部分生效")
    }

    /** 数量上限：超 `max-topics` 整批拒绝并逐条说明，**绝不静默截断**。 */
    @Test
    fun `超出数量上限整批拒绝且不截断`() {
        val registry = registry(maxTopics = 2)

        val result = registry.replace(setOf("a.1", "a.2", "a.3"))

        assertFalse(result.applied, "超上限应整批拒绝")
        assertEquals(1, result.rejected.size, "只应列出放不下的那一条")
        assertEquals("a.3", result.rejected[0].expression, "明细应指向越界的那条")
        assertTrue(
            result.rejected[0].reason.contains("max-topics=2"),
            "原因应含上限值，实际=${result.rejected[0].reason}",
        )
        assertTrue(result.effective.isEmpty(), "被拒时生效集合为空（本来就无订阅）")
        assertTrue(registry.snapshot().isEmpty(), "不得静默截断成 2 条（那会让 JM 以为全部订阅成功）")
    }

    /** 增量 add 也受数量上限约束：已生效条数计入总数。 */
    @Test
    fun `增量添加同样受数量上限约束`() {
        val registry = registry(maxTopics = 2)
        registry.replace(setOf("a.1"))

        val within = registry.add(setOf("a.2"))
        val beyond = registry.add(setOf("a.3"))

        assertTrue(within.applied, "未超上限应应用")
        assertFalse(beyond.applied, "超上限应整批拒绝")
        assertEquals(listOf("a.1", "a.2"), registry.snapshot().toList(), "被拒后仍保持变更前的两条")
    }

    /** 增量 add 对已存在的表达式幂等。 */
    @Test
    fun `增量添加幂等`() {
        val registry = registry()
        registry.replace(setOf("a.1"))

        val again = registry.add(setOf("a.1"))

        assertTrue(again.applied, "重复添加已存在表达式不算错")
        assertEquals(listOf("a.1"), again.effective.toList(), "生效集合不应变化")
    }

    /** 前缀表达式参与匹配（层级语义）。 */
    @Test
    fun `前缀表达式参与匹配`() {
        val registry = registry()
        registry.replace(setOf("activity.*"))

        assertTrue(registry.matchesAny("activity.12345"), "应命中同域对象")
        assertTrue(registry.matchesAny("activity.12345.sub"), "应命中层级子主题")
        assertFalse(registry.matchesAny("economy.12345"), "不得跨域命中")
    }

    /** 上限来自供应器：同一注册表在配置放宽后即可接受更多表达式（不重建实例）。 */
    @Test
    fun `上限取自供应器`() {
        var maxTopics = 1
        val registry = TopicSubscriptionRegistry { SubscribeSettings(maxTopics = maxTopics) }

        assertFalse(registry.replace(setOf("a.1", "a.2")).applied, "上限为 1 时应拒绝两条")

        maxTopics = 4

        assertTrue(registry.replace(setOf("a.1", "a.2")).applied, "上限放宽后无需重建注册表即可生效")
    }
}
