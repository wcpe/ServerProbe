package top.wcpe.mc.plugin.serverprobe.core.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 统一流量出口应合并来源，并继续将 IP 标签限制为 Top 100 加 other。 */
class PacketTrafficServiceTest {

    @Test
    fun `合并多个来源并将超出上限的 IP 归入 other`() {
        val service = PacketTrafficService()
        service.register(FixedTrafficSource(report(10, mapOf("a" to 4, "b" to 2))))
        service.register(FixedTrafficSource(report(20, mapOf("a" to 3, "c" to 1))))

        service.refreshForTest(1_000)

        assertEquals(30, service.currentReport().ingressBytesPerSecond)
        assertEquals(7, service.currentReport().maskedIpPacketCounts["a"])
        assertEquals(2, service.currentReport().maskedIpPacketCounts["b"])
        assertEquals(1, service.currentReport().maskedIpPacketCounts["c"])
    }

    private fun report(bytes: Long, ips: Map<String, Long>) = PacketTrafficReport(
        ingressBytesPerSecond = bytes,
        egressBytesPerSecond = 0,
        ingressPacketsPerSecond = bytes,
        egressPacketsPerSecond = 0,
        packetTypeCounts = mapOf("Packet" to bytes),
        maskedIpPacketCounts = ips,
    )

    private class FixedTrafficSource(private val report: PacketTrafficReport) : PacketTrafficSource {
        override fun snapshotTraffic(elapsedMillis: Long): PacketTrafficReport = report
    }
}
