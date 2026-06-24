package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * [BusinessHost] 单元测试(JBIS 业务对接派发 + 事故域隔离,见 ADR-0015)。
 *
 * 覆盖高风险区(testing-and-quality §2 并发 / 主线程不阻塞):域路由、未注册降级、
 * Provider 抛异常 / 卡死的事故域隔离(降级失败、不向上抛、有界超时不永占调用线程)。
 */
class BusinessHostTest {

    /** 行为可配置的桩 Provider。 */
    private class StubProvider(
        override val domain: String,
        private val behavior: (action: String, payload: String) -> BridgeCommandResult,
    ) : BusinessProvider {
        override fun manifest(): Map<String, Any?> = mapOf("actions" to listOf("ping"))
        override fun dispatch(action: String, payload: String): BridgeCommandResult = behavior(action, payload)
    }

    /** 已注册域:dispatch 路由到对应 Provider 并回其结果。 */
    @Test
    fun `dispatch 路由到对应域 Provider 并回其结果`() {
        val host = BusinessHost()
        host.register(StubProvider("economy") { action, payload -> BridgeCommandResult.ok("$action|$payload") })

        val result = host.dispatch("economy", "balance", "alice")

        assertTrue(result.success, "已注册域应成功")
        assertEquals("balance|alice", result.output)
    }

    /** 未注册域:dispatch 降级为「域不可用」,不抛。 */
    @Test
    fun `未注册的域 dispatch 降级为域不可用`() {
        val host = BusinessHost()

        val result = host.dispatch("inventory", "view", "")

        assertFalse(result.success, "未注册域应降级失败")
        assertTrue(result.error.contains("inventory"), "错误应点明域:${result.error}")
    }

    /** Provider 抛异常:被事故域隔离降级为失败,不向上传播。 */
    @Test
    fun `Provider 抛异常被事故域隔离降级为失败`() {
        val host = BusinessHost()
        host.register(StubProvider("economy") { _, _ -> throw IllegalStateException("boom") })

        val result = host.dispatch("economy", "balance", "")

        assertFalse(result.success, "Provider 抛异常应降级失败而非传播")
    }

    /** Provider 卡死:dispatch 有界超时降级,调用线程不被永久卡住(事故域隔离铁律)。 */
    @Test
    fun `Provider 卡死时 dispatch 有界超时降级且不永占调用线程`() {
        val host = BusinessHost()
        host.dispatchTimeoutMs = TEST_TIMEOUT_MS
        val released = CountDownLatch(1)
        host.register(StubProvider("economy") { _, _ ->
            released.await()
            BridgeCommandResult.ok("late")
        })

        val startNanos = System.nanoTime()
        val result = host.dispatch("economy", "balance", "")
        val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS

        assertFalse(result.success, "卡死应超时降级失败")
        assertTrue(elapsedMs < MAX_RETURN_MS, "调用线程应在超时内返回(实测 ${elapsedMs}ms),不被 Provider 永久卡住")
        released.countDown()
    }

    /**
     * domains:反映已注册的业务域(能力发现的注册侧)。
     *
     * 注:[BusinessHost.manifest] 的 JSON 汇总走 TabooLib `Configuration` codec(运行期模块,单测 classpath 无),
     * 故其字符串编码在集成 / 真机验;此处只验注册侧 + Provider 自声明的结构化 manifest。
     */
    @Test
    fun `domains 反映已注册的业务域`() {
        val host = BusinessHost()
        assertTrue(host.domains().isEmpty(), "初始无注册域")

        host.register(StubProvider("economy") { _, _ -> BridgeCommandResult.ok() })
        host.register(StubProvider("inventory") { _, _ -> BridgeCommandResult.ok() })

        val domains = host.domains()
        assertEquals(2, domains.size, "应有两个已注册域")
        assertTrue(domains.contains("economy") && domains.contains("inventory"), "应含 economy 与 inventory:$domains")
    }

    private companion object {
        /** 测试用小超时(毫秒),避免命中生产 5s 超时拖慢测试。 */
        private const val TEST_TIMEOUT_MS = 100L

        /** 调用线程应在此上限内返回(毫秒),证明有界不被卡死。 */
        private const val MAX_RETURN_MS = 2_000L

        /** 纳秒→毫秒换算。 */
        private const val NANOS_PER_MS = 1_000_000L
    }
}
