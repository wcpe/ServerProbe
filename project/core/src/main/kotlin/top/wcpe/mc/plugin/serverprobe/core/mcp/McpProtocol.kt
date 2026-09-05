package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.security.MessageDigest

/** 一个可由 MCP `tools/list` 返回的最小工具描述。 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?> = emptyMap(),
)

/** MCP 工具目录与同步调用边界；长任务和 Arthas 后续以独立实现加入。 */
interface McpToolProvider {
    fun tools(): List<McpTool>
    fun call(name: String, arguments: JsonObject?): Map<String, Any?>
}

/** MCP 控制面的运行配置；密钥为空表示显式允许无认证。 */
data class McpSettings(val enabled: Boolean, val host: String, val port: Int, val bearerSecret: String)

/** Bearer 比较必须不走普通字符串相等，避免把密钥前缀差异暴露为可观测时间差。 */
object McpBearerAuth {

    fun matches(authorization: String?, secret: String): Boolean {
        if (secret.isEmpty() || authorization == null) {
            return secret.isEmpty()
        }
        val expected = (BEARER_PREFIX + secret).toByteArray(Charsets.UTF_8)
        val actual = authorization.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }

    private const val BEARER_PREFIX = "Bearer "
}

/** 危险配置只告警、不拒绝用户明确授权的完整控制面。 */
object McpSafetyWarnings {

    fun messages(settings: McpSettings): List<String> = buildList {
        if (settings.bearerSecret.isEmpty()) {
            add("MCP 控制面未配置密钥，任何可连接客户端均可取得完整控制权限")
        }
        if (!isLoopback(settings.host)) {
            add("MCP 控制面监听非回环地址 ${settings.host}:${settings.port}，明文网络将暴露完整控制权限")
        }
    }

    private fun isLoopback(host: String): Boolean = host == "127.0.0.1" || host == "::1" || host.equals("localhost", true)
}

/** Streamable HTTP 端点使用的 JSON-RPC 2.0 分发器。 */
class McpJsonRpcDispatcher(
    private val tools: McpToolProvider,
    private val audit: McpAuditTrail? = null,
    private val encodeResult: (Any?) -> String = Json::encode,
) {

    /** 保持已有调用方传入 JSON 写入器的位置参数兼容。 */
    constructor(tools: McpToolProvider, encodeResult: (Any?) -> String) : this(tools, null, encodeResult)

    fun dispatch(request: JsonObject, sourceIp: String = "未知来源"): Map<String, Any?>? {
        val id = request.getRaw("id")
        if (request.getString("jsonrpc") != JSON_RPC_VERSION) {
            return error(id, INVALID_REQUEST, "JSON-RPC 版本必须为 2.0")
        }
        return when (request.getString("method")) {
            INITIALIZE -> result(id, initializeResult())
            PING -> result(id, emptyMap<String, Any?>())
            TOOLS_LIST -> result(id, mapOf("tools" to tools.tools().map(::toolDescription)))
            TOOLS_CALL -> callTool(id, request.getObject("params"), sourceIp)
            INITIALIZED_NOTIFICATION -> null
            else -> error(id, METHOD_NOT_FOUND, "未实现的 MCP 方法")
        }
    }

    private fun callTool(id: Any?, params: JsonObject?, sourceIp: String): Map<String, Any?> {
        val name = params?.getString("name")?.takeIf(String::isNotBlank)
            ?: return error(id, INVALID_PARAMS, "tools/call 缺少工具名称")
        val started = System.nanoTime()
        val value = runCatching { tools.call(name, params?.getObject("arguments")) }.getOrElse {
            audit?.record(McpAuditRecord(sourceIp, name, null, elapsedMillis(started), "FAILED", params?.getObject("arguments").toString()))
            return error(id, INVALID_PARAMS, it.message ?: "未找到或无法执行的 MCP 工具")
        }
        val arguments = params?.getObject("arguments").toString()
        audit?.record(McpAuditRecord(sourceIp, name, value["taskId"]?.toString(), elapsedMillis(started), "SUCCEEDED", arguments))
        return result(id, mapOf("content" to listOf(mapOf("type" to "text", "text" to encodeResult(value)))))
    }

    private fun elapsedMillis(started: Long): Long = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun initializeResult(): Map<String, Any?> = linkedMapOf(
        "protocolVersion" to PROTOCOL_VERSION,
        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
        "serverInfo" to mapOf("name" to "ServerProbe", "version" to "开发版"),
    )

    private fun toolDescription(tool: McpTool): Map<String, Any?> = linkedMapOf(
        "name" to tool.name,
        "description" to tool.description,
        "inputSchema" to mapOf("type" to "object", "properties" to tool.inputSchema),
    )

    private fun result(id: Any?, result: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "jsonrpc" to JSON_RPC_VERSION,
        "id" to id,
        "result" to result,
    )

    private fun error(id: Any?, code: Int, message: String): Map<String, Any?> = linkedMapOf(
        "jsonrpc" to JSON_RPC_VERSION,
        "id" to id,
        "error" to mapOf("code" to code, "message" to message),
    )

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        private const val JSON_RPC_VERSION = "2.0"
        private const val INITIALIZE = "initialize"
        private const val INITIALIZED_NOTIFICATION = "notifications/initialized"
        private const val PING = "ping"
        private const val TOOLS_LIST = "tools/list"
        private const val TOOLS_CALL = "tools/call"
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
    }
}
