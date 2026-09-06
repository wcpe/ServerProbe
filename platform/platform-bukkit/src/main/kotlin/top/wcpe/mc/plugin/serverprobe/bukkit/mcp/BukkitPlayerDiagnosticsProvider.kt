package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.core.mcp.BoundedEventRing
import top.wcpe.mc.plugin.serverprobe.core.mcp.InventorySummary
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerActivityEvent
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerDiagnosticsProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerLookupRequest
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerLookupResult
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.UUID

/**
 * FR-20 Bukkit 在线玩家诊断实现。
 *
 * - 主线程周期刷新在线玩家摘要到 [cache]（避免 MCP 请求线程直读 Bukkit）；
 *   单周期刷新上限 [CACHE_PLAYER_LIMIT]，超出截断标记。
 * - 背包摘要仅 Material/数量/槽位，**不读 NBT**（隐私与开销）。
 * - 事件时间线来自 [PlayerJoinEvent]/[PlayerQuitEvent]/[AsyncPlayerChatEvent]/[PlayerChangedWorldEvent]，
 *   写入有界环形缓冲 [BoundedEventRing]，按玩家 UUID 过滤。
 * - 隐私：响应仅经 MCP 单次返回，不写日志/磁盘、不进 Prometheus/Web/历史。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitPlayerDiagnosticsProvider : PlayerDiagnosticsProvider, Listener {

    /** 玩家名 → 摘要缓存（主线程写、MCP 线程读）。 */
    @Volatile
    private var cache: Map<String, PlayerLookupResult> = emptyMap()

    /** 最近事件环形缓冲（默认 2000）。 */
    private val eventRing = BoundedEventRing<Pair<UUID, PlayerActivityEvent>>(2000)

    private var task: taboolib.common.platform.service.PlatformExecutor.PlatformTask? = null

    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        // 主线程限频采样：默认与 world.sample-period-ticks 一致（约 30 秒）
        task = submit(period = 600L, async = false) {
            runCatching { refresh() }.onFailure { ProbeLogger.error("玩家诊断缓存刷新失败", it) }
        }
        // 事件监听：经 Bukkit 注册（需插件实例）
        runCatching {
            val plugin = requireNotNull(Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)) { "未找到 ServerProbe 插件实例" }
            Bukkit.getPluginManager().registerEvents(this, plugin)
        }.onFailure { ProbeLogger.warn("玩家诊断事件监听注册失败：${it.javaClass.simpleName}") }
        ProbeLogger.info("玩家诊断提供者已注册")
    }

    @PreDestroy
    fun shutdown() {
        task?.cancel()
    }

    override fun lookup(request: PlayerLookupRequest): PlayerLookupResult? {
        val key = request.name?.lowercase() ?: cache.values.firstOrNull { it.uuid == request.uuid.toString() }?.name?.lowercase()
            ?: return PlayerLookupResult(request.name ?: "", request.uuid?.toString() ?: "", online = false, reason = "玩家不在线")
        val cached = cache[key]
            ?: return PlayerLookupResult(request.name ?: "", request.uuid?.toString() ?: "", online = false, reason = "玩家数据未就绪，稍后重试")
        return cached
    }

    /** 主线程刷新：遍历在线玩家，背包摘要只取 Material/数量/槽位；单周期上限截断。 */
    private fun refresh() {
        val players = Bukkit.getOnlinePlayers()
        val limited = players.size > CACHE_PLAYER_LIMIT
        val sampled = players.take(CACHE_PLAYER_LIMIT)
        val updated = sampled.mapNotNull { player -> buildSummary(player) }.associateBy { it.name.lowercase() }
        cache = updated
        if (limited) ProbeLogger.debug("玩家诊断缓存已达单周期上限 ${CACHE_PLAYER_LIMIT}，已截断")
    }

    private fun buildSummary(player: Player): PlayerLookupResult? = runCatching {
        val location = player.location
        PlayerLookupResult(
            name = player.name,
            uuid = player.uniqueId.toString(),
            online = true,
            world = location.world?.name,
            location = linkedMapOf(
                "x" to location.x, "y" to location.y, "z" to location.z,
                "yaw" to location.yaw.toDouble(), "pitch" to location.pitch.toDouble(),
            ),
            gameMode = player.gameMode.name,
            health = player.health.toDouble(),
            foodLevel = player.foodLevel,
            saturation = player.saturation.toDouble(),
            xpLevel = player.level,
            inventory = player.inventory.contents.mapIndexedNotNull { slot, item ->
                item?.let { InventorySummary(it.type.name, it.amount, slot) }
            }.takeIf { it.isNotEmpty() },
            enderChest = player.enderChest.contents.mapIndexedNotNull { slot, item ->
                item?.let { InventorySummary(it.type.name, it.amount, slot) }
            }.takeIf { it.isNotEmpty() },
            equipment = listOfNotNull(
                player.inventory.helmet?.let { InventorySummary(it.type.name, it.amount, 0) },
                player.inventory.chestplate?.let { InventorySummary(it.type.name, it.amount, 1) },
                player.inventory.leggings?.let { InventorySummary(it.type.name, it.amount, 2) },
                player.inventory.boots?.let { InventorySummary(it.type.name, it.amount, 3) },
                player.inventory.itemInMainHand?.let { InventorySummary(it.type.name, it.amount, 4) },
            ).takeIf { it.isNotEmpty() },
            chunk = linkedMapOf("x" to (location.blockX shr 4), "z" to (location.blockZ shr 4)),
            recentActivity = eventRing.recent(MAX_ACTIVITY) { it.first == player.uniqueId }.map { it.second },
        )
    }.getOrNull()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        record(event.player.uniqueId, "join", event.player.name)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        record(event.player.uniqueId, "quit", event.player.name)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChat(event: AsyncPlayerChatEvent) {
        record(event.player.uniqueId, "chat", event.message.take(64))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        record(event.player.uniqueId, "world", event.player.world.name)
    }

    private fun record(uuid: UUID, type: String, detail: String?) {
        eventRing.add(uuid to PlayerActivityEvent(type, System.currentTimeMillis(), detail))
    }

    private companion object {
        const val CACHE_PLAYER_LIMIT = 200
        const val MAX_ACTIVITY = 20
        const val PLUGIN_NAME = "ServerProbe"
    }
}
