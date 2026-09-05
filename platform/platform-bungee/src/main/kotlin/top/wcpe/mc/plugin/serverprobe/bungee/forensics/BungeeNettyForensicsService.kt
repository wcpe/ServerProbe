package top.wcpe.mc.plugin.serverprobe.bungee.forensics

import net.md_5.bungee.api.ProxyServer
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

/** BungeeCord 玩家代理连接的双向 Netty 数据包采集服务。 */
@Service
@PlatformSide(Platform.BUNGEE)
class BungeeNettyForensicsService : PacketTrafficSource, BungeeNettyForensicsLifecycle {

    @Inject
    lateinit var forensics: PacketForensicsService

    @Inject
    lateinit var traffic: PacketTrafficService

    private val aggregator = PacketTrafficAggregator()
    private var channels: ReflectiveNettyForensicsRegistry? = null

    /** 登录前已在线的玩家也应在服务启动后接入。 */
    @PostEnable
    fun start() {
        if (Platform.CURRENT != Platform.BUNGEE) return
        channels = ReflectiveNettyForensicsRegistry(
            platformId = PLATFORM_ID,
            channelGetterPath = listOf("getCh", "getHandle"),
            decoderHandlerName = PACKET_DECODER,
            encoderHandlerName = PACKET_ENCODER,
            recorder = NettyPacketForensicsRecorder(forensics::enqueue, aggregator, capturePolicy()),
            maxPayloadBytes = ProbeConfig.networkForensicsMaxPayloadBytes(),
            debug = NettyForensicsDebugLogger({ ProbeLogger.debugEnabled }, ProbeLogger::debug),
        )
        for (player in server<ProxyServer>().players) attach(player)
        traffic.register(this)
        ProbeLogger.info("BungeeCord Netty 数据包采集已启用")
    }

    /** 连接完成登录后接入；反射路径不可用时仅跳过这一条连接。 */
    override fun attach(player: Any) {
        val attached = channels?.attach(player, identityOf(player)) ?: return
        if (!attached) ProbeLogger.warn("无法接入一条 BungeeCord 玩家网络连接，已跳过该连接的数据包取证")
    }

    /** 连接断开后移除处理器，重连会得到独立处理器。 */
    override fun detach(player: Any) {
        channels?.detach(player)
    }

    /** 提取并清空本采样窗口的代理流量聚合。 */
    override fun snapshotTraffic(elapsedMillis: Long): PacketTrafficReport = aggregator.snapshotAndReset(elapsedMillis)

    /** 卸载时主动移除在线连接处理器，避免旧实例滞留 ChannelPipeline。 */
    @PreDestroy
    fun stop() {
        traffic.unregister(this)
        channels?.detachAll()
        channels = null
    }

    private fun identityOf(player: Any) = ReflectiveNettyPacketIdentity.resolve(player, "getName", "getSocketAddress")

    private fun capturePolicy(): PacketCapturePolicy = PacketCapturePolicy(
        ProbeConfig.networkForensicsPayloadPacketTypes(),
        ProbeConfig.networkForensicsPayloadChannels(),
        ProbeConfig.networkForensicsMaxPayloadBytes(),
    )

    private companion object {
        private const val PLATFORM_ID = "bungee"
        private const val PACKET_DECODER = "packet-decoder"
        private const val PACKET_ENCODER = "packet-encoder"
    }
}
