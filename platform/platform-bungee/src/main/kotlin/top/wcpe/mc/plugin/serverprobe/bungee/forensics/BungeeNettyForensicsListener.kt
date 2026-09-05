package top.wcpe.mc.plugin.serverprobe.bungee.forensics

import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.taboolib.ioc.annotation.Inject

/** BungeeCord 登录与断连事件到 Netty 取证服务的生命周期桥接。 */
@PlatformSide(Platform.BUNGEE)
object BungeeNettyForensicsListener {

    @Inject
    lateinit var forensics: BungeeNettyForensicsLifecycle

    /** 玩家已经建立后端桥接后再接入，避免登录初始管线被切换。 */
    @SubscribeEvent
    fun onServerConnected(event: ServerConnectedEvent) {
        forensics.attach(event.player)
    }

    /** 玩家断开时移除 ServerProbe 私有处理器。 */
    @SubscribeEvent
    fun onDisconnect(event: PlayerDisconnectEvent) {
        forensics.detach(event.player)
    }
}
