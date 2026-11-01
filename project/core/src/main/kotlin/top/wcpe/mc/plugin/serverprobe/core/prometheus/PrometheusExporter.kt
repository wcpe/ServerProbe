package top.wcpe.mc.plugin.serverprobe.core.prometheus

import com.sun.net.httpserver.HttpServer
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneModeService
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerGate
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerLauncher
import top.wcpe.mc.plugin.serverprobe.core.cpu.CpuAttributionSampler
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficService
import top.wcpe.mc.plugin.serverprobe.core.net.HttpListenerFactory
import top.wcpe.mc.plugin.serverprobe.core.net.JdkHttpListenerFactory
import top.wcpe.mc.plugin.serverprobe.core.net.LocalHttpListener
import top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileHolder
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Prometheus `/metrics`导出端点(导出通道之一)。
 *
 * 基于 JDK 自带 `com.sun.net.httpserver.HttpServer`(零第三方依赖)起一个轻量 HTTP 服务,
 * 在 `/metrics`路径以 Prometheus exposition format 暴露最新一份指标快照,供 Prometheus 抓取、对接 Grafana。
 *
 * ## 平台无关 / 双端各起一个
 * 本类位于平台无关的 core 模块,不触碰任何平台 API:数据经注入的 [ProbeReadApi] 取得,文本经
 * [PrometheusTextFormatter] 渲染。Bukkit 与代理端各为独立进程,各自按本端 `config.yml`的端口起**一个**端点。
 *
 * ## 托管模式下零监听(FR-46,协议规格 §3.6)
 * 托管模式下本端点**一律不起服**——判断依据是运行模式,不是 `metrics.enabled`:
 * 该键虽属 FORBIDDEN 档(禁止远程开启),但本地 `config.yml` 里遗留的 `true` 不会被迁移清掉,
 * 故由 [LocalListenerGate] 按模式强制关断。独立模式下起停行为与引入托管能力前完全一致。
 *
 * ## 稳定性:绝不成为事故源(规范/PRD 非功能性约束)
 * 探针为只读旁路,导出端点更不应影响宿主:
 * - [start] 只起后台线程,**主线程零阻塞**;起服失败仅 WARN 降级、绝不抛(如端口被占用);
 * - 请求处理跑在 HttpServer 自带的 **daemon 缓存线程池**(非主线程),不阻塞服务器主线程(规范 R7);
 * - 单次请求异常被捕获,尽力回 500 后即关闭交换,不向线程池抛出。
 *
 * ## 安全:鉴权 + 绑定地址 + IP 白名单(PRD 安全约束)
 * 见 [MetricsHttpHandler] 的鉴权策略:token(可选 Bearer)+ IP 白名单双重控制,默认仅本机可访问、不裸奔;
 * 日志中不输出 token。
 *
 * 生命周期:作为 IOC [Service] 由容器管理,[start] 于 `@PostEnable`起服、[stop] 于 `@PreDestroy`停服。
 */
@Service
class PrometheusExporter {

    /** 只读数据出口,提供最新指标快照供渲染;经 IOC 注入,与命令/其它消费方共享同一实例。 */
    @Inject
    lateinit var readApi: ProbeReadApi

    /** 运行期 CPU 归因采样器;供 /metrics 一并导出各插件样本占比(未启用时无数据跳过)。 */
    @Inject
    lateinit var cpuSampler: CpuAttributionSampler

    /** 的只读聚合流量出口，Prometheus 只读取脱敏后的计数。 */
    @Inject
    lateinit var traffic: PacketTrafficService

    /**
     * 托管模式判定服务(FR-42):本端点必须先等它给出结论,再决定是否 bind——
     * 否则托管探针可能在判定完成前抢先把端口开起来(FR-46 零监听承诺)。
     */
    @Inject
    lateinit var modeService: ControlPlaneModeService

    /**
     * 最近一次启动画像的内存持有者(FR-25);供 /metrics 导出启动区块。
     * 刻意只读内存值不读盘(请求线程禁阻塞 IO);代理端无生产者时恒为 null,启动区块自然跳过。
     */
    @Inject(required = false)
    var startupProfileHolder: StartupProfileHolder? = null

    /**
     * 本地监听器工厂:把 `HttpServer.create` 收口为单一可替换注入点。
     * 单测据此断言"托管模式下一次 bind 都没有发生"(FR-46 验收手段)。
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
     * 启动 `/metrics`端点。
     *
     * 配置开关关闭则直接跳过;否则起后台线程**等托管模式判定**,只有独立模式才真正 bind。
     * 主线程只做"读开关 + 起线程",既不阻塞也不做 IO。
     */
    @PostEnable
    fun start() {
        stopping = false
        // 配置读取与判定放到后台线程：主线程零阻塞，且避免“配置尚未注入时读到默认 false 而误跳过端点”。
        // 托管模式下必须零监听：先等模式判定，再在后台线程里决定是否 bind。
        LocalListenerLauncher(LocalListenerGate.Surface.METRICS, awaitDecision = { modeService.awaitReady(it) })
            .launch {
                if (!ProbeConfig.awaitLoaded()) {
                    ProbeLogger.warn("Prometheus 端点等待配置就绪超时，将按当前可见值判断 metrics.enabled")
                }
                if (!runCatching { ProbeConfig.metricsEnabled() }.getOrDefault(false)) {
                    ProbeLogger.info("Prometheus /metrics 端点未开启(metrics.enabled=false),已跳过")
                    return@launch
                }
                bindIfStandalone()
            }
    }

    /**
     * 停止 `/metrics`端点。
     *
     * 在容器关闭时由 `@PreDestroy` 回调触发,释放底层监听与线程池。
     */
    @PreDestroy
    fun stop() {
        stopping = true
        synchronized(bindLock) { stopQuietly() }
        ProbeLogger.info("Prometheus /metrics 端点已停止")
    }

    /**
     * bind 的唯一入口:先过托管闸门,再按配置起服。
     *
     * 独立模式行为与改造前一致(同样的 host/port、同样的 daemon 线程池、同样的 WARN 降级);
     * 托管模式在此直接返回,**绝不创建监听器**。内部可见以便单测断言"未 bind"。
     */
    internal fun bindIfStandalone() {
        // 判断依据是运行模式而非配置值:本地遗留的 metrics.enabled=true 不作数
        if (!LocalListenerGate.allows(LocalListenerGate.Surface.METRICS)) {
            return
        }
        val host = ProbeConfig.metricsHost()
        val port = ProbeConfig.metricsPort()
        synchronized(bindLock) {
            // stop 已先到:不再 bind,避免留下无人关闭的监听
            if (stopping) {
                return
            }
            // 幂等保护:重复回调时先停旧实例,避免句柄/端口泄漏
            stopQuietly()
            try {
                server = listenerFactory.bind(InetSocketAddress(host, port), 0) { httpServer -> configure(httpServer) }
                // 安全:日志只记地址端口与路径,绝不输出 token
                ProbeLogger.info("Prometheus /metrics 端点已启动,监听 $host:$port$METRICS_PATH")
            } catch (e: Exception) {
                // 起服失败静默降级:探针绝不成为事故源,不影响插件其余功能
                ProbeLogger.warn("Prometheus /metrics 端点启动失败($host:$port),已降级跳过:${e.message}(若端口被占用或系统保留,请改 metrics.port 后重试)")
            }
        }
    }

    /**
     * 挂载线程池与 `/metrics` 处理器(在 `HttpServer.create` 之后、`start` 之前执行)。
     *
     * @param httpServer JDK HTTP 服务实例。
     */
    private fun configure(httpServer: HttpServer) {
        // 请求处理跑在守护线程池:不阻塞主线程,且不阻碍 JVM 退出
        httpServer.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ServerProbe-Metrics").apply { isDaemon = true }
        }
        httpServer.createContext(
            METRICS_PATH,
            MetricsHttpHandler(
                readApi,
                ProbeConfig.metricsToken(),
                ProbeConfig.metricsAllowedIps(),
                cpuSampler,
                traffic,
                startupProfileProvider = { startupProfileHolder?.get() },
            )
        )
    }

    /**
     * 安静地停止当前监听(若有),供 [bindIfStandalone] 幂等保护与 [stop] 复用。
     *
     * 停服异常被吞并(此时本就在关闭路径,无需再向外抛)。
     */
    private fun stopQuietly() {
        val current = server ?: return
        runCatching { current.stop() }
        server = null
    }

    private companion object {

        /** 导出端点路径。 */
        private const val METRICS_PATH = "/metrics"
    }
}
