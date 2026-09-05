package top.wcpe.mc.plugin.serverprobe.bukkit.collector

import org.bukkit.Bukkit
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.platform.Folia
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.sampler.ServerTickSampler
import top.wcpe.mc.plugin.serverprobe.bukkit.folia.FoliaObservedRegionService
import top.wcpe.mc.plugin.serverprobe.bukkit.sampler.TickClock
import top.wcpe.mc.plugin.serverprobe.bukkit.sampler.TickSamplerFactory
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.ManagementFactory
import top.wcpe.mc.plugin.serverprobe.api.collector.ServerMetricsCollector as ServerMetricsCollectorApi

/**
 * Bukkit 服务器指标采集器(FR2.2)。
 *
 * 采集 TPS/MSPT(经 [ServerTickSampler])、在线/最大人数与运行时长,聚合为 [ServerMetrics]。
 * TPS/MSPT 是架构中唯一需要多版本 + 多平台兼容的关键指标,采样器在 [PostConstruct] 由
 * [TickSamplerFactory] 按环境(Paper / 老版本 NMS / 自采 / Folia)探测装配。
 *
 * 仅在 Bukkit 平台生效([PlatformSide]);作为 IOC [Service] 由容器实例化并注入 [registry],
 * 初始化完成后自注册到 [ProbeRegistry] 供编排层发现。Legacy/Self 采样路径需自建 [TickClock]
 * 喂样本,其启停绑定本采集器生命周期([PostConstruct] 启动、[PreDestroy] 取消)。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitServerCollector : ServerMetricsCollectorApi {

    /** 组件注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: ProbeRegistry

    /** Folia 已观测 region 服务；非 Folia 时返回空快照。 */
    @Inject
    lateinit var foliaObservedRegionService: FoliaObservedRegionService

    /** 选中的 tick 采样器,[PostConstruct] 后非空。 */
    private lateinit var tickSampler: ServerTickSampler

    /** 自建 tick 时钟;仅 Legacy/Self 路径有值,Paper/Folia 路径为 null。 */
    private var tickClock: TickClock? = null

    /**
     * 依赖注入完成后探测装配采样器、按需启动 tick 时钟并自注册。
     *
     * 采用 [PostConstruct] 而非构造期,确保 [registry] 已注入;同时将采样器一并登记到
     * [ProbeRegistry](契约约定 tick 采样器于 P5 就绪后自注册)。
     */
    @PostConstruct
    fun register() {
        // IOC 容器不感知 @PlatformSide,会在所有平台实例化本类;故在此显式平台门:
        // 仅 Bukkit 端注册,避免代理端触碰缺失的 Bukkit/NMS 类。
        if (Platform.CURRENT != Platform.BUKKIT) return
        val selection = TickSamplerFactory.create()
        tickSampler = selection.sampler
        tickClock = selection.clock
        // 仅 Legacy/Self 路径需要 tick 时钟驱动样本
        tickClock?.start()
        registry.register(this)
        registry.register(tickSampler)
        ProbeLogger.info("服务器采集器已注册,TPS 来源=${tickSampler.source}")
    }

    /**
     * 卸载时停止 tick 时钟(若有),释放调度任务。
     */
    @PreDestroy
    fun shutdown() {
        tickClock?.stop()
    }

    /**
     * 采集当前服务器指标快照。
     *
     * @return 当前时刻的 [ServerMetrics]。
     */
    override fun collect(): ServerMetrics {
        val players = Bukkit.getOnlinePlayers()
        // 在线玩家 ping 分布(FR2.4):1.16.1+ 有 Player#getPing()(内存读取,开销小);
        // 低版本无该方法时会抛 NoSuchMethodError,此处 runCatching 兜底为"不可用",绝不成为事故源。
        // 在线但全部 ping 不可用(如低版本)时分布置 null(N/A),与"无人"区分。
        val pings = players.mapNotNull { p ->
            runCatching { p.ping }.getOrNull()?.takeIf { it >= 0 }
        }
        val pingDistribution = if (pings.isNotEmpty()) PingBuckets.bucket(pings) else null
        val metrics = ServerMetrics.builder()
            .tick(tickSampler.sample())
            .onlinePlayers(players.size)
            .maxPlayers(Bukkit.getMaxPlayers())
            .uptimeMs(ManagementFactory.getRuntimeMXBean().uptime)
            .pingDistribution(pingDistribution)
        if (Folia.isFolia) {
            val observed = foliaObservedRegionService.snapshot()
            metrics.observedRegions(observed.regions)
            metrics.observedRegionWorlds(observed.worlds)
        }
        return metrics.build()
    }
}
