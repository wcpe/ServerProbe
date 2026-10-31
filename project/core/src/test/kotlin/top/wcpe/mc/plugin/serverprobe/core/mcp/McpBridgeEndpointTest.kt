package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testJsonObject

/**
 * [McpBridgeEndpoint] 单测：托管模式下 MCP 经桥的**分发语义**。
 *
 * 走对象入口（不解 JSON 文本），因此不依赖运行期 JSON 后端；断言的是“请求 → JSON-RPC 响应”的归因正确性，
 * 这正是控制面读回执时唯一在意的东西：`id` 要能对上、`error.code` 要能分辨“方法没实现”与“工具不存在”。
 */
class McpBridgeEndpointTest {

    private val endpoint = McpBridgeEndpoint(McpJsonRpcDispatcher(listOf(EchoProvider), null, McpJsonWriter::encode))

    /** `tools/list` 必须列出真实工具名（控制面据此渲染可用能力）。 */
    @Test
    fun `tools list 返回工具目录`() {
        val result = endpoint.handle(request("tools/list"))

        assertTrue(result.success, "分发本身应成功：${result.error}")
        assertTrue(result.output.contains(TOOL_NAME), "回执应包含工具名：${result.output}")
        assertTrue(result.output.contains("\"jsonrpc\":\"2.0\""), "应回标准 JSON-RPC 信封：${result.output}")
    }

    /** `tools/call` 必须落到真正执行，并把结果包成 MCP 规范的 `content` 数组。 */
    @Test
    fun `tools call 返回工具结果`() {
        val result = endpoint.handle(
            testJsonObject(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 2,
                    "method" to "tools/call",
                    "params" to mapOf("name" to TOOL_NAME, "arguments" to mapOf("echo" to "hi")),
                ),
            ),
        )

        assertTrue(result.success, "分发本身应成功：${result.error}")
        assertTrue(result.output.contains("\"content\""), "应符合 MCP 的 content 形态：${result.output}")
        assertTrue(result.output.contains(ECHO_FIELD), "应包含工具输出字段：${result.output}")
    }

    /**
     * 协议层错误必须仍以**成功回执**回来，内部带 JSON-RPC `error.code`。
     *
     * 若把这类错误变成桥的 `fail`，控制面就只剩一句中文原因，拿不到 `-32601` 这种可编程判别的信息。
     */
    @Test
    fun `未实现的方法回 JSON-RPC 错误而非桥失败`() {
        val result = endpoint.handle(request("no/such-method"))

        assertTrue(result.success, "协议层错误不应升级为桥失败：${result.error}")
        assertTrue(result.output.contains("\"error\""), "应包含 error 对象：${result.output}")
        assertTrue(result.output.contains("-32601"), "应保留 METHOD_NOT_FOUND 错误码：${result.output}")
    }

    /** 未知工具同样回 JSON-RPC 错误（`-32602`），便于控制面区分“拼错名字”。 */
    @Test
    fun `未知工具回参数错误码`() {
        val result = endpoint.handle(
            testJsonObject(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 3,
                    "method" to "tools/call",
                    "params" to mapOf("name" to "nope"),
                ),
            ),
        )

        assertTrue(result.success, "协议层错误不应升级为桥失败：${result.error}")
        assertTrue(result.output.contains("-32602"), "应保留 INVALID_PARAMS 错误码：${result.output}")
    }

    /** 通知类请求（无 id、无需响应）回空串，表示“已受理但不回体”。 */
    @Test
    fun `通知类请求回空体`() {
        val result = endpoint.handle(request("notifications/initialized", withId = false))

        assertTrue(result.success)
        assertTrue(result.output.isEmpty(), "通知不应有响应体，实际：${result.output}")
    }

    /** 空载荷与超限载荷属于**桥层面**的错误，必须是 fail。 */
    @Test
    fun `空载荷直接失败`() {
        val result = endpoint.handle("   ")

        assertFalse(result.success)
        assertTrue(result.error.contains("载荷为空"), result.error)
    }

    /** JSON-RPC 版本不符回 `-32600`（不是桥失败）。 */
    @Test
    fun `版本不符回无效请求码`() {
        val result = endpoint.handle(
            testJsonObject(mapOf("jsonrpc" to "1.0", "id" to 4, "method" to "ping")),
        )

        assertTrue(result.success)
        assertTrue(result.output.contains("-32600"), result.output)
    }

    private fun request(method: String, withId: Boolean = true): JsonObject = testJsonObject(
        buildMap {
            put("jsonrpc", "2.0")
            put("method", method)
            if (withId) put("id", 1)
        },
    )

    /** 最小工具提供者：一个工具，回显固定字段。 */
    private object EchoProvider : McpToolProvider {

        override fun tools(): List<McpTool> = listOf(McpTool(TOOL_NAME, "测试用回显工具"))

        override fun call(name: String, arguments: JsonObject?): Map<String, Any?> =
            mapOf(ECHO_FIELD to (arguments?.getString("echo") ?: ""))
    }

    private companion object {
        private const val TOOL_NAME = "probe_echo"
        private const val ECHO_FIELD = "echoed"
    }
}
