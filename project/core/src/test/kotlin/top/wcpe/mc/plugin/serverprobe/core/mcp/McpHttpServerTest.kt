package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonCodec
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** 验证真实 HTTP 层能写出包含嵌套状态的 JSON-RPC 工具响应。 */
class McpHttpServerTest {

    @Test
    fun `server status 经 HTTP JSON RPC 返回结果`() {
        val port = availablePort()
        val originalCodec = Json.codec
        Json.codec = RequestJsonCodec
        val server = McpHttpServer(
            McpSettings(true, "127.0.0.1", port, ""),
            McpJsonRpcDispatcher(StatusToolProvider(), McpJsonWriter::encode),
        )
        server.start()
        try {
            val response = request(port)

            assertEquals(200, response.code)
            assertTrue(response.body.contains("\"result\""), response.body)
            assertTrue(response.body.contains("latestMetricSnapshot"), response.body)
        } finally {
            server.stop()
            Json.codec = originalCodec
        }
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    private fun request(port: Int): HttpResult {
        val connection = (URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"server_status"}}"""
        connection.outputStream.use { it.write(body.toByteArray()) }
        return HttpResult(connection.responseCode, connection.inputStream.bufferedReader().use { it.readText() })
    }

    private class StatusToolProvider : McpToolProvider {
        override fun tools(): List<McpTool> = listOf(McpTool("server_status", "读取当前服务器状态"))

        override fun call(name: String, arguments: JsonObject?): Map<String, Any?> {
            require(name == "server_status")
            return linkedMapOf(
                "jvm" to mapOf("heapUsedBytes" to 1024L),
                "classLoading" to mapOf("loadedClassCount" to 12),
                "latestMetricSnapshot" to mapOf("timestamp" to 1L),
            )
        }
    }

    private data class HttpResult(val code: Int, val body: String)

    private object RequestJsonCodec : JsonCodec {
        override fun encode(value: Any?): String = error("本测试只验证 HTTP 请求解析")

        override fun parse(json: String): JsonObject = MapJsonObject(mapOf(
            "jsonrpc" to "2.0",
            "id" to 1,
            "method" to "tools/call",
            "params" to mapOf("name" to "server_status"),
        ))
    }

    private class MapJsonObject(private val values: Map<String, Any?>) : JsonObject {
        override fun getRaw(key: String): Any? = values[key]
        override fun getString(key: String, default: String): String = values[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = values[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = values[key] as? List<String> ?: emptyList()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getObject(key: String): JsonObject? = (values[key] as? Map<String, Any?>)?.let(::MapJsonObject)
    }
}
