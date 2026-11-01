package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration

/**
 * [ProbeConfig.awaitLoaded] 的行为契约。
 *
 * 背景：`conf` 由 TabooLib 在加载期反射注入，而 IOC 组件的装配线程可能跑在注入之前。
 * 实测（代理端托管 E2E）出现过「`config.yml` 里 `mcp.enabled: true`，组件刚启动却读到默认 false」，
 * 导致 MCP 工具面偶发不挂载。故装配期读开关前要过这道门，本类钉住它的两个行为面：
 * 配置可用 → 立即返回 true；配置不可用 → 有界超时后返回 false（绝不无限阻塞）。
 */
class ProbeConfigAwaitLoadedTest {

    /** 配置可用时：立即返回 true，不白等。 */
    @Test
    fun `配置已注入时立即就绪`() {
        ProbeConfig.conf = testConfiguration(mapOf("mcp.enabled" to true))

        val started = System.nanoTime()
        val ready = ProbeConfig.awaitLoaded(WAIT_MS)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(ready, "配置已注入时必须判定为就绪")
        assertTrue(elapsedMs < WAIT_MS, "已就绪时不应白等到超时（实际 ${elapsedMs}ms）")
        assertTrue(ProbeConfig.mcpEnabled(), "就绪后读到的 mcp.enabled 应与替身一致")
    }

    /** 配置不可用时：按超时有界返回 false，且耗时接近超时（证明确实等过、但没有死等）。 */
    @Test
    fun `配置未注入时按超时返回未就绪`() {
        val field = ProbeConfig::class.java.getDeclaredField("conf").apply { isAccessible = true }
        field.set(ProbeConfig, null)
        try {
            val started = System.nanoTime()
            val ready = ProbeConfig.awaitLoaded(SHORT_WAIT_MS)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertFalse(ready, "未注入时必须返回未就绪，由调用方决定降级策略")
            assertTrue(elapsedMs >= SHORT_WAIT_MS, "应当等满超时窗口（实际 ${elapsedMs}ms）")
        } finally {
            // 全局单例字段，必须恢复，避免污染同 JVM 内的其它用例
            field.set(ProbeConfig, testConfiguration())
        }
    }

    private companion object {
        private const val WAIT_MS = 1_000L
        private const val SHORT_WAIT_MS = 120L
    }
}
