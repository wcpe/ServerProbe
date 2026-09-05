package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketCapturePolicy
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketForensicsObservation
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficAggregator

/** 已在 Netty EventLoop 中解析出的连接身份，不再回调 Bukkit API。 */
internal data class BukkitPacketIdentity(
    val playerUuid: String?,
    val playerName: String?,
    val ip: String?,
)

/** 一段原始网络字节的有界摘要，完整内容不会跨 EventLoop 留存。 */
internal data class RawPacketCapture(
    val originalLength: Int,
    val sha256: String,
    val candidatePayload: ByteArray,
    val fallbackPacketType: String,
)

/** Netty 侧仅聚合和入队的最小取证写入器。 */
internal class BukkitPacketForensicsRecorder(
    private val enqueue: (PacketForensicsObservation) -> Boolean,
    private val aggregator: PacketTrafficAggregator,
    private val capturePolicy: PacketCapturePolicy,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** 记录一条已完成包类型关联的原始数据包，不执行阻塞 IO。 */
    fun record(identity: BukkitPacketIdentity, direction: PacketDirection, packetType: String, channel: String?, raw: RawPacketCapture) {
        aggregator.record(direction, packetType, raw.originalLength, identity.ip)
        enqueue(
            PacketForensicsObservation(
                capturedAtMs = nowMs(),
                direction = direction,
                playerUuid = identity.playerUuid,
                playerName = identity.playerName,
                ip = identity.ip,
                packetType = packetType,
                channel = channel,
                payload = capturePolicy.capture(
                    packetType,
                    channel,
                    raw.originalLength,
                    raw.sha256,
                    raw.candidatePayload,
                ),
            ),
        )
    }
}
