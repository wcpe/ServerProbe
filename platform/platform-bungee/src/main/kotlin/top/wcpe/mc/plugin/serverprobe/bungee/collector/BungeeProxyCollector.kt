package top.wcpe.mc.plugin.serverprobe.bungee.collector

import net.md_5.bungee.api.ProxyServer
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.server
import top.wcpe.mc.plugin.serverprobe.api.model.BackendServer
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import top.wcpe.mc.plugin.serverprobe.api.collector.ProxyMetricsCollector as ProxyMetricsCollectorApi

/**
 * BungeeCord 代理端指标采集器(M1,A 方案)。
 *
 * 采集代理总在线([ProxyServer.getPlayers])与各子服在线([ProxyServer.getServers] 的
 * [net.md_5.bungee.api.config.ServerInfo.getPlayers]),聚合为 [ProxyMetrics]。
 * 取数均为代理本地视角的内存读取,无网络往返。
 *
 * 仅在 BungeeCord 平台生效([PlatformSide]);作为 IOC [Service] 由容器实例化并注入 [registry],
 * 初始化完成后自注册到 [ProbeRegistry] 供编排层发现。[ProxyServer] 经 TabooLib 的 [server] 取得
 * (代理端 `PlatformIO.server()` 返回 `ProxyServer`)。
 *
 * 注:JVM 指标由 core 的通用 `JvmMetricsCollector`(平台无关)在代理端一并注册,无需在此重复;
 * 代理端快照自然含 jvm + proxy(server 为 null)。子服 ping/可达性、玩家路由等留 M2。
 */
@Service
@PlatformSide(Platform.BUNGEE)
class BungeeProxyCollector : ProxyMetricsCollectorApi {

    /** 组件注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: ProbeRegistry

    /**
     * 依赖注入完成后自注册到注册中心。
     *
     * 采用 [PostConstruct] 而非构造期注册,确保 [registry] 已注入完毕再使用。
     */
    @PostConstruct
    fun register() {
        // IOC 容器不感知 @PlatformSide,会在所有平台实例化本类;故在此显式平台门:
        // 仅 BungeeCord 端注册,避免服务端编排调用 collect() 触碰缺失的 ProxyServer 类(NoClassDefFoundError)。
        if (Platform.CURRENT != Platform.BUNGEE) return
        registry.register(this)
        ProbeLogger.info("代理端采集器已注册")
    }

    /**
     * 采集当前代理端指标快照。
     *
     * @return 当前时刻的 [ProxyMetrics]。
     */
    override fun collect(): ProxyMetrics {
        val proxy = server<ProxyServer>()
        return ProxyMetrics(
            totalOnline = proxy.players.size,
            backends = proxy.servers.map { (name, info) -> BackendServer(name, info.players.size) }
        )
    }
}
