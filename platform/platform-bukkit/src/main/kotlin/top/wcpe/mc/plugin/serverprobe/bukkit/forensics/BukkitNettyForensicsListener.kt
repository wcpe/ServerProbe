package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.taboolib.ioc.annotation.Inject

/** 将 Bukkit 玩家连接生命周期映射为 Netty 管线的幂等接入与拆除。 */
@PlatformSide(Platform.BUKKIT)
object BukkitNettyForensicsListener {

    @Inject
    lateinit var forensics: BukkitNettyForensicsLifecycle

    /** 玩家已完成登录时接入其网络 Channel。 */
    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        forensics.attach(event.player)
    }

    /** 玩家离开时主动移除处理器，重连会得到新的独立处理器。 */
    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        forensics.detach(event.player)
    }
}
