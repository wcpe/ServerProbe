package top.wcpe.mc.plugin.serverprobe.velocity.forensics

import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.taboolib.ioc.annotation.Inject

/** Velocity 登录与断连事件到 Netty 取证服务的生命周期桥接。 */
@PlatformSide(Platform.VELOCITY)
object VelocityNettyForensicsListener {

    @Inject
    lateinit var forensics: VelocityNettyForensicsLifecycle

    /** 登录完成后 ConnectedPlayer 已关联 MinecraftConnection。 */
    @SubscribeEvent
    fun onPostLogin(event: PostLoginEvent) {
        forensics.attach(event.player)
    }

    /** 断连后移除当前连接上的 ServerProbe 处理器。 */
    @SubscribeEvent
    fun onDisconnect(event: DisconnectEvent) {
        forensics.detach(event.player)
    }
}
