package top.wcpe.mc.plugin.serverprobe.velocity.forensics

import com.velocitypowered.api.proxy.ProxyServer
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.server
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.forensics.NettyPacketForensicsRecorder
import top.wcpe.mc.plugin.serverprobe.core.forensics.NettyForensicsDebugLogger
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketCapturePolicy
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketForensicsService
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficAggregator
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficReport
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficService
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficSource
import top.wcpe.mc.plugin.serverprobe.core.forensics.ReflectiveNettyForensicsRegistry
import top.wcpe.mc.plugin.serverprobe.core.forensics.ReflectiveNettyPacketIdentity
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service

/** Velocity 玩家代理连接的双向 Netty 数据包采集服务。 */
@Service
@PlatformSide(Platform.VELOCITY)
class VelocityNettyForensicsService : PacketTrafficSource, VelocityNettyForensicsLifecycle {

    @Inject
    lateinit var forensics: PacketForensicsService

    @Inject
    lateinit var traffic: PacketTrafficService

    private val aggregator = PacketTrafficAggregator()
    private var channels: ReflectiveNettyForensicsRegistry? = null

    /** 服务启动时补接已经完成登录的 Velocity 玩家。 */
    @PostEnable
    fun start() {
        if (!ProbeConfig.networkForensicsEnabled()) return
        if (Platform.CURRENT != Platform.VELOCITY) return
        channels = ReflectiveNettyForensicsRegistry(
            platformId = PLATFORM_ID,
            channelGetterPath = listOf("getConnection", "getChannel"),
            decoderHandlerName = MINECRAFT_DECODER,
            encoderHandlerName = MINECRAFT_ENCODER,
            recorder = NettyPacketForensicsRecorder(forensics::enqueue, aggregator, capturePolicy()),
            maxPayloadBytes = ProbeConfig.networkForensicsMaxPayloadBytes(),
            debug = NettyForensicsDebugLogger({ ProbeLogger.debugEnabled }, ProbeLogger::debug),
        )
        for (player in server<ProxyServer>().allPlayers) attach(player)
        traffic.register(this)
        ProbeLogger.info("Velocity Netty 数据包采集已启用")
    }

    /** PostLogin 后通过已验证的内部 getter 链接入 Channel。 */
    override fun attach(player: Any) {
        val attached = channels?.attach(player, identityOf(player)) ?: return
        if (!attached) ProbeLogger.warn("无法接入一条 Velocity 玩家网络连接，已跳过该连接的数据包取证")
    }

    /** Disconnect 后幂等拆除该连接的三个私有处理器。 */
    override fun detach(player: Any) {
        channels?.detach(player)
    }

    /** 提取并清空本采样窗口的代理流量聚合。 */
    override fun snapshotTraffic(elapsedMillis: Long): PacketTrafficReport = aggregator.snapshotAndReset(elapsedMillis)

    /** 卸载时释放全部已接入连接。 */
    @PreDestroy
    fun stop() {
        traffic.unregister(this)
        channels?.detachAll()
        channels = null
    }

    private fun identityOf(player: Any) = ReflectiveNettyPacketIdentity.resolve(player, "getUsername", "getRemoteAddress")

    private fun capturePolicy(): PacketCapturePolicy = PacketCapturePolicy(
        ProbeConfig.networkForensicsPayloadPacketTypes(),
        ProbeConfig.networkForensicsPayloadChannels(),
        ProbeConfig.networkForensicsMaxPayloadBytes(),
    )

    private companion object {
        private const val PLATFORM_ID = "velocity"
        private const val MINECRAFT_DECODER = "minecraft-decoder"
        private const val MINECRAFT_ENCODER = "minecraft-encoder"
    }
}
