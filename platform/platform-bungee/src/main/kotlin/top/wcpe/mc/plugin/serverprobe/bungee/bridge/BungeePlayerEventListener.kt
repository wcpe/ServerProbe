package top.wcpe.mc.plugin.serverprobe.bungee.bridge

import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ServerSwitchEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.taboolib.ioc.annotation.Inject

/**
 * BungeeCord 代理端玩家路由监听器(FR-066,JianManager ADR-016 探针侧)。
 *
 * 代理端是「精确跨服感知」的来源:它知道玩家在群组内**哪些子服之间切换**。监听:
 * - [PostLoginEvent]:玩家连入代理(尚未进入任何子服)→ 上报 player_join(代理视角的入网)。
 * - [ServerSwitchEvent]:玩家进入/切换子服 → 上报 cross_server(from→to,首个子服时 from 为空)。
 * - [PlayerDisconnectEvent]:玩家离开代理 → 上报 player_quit(代理视角的离网)。
 *
 * 与 Bukkit 子服端的 join/quit(子服本地视角)互补:代理端给出**跨子服的路由全貌**,
 * 子服端给出**各子服本地的进出**;CP 侧据二者维护精确的「玩家在哪个子服」名册。
 *
 * ## 线程与零阻塞
 * 代理事件在 BungeeCord 事件线程派发;[BridgeClient.emitPlayerEvent] 整体不抛、写经 socket 写锁串行化
 * (未连接静默丢弃、失败仅 WARN),任意线程调用安全,绝不阻塞代理。
 *
 * ## 形态
 * `object` + `@SubscribeEvent`(TabooLib 要求事件宿主为 object);`@Inject` 由 IOC 注入 [BridgeClient]。
 * 仅 BungeeCord 平台生效([PlatformSide]);开关关闭(独立使用探针)时各回调直接返回,零副作用。
 */
@PlatformSide(Platform.BUNGEE)
object BungeePlayerEventListener {

    /** 插件桥客户端(core),玩家路由事件上报出口。 */
    @Inject
    lateinit var bridgeClient: BridgeClient

    /**
     * 玩家连入代理:上报 player_join(代理视角入网,尚未落到具体子服)。
     *
     * @param event 代理登录完成事件。
     */
    @SubscribeEvent
    fun onPostLogin(event: PostLoginEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        val player = event.player
        bridgeClient.emitPlayerEvent(
            EVENT_PLAYER_JOIN,
            mapOf(
                FIELD_PLAYER_NAME to player.name,
                FIELD_PLAYER_UUID to player.uniqueId.toString(),
            )
        )
    }

    /**
     * 玩家进入/切换子服:上报 cross_server(from→to)。
     *
     * [ServerSwitchEvent.getFrom] 在玩家首次进入子服时为 null(尚无来源子服),此时 fromServer 留空、
     * 由下游按「进入子服」处理;切换时 from 为原子服、to 为当前子服([net.md_5.bungee.api.connection.ProxiedPlayer.getServer]).
     *
     * @param event 子服切换事件。
     */
    @SubscribeEvent
    fun onServerSwitch(event: ServerSwitchEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        val player = event.player
        val to = player.server?.info?.name.orEmpty()
        val from = event.from?.name.orEmpty()
        bridgeClient.emitPlayerEvent(
            EVENT_CROSS_SERVER,
            mapOf(
                FIELD_PLAYER_NAME to player.name,
                FIELD_PLAYER_UUID to player.uniqueId.toString(),
                FIELD_FROM_SERVER to from,
                FIELD_TO_SERVER to to,
            )
        )
    }

    /**
     * 玩家离开代理:上报 player_quit(代理视角离网)。
     *
     * @param event 代理断开事件。
     */
    @SubscribeEvent
    fun onDisconnect(event: PlayerDisconnectEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        val player = event.player
        bridgeClient.emitPlayerEvent(
            EVENT_PLAYER_QUIT,
            mapOf(
                FIELD_PLAYER_NAME to player.name,
                FIELD_PLAYER_UUID to player.uniqueId.toString(),
            )
        )
    }

    /** 事件子类型:玩家进入(代理视角入网)。 */
    private const val EVENT_PLAYER_JOIN = "player_join"

    /** 事件子类型:玩家离开(代理视角离网)。 */
    private const val EVENT_PLAYER_QUIT = "player_quit"

    /** 事件子类型:跨服路由。 */
    private const val EVENT_CROSS_SERVER = "cross_server"

    /** 结构化字段:玩家名(键名须与 Worker 解析约定一致)。 */
    private const val FIELD_PLAYER_NAME = "playerName"

    /** 结构化字段:玩家 UUID。 */
    private const val FIELD_PLAYER_UUID = "playerUuid"

    /** 结构化字段:来源子服。 */
    private const val FIELD_FROM_SERVER = "fromServer"

    /** 结构化字段:目标子服。 */
    private const val FIELD_TO_SERVER = "toServer"
}
