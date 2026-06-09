package top.wcpe.mc.plugin.serverprobe.bukkit.collector

import org.bukkit.Bukkit
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import top.wcpe.mc.plugin.serverprobe.api.model.WorldMetrics
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import top.wcpe.mc.plugin.serverprobe.api.collector.WorldMetricsCollector as WorldMetricsCollectorApi

/**
 * Bukkit 世界指标采集器(FR2.3)。
 *
 * 遍历所有世界,采集已加载区块数、实体数、方块实体(TileEntity)数及可选的按类型实体分布,
 * 聚合为各世界的 [WorldMetrics]。
 *
 * **线程模型**:读取 Bukkit 世界/区块/实体数据必须在服务器主线程,而编排层([MetricOrchestrator])
 * 在异步线程驱动采集。为此本采集器以 [ProbeConfig.worldSamplePeriodTicks] 为周期启动**主线程**
 * 限频采样任务([sampleWorlds]),把结果写入 [cache];[collect] 仅返回最近一次缓存(异步读 `@Volatile`
 * 字段安全)。世界采集开销高于轻量指标,独立限频避免每个 collect 周期重扫。
 *
 * **Folia 受限**:Folia 下实体/方块实体按区域分线程管理,需逐区块 `callRegion` 才能安全统计,
 * 成本较高(M2 后续完整支持)。当前 Folia 路线仅给出已加载区块数,实体/方块实体数置 -1(N/A)、
 * 按类型分布置 null。
 *
 * 仅在 Bukkit 平台生效([PlatformSide]);作为 IOC [Service] 由容器实例化并注入 [registry],
 * 初始化完成后自注册到 [ProbeRegistry] 供编排层发现。任务启停绑定本采集器生命周期
 * ([PostConstruct] 启动、[PreDestroy] 取消)。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitWorldCollector : WorldMetricsCollectorApi {

    /** 组件注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: ProbeRegistry

    /**
     * 最近一次世界采样结果缓存。
     *
     * 由主线程采样任务写、由异步编排线程经 [collect] 读,故用 `@Volatile` 保证可见性。
     */
    @Volatile
    private var cache: List<WorldMetrics> = emptyList()

    /** 主线程限频采样任务句柄;未启动或已停止时为 null。 */
    private var task: PlatformExecutor.PlatformTask? = null

    /**
     * 依赖注入完成后启动主线程限频采样任务并自注册。
     *
     * 采用 [PostConstruct] 而非构造期,确保 [registry] 已注入。采样任务以 `async = false`
     * 运行于主线程(读 Bukkit API 安全),单次采样异常被捕获记录,不中断调度。
     */
    @PostConstruct
    fun register() {
        // IOC 容器不感知 @PlatformSide,会在所有平台实例化本类;故在此显式平台门:
        // 仅 Bukkit 端注册,避免代理端触碰缺失的 Bukkit 类。
        if (Platform.CURRENT != Platform.BUKKIT) return
        task = submit(period = ProbeConfig.worldSamplePeriodTicks(), async = false) {
            runCatching { sampleWorlds() }.onFailure { ProbeLogger.error("世界采集失败", it) }
        }
        registry.register(this)
        ProbeLogger.info("世界采集器已注册,采样周期=${ProbeConfig.worldSamplePeriodTicks()} ticks")
    }

    /**
     * 卸载时取消采样任务,释放调度句柄。
     */
    @PreDestroy
    fun shutdown() {
        task?.cancel()
    }

    /**
     * 返回最近一次世界采样缓存。
     *
     * @return 各世界指标列表;尚无采样时为空列表。
     */
    override fun collect(): List<WorldMetrics> = cache

    /**
     * 遍历所有世界完成一次采样并刷新 [cache]。仅由调度器在主线程调用。
     *
     * 非 Folia:已加载区块数、实体总数(按开关附带类型分布)、方块实体数全量统计;
     * Folia:仅已加载区块数,实体/方块实体数置 -1(N/A)、类型分布置 null(详见类 KDoc)。
     */
    private fun sampleWorlds() {
        // Folia 下实体/方块实体逐区块 callRegion 统计成本高,M2 后续完整支持,当前 N/A
        val folia = taboolib.platform.Folia.isFolia
        val withTypes = ProbeConfig.worldEntityTypes()
        cache = Bukkit.getWorlds().map { world ->
            val loadedChunks = world.loadedChunks
            if (folia) {
                WorldMetrics(
                    name = world.name,
                    loadedChunks = loadedChunks.size,
                    entityCount = N_A,
                    tileEntityCount = N_A,
                    entitiesByType = null
                )
            } else {
                val entities = world.entities
                WorldMetrics(
                    name = world.name,
                    loadedChunks = loadedChunks.size,
                    entityCount = entities.size,
                    tileEntityCount = loadedChunks.sumOf { it.tileEntities.size },
                    entitiesByType = if (withTypes) entities.groupingBy { it.type.name }.eachCount() else null
                )
            }
        }
    }

    private companion object {

        /** 不可用占位:Folia 受限时实体/方块实体数以 -1 表示 N/A。 */
        private const val N_A = -1
    }
}
