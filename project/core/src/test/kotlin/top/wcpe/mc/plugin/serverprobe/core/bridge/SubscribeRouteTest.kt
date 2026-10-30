package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicSubscriptionRegistry
import top.wcpe.mc.plugin.serverprobe.core.config.SubscribeSettings

/**
 * [SubscribeRoute] 单元测试（事件主题订阅规格 §3.7 + §5：精确 / 清空 / 上限不静默截断 / 幂等退订）。
 *
 * payload 解析与输出编码都注入纯文本实现：core 的单测类路径没有 nightconfig，
 * 而被测对象是**订阅变更语义**（整批原子、回执内容、幂等），不是 JSON 库。
 */
class SubscribeRouteTest {

    /** 注入小上限；默认 8 / 32 便于构造边界。 */
    private fun registry(maxTopics: Int = 8, maxExpressionChars: Int = 32): TopicSubscriptionRegistry =
        TopicSubscriptionRegistry { SubscribeSettings(maxTopics = maxTopics, maxExpressionChars = maxExpressionChars) }

    /**
     * 测试用 payload 解析器：逗号分隔的表达式列表。
     *
     * 约定 `<invalid>` 表示非法 payload（返回 null），空串表示显式空数组——
     * 这两种情况在规格里语义完全不同（前者失败回执，后者清空订阅）。
     */
    private val decode: (String) -> List<String>? = { payload ->
        when {
            payload == INVALID_PAYLOAD -> null
            payload.isEmpty() -> emptyList()
            else -> payload.split(',')
        }
    }

    /** 测试用编码器：输出形如 `[a.1,a.2]`，可直接与回执 `output` 比对。 */
    private val encode: (List<String>) -> String = { expressions -> expressions.joinToString(",", "[", "]") }

    /** 构造受测路由（未指定注册表时用默认上限的新实例）。 */
    private fun route(target: TopicSubscriptionRegistry = TopicSubscriptionRegistry()): SubscribeRoute =
        SubscribeRoute(target, decode, encode)

    /** 构造一条订阅命令。 */
    private fun command(action: String, payload: String, domain: String = DOMAIN_CORE): BridgeCommand =
        BridgeCommand(action = action, target = "", reason = "", requestId = "req-1", domain = domain, payload = payload)

    /** 序位与执行池：120 / COMMAND（权威分配表）。 */
    @Test
    fun `序位与执行池符合分配表`() {
        val route = route()

        assertEquals(120, route.order, "subscribe 路由序位必须是 120")
        assertEquals(ExecutorKind.COMMAND, route.executor, "订阅变更必须在命令池执行，不得占用读线程")
    }

    /** `claims` 只认 `domain == core` 且动作是 subscribe/unsubscribe。 */
    @Test
    fun `claims 只认领core的订阅动作`() {
        val route = route()

        assertTrue(route.claims(command(ACTION_SUBSCRIBE, "a.1")), "core.subscribe 应命中")
        assertTrue(route.claims(command(ACTION_UNSUBSCRIBE, "a.1")), "core.unsubscribe 应命中")
        assertFalse(route.claims(command(ACTION_SUBSCRIBE, "a.1", domain = "activity")), "业务域不得命中")
        assertFalse(route.claims(command(ACTION_SUBSCRIBE, "a.1", domain = "")), "空域不得命中（那是治理兜底）")
        assertFalse(route.claims(command("bridge_status", "a.1")), "其它 core 动作不得命中")
    }

    /** 成功回执的 `output` 为当前生效集合，且按字典序。 */
    @Test
    fun `订阅成功后回显生效集合`() {
        val registry = registry()
        val result = route(registry).handle(command(ACTION_SUBSCRIBE, "b.1,a.1"))

        assertTrue(result.success, "合法订阅应成功")
        assertEquals("[a.1,b.1]", result.output, "output 应为当前生效集合的 JSON")
        assertEquals(listOf("a.1", "b.1"), registry.snapshot().toList(), "注册表应与回执一致")
    }

    /** `topics: []` → 清空订阅，恢复全量上报。 */
    @Test
    fun `空数组清空订阅`() {
        val registry = registry()
        registry.replace(setOf("a.1", "a.2"))

        val result = route(registry).handle(command(ACTION_SUBSCRIBE, ""))

        assertTrue(result.success, "清空应成功")
        assertEquals("[]", result.output, "output 应为空数组")
        assertFalse(registry.hasSubscriptions(), "清空后应无订阅（全量上报）")
    }

    /**
     * 超上限：`ok=false`、`error` 含上限值与越界明细、生效集合**保持变更前**。
     *
     * 断言"注册表仍是变更前那一条"是关键：静默截断会让 JM 以为全部订阅成功。
     */
    @Test
    fun `超上限整批拒绝且保持变更前状态`() {
        val registry = registry(maxTopics = 2)
        registry.replace(setOf("keep.1"))

        val result = route(registry).handle(command(ACTION_SUBSCRIBE, "a.1,a.2,a.3"))

        assertFalse(result.success, "超上限应失败")
        assertTrue(result.error.contains("max-topics=2"), "error 应含上限值，实际=${result.error}")
        assertTrue(result.error.contains("a.3"), "error 应含逐条被拒明细，实际=${result.error}")
        assertEquals(listOf("keep.1"), registry.snapshot().toList(), "被拒后必须保持变更前的订阅")
    }

    /** 一条非法表达式即整批拒绝：合法的那条也不得生效。 */
    @Test
    fun `含非法表达式时整批拒绝`() {
        val registry = registry()
        registry.replace(setOf("keep.1"))

        val result = route(registry).handle(command(ACTION_SUBSCRIBE, "a.1,bad topic"))

        assertFalse(result.success, "含空白表达式应整批失败")
        assertTrue(result.error.contains("bad topic"), "error 应指向被拒表达式，实际=${result.error}")
        assertEquals(listOf("keep.1"), registry.snapshot().toList(), "合法的那条也不得部分生效")
    }

    /** payload 缺 `topics` 数组 → 失败回执（不把它当成"清空"）。 */
    @Test
    fun `payload非法时失败且不清空订阅`() {
        val registry = registry()
        registry.replace(setOf("keep.1"))

        val result = route(registry).handle(command(ACTION_SUBSCRIBE, INVALID_PAYLOAD))

        assertFalse(result.success, "非法 payload 应失败")
        assertTrue(result.error.contains("topics"), "error 应说明缺 topics，实际=${result.error}")
        assertEquals(listOf("keep.1"), registry.snapshot().toList(), "不得因一次拼错的下发清空订阅")
    }

    /** 退订按表达式移除，`output` 回显剩余集合。 */
    @Test
    fun `退订移除指定表达式`() {
        val registry = registry()
        registry.replace(setOf("a.1", "a.2"))

        val result = route(registry).handle(command(ACTION_UNSUBSCRIBE, "a.1"))

        assertTrue(result.success, "退订应成功")
        assertEquals("[a.2]", result.output, "output 应为剩余表达式")
        assertEquals(listOf("a.2"), registry.snapshot().toList(), "注册表应只剩未退订的那条")
    }

    /** 退订不存在的表达式不报错（幂等），生效集合不变。 */
    @Test
    fun `退订不存在的表达式幂等`() {
        val registry = registry()
        registry.replace(setOf("a.1"))

        val result = route(registry).handle(command(ACTION_UNSUBSCRIBE, "not-exists.1,another.2"))

        assertTrue(result.success, "移除不存在的表达式不应报错")
        assertEquals("[a.1]", result.output, "output 应回显未变化的集合")
        assertEquals(listOf("a.1"), registry.snapshot().toList(), "注册表不应变化")
    }

    /** 退订全部表达式后回到"无订阅"（全量上报）。 */
    @Test
    fun `退订全部后回到无订阅`() {
        val registry = registry()
        registry.replace(setOf("a.1", "a.2"))

        val result = route(registry).handle(command(ACTION_UNSUBSCRIBE, "a.1,a.2"))

        assertTrue(result.success, "退订全部应成功")
        assertFalse(registry.hasSubscriptions(), "应回到无订阅（全量上报），而不是订阅了空集")
    }

    private companion object {
        const val DOMAIN_CORE = "core"
        const val ACTION_SUBSCRIBE = "subscribe"
        const val ACTION_UNSUBSCRIBE = "unsubscribe"

        /** 约定：该 payload 表示"缺少 topics 数组"。 */
        const val INVALID_PAYLOAD = "<invalid>"
    }
}
