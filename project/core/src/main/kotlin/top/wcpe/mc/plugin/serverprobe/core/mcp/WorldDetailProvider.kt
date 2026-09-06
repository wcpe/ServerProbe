package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionMetrics

/**
 * MCP 世界明细提供者契约（FR-17）。
 *
 * 为 `server_status` 的 `worlds` 输出提供输出层增强字段：每世界的实体类型分布
 * （[WorldDetail.entityTypeCounts]）与 Folia 已观测 region 明细（[WorldDetail.regionStats]）。
 * 由平台模块（platform-bukkit）实现并作为 IOC Bean 注入 [McpControlPlane]；
 * 非 Bukkit 平台或实现缺失时 [McpControlPlane.serverStatus] 保持既有聚合输出（向后兼容）。
 *
 * 本契约只做输出增强，**不修改 api 的 [WorldMetrics] 数据模型**；实体类型分布与
 * region 明细均由平台侧复用既有采集能力（主线程限频采样缓存），MCP 请求线程仅读缓存。
 */
interface WorldDetailProvider {

    /**
     * 返回各世界的明细增强字段。
     *
     * @return 每世界一条明细；尚无采样或平台不支持时为空列表。
     */
    fun details(): List<WorldDetail>
}

/**
 * 单个世界的明细增强字段（FR-17）。
 *
 * [entityTypeCounts] 与 [regionStats] 均允许为 null：实体类型分布仅在开启
 * `world-entity-types` 且非 Folia 时有值（口径与既有 [WorldMetrics.entitiesByType] 一致）；
 * region 明细仅 Folia 有值（透传 FR-12 已观测 region 指标）。
 */
data class WorldDetail(
    /** 世界名称，与 [WorldMetrics.name] 对齐。 */
    val name: String,
    /** 按实体类型名 → 数量的分布；未开启采集或 Folia 受限时为 null。 */
    val entityTypeCounts: Map<String, Int>?,
    /** Folia 已观测 region 明细（透传 [ObservedRegionMetrics]）；非 Folia 为 null。 */
    val regionStats: List<ObservedRegionMetrics>?,
)
