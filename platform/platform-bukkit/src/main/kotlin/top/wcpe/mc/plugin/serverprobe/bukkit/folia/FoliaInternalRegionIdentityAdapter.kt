package top.wcpe.mc.plugin.serverprobe.bukkit.folia

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 唯一允许访问 Folia 内部 region 类型的反射边界。
 *
 * 调用发生在 Folia 已进入真实 region tick 的事件线程中，只读取当前 region、中心区块与
 * 已发布的统计字段；任何解析失败均返回 null，调用方不得据此伪造指标。
 */
class FoliaInternalRegionIdentityAdapter private constructor(
    private val members: FoliaIdentityMembers
) {

    /** 返回事件线程当前真实 tick region 的轻量身份；无法解析时返回 null。 */
    fun current(): FoliaRegionContext? = runCatching {
        val region = members.currentRegion.invoke(null) ?: return null
        val data = members.regionData.invoke(region)
        val center = members.regionCenterChunk.invoke(region) ?: return null
        FoliaRegionContext(
            internalId = members.regionId.getLong(region),
            worldName = members.worldName.invoke(members.bukkitWorld.invoke(members.regionWorld.get(data))).toString(),
            centerChunkX = members.chunkX.getInt(center),
            centerChunkZ = members.chunkZ.getInt(center),
            playerCount = (members.playerCount.invoke(members.regionStats.invoke(data)) as Number).toInt()
        )
    }.getOrNull()

    companion object {

        /** 在 Folia 运行时解析必要成员，非 Folia 或版本不兼容时返回 null。 */
        fun create(): FoliaInternalRegionIdentityAdapter? = runCatching {
            val scheduler = Class.forName(TICK_REGION_SCHEDULER)
            val region = Class.forName(THREADED_REGION)
            val data = Class.forName(TICK_REGION_DATA)
            val stats = Class.forName(REGION_STATS)
            val chunk = Class.forName(CHUNK_POS)
            FoliaInternalRegionIdentityAdapter(FoliaIdentityMembers(
                currentRegion = scheduler.getMethod("getCurrentRegion"),
                regionCenterChunk = region.getMethod("getCenterChunk"),
                regionData = region.getMethod("getData"),
                regionId = region.getField("id"),
                regionWorld = data.getField("world"),
                regionStats = data.getMethod("getRegionStats"),
                playerCount = stats.getMethod("getPlayerCount"),
                bukkitWorld = data.getField("world").type.getMethod("getWorld"),
                worldName = Class.forName(BUKKIT_WORLD).getMethod("getName"),
                chunkX = chunk.getField("x"),
                chunkZ = chunk.getField("z")
            ))
        }.getOrNull()

        private const val TICK_REGION_SCHEDULER = "io.papermc.paper.threadedregions.TickRegionScheduler"
        private const val THREADED_REGION = "io.papermc.paper.threadedregions.ThreadedRegionizer\$ThreadedRegion"
        private const val TICK_REGION_DATA = "io.papermc.paper.threadedregions.TickRegions\$TickRegionData"
        private const val REGION_STATS = "io.papermc.paper.threadedregions.TickRegions\$RegionStats"
        private const val CHUNK_POS = "net.minecraft.world.level.ChunkPos"
        private const val BUKKIT_WORLD = "org.bukkit.World"
    }
}

/** Folia 内部反射成员的不可变集合，避免适配器构造器泄露反射细节。 */
private data class FoliaIdentityMembers(
    val currentRegion: Method,
    val regionCenterChunk: Method,
    val regionData: Method,
    val regionId: Field,
    val regionWorld: Field,
    val regionStats: Method,
    val playerCount: Method,
    val bukkitWorld: Method,
    val worldName: Method,
    val chunkX: Field,
    val chunkZ: Field,
)
