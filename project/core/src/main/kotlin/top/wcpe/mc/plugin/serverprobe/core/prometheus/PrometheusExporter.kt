package top.wcpe.mc.plugin.serverprobe.core.prometheus

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
 * Prometheus `/metrics` 导出端点(FR4.2,导出通道之一)。
 *
 * 基于 JDK 自带 `com.sun.net.httpserver.HttpServer`(零第三方依赖)起一个轻量 HTTP 服务,
 * 在 `/metrics` 路径以 Prometheus exposition format 暴露最新一份指标快照,供 Prometheus 抓取、对接 Grafana。
 *
 * ## 平台无关 / 双端各起一个
 * 本类位于平台无关的 core 模块,不触碰任何平台 API:数据经注入的 [ProbeReadApi] 取得,文本经
 * [PrometheusTextFormatter] 渲染。Bukkit 与代理端各为独立进程,各自按本端 `config.yml` 的端口起**一个**端点。
 *
 * ## 稳定性:绝不成为事故源(规范/PRD 非功能性约束)
 * 探针为只读旁路,导出端点更不应影响宿主:
 * - [start] 整体包裹 try/catch,**起服失败仅 WARN 降级、绝不抛**(如端口被占用),不影响插件其余功能与启用流程;
 * - 请求处理跑在 HttpServer 自带的 **daemon 缓存线程池**(非主线程),不阻塞服务器主线程(规范 R7);
 * - 单次请求异常被捕获,尽力回 500 后即关闭交换,不向线程池抛出。
 *
 * ## 安全:鉴权 + 绑定地址 + IP 白名单(PRD 安全约束)
 * 见 [MetricsHttpHandler] 的鉴权策略:token(可选 Bearer)+ IP 白名单双重控制,默认仅本机可访问、不裸奔;
 * 日志中不输出 token。
 *
 * 生命周期:作为 IOC [Service] 由容器管理,[start] 于 `@PostEnable` 起服、[stop] 于 `@PreDestroy` 停服。
 */
@Service
class PrometheusExporter {

    /** 只读数据出口,提供最新指标快照供渲染;经 IOC 注入,与命令/其它消费方共享同一实例。 */
    @Inject
    lateinit var readApi: ProbeReadApi

    /** 运行中的 HTTP 服务句柄;未开启或起服失败时为 null。 */
    @Volatile
    private var server: HttpServer? = null

    /**
     * 启动 `/metrics` 端点。
     *
     * 流程:配置开关关闭则直接跳过;否则按配置的 host/port 创建 [HttpServer],挂载 daemon 线程池与
     * `/metrics` 处理器并启动。整体 try/catch:任何异常(端口占用、地址非法等)仅以 WARN 降级,
     * 绝不抛出,确保探针不因导出端点起不来而影响插件启用。
     */
    @PostEnable
    fun start() {
        if (!ProbeConfig.metricsEnabled()) {
            ProbeLogger.info("Prometheus /metrics 端点未开启(metrics.enabled=false),已跳过")
            return
        }
        // 幂等保护:重复回调时先停旧实例,避免句柄/端口泄漏
        stopQuietly()
        val host = ProbeConfig.metricsHost()
        val port = ProbeConfig.metricsPort()
        try {
            val httpServer = HttpServer.create(InetSocketAddress(host, port), 0)
            // 请求处理跑在守护线程池:不阻塞主线程,且不阻碍 JVM 退出
            httpServer.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "ServerProbe-Metrics").apply { isDaemon = true }
            }
            httpServer.createContext(
                METRICS_PATH,
                MetricsHttpHandler(readApi, ProbeConfig.metricsToken(), ProbeConfig.metricsAllowedIps())
            )
            httpServer.start()
            server = httpServer
            // 安全:日志只记地址端口与路径,绝不输出 token
            ProbeLogger.info("Prometheus /metrics 端点已启动,监听 $host:$port$METRICS_PATH")
        } catch (e: Exception) {
            // 起服失败静默降级:探针绝不成为事故源,不影响插件其余功能
            ProbeLogger.warn("Prometheus /metrics 端点启动失败($host:$port),已降级跳过:${e.message}(若端口被占用或系统保留,请改 metrics.port 后重试)")
        }
    }

    /**
     * 停止 `/metrics` 端点。
     *
     * 在容器关闭时由 `@PreDestroy` 回调触发,释放底层 HTTP 服务与线程池。
     */
    @PreDestroy
    fun stop() {
        stopQuietly()
        ProbeLogger.info("Prometheus /metrics 端点已停止")
    }

    /**
     * 安静地停止当前 HTTP 服务(若有):立即关闭(delay=0)并清空句柄。
     *
     * 供 [start] 幂等保护与 [stop] 复用;停服异常被吞并(此时本就在关闭路径,无需再向外抛)。
     */
    private fun stopQuietly() {
        val current = server ?: return
        runCatching { current.stop(0) }
        server = null
    }

    private companion object {

        /** 导出端点路径。 */
        private const val METRICS_PATH = "/metrics"
    }
}
