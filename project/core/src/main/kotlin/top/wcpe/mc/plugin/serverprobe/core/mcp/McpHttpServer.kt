package top.wcpe.mc.plugin.serverprobe.core.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** JDK HttpServer 上的最小 Streamable HTTP MCP 服务，不引入上游 MCP 实现。 */
class McpHttpServer(
    private val settings: McpSettings,
    private val dispatcher: McpJsonRpcDispatcher,
) {

    @Volatile
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    fun start() {
        check(server == null) { "MCP HTTP 服务已经启动" }
        val httpServer = HttpServer.create(InetSocketAddress(settings.host, settings.port), BACKLOG)
        val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } }
        httpServer.executor = pool
        httpServer.createContext(MCP_PATH, McpHttpHandler(settings.bearerSecret, dispatcher))
        httpServer.start()
        executor = pool
        server = httpServer
    }

    fun stop() {
        server?.stop(STOP_DELAY_SECONDS)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private companion object {
        private const val MCP_PATH = "/mcp"
        private const val BACKLOG = 0
        private const val STOP_DELAY_SECONDS = 0
        private const val THREAD_NAME = "ServerProbe-MCP"
    }
}

/** 单次 Streamable HTTP 请求：鉴权、限制请求体、JSON-RPC 分发和最小错误响应。 */
private class McpHttpHandler(
    private val secret: String,
    private val dispatcher: McpJsonRpcDispatcher,
) : HttpHandler {

    override fun handle(exchange: HttpExchange) {
        // 顶层 HTTP 兜底:任何处理异常都只记日志并回 500,探针绝不能因单请求崩溃,故有意宽捕。
        @Suppress("TooGenericExceptionCaught")
        try {
            val response = process(exchange)
            if (response.body == null) {
                exchange.sendResponseHeaders(response.code, NO_RESPONSE_BODY)
            } else {
                respond(exchange, response.code, response.body)
            }
        } catch (error: Exception) {
            ProbeLogger.error("处理 MCP 请求失败(method=${exchange.requestMethod}, path=${exchange.requestURI.path})", error)
            runCatching { respond(exchange, INTERNAL_ERROR, errorResponse(SERVER_ERROR, "服务器处理 MCP 请求失败")) }
        } finally {
            exchange.close()
        }
    }

    private fun process(exchange: HttpExchange): McpHttpResponse {
        if (exchange.requestMethod != POST) {
            return error(METHOD_NOT_ALLOWED, INVALID_REQUEST, "MCP 端点仅接受 POST 请求")
        }
        if (!McpBearerAuth.matches(exchange.requestHeaders.getFirst(AUTHORIZATION), secret)) {
            return error(UNAUTHORIZED, INVALID_REQUEST, "MCP 鉴权失败")
        }
        val body = exchange.requestBody.use(::readBody) ?: run {
            return error(PAYLOAD_TOO_LARGE, INVALID_REQUEST, "MCP 请求体超过上限")
        }
        val request = runCatching { Json.parse(body) }.getOrElse {
            return error(BAD_REQUEST, PARSE_ERROR, "MCP JSON 请求格式无效")
        }
        val response = dispatcher.dispatch(request, exchange.remoteAddress.address?.hostAddress ?: "未知来源")
        return response?.let { McpHttpResponse(OK, McpJsonWriter.encode(it)) } ?: McpHttpResponse(ACCEPTED, null)
    }

    private fun readBody(stream: java.io.InputStream): String? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) {
                return output.toString(Charsets.UTF_8.name())
            }
            if (output.size() + count > MAX_REQUEST_BYTES) {
                return null
            }
            output.write(buffer, 0, count)
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set(CONTENT_TYPE, JSON_CONTENT_TYPE)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun error(httpCode: Int, rpcCode: Int, message: String): McpHttpResponse =
        McpHttpResponse(httpCode, errorResponse(rpcCode, message))

    private fun errorResponse(code: Int, message: String): String = McpJsonWriter.encode(linkedMapOf(
        "jsonrpc" to "2.0",
        "id" to null,
        "error" to mapOf("code" to code, "message" to message),
    ))

    private companion object {
        private const val POST = "POST"
        private const val AUTHORIZATION = "Authorization"
        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val OK = 200
        private const val ACCEPTED = 202
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val METHOD_NOT_ALLOWED = 405
        private const val PAYLOAD_TOO_LARGE = 413
        private const val INTERNAL_ERROR = 500
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val SERVER_ERROR = -32603
        private const val NO_RESPONSE_BODY = -1L
        private const val MAX_REQUEST_BYTES = 2 * 1_024 * 1_024
        private const val READ_BUFFER_BYTES = 8 * 1_024
    }
}

/** HTTP 状态与响应正文，null 正文表示 JSON-RPC 通知已被接受。 */
private data class McpHttpResponse(val code: Int, val body: String?)
