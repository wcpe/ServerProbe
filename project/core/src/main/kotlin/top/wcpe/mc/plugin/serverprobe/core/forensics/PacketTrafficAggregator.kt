package top.wcpe.mc.plugin.serverprobe.core.forensics

import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/** 网络线程可并发写入的包速率、类型与脱敏 IP 聚合器。 */
class PacketTrafficAggregator(private val maxIpLabels: Int = DEFAULT_MAX_IP_LABELS) {

    /** 入方向字节与包数。 */
    private val ingressBytes = LongAdder()
    private val ingressPackets = LongAdder()

    /** 出方向字节与包数。 */
    private val egressBytes = LongAdder()
    private val egressPackets = LongAdder()

    /** 本窗口内各包类型的次数。 */
    private val packetTypes = ConcurrentHashMap<String, LongAdder>()

    /** 本窗口内各脱敏 IP 前缀的次数。 */
    private val maskedIps = ConcurrentHashMap<String, LongAdder>()

    /** EventLoop 侧只做无锁计数，不执行 IO 或排序。 */
    fun record(direction: PacketDirection, packetType: String, length: Int, ip: String?) {
        incrementDirection(direction, length.coerceAtLeast(0))
        packetTypes.getOrPut(packetType) { LongAdder() }.increment()
        ip?.let { maskedIps.getOrPut(IpMasker.mask(it)) { LongAdder() }.increment() }
    }

    /**
     * 提取并清空当前窗口，速率按 [elapsedMillis] 精确换算。
     *
     * Top-N 选择在采集线程进行，Prometheus 仅接收已脱敏的有限标签集合。
     */
    fun snapshotAndReset(elapsedMillis: Long): PacketTrafficReport {
        val elapsed = elapsedMillis.coerceAtLeast(MIN_ELAPSED_MILLIS)
        val ipCounts = drain(maskedIps)
        return PacketTrafficReport(
            ingressBytesPerSecond = rate(ingressBytes.sumThenReset(), elapsed),
            egressBytesPerSecond = rate(egressBytes.sumThenReset(), elapsed),
            ingressPacketsPerSecond = rate(ingressPackets.sumThenReset(), elapsed),
            egressPacketsPerSecond = rate(egressPackets.sumThenReset(), elapsed),
            packetTypeCounts = drain(packetTypes),
            maskedIpPacketCounts = topIps(ipCounts),
        )
    }

    /** 按方向累计字节和包数。 */
    private fun incrementDirection(direction: PacketDirection, length: Int) {
        if (direction == PacketDirection.INGRESS) {
            ingressBytes.add(length.toLong())
            ingressPackets.increment()
        } else {
            egressBytes.add(length.toLong())
            egressPackets.increment()
        }
    }

    /** 读取并清空一组 LongAdder，零值条目不进入快照。 */
    private fun drain(source: ConcurrentHashMap<String, LongAdder>): Map<String, Long> =
        source.entries.mapNotNull { (key, value) -> value.sumThenReset().takeIf { it > 0 }?.let { key to it } }.toMap()

    /** 将超出标签上限的前缀合并为 other。 */
    private fun topIps(counts: Map<String, Long>): Map<String, Long> {
        val sorted = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        val top = sorted.take(maxIpLabels.coerceAtLeast(0)).associate { it.key to it.value }.toMutableMap()
        val other = sorted.drop(maxIpLabels.coerceAtLeast(0)).sumOf { it.value }
        if (other > 0) top[OTHER_IP_LABEL] = other
        return top
    }

    /** 将窗口累计量换算为每秒速率。 */
    private fun rate(value: Long, elapsedMillis: Long): Long = value * MILLIS_PER_SECOND / elapsedMillis

    private companion object {
        private const val DEFAULT_MAX_IP_LABELS = 100
        private const val MIN_ELAPSED_MILLIS = 1L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val OTHER_IP_LABEL = "other"
    }
}

/** 一个采集窗口内的网络聚合快照。 */
data class PacketTrafficReport(
    val ingressBytesPerSecond: Long,
    val egressBytesPerSecond: Long,
    val ingressPacketsPerSecond: Long,
    val egressPacketsPerSecond: Long,
    val packetTypeCounts: Map<String, Long>,
    val maskedIpPacketCounts: Map<String, Long>,
)
