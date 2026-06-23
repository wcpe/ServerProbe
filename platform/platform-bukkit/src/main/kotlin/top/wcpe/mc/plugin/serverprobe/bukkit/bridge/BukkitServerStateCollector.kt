package top.wcpe.mc.plugin.serverprobe.bukkit.bridge

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import top.wcpe.mc.plugin.serverprobe.core.bridge.ServerStateSupport
import top.wcpe.mc.plugin.serverprobe.core.json.Json

/**
 * Bukkit 子服全量状态采集器(FR-076,JianManager ADR-016 探针侧)。
 *
 * 为「服务器状态」按需查询([BukkitBridgeCommandHandler] 的 `query_state` 动作)采集本子服的全量内部状态,
 * 编码为单行 JSON 经 command_result.output 回传给 Worker(再透传前端 FR-077)。覆盖面:
 * - **server**:版本 / Bukkit 版本 / MOTD / 视距 / 在线·最大人数 / 白名单·在线模式 / 难度 / 插件清单(有界)。
 * - **worlds**:每个世界 名/环境/已加载区块/实体/方块实体/玩家数/难度(有界)。
 * - **jvm**:经 core [ServerStateSupport.jvmSnapshot](MXBean,平台无关)。
 * - **classloader**(FR-076 重点):类加载计数 + 各插件类加载器层级链(经 core 工具,**不枚举类**)。
 * - **scheduler**:待执行任务数 / 活跃 worker 数。
 * - **listeners**:已注册监听器条目数摘要(经 [HandlerList],有界)。
 *
 * ## 非侵入(PRD 验收:绝不拖慢服务器)
 * - 需要主线程的只读数据(世界/区块/实体/插件/调度器/监听器)由调用方([BukkitBridgeCommandHandler])
 *   经 `submit(async=false)` + 限时锁存在主线程**快照**;本采集器只做一次性只读读取,不持锁、不阻塞 tick。
 * - JVM / classloader 计数经 MXBean,**不需主线程**,但与上面同处一次快照内,开销极小。
 * - 大集合(插件 / 世界 / 加载器链)统一经 [ServerStateSupport.bounded] 有界裁剪 + 计数,绝不产生大帧。
 * - 全部 `runCatching` 兜底:任一子项异常 → 该项 N/A,不影响其余、不抛(沿用「探针只读优先、绝不成为事故源」)。
 */
object BukkitServerStateCollector {

    /**
     * 采集全量状态并编码为单行 JSON。整体绝不抛:任一分区异常降级为 `{ "error": ... }`,顶层始终产出合法 JSON。
     *
     * @return 全状态 JSON 字符串(server/worlds/jvm/classloader/scheduler/listeners)。
     */
    fun collectJson(): String {
        val state = linkedMapOf<String, Any?>(
            "collectedAt" to System.currentTimeMillis(),
            "server" to section { serverSection() },
            "worlds" to section { worldsSection() },
            "jvm" to section { ServerStateSupport.jvmSnapshot() },
            "classloader" to section { classloaderSection() },
            "scheduler" to section { schedulerSection() },
            "listeners" to section { listenersSection() },
        )
        return Json.encode(state)
    }

    /** 分区兜底:子项异常时该分区降级为 `{ "error": ... }`,不拖垮整帧。 */
    private inline fun section(block: () -> Any?): Any? =
        runCatching(block).getOrElse { linkedMapOf("error" to (it.message ?: "采集失败")) }

    /** server 分区:版本/视距/在线/MOTD/插件清单(有界)等。 */
    private fun serverSection(): Map<String, Any?> {
        val server = Bukkit.getServer()
        val plugins = Bukkit.getPluginManager().plugins.map { p ->
            linkedMapOf<String, Any?>(
                "name" to p.name,
                "version" to runCatching { p.description.version }.getOrDefault("?"),
                "enabled" to p.isEnabled,
            )
        }
        return linkedMapOf(
            "version" to server.version,
            "bukkitVersion" to server.bukkitVersion,
            "motd" to runCatching { server.motd }.getOrDefault(""),
            "viewDistance" to runCatching { server.viewDistance }.getOrDefault(-1),
            "onlinePlayers" to Bukkit.getOnlinePlayers().size,
            "maxPlayers" to server.maxPlayers,
            "onlineMode" to server.onlineMode,
            "whitelistEnabled" to runCatching { server.hasWhitelist() }.getOrDefault(false),
            "allowNether" to runCatching { server.allowNether }.getOrDefault(false),
            "allowEnd" to runCatching { server.allowEnd }.getOrDefault(false),
            "plugins" to ServerStateSupport.bounded(plugins),
        )
    }

    /** worlds 分区:每个世界 名/环境/区块/实体/方块实体/玩家数/难度(有界裁剪世界数)。 */
    private fun worldsSection(): Map<String, Any?> {
        val worlds = Bukkit.getWorlds().map { w ->
            val loadedChunks = runCatching { w.loadedChunks }.getOrNull()
            linkedMapOf<String, Any?>(
                "name" to w.name,
                "environment" to w.environment.name,
                "difficulty" to runCatching { w.difficulty.name }.getOrDefault("?"),
                "loadedChunks" to (loadedChunks?.size ?: -1),
                "entities" to runCatching { w.entities.size }.getOrDefault(-1),
                "tileEntities" to (loadedChunks?.sumOf { runCatching { it.tileEntities.size }.getOrDefault(0) } ?: -1),
                "players" to runCatching { w.players.size }.getOrDefault(-1),
                "seed" to runCatching { w.seed }.getOrDefault(0L),
            )
        }
        return ServerStateSupport.bounded(worlds)
    }

    /**
     * classloader 分区(FR-076 重点):类加载计数 + 各插件类加载器层级链。
     *
     * 计数经 core [ServerStateSupport.classLoadingCounts](ClassLoadingMXBean,不枚举类);
     * 每个插件取其实例 `javaClass.classLoader` 的 parent 链摘要(经 core 工具,只取加载器类名身份)。
     */
    private fun classloaderSection(): Map<String, Any?> {
        val perPlugin = Bukkit.getPluginManager().plugins.map { p ->
            val loader = runCatching { p.javaClass.classLoader }.getOrNull()
            linkedMapOf<String, Any?>(
                "plugin" to p.name,
                "loaderClass" to (runCatching { loader?.javaClass?.name }.getOrNull() ?: "?"),
                "chain" to ServerStateSupport.classLoaderChain(loader),
            )
        }
        return linkedMapOf(
            "counts" to ServerStateSupport.classLoadingCounts(),
            "pluginLoaders" to ServerStateSupport.bounded(perPlugin),
        )
    }

    /** scheduler 分区:待执行任务数 / 活跃 worker 数(版本通用 API)。 */
    private fun schedulerSection(): Map<String, Any?> {
        val scheduler = Bukkit.getScheduler()
        return linkedMapOf(
            "pendingTasks" to runCatching { scheduler.pendingTasks.size }.getOrDefault(-1),
            "activeWorkers" to runCatching { scheduler.activeWorkers.size }.getOrDefault(-1),
        )
    }

    /**
     * listeners 分区:已注册事件监听器条目数摘要(经 [HandlerList.getRegisteredListeners] 的全局视角)。
     *
     * 仅做计数级摘要(注册条目总数 + 按插件分组计数,有界),不逐条 dump 监听器实例(避免大数据)。
     */
    private fun listenersSection(): Map<String, Any?> {
        val registered = runCatching { HandlerList.getRegisteredListeners(null) }.getOrNull()
        if (registered == null) {
            // 部分实现 getRegisteredListeners(null) 不支持:降级为仅给出无法精确统计的标记。
            return linkedMapOf("supported" to false)
        }
        val byPlugin = registered.groupingBy { runCatching { it.plugin.name }.getOrDefault("?") }.eachCount()
        val byPluginList = byPlugin.entries
            .sortedByDescending { it.value }
            .map { linkedMapOf<String, Any?>("plugin" to it.key, "count" to it.value) }
        return linkedMapOf(
            "supported" to true,
            "totalRegistered" to registered.size,
            "byPlugin" to ServerStateSupport.bounded(byPluginList),
        )
    }
}
