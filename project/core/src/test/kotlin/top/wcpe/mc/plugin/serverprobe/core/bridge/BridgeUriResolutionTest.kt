package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * 桥地址解析回归测试。
 *
 * 背景（真机验收发现）：`java.net.URI` 只对 RFC 2396 的「服务器型」scheme 白名单
 * （http/https/ftp 等）填充 `host`；**`ws` / `wss` 不在其列**，
 * `URI("ws://127.0.0.1:9102/ws/plugin-bridge").host` 返回 null。
 *
 * 后果（修复前）：
 * - 日志打印成「目标 null:80」，排障时被误读成「配置没生效」；
 * - 更严重的是 `buildTargetUri` 照抄 `base.host`，握手指向 `ws:null:9102/...`。
 *
 * 本测试锁死三个性质：
 * 1. `safeAuthority` 对 ws/wss 能正确取出 host:port；
 * 2. `safeAuthority` **绝不泄漏 query**（token 在 query 里）；
 * 3. `buildTargetUri` 构造出的 URI，host/port/path 与配置一致，query 含编码后的 token。
 */
class BridgeUriResolutionTest {

    // ── safeAuthority ────────────────────────────────────────────

    @Test
    fun `ws 地址能取到 host 和端口`() {
        val authority = testClient().safeAuthority("ws://127.0.0.1:9102/ws/plugin-bridge")
        assertEquals("127.0.0.1:9102", authority)
    }

    @Test
    fun `wss 地址同样可取`() {
        assertEquals("example.com:443", testClient().safeAuthority("wss://example.com:443/ws"))
    }

    @Test
    fun `无端口时补 80`() {
        assertEquals("127.0.0.1:80", testClient().safeAuthority("ws://127.0.0.1/ws"))
    }

    @Test
    fun `query 绝不进入日志`() {
        val url = "ws://127.0.0.1:9102/ws/plugin-bridge?token=jmet_secret_value&instance=abc"
        val authority = testClient().safeAuthority(url)
        assertEquals("127.0.0.1:9102", authority)
        assertFalse(authority.contains("token"), "日志内容不得含 token 键")
        assertFalse(authority.contains("jmet_"), "日志内容不得含 token 值")
    }

    @Test
    fun `空地址给出明确文案`() {
        assertEquals("(未配置)", testClient().safeAuthority(""))
        assertEquals("(未配置)", testClient().safeAuthority("   "))
    }

    @Test
    fun `IPv6 字面量能正确切分`() {
        assertEquals("[::1]:9102", testClient().safeAuthority("ws://[::1]:9102/ws"))
        assertEquals("[::1]:80", testClient().safeAuthority("ws://[::1]/ws"))
    }

    // ── buildTargetUri ───────────────────────────────────────────

    @Test
    fun `握手 URI 的 host 非空且与配置一致`() {
        val uri = testClient().buildTargetUri("ws://127.0.0.1:9102/ws/plugin-bridge", "tok", "inst-1")
        assertEquals("ws", uri.scheme)
        assertEquals("127.0.0.1", uri.host, "host 不得为 null —— 这是修复前的真实缺陷")
        assertEquals(9102, uri.port)
        assertEquals("/ws/plugin-bridge", uri.path)
    }

    @Test
    fun `握手 URI 的 query 含编码后的 token 与 instance`() {
        val uri = testClient().buildTargetUri("ws://127.0.0.1:9102/ws", "a&b=c", "inst/1")
        val query = uri.query ?: error("query 不应为空")
        assertTrue(query.contains("token="), "query 应含 token")
        assertTrue(query.contains("instance="), "query 应含 instance")
        // 特殊字符必须被编码，否则会污染 query
        assertFalse(query.contains("a&b=c"), "token 中的 & 与 = 必须编码")
        assertFalse(query.contains("inst/1"), "instance 中的 / 必须编码")
    }

    @Test
    fun `无端口的配置在握手 URI 中为 -1`() {
        val uri = testClient().buildTargetUri("ws://127.0.0.1/ws", "t", "i")
        assertEquals("127.0.0.1", uri.host)
        assertEquals(-1, uri.port)
    }

    @Test
    fun `IPv6 配置的握手 URI host 保留方括号形式`() {
        val uri = testClient().buildTargetUri("ws://[::1]:9102/ws", "t", "i")
        assertEquals("9102", uri.port.toString())
        assertTrue(uri.host?.contains("::1") == true, "IPv6 host 应保留，实际=${uri.host}")
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    /**
     * 取一个仅用于调用纯函数的 BridgeClient 实例。
     *
     * 两个被测方法都是**纯函数**（不碰连接、不起线程），`BridgeClient` 是无参构造，
     * 直接 new 即可；不调用 `start()` 就不会起任何线程。
     */
    private fun testClient(): BridgeClient = BridgeClient()

    /**
     * 记录 JDK 对 `ws` scheme 的 host 解析行为。
     *
     * 【真机复验结论】原以为 `java.net.URI` 不为 `ws` 填充 host，实测 **JDK 21 会正常填充**
     * （`URI("ws://127.0.0.1:9102/x").host == "127.0.0.1"`）。
     *
     * 那真机日志的 `null:80` 从何而来？——是**配置值为空**时 `URI("")` 的 host 为 null、
     * port 为 -1，落到 `${null}:${80}` = `null:80`。即：**日志看起来像解析 bug，
     * 实际是"配置没读到"**，`null:80` 把排障引向了错误方向。
     *
     * 因此修复的真正价值不是"绕开 URI 白名单"，而是：
     * 1. 空配置给出 `(未配置)` 而非 `null:80`，让"没配"一眼可辨；
     * 2. 手工切 authority 不依赖任何 scheme 行为，跨 JDK 稳定。
     *
     * 本测试不再断言 `host == null`（那是错的），只记录"当前 JDK 能解析 ws"这一事实。
     */
    @Test
    fun `记录 JDK 对 ws scheme 的 host 解析行为`() {
        val host = runCatching { URI("ws://127.0.0.1:9102/x").host }.getOrNull()
        // 不断言具体值（JDK 版本间可能不同），只要求"要么解析成功、要么为 null"这两种已知形态，
        // 且无论哪种，本类的实现都已不依赖它。
        assertTrue(
            host == null || host == "127.0.0.1",
            "URI 对 ws 的 host 解析出现第三种形态：$host —— 请复核 splitAuthority 的假设",
        )
    }

    /** 回归：空配置必须给出可辨识文案，而不是会误导排障的 `null:80`。 */
    @Test
    fun `空配置不再产出 null 冒号 80`() {
        val client = testClient()
        for (blank in listOf("", "   ", "\t")) {
            val authority = client.safeAuthority(blank)
            assertFalse(authority.contains("null"), "空配置不得产出含 null 的文案，实际=$authority")
            assertEquals("(未配置)", authority)
        }
    }
}
