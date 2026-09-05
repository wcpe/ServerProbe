package top.wcpe.mc.plugin.serverprobe.core.forensics

import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection

/** 平台规范化完成后交给取证存储的一条待写入记录。 */
data class PacketForensicsObservation(
    val capturedAtMs: Long,
    val direction: PacketDirection,
    val playerUuid: String?,
    val playerName: String?,
    val ip: String?,
    val packetType: String,
    val channel: String?,
    val payload: CapturedPacketPayload,
)

/** 网络线程异步写入、受信任读取方查询取证记录的边界。 */
interface PacketForensicsStore : AutoCloseable {

    /** 非阻塞入队；队列已满或服务不可用时返回 false。 */
    fun enqueue(observation: PacketForensicsObservation): Boolean

    /** 在调用线程执行受限分页查询，不得由服务器主线程调用。 */
    fun query(query: NetworkPacketQuery): NetworkPacketPage

    /** 返回当前可用性与队列丢弃数。 */
    fun status(): NetworkForensicsStatus
}
