package top.wcpe.mc.plugin.serverprobe.core.prometheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.net.InetSocketAddress

/**
 * `/metrics` 请求处理器:鉴权 + 渲染最新快照为 Prometheus 文本(FR4.2)。
 *
 * 与 [PrometheusExporter](负责服务生命周期)职责分离:本类只负责**单次请求**的鉴权与响应,
 * 跑在 HttpServer 自带的 daemon 线程池上(非主线程)。无状态(鉴权配置在构造期固定),可被并发调用。
 *
 * ## 鉴权策略(PRD 安全约束:鉴权 + 绑定地址 + 不裸奔)
 * 通过需同时满足两道校验,任一不过即拒绝且不泄露细节:
 * 1. **token**:[token] 非空时,要求请求头 `Authorization: Bearer <token>` 完全匹配,否则 401;
 *    [token] 为空表示不启用 token 鉴权,此时**仅靠 IP 白名单**兜底(默认仅本机,绝不对全网裸奔)。
 * 2. **IP 白名单**:来源 IP 不在 [allowedIps] 内则 403。
 *
 * 鉴权失败响应体仅给极简文案、不含任何配置/路径/token 信息(规范第 12/14 条)。
 *
 * @property readApi 只读数据出口,提供最新指标快照。
 * @property token 鉴权 token;空串表示不启用 token 鉴权(仅 IP 白名单)。
 * @property allowedIps 允许访问的来源 IP 白名单(恒非空,至少含本机)。
 */
class MetricsHttpHandler(
    private val readApi: ProbeReadApi,
    private val token: String,
    private val allowedIps: List<String>
) : HttpHandler {

    /**
     * 处理一次 `/metrics` 请求:先鉴权,通过后渲染并回 200,否则回对应错误码。
     *
     * 整体 try/catch 兜底:任何异常尽力回 500 后关闭交换,绝不向线程池抛出(探针不成事故源)。
     *
     * @param exchange 本次 HTTP 交换。
     */
    override fun handle(exchange: HttpExchange) {
        try {
            val denyStatus = checkAuth(exchange)
            if (denyStatus != null) {
                respond(exchange, denyStatus, denyStatus.reason)
                return
            }
            val body = PrometheusTextFormatter.format(readApi.latestSnapshot())
            respondMetrics(exchange, body)
        } catch (e: Exception) {
            // 单次请求异常:记 WARN 并尽力回 500,不影响线程池与其它请求
            ProbeLogger.warn("处理 /metrics 请求时发生异常:${e.message}")
            runCatching { respond(exchange, HttpStatus.INTERNAL_ERROR, HttpStatus.INTERNAL_ERROR.reason) }
        } finally {
            exchange.close()
        }
    }

    /**
     * 鉴权校验:依次校验 token(若启用)与 IP 白名单。
     *
     * @param exchange 本次 HTTP 交换。
     * @return 校验不通过时返回对应的拒绝状态([HttpStatus.UNAUTHORIZED]/[HttpStatus.FORBIDDEN]);通过时返回 null。
     */
    private fun checkAuth(exchange: HttpExchange): HttpStatus? {
        // token 非空才启用 Bearer 校验;为空则跳过(仅靠下方 IP 白名单兜底,不裸奔)
        if (token.isNotEmpty() && !isBearerMatched(exchange)) {
            return HttpStatus.UNAUTHORIZED
        }
        if (!isIpAllowed(exchange.remoteAddress)) {
            return HttpStatus.FORBIDDEN
        }
        return null
    }

    /**
     * 校验请求头 `Authorization` 是否为匹配的 `Bearer <token>`。
     *
     * @param exchange 本次 HTTP 交换。
     * @return token 匹配返回 true,缺失/格式不符/不匹配返回 false。
     */
    private fun isBearerMatched(exchange: HttpExchange): Boolean {
        val authorization = exchange.requestHeaders.getFirst(HEADER_AUTHORIZATION) ?: return false
        if (!authorization.startsWith(BEARER_PREFIX)) {
            return false
        }
        return authorization.substring(BEARER_PREFIX.length) == token
    }

    /**
     * 校验来源地址是否在 IP 白名单内。
     *
     * @param remote 来源套接字地址(可能为 null);取其 IP 文本与 [allowedIps] 比对。
     * @return 在白名单内返回 true;地址不可解析或不在白名单返回 false。
     */
    private fun isIpAllowed(remote: InetSocketAddress?): Boolean {
        val ip = remote?.address?.hostAddress ?: return false
        return ip in allowedIps
    }

    /**
     * 以 Prometheus 内容类型回写 200 与指标文本。
     *
     * @param exchange 本次 HTTP 交换。
     * @param body Prometheus exposition 文本。
     */
    private fun respondMetrics(exchange: HttpExchange, body: String) {
        exchange.responseHeaders.set(HEADER_CONTENT_TYPE, CONTENT_TYPE_PROMETHEUS)
        writeBody(exchange, HttpStatus.OK.code, body)
    }

    /**
     * 以纯文本回写指定状态码与文案(用于鉴权拒绝与错误兜底)。
     *
     * @param exchange 本次 HTTP 交换。
     * @param status 状态码。
     * @param message 响应体文案(极简、不含敏感信息)。
     */
    private fun respond(exchange: HttpExchange, status: HttpStatus, message: String) {
        exchange.responseHeaders.set(HEADER_CONTENT_TYPE, CONTENT_TYPE_TEXT)
        writeBody(exchange, status.code, message)
    }

    /**
     * 写出响应头与响应体(UTF-8)。
     *
     * @param exchange 本次 HTTP 交换。
     * @param code HTTP 状态码。
     * @param body 响应体文本。
     */
    private fun writeBody(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        // use 确保 OutputStream 释放(规范第 8 条:资源必释放)
        exchange.responseBody.use { it.write(bytes) }
    }

    /**
     * HTTP 状态码及其极简响应文案。
     *
     * 集中收口本处理器用到的几个状态码与文案,避免散落魔法值(规范第 6 条);
     * 拒绝类文案刻意保持极简、不泄露任何鉴权/配置细节(规范第 14 条)。
     *
     * @property code HTTP 数值状态码。
     * @property reason 对外响应体文案。
     */
    private enum class HttpStatus(val code: Int, val reason: String) {
        /** 200:鉴权通过、正常返回指标。 */
        OK(200, "OK"),

        /** 401:token 鉴权未通过。 */
        UNAUTHORIZED(401, "Unauthorized"),

        /** 403:来源 IP 不在白名单。 */
        FORBIDDEN(403, "Forbidden"),

        /** 500:服务端处理异常的兜底。 */
        INTERNAL_ERROR(500, "Internal Server Error")
    }

    private companion object {

        /** 请求头名:鉴权。 */
        private const val HEADER_AUTHORIZATION = "Authorization"

        /** Bearer token 前缀。 */
        private const val BEARER_PREFIX = "Bearer "

        /** 响应头名:内容类型。 */
        private const val HEADER_CONTENT_TYPE = "Content-Type"

        /** Prometheus exposition format 内容类型(0.0.4)。 */
        private const val CONTENT_TYPE_PROMETHEUS = "text/plain; version=0.0.4; charset=utf-8"

        /** 纯文本内容类型(鉴权拒绝/错误响应用)。 */
        private const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"
    }
}
