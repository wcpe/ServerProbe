package top.wcpe.mc.plugin.serverprobe.bukkit.bridge

import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.taboolib.ioc.annotation.Inject

/**
 * Bukkit 玩家事件监听器(FR-066,JianManager ADR-016 探针侧)。
 *
 * 监听本子服的玩家 join/quit/chat,经 [BridgeClient] 反向 WS 实时上报到本机 Worker
 * (→ gRPC → CP → SSE → 浏览器)。**本子服视角**:Bukkit 端只知玩家在「本子服」进出,
 * 跨服路由(玩家在群组内子服间切换)由代理端 [top.wcpe.mc.plugin.serverprobe.bungee.bridge.BungeePlayerEventListener]
 * 上报(see BC 端 cross_server)。子服名取探针配置的 server-name(与监控/桥握手口径一致)。
 *
 * ## 线程与零阻塞
 * join/quit 在主线程派发、chat 为异步事件;[BridgeClient.emitPlayerEvent] 内部写经 socket 写锁串行化、
 * 整体不抛(未连接静默丢弃、失败仅 WARN),故任意线程调用均安全且**绝不阻塞服务器主线程做网络 IO**
 * (写的是已连接 socket 的本机回环,极轻;且失败即弃)。
 *
 * ## 形态
 * `object` + `@SubscribeEvent`(TabooLib 要求事件宿主为 object,见 [top.wcpe.mc.plugin.serverprobe.bukkit.startup.StartupLoadListener]);
 * `@Inject` 由 IOC 注入 [BridgeClient]。仅 Bukkit 平台生效([PlatformSide])。
 *
 * ## 开关
 * 仅在插件桥开启([ProbeConfig.bridgeEnabled])时上报;关闭(独立使用探针)时所有回调直接返回,零副作用。
 */
@PlatformSide(Platform.BUKKIT)
object BukkitPlayerEventListener {

    /** 插件桥客户端(core),玩家事件上报出口。 */
    @Inject
    lateinit var bridgeClient: BridgeClient

    /**
     * 玩家进入本子服:上报 player_join(玩家名 + UUID + 本子服名)。
     *
     * @param event Bukkit 玩家加入事件。
     */
    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        bridgeClient.emitPlayerEvent(
            EVENT_PLAYER_JOIN,
            mapOf(
                FIELD_PLAYER_NAME to event.player.name,
                FIELD_PLAYER_UUID to event.player.uniqueId.toString(),
                FIELD_SERVER to serverName(),
            )
        )
    }

    /**
     * 玩家离开本子服:上报 player_quit(玩家名 + UUID + 本子服名)。
     *
     * @param event Bukkit 玩家退出事件。
     */
    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        bridgeClient.emitPlayerEvent(
            EVENT_PLAYER_QUIT,
            mapOf(
                FIELD_PLAYER_NAME to event.player.name,
                FIELD_PLAYER_UUID to event.player.uniqueId.toString(),
                FIELD_SERVER to serverName(),
            )
        )
    }

    /**
     * 玩家发言:上报 chat(玩家名 + 内容 + 本子服名)。
     *
     * 用 [AsyncPlayerChatEvent](异步派发,跨版本通用);仅读取玩家名与消息,不改写聊天、不取消事件,
     * 对聊天流程零干预。异步线程调用 [BridgeClient.emitPlayerEvent] 安全(见类 KDoc)。
     *
     * @param event Bukkit 异步聊天事件。
     */
    @SubscribeEvent
    fun onChat(event: AsyncPlayerChatEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        bridgeClient.emitPlayerEvent(
            EVENT_CHAT,
            mapOf(
                FIELD_PLAYER_NAME to event.player.name,
                FIELD_PLAYER_UUID to event.player.uniqueId.toString(),
                FIELD_MESSAGE to event.message,
                FIELD_SERVER to serverName(),
            )
        )
    }

    /** 本子服名:取探针配置的 server-name(与监控/桥握手口径一致);未配置时为空串(下游按实例聚合)。 */
    private fun serverName(): String = ProbeConfig.configuredServerName().orEmpty()

    /** 事件子类型:玩家进入。 */
    private const val EVENT_PLAYER_JOIN = "player_join"

    /** 事件子类型:玩家离开。 */
    private const val EVENT_PLAYER_QUIT = "player_quit"

    /** 事件子类型:聊天。 */
    private const val EVENT_CHAT = "chat"

    /** 结构化字段:玩家名(键名须与 Worker 解析约定一致)。 */
    private const val FIELD_PLAYER_NAME = "playerName"

    /** 结构化字段:玩家 UUID。 */
    private const val FIELD_PLAYER_UUID = "playerUuid"

    /** 结构化字段:消息内容。 */
    private const val FIELD_MESSAGE = "message"

    /** 结构化字段:子服名。 */
    private const val FIELD_SERVER = "server"
}
