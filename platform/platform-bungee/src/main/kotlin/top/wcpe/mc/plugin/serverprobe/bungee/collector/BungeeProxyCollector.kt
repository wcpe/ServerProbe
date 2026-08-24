package top.wcpe.mc.plugin.serverprobe.bungee.collector

import net.md_5.bungee.api.ProxyServer
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.server
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import top.wcpe.mc.plugin.serverprobe.api.collector.ProxyMetricsCollector
import top.wcpe.mc.plugin.serverprobe.api.model.BackendServer
import top.wcpe.mc.plugin.serverprobe.api.model.PlayerPing
import top.wcpe.mc.plugin.serverprobe.api.model.PlayerRoute
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * BungeeCord 代理端指标采集器(FR2.5)。
 *
 * 采集代理总在线、各子服在线,并扩展三组 M2+ 指标:
 * - **子服 ping / 可达性**:`ServerInfo.ping()` 有网络往返,由后台周期任务([pingTask])在
 *   异步线程逐子服发起并缓存最近结果到 [pingCache],[collect] 只读缓存,不阻塞采集/代理主线程。
 * - **玩家路由**:遍历代理玩家取其所在子服名([PlayerRoute])。
 * - **每玩家 ping**:遍历代理玩家取其 RTT([PlayerPing])。
 *
 * ## 类签名隔离(Bukkit 端不崩的关键)
 * 本类位于 platform-bungee 模块,方法实现大量触碰 BungeeCord API(ProxyServer / ServerInfo /
 * ServerPing / ProxiedPlayer)。探针 IOC 容器在 **Bukkit 端也会扫描本类**(@Service 扫描不分平台),
 * 若这些 BungeeCord 类型出现在**方法签名**(参数/返回/合成 lambda 签名)里,扫描器反射
 * `getDeclaredMethods` 时会抛 `NoClassDefFoundError` 并中断整个容器初始化(曾致 ProbeRegistry
 * 等 bean 全部漏注册)。因此本类**严禁在 lambda 参数中直接引用 BungeeCord 类型**,一律改用
 * `for` 循环(局部变量类型不进方法签名)。这是本模块的硬约束,新代码须遵守。
 *
 * 仅在 BungeeCord 平台生效([PlatformSide]);作为 IOC [Service] 由容器实例化并注入 [registry],
 * 初始化完成后自注册到 [ProbeRegistry] 供编排层发现。[ProxyServer] 经 TabooLib 的 [server] 取得。
 */
@Service
@PlatformSide(Platform.BUNGEE)
class BungeeProxyCollector : ProxyMetricsCollector {

    /** 组件注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: ProbeRegistry

    /**
     * 子服 ping 缓存:子服名 → 最近一次 ping 结果。由后台周期任务写、[collect] 读,
     * 用 [ConcurrentHashMap] 保证跨线程安全(读多写少)。
     */
    private val pingCache = ConcurrentHashMap<String, BackendPing>()

    /** 后台周期 ping 任务句柄;未启动或已停止时为 null。 */
    private var pingTask: PlatformExecutor.PlatformTask? = null

    /**
     * 依赖注入完成后自注册,并启动后台周期 ping 任务。
     *
     * 采用 [PostConstruct] 而非构造期注册,确保 [registry] 已注入完毕再使用;
     * 显式平台门避免代理端触碰缺失的 Bukkit 类(NoClassDefFoundError)。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUNGEE) return
        registry.register(this)
        startPingTask()
        ProbeLogger.info("代理端采集器已注册(含子服 ping/路由/每玩家 ping)")
    }

    /**
     * 卸载时取消后台周期 ping 任务,释放调度资源。
     */
    @PreDestroy
    fun shutdown() {
        pingTask?.cancel()
    }

    /**
     * 启动后台周期 ping 任务:每 [ProbeConfig.collectPeriodTicks] 个 tick 在异步线程
     * 对全部子服发起一次 `ServerInfo.ping()`,把最近结果写入 [pingCache]。
     *
     * 子服 ping 是网络往返(可能数秒),绝不能在采集/主线程同步等待,故独立后台任务;
     * 单次 ping 失败(超时/不可达)记录为不可达、pingMs=-1,不影响其它子服与整个任务。
     */
    private fun startPingTask() {
        pingTask = submit(period = ProbeConfig.collectPeriodTicks(), async = true) {
            runCatching { pingAllBackends() }
                .onFailure { ProbeLogger.error("代理端子服 ping 任务异常", it) }
        }
        ProbeLogger.info("已启动代理端子服 ping 周期任务")
    }

    /**
     * 对当前全部子服调用 Callback 式 ping,更新 [pingCache]。
     *
     * BungeeCord 2088 仅保留 `ping(Callback)`,不再提供无参 Future 重载；反射适配封装在
     * [BungeeServerPingInvoker]，其静态签名不含 BungeeCord 类型，保持 Bukkit 端扫描安全。
     */
    private fun pingAllBackends() {
        val proxy = server<ProxyServer>()
        for (info in proxy.servers.values) {
            val result = BungeeServerPingInvoker.ping(info, PING_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            pingCache[info.name] = BackendPing(result.pingMs, result.reachable)
        }
    }

    /**
     * 采集当前代理端指标快照。
     *
     * 聚合:总在线、各子服在线(叠加 ping 缓存结果)、玩家路由、每玩家 ping。
     * **注意**:全部用 `for` 循环(不用 lambda),BungeeCord 类型只出现在局部变量,不进方法签名。
     *
     * @return 当前时刻的 [ProxyMetrics]。
     */
    override fun collect(): ProxyMetrics {
        val proxy = server<ProxyServer>()
        val players = proxy.players
        val routes = ArrayList<PlayerRoute>()
        val pings = ArrayList<PlayerPing>()
        for (p in players) {
            val serverName = p.server?.info?.name
            if (serverName != null) {
                routes.add(PlayerRoute.builder().name(p.name).server(serverName).build())
            }
            pings.add(PlayerPing.builder().name(p.name).pingMs(p.ping).build())
        }
        val backends = ArrayList<BackendServer>()
        for ((name, info) in proxy.servers) {
            val cached = pingCache[name]
            backends.add(
                BackendServer.builder()
                    .name(name)
                    .online(info.players.size)
                    .pingMs(cached?.pingMs ?: -1)
                    .reachable(cached?.reachable ?: false)
                    .build()
            )
        }
        return ProxyMetrics.builder()
            .totalOnline(players.size)
            .backends(backends)
            .playerPings(pings)
            .playerRoutes(routes)
            .build()
    }

    /**
     * 子服 ping 缓存条目。
     *
     * @property pingMs 最近一次 ping 的 RTT(毫秒);-1 = 尚未测到 / 不可用。
     * @property reachable 最近一次 ping 是否成功。
     */
    private data class BackendPing(val pingMs: Int, val reachable: Boolean)

    private companion object {

        /** 单次子服 ping 的超时(秒):超时视为不可达,避免长时间挂起后台任务。 */
        private const val PING_TIMEOUT_SECONDS = 3L
    }
}
