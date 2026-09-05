package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketCapturePolicy
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketForensicsService
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficAggregator
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficReport
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficService
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficSource
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service

/** Bukkit、Spigot、Paper 与 Folia 共用的玩家 Netty 双向采集服务。 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitNettyForensicsService : PacketTrafficSource, BukkitNettyForensicsLifecycle {

    @Inject
    lateinit var forensics: PacketForensicsService

    @Inject
    lateinit var traffic: PacketTrafficService

    private val aggregator = PacketTrafficAggregator()
    private var channels: BukkitNettyChannelRegistry? = null

    /** 在取证存储启用后接入当前已在线玩家，后续连接由事件监听器接入。 */
    @PostEnable
    fun start() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        channels = BukkitNettyChannelRegistry(
            recorder = BukkitPacketForensicsRecorder(
                enqueue = forensics::enqueue,
                aggregator = aggregator,
                capturePolicy = capturePolicy(),
            ),
            maxPayloadBytes = ProbeConfig.networkForensicsMaxPayloadBytes(),
        )
        Bukkit.getOnlinePlayers().forEach(::attach)
        traffic.register(this)
        ProbeLogger.info("Bukkit Netty 数据包采集已启用")
    }

    /** 玩家加入后注入其已建立的 Channel；失败时只降级该连接。 */
    override fun attach(player: Player) {
        val attached = channels?.attach(player) ?: return
        if (!attached) ProbeLogger.warn("无法接入一条玩家网络连接，已跳过该连接的数据包取证")
    }

    /** 玩家离开后移除其处理器并清空未配对的有界原始包。 */
    override fun detach(player: Player) {
        channels?.detach(player)
    }

    /** 供后续统一采集出口提取并清空本窗口聚合指标。 */
    override fun snapshotTraffic(elapsedMillis: Long): PacketTrafficReport = aggregator.snapshotAndReset(elapsedMillis)

    /** 插件卸载时移除仍在线连接的处理器，避免旧实例滞留 ChannelPipeline。 */
    @PreDestroy
    fun stop() {
        traffic.unregister(this)
        channels?.detachAll()
        channels = null
    }

    private fun capturePolicy(): PacketCapturePolicy = PacketCapturePolicy(
        ProbeConfig.networkForensicsPayloadPacketTypes(),
        ProbeConfig.networkForensicsPayloadChannels(),
        ProbeConfig.networkForensicsMaxPayloadBytes(),
    )
}
