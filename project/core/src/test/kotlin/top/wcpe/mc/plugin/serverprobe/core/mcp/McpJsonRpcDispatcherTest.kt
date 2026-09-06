package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject

/** MCP JSON-RPC 分发、鉴权与危险配置告警的纯逻辑测试。 */
class McpJsonRpcDispatcherTest {

    private val dispatcher = McpJsonRpcDispatcher(FakeToolProvider()) { value -> value.toString() }

    @Test
    fun `初始化返回协议版本与工具能力`() {
        val response = dispatcher.dispatch(request(7, "initialize"))!!

        assertEquals("2.0", response["jsonrpc"])
        assertEquals(7, response["id"])
        val result = response["result"] as Map<*, *>
        assertEquals(McpJsonRpcDispatcher.PROTOCOL_VERSION, result["protocolVersion"])
        assertTrue(result.containsKey("capabilities"))
    }

    @Test
    fun `工具列表和调用按 MCP 结果结构返回`() {
        val listed = dispatcher.dispatch(request(8, "tools/list"))!!
        val tools = (listed["result"] as Map<*, *>)["tools"] as List<*>
        assertEquals("server_status", (tools.single() as Map<*, *>)["name"])

        val called = dispatcher.dispatch(request(9, "tools/call", mapOf("name" to "server_status")))!!
        val content = ((called["result"] as Map<*, *>)["content"] as List<*>).single() as Map<*, *>
        assertEquals("text", content["type"])
        assertTrue(content["text"].toString().contains("状态"))
    }

    @Test
    fun `工具调用把 arguments 传递给工具提供者`() {
        val provider = ArgumentToolProvider()
        val dispatcher = McpJsonRpcDispatcher(provider) { value -> value.toString() }

        dispatcher.dispatch(request(10, "tools/call", mapOf(
            "name" to "server_command",
            "arguments" to mapOf("command" to "list"),
        )))

        assertEquals("list", provider.command)
    }

    @Test
    fun `多提供者工具列表合并且按名路由`() {
        val dispatcher = McpJsonRpcDispatcher(listOf(FakeToolProvider(), ArgumentToolProvider())) { value -> value.toString() }

        val listed = dispatcher.dispatch(request(11, "tools/list"))!!
        val tools = (listed["result"] as Map<*, *>)["tools"] as List<*>
        assertEquals(2, tools.size)
        assertEquals(setOf("server_status", "server_command"), tools.map { (it as Map<*, *>)["name"] }.toSet())

        val called = dispatcher.dispatch(request(12, "tools/call", mapOf(
            "name" to "server_command",
            "arguments" to mapOf("command" to "list"),
        )))!!
        val content = ((called["result"] as Map<*, *>)["content"] as List<*>).single() as Map<*, *>
        assertEquals("text", content["type"])
        assertTrue(content["text"] != null)
    }

    @Test
    fun `同一提供者多个工具名不重复展开`() {
        // 单 provider 提供多个工具：map 的 values 会含同一实例多次，tools/list 必须按实例去重
        val dispatcher = McpJsonRpcDispatcher(MultiToolProvider()) { value -> value.toString() }

        val listed = dispatcher.dispatch(request(14, "tools/list"))!!
        val tools = (listed["result"] as Map<*, *>)["tools"] as List<*>
        val names = tools.map { (it as Map<*, *>)["name"] }
        assertEquals(setOf("tool_a", "tool_b"), names.toSet())
        assertEquals(2, names.size, "同一提供者的多工具不应重复展开")
    }

    @Test
    fun `工具描述包含参数示例工作流输出关键段`() {
        val provider = ExampleToolProvider()
        val dispatcher = McpJsonRpcDispatcher(provider) { value -> value.toString() }
        val listed = dispatcher.dispatch(request(13, "tools/list"))!!
        val tool = ((listed["result"] as Map<*, *>)["tools"] as List<*>).single() as Map<*, *>
        val description = tool["description"] as String
        assertTrue(description.contains("参数"))
        assertTrue(description.contains("示例"))
        assertTrue(description.contains("同步"))
        assertTrue(description.contains("输出"))
        assertTrue(tool["inputSchema"] is Map<*, *>)
    }

    @Test
    fun `未知方法和非法工具返回 JSON RPC 错误`() {
        val methodError = dispatcher.dispatch(request("a", "missing"))!!
        assertEquals(-32601, ((methodError["error"] as Map<*, *>)["code"]))

        val toolError = dispatcher.dispatch(request("b", "tools/call", mapOf("name" to "missing")))!!
        assertEquals(-32602, ((toolError["error"] as Map<*, *>)["code"]))
    }

    @Test
    fun `通知不返回 JSON RPC 响应`() {
        assertNull(dispatcher.dispatch(request(null, "notifications/initialized")))
    }

    @Test
    fun `Bearer 按完整报文常量时间匹配`() {
        assertTrue(McpBearerAuth.matches("Bearer 保密值", "保密值"))
        assertFalse(McpBearerAuth.matches("Bearer 错误值", "保密值"))
        assertFalse(McpBearerAuth.matches("Basic 保密值", "保密值"))
    }

    @Test
    fun `空密钥和非回环地址各自产生中文警告`() {
        val warnings = McpSafetyWarnings.messages(McpSettings(true, "0.0.0.0", 9942, ""))

        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.any { character -> character.code > 127 } })
    }

    private fun request(id: Any?, method: String, params: Map<String, Any?> = emptyMap()): JsonObject =
        MapJsonObject(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))

    private class FakeToolProvider : McpToolProvider {
        override fun tools(): List<McpTool> = listOf(McpTool("server_status", "读取服务器状态"))

        override fun call(name: String, arguments: JsonObject?): Map<String, Any?> {
            require(name == "server_status")
            return mapOf("状态" to "正常")
        }
    }

    private class ArgumentToolProvider : McpToolProvider {
        var command: String? = null

        override fun tools(): List<McpTool> = listOf(McpTool("server_command", "执行控制台命令"))

        override fun call(name: String, arguments: JsonObject?): Map<String, Any?> {
            command = arguments?.getString("command")
            return emptyMap()
        }
    }

    private class ExampleToolProvider : McpToolProvider {
        override fun tools(): List<McpTool> = listOf(
            McpTool(
                "server_status", "读取服务器状态",
                inputSchema = mapOf("timeout" to mapOf("type" to "integer")),
                usageExample = "{\"timeout\":5}",
                workflow = "同步调用，直接返回结果",
                outputFields = mapOf("status" to "状态"),
            ),
        )

        override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = mapOf("status" to "ok")
    }

    private class MultiToolProvider : McpToolProvider {
        override fun tools(): List<McpTool> = listOf(
            McpTool("tool_a", "工具 A"),
            McpTool("tool_b", "工具 B"),
        )

        override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = mapOf("name" to name)
    }

    private class MapJsonObject(private val values: Map<String, Any?>) : JsonObject {
        override fun getString(key: String, default: String): String = values[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = values[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = values[key] as? List<String> ?: emptyList()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getObject(key: String): JsonObject? = (values[key] as? Map<String, Any?>)?.let(::MapJsonObject)
        override fun getRaw(key: String): Any? = values[key]
    }
}
