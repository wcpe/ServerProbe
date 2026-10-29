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
 * Bukkit 世界明细提供者。
 *
 * 为 `server_status`的 worlds 输出提供增强字段，**不重复遍历实体、不新增采集能力**：
 * - [WorldDetail.entityTypeCounts]：复用 [BukkitWorldCollector] 的主线程限频采样缓存
 *   （口径与既有 `WorldMetrics.entitiesByType`一致）。采集器以
 *   `world-entity-types=false`关闭类型统计或 Folia 受限时，其缓存中的
 *   `entitiesByType`为 null，此处原样透传 null（N/A），与既有聚合输出语义一致。
 * - [WorldDetail.regionStats]：Folia 下透传 [FoliaObservedRegionService.snapshot] 的
 * 已观测 region 明细（复用）；非 Folia 为 null。
 *
 * 线程模型：MCP 请求线程仅读取各采集服务的 `@Volatile`缓存（Bukkit 世界数据已由
 * 采集器在主线程限频采样完毕），本实现不做任何主线程切换或实体遍历。
 *
 * 仅在 Bukkit 平台生效（[PlatformSide]），由 IOC 容器实例化并作为
 * [WorldDetailProvider] Bean 注入 core 的 [top.wcpe.mc.plugin.serverprobe.core.mcp.McpControlPlane]。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitWorldDetailProvider : WorldDetailProvider {

    /**
     * 世界指标采集器（IOC 注入的真实实现）。
     *
     * 保留这个字段是为了 IOC 能够按具体类型注入；实际读取统一走 [worldMetricsReader]，
     * 后者默认转发到这里。
     */
    @Inject(required = false)
    lateinit var worldCollector: BukkitWorldCollector

    @Inject(required = false)
    lateinit var foliaObservedRegionService: FoliaObservedRegionService

    /**
     * 世界指标读取接缝。
     *
     * 默认为 null，此时读取转发给 [worldCollector]（生产路径）。
     * 测试可以把它换成一个固定数据的实现，从而验证“数据是否被正确透传”，
     * 而不是只能反射查字段类型。
     */
    internal var worldMetricsReader: WorldMetricsReader? = null

    /**
     * 平台门接缝。
     *
     * <p>生产行为与直接写 `Platform.CURRENT == Platform.BUKKIT` 完全一致（就是默认值）；
     * 开这个口子只为了让测试能模拟“运行在 Bukkit 上”，从而真正走到映射代码。</p>
     *
     * <p><b>为什么不用 `Platform.CURRENT` 直接测</b>：它是进程级不可变事实，
     * 单元测试跑在普通 JVM 上，永远不可能是 BUKKIT。不开口子的话，
     * 这个类最核心的“读缓存 → 映射明细”逻辑一行都覆盖不到。</p>
     */
    internal var isBukkitPlatform: () -> Boolean = { Platform.CURRENT == Platform.BUKKIT }

    override fun details(): List<WorldDetail> {
        // 非 Bukkit 平台（IOC 不感知 @PlatformSide 时的兜底门）不读取 Bukkit 缓存
        if (!isBukkitPlatform()) {
            return emptyList()
        }
        return runCatching {
            val regionByWorld = if (Folia.isFolia) {
                // @Inject(required=false)：Bean 缺席（非 Bukkit 或服务降级）时跳过 region 明细，避免 NPE 全量降级
                foliaObservedRegionService?.snapshot()?.regions?.groupBy { it.worldName } ?: emptyMap()
            } else {
                emptyMap()
            }
            reader().collect().map { metrics ->
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

    /**
     * 取当前读取源：优先测试注入的接缝，否则转发到 IOC 注入的采集器。
     *
     * 真实采集器未注入时返回空列表（与“还没有任何采样”同义），
     * 而不是抛异常——查询路径不应因为一个可选依赖缺席而崩。
     */
    private fun reader(): WorldMetricsReader =
        worldMetricsReader ?: WorldMetricsReader {
            if (::worldCollector.isInitialized) worldCollector.collect() else emptyList()
        }
}
