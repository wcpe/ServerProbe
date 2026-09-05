package top.wcpe.mc.plugin.serverprobe.core.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Web 面板(FR4.3,M3,P2):启动画像详情 / 历史趋势 / 总览。
 *
 * 基于 JDK 自带 `com.sun.net.httpserver.HttpServer`(零第三方依赖)起轻量 HTTP 服务,
 * 提供三个只读页面:总览(`/`)、启动画像(`/startup`)、历史趋势(`/history`),
 * 数据经注入的 [ProbeReadApi] 读取(最新快照 / 最近启动画像 / 近期历史),HTML 由 [WebPanelHtml] 渲染。
 *
 * ## 稳定性:绝不成为事故源
 * - [start] 整体 try/catch,**起服失败仅 WARN 降级**(如端口被占用),不影响插件启用;
 * - 请求处理跑在 HttpServer 自带 daemon 线程池(非主线程),不阻塞服务器主线程;
 * - 单次请求异常捕获后尽力回 500,不向线程池抛出。
 *
 * ## 安全:鉴权 + 绑定地址(PRD 安全约束)
 * 与 Prometheus 端点同策略:token(可选 `Authorization: Bearer <token>`)+ IP 白名单双重校验,
 * 默认仅本机(127.0.0.1)可访问、不裸奔;日志不输出 token。
 *
 * 生命周期:作为 IOC [Service],[start] 于 `@PostEnable` 起服、[stop] 于 `@PreDestroy` 停服。
 * 默认关闭(`web.enabled=false`),需显式开启。
 */
@Service
class WebPanelServer {

    /** 只读数据出口,提供最新快照 / 启动画像 / 近期历史;经 IOC 注入。 */
    @Inject
    lateinit var readApi: ProbeReadApi

    /** 运行中的 HTTP 服务句柄;未开启或起服失败时为 null。 */
    @Volatile
    private var server: HttpServer? = null

    /**
     * 启动 Web 面板。
     *
     * 配置开关关闭则跳过;否则按 host/port 创建 [HttpServer],挂载 daemon 线程池与三个
     * 页面处理器并启动。整体 try/catch:任何异常仅 WARN 降级,绝不抛出。
     */
    @PostEnable
    fun start() {
        if (!ProbeConfig.webEnabled()) {
            ProbeLogger.info("Web 面板未开启(web.enabled=false),已跳过")
            return
        }
        stopQuietly()
        val host = ProbeConfig.webHost()
        val port = ProbeConfig.webPort()
        // 起服失败需广捕兜底:探针绝不成为事故源,任何异常只降级跳过,故 catch(Exception) 有意为之。
        @Suppress("TooGenericExceptionCaught")
        try {
            val httpServer = HttpServer.create(InetSocketAddress(host, port), 0)
            httpServer.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "ServerProbe-Web").apply { isDaemon = true }
            }
            val handler = PanelHandler(readApi, ProbeConfig.webToken(), ProbeConfig.webAllowedIps())
            httpServer.createContext(ROOT_PATH, handler)
            httpServer.createContext(STARTUP_PATH, handler)
            httpServer.createContext(HISTORY_PATH, handler)
            httpServer.createContext(NETWORK_FORENSICS_PATH, handler)
            httpServer.start()
            server = httpServer
            ProbeLogger.info("Web 面板已启动,监听 $host:$port(/、/startup、/history、/network-forensics)")
        } catch (e: Exception) {
            // 起服失败静默降级:探针绝不成为事故源
            ProbeLogger.warn("Web 面板启动失败($host:$port),已降级跳过:${e.message}(若端口被占用请改 web.port 后重试)")
        }
    }

    /**
     * 停止 Web 面板。
     *
     * 在容器关闭时由 `@PreDestroy` 回调触发,释放底层 HTTP 服务与线程池。
     */
    @PreDestroy
    fun stop() {
        stopQuietly()
        ProbeLogger.info("Web 面板已停止")
    }

    /** 安静地停止当前 HTTP 服务(若有),供 [start] 幂等保护与 [stop] 复用。 */
    private fun stopQuietly() {
        val current = server ?: return
        runCatching { current.stop(0) }
        server = null
    }

    private companion object {

        /** 总览页路径。 */
        private const val ROOT_PATH = "/"

        /** 启动画像页路径。 */
        private const val STARTUP_PATH = "/startup"

        /** 历史趋势页路径。 */
        private const val HISTORY_PATH = "/history"

        /** 网络包取证查询页路径。 */
        private const val NETWORK_FORENSICS_PATH = "/network-forensics"
    }
}

/**
 * Web 面板请求处理器:鉴权 + 按路径渲染对应页面(FR4.3)。
 *
 * 无状态(鉴权配置构造期固定),跑在 HttpServer 的 daemon 线程池上,可并发调用。
 *
 * @property readApi 只读数据出口。
 * @property token 鉴权 token;空串表示不启用 token 鉴权(仅 IP 白名单)。
 * @property allowedIps 允许访问的来源 IP 白名单(恒非空,至少含本机)。
 */
private class PanelHandler(
    private val readApi: ProbeReadApi,
    private val token: String,
    private val allowedIps: List<String>
) : HttpHandler {

    /**
     * 处理一次请求:先鉴权,通过后按路径渲染页面,否则回对应错误码。
     * 整体 try/catch 兜底:任何异常尽力回 500,绝不向线程池抛出。
     *
     * @param exchange 本次 HTTP 交换。
     */
    // 单次请求需广捕兜底:任何异常只降级该请求,不向线程池抛出,故 catch(Exception) 有意为之。
    @Suppress("TooGenericExceptionCaught")
    override fun handle(exchange: HttpExchange) {
        try {
            val deny = checkAuth(exchange)
            if (deny != null) {
                respond(exchange, deny, "Unauthorized")
                return
            }
            val html = when (exchange.requestURI.path) {
                "/startup" -> WebPanelHtml.renderStartup(readApi.lastStartupProfile())
                "/history" -> WebPanelHtml.renderHistory(readApi.recentSnapshots(HISTORY_LIMIT))
                "/network-forensics" -> renderNetworkForensics(exchange)
                else -> WebPanelHtml.renderHome(readApi.latestSnapshot())
            }
            respond(exchange, 200, html, "text/html; charset=utf-8")
        } catch (_: IllegalArgumentException) {
            respond(exchange, 400, "Bad Request")
        } catch (e: Exception) {
            ProbeLogger.warn("处理 Web 面板请求时发生异常:${e.message}")
            runCatching { respond(exchange, 500, "Internal Server Error") }
        } finally {
            exchange.close()
        }
    }

    /** 解析受限查询并经既有只读 API 读取完整取证记录；鉴权已在外层完成。 */
    private fun renderNetworkForensics(exchange: HttpExchange): String {
        if (exchange.requestURI.rawQuery.isNullOrBlank()) {
            return WebPanelHtml.renderNetworkForensicsGuide()
        }
        val query = NetworkForensicsWebQuery.parse(exchange.requestURI.rawQuery)
        return WebPanelHtml.renderNetworkForensics(
            readApi.queryNetworkPackets(query.toApiQuery()),
            readApi.networkForensicsStatus(),
            query,
        )
    }

    /** 鉴权校验:token(若启用)+ IP 白名单。返回拒绝状态码或 null(通过)。 */
    private fun checkAuth(exchange: HttpExchange): Int? {
        if (token.isNotEmpty() && !isBearerMatched(exchange)) {
            return 401
        }
        if (!isIpAllowed(exchange.remoteAddress)) {
            return 403
        }
        return null
    }

    /** 校验 `Authorization: Bearer <token>`。 */
    private fun isBearerMatched(exchange: HttpExchange): Boolean {
        val authorization = exchange.requestHeaders.getFirst(HEADER_AUTHORIZATION) ?: return false
        if (!authorization.startsWith(BEARER_PREFIX)) {
            return false
        }
        return authorization.substring(BEARER_PREFIX.length) == token
    }

    /** 校验来源 IP 是否在白名单内。 */
    private fun isIpAllowed(remote: InetSocketAddress?): Boolean {
        val ip = remote?.address?.hostAddress ?: return false
        return ip in allowedIps
    }

    /** 回写响应(UTF-8)。 */
    private fun respond(exchange: HttpExchange, code: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set(HEADER_CONTENT_TYPE, contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {

        /** 请求头名:鉴权。 */
        private const val HEADER_AUTHORIZATION = "Authorization"

        /** Bearer token 前缀。 */
        private const val BEARER_PREFIX = "Bearer "

        /** 响应头名:内容类型。 */
        private const val HEADER_CONTENT_TYPE = "Content-Type"

        /** 历史趋势页展示的快照条数。 */
        private const val HISTORY_LIMIT = 120
    }
}
