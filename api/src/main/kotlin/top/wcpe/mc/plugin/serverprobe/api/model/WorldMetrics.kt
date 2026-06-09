package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 单个世界的运行时指标快照(FR2.3,Bukkit 端)。
 *
 * 描述某一世界当前的已加载区块数、实体数、方块实体(TileEntity)数,以及可选的按类型实体分布。
 * 仅 Bukkit 系服务端具备此语义,聚合于 [ServerMetrics.worlds]。
 *
 * **Folia 受限说明**:Folia 下实体/方块实体按区域(region)分线程管理,需逐区块 `callRegion`
 * 才能安全统计,成本较高(M2 后续完整支持)。当前 Folia 路线仅给出已加载区块数,
 * [entityCount] 与 [tileEntityCount] 置 -1(表示 N/A),[entitiesByType] 置 null。
 *
 * @property name 世界名称。
 * @property loadedChunks 已加载区块数。
 * @property entityCount 实体总数;Folia 受限时为 -1(N/A)。
 * @property tileEntityCount 方块实体(TileEntity)总数;Folia 受限时为 -1(N/A)。
 * @property entitiesByType 按实体类型名聚合的数量分布;未开启分类统计或 Folia 受限时为 null。
 */
data class WorldMetrics(
    val name: String,
    val loadedChunks: Int,
    val entityCount: Int,
    val tileEntityCount: Int,
    val entitiesByType: Map<String, Int>?
)
