package top.wcpe.mc.plugin.serverprobe.core.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneModeService
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerGate
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerLauncher
import top.wcpe.mc.plugin.serverprobe.core.net.HttpListenerFactory
import top.wcpe.mc.plugin.serverprobe.core.net.JdkHttpListenerFactory
import top.wcpe.mc.plugin.serverprobe.core.net.LocalHttpListener
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Web 面板(M3,P2):启动画像详情 / 历史趋势 / 总览。
 *
 * 基于 JDK 自带 `com.sun.net.httpserver.HttpServer`(零第三方依赖)起轻量 HTTP 服务,
 * 提供三个只读页面:总览(`/`)、启动画像(`/startup`)、历史趋势(`/history`),
 * 数据经注入的 [ProbeReadApi] 读取(最新快照 / 最近启动画像 / 近期历史),HTML 由 [WebPanelHtml] 渲染。
 *
 * ## 托管模式下零监听(FR-34,协议规格 §3.6)
 * 托管模式下本面板**一律不起服**——判断依据是运行模式,不是 `web.enabled`:该键虽属 FORBIDDEN 档,
 * 但本地 `config.yml` 里遗留的 `true` 不会被迁移清掉,故由 [LocalListenerGate] 按模式强制关断。
 * 独立模式起停行为与引入托管能力前完全一致。
 *
 * ## 稳定性:绝不成为事故源
 * - [start] 只起后台线程,**主线程零阻塞**;起服失败仅 WARN 降级(如端口被占用),不影响插件启用;
 * - 请求处理跑在 HttpServer 自带 daemon 线程池(非主线程),不阻塞服务器主线程;
 * - 单次请求异常捕获后尽力回 500,不向线程池抛出。
 *
 * ## 安全:鉴权 + 绑定地址(PRD 安全约束)
 * 与 Prometheus 端点同策略:token(可选 `Authorization: Bearer <token>`)+ IP 白名单双重校验,
 * 默认仅本机(127.0.0.1)可访问、不裸奔;日志不输出 token。
 *
 * 生命周期:作为 IOC [Service],[start] 于 `@PostEnable`起服、[stop] 于 `@PreDestroy`停服。
 * 默认关闭(`web.enabled=false`),需显式开启。
 */
@Service
class WebPanelServer {

    /** 只读数据出口,提供最新快照 / 启动画像 / 近期历史;经 IOC 注入。 */
    @Inject
    lateinit var readApi: ProbeReadApi

    /**
     * 托管模式判定服务(FR-30):面板必须先等它给出结论,再决定是否 bind——
     * 否则托管探针可能在判定完成前抢先把端口开起来(FR-34 零监听承诺)。
     */
    @Inject
    lateinit var modeService: ControlPlaneModeService

    /**
     * 本地监听器工厂:把 `HttpServer.create` 收口为单一可替换注入点。
     * 单测据此断言"托管模式下一次 bind 都没有发生"(FR-34 验收手段)。
     */
    internal var listenerFactory: HttpListenerFactory = JdkHttpListenerFactory

    /** 运行中的监听句柄;未开启、托管模式或起服失败时为 null。 */
    @Volatile
    private var server: LocalHttpListener? = null

    /** 是否已进入停止流程:防止"stop 先到、bind 后到"时泄漏一个不该存在的监听。 */
    @Volatile
    private var stopping = false

    /** bind/stop 的串行化锁。 */
    private val bindLock = Any()

    /**
     * 启动 Web 面板。
     *
     * 配置开关关闭则跳过;否则起后台线程**等托管模式判定**,只有独立模式才真正 bind。
     * 主线程只做"读开关 + 起线程",既不阻塞也不做 IO。
     */
    @PostEnable
    fun start() {
        if (!ProbeConfig.webEnabled()) {
            ProbeLogger.info("Web 面板未开启(web.enabled=false),已跳过")
            return
        }
        stopping = false
        // 托管模式下必须零监听:先等模式判定,再在后台线程里决定是否 bind
        LocalListenerLauncher(LocalListenerGate.Surface.WEB, awaitDecision = { modeService.awaitReady(it) })
            .launch(::bindIfStandalone)
    }

    /**
     * 停止 Web 面板。
     *
     * 在容器关闭时由 `@PreDestroy` 回调触发,释放底层监听与线程池。
     */
    @PreDestroy
    fun stop() {
        stopping = true
        synchronized(bindLock) { stopQuietly() }
        ProbeLogger.info("Web 面板已停止")
    }

    /**
     * bind 的唯一入口:先过托管闸门,再按配置起服。
     *
     * 托管模式在此直接返回,**绝不创建监听器**;独立模式行为与改造前一致。内部可见以便单测断言"未 bind"。
     */
    internal fun bindIfStandalone() {
        // 判断依据是运行模式而非配置值:本地遗留的 web.enabled=true 不作数
        if (!LocalListenerGate.allows(LocalListenerGate.Surface.WEB)) {
            return
        }
        val host = ProbeConfig.webHost()
        val port = ProbeConfig.webPort()
        synchronized(bindLock) {
            // stop 已先到:不再 bind,避免留下无人关闭的监听
            if (stopping) {
                return
            }
            stopQuietly()
            // 起服失败需广捕兜底:探针绝不成为事故源,任何异常只降级跳过,故 catch(Exception) 有意为之。
            @Suppress("TooGenericExceptionCaught")
            try {
                server = listenerFactory.bind(InetSocketAddress(host, port), 0) { httpServer -> configure(httpServer) }
                ProbeLogger.info("Web 面板已启动,监听 $host:$port(/、/startup、/history、/network-forensics)")
            } catch (e: Exception) {
                // 起服失败静默降级:探针绝不成为事故源
                ProbeLogger.warn("Web 面板启动失败($host:$port),已降级跳过:${e.message}(若端口被占用请改 web.port 后重试)")
            }
        }
    }

    /**
     * 挂载线程池与各页面处理器(在 `HttpServer.create` 之后、`start` 之前执行)。
     *
     * @param httpServer JDK HTTP 服务实例。
     */
    private fun configure(httpServer: HttpServer) {
        httpServer.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ServerProbe-Web").apply { isDaemon = true }
        }
        val handler = PanelHandler(readApi, ProbeConfig.webToken(), ProbeConfig.webAllowedIps())
        httpServer.createContext(ROOT_PATH, handler)
        httpServer.createContext(STARTUP_PATH, handler)
        httpServer.createContext(HISTORY_PATH, handler)
        httpServer.createContext(NETWORK_FORENSICS_PATH, handler)
    }

    /** 安静地停止当前监听(若有),供 [bindIfStandalone] 幂等保护与 [stop] 复用。 */
    private fun stopQuietly() {
        val current = server ?: return
        runCatching { current.stop() }
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
 * Web 面板请求处理器:鉴权 + 按路径渲染对应页面。
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
