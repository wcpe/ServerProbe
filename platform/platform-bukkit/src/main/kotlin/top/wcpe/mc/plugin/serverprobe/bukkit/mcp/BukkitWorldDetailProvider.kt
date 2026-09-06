package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.platform.Folia
import top.wcpe.mc.plugin.serverprobe.bukkit.collector.BukkitWorldCollector
import top.wcpe.mc.plugin.serverprobe.bukkit.folia.FoliaObservedRegionService
import top.wcpe.mc.plugin.serverprobe.core.mcp.WorldDetail
import top.wcpe.mc.plugin.serverprobe.core.mcp.WorldDetailProvider
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * Bukkit 世界明细提供者（FR-17）。
 *
 * 为 `server_status` 的 worlds 输出提供增强字段，**不重复遍历实体、不新增采集能力**：
 * - [WorldDetail.entityTypeCounts]：复用 [BukkitWorldCollector] 的主线程限频采样缓存
 *   （口径与既有 `WorldMetrics.entitiesByType` 一致）。采集器以
 *   `world-entity-types=false` 关闭类型统计或 Folia 受限时，其缓存中的
 *   `entitiesByType` 为 null，此处原样透传 null（N/A），与既有聚合输出语义一致。
 * - [WorldDetail.regionStats]：Folia 下透传 [FoliaObservedRegionService.snapshot] 的
 *   已观测 region 明细（FR-12 复用）；非 Folia 为 null。
 *
 * 线程模型：MCP 请求线程仅读取各采集服务的 `@Volatile` 缓存（Bukkit 世界数据已由
 * 采集器在主线程限频采样完毕），本实现不做任何主线程切换或实体遍历。
 *
 * 仅在 Bukkit 平台生效（[PlatformSide]），由 IOC 容器实例化并作为
 * [WorldDetailProvider] Bean 注入 core 的 [top.wcpe.mc.plugin.serverprobe.core.mcp.McpControlPlane]。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitWorldDetailProvider : WorldDetailProvider {

    /** 世界指标采集器缓存（实体类型分布数据源；Folia/关闭统计时为 null）。 */
    @Inject(required = false)
    lateinit var worldCollector: BukkitWorldCollector

    @Inject(required = false)
    lateinit var foliaObservedRegionService: FoliaObservedRegionService

    override fun details(): List<WorldDetail> {
        // 非 Bukkit 平台（IOC 不感知 @PlatformSide 时的兜底门）不读取 Bukkit 缓存
        if (Platform.CURRENT != Platform.BUKKIT) {
            return emptyList()
        }
        return runCatching {
            val regionByWorld = if (Folia.isFolia) {
                // @Inject(required=false)：Bean 缺席（非 Bukkit 或服务降级）时跳过 region 明细，避免 NPE 全量降级
                foliaObservedRegionService?.snapshot()?.regions?.groupBy { it.worldName } ?: emptyMap()
            } else {
                emptyMap()
            }
            worldCollector.collect().map { metrics ->
                WorldDetail(
                    name = metrics.name,
                    entityTypeCounts = metrics.entitiesByType,
                    regionStats = regionByWorld[metrics.name],
                )
            }
        }.onFailure { error ->
            ProbeLogger.warn("世界明细提供者采样失败：${error.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }
}
