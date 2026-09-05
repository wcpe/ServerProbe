package top.wcpe.mc.plugin.serverprobe.core.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection

/** 数据包速率、类型计数与脱敏 IP Top-N 聚合测试。 */
class PacketTrafficAggregatorTest {

    /** 一个采集窗口应按实际窗口长度换算双向字节和包速率。 */
    @Test
    fun `窗口快照换算双向速率与包类型计数`() {
        val aggregator = PacketTrafficAggregator(maxIpLabels = 2)
        aggregator.record(PacketDirection.INGRESS, "Login", 100, "203.0.113.77")
        aggregator.record(PacketDirection.EGRESS, "KeepAlive", 50, "203.0.113.77")

        val report = aggregator.snapshotAndReset(500)

        assertEquals(200, report.ingressBytesPerSecond)
        assertEquals(100, report.egressBytesPerSecond)
        assertEquals(2, report.ingressPacketsPerSecond)
        assertEquals(2, report.egressPacketsPerSecond)
        assertEquals(1, report.packetTypeCounts["Login"])
        assertEquals(2, report.maskedIpPacketCounts["203.0.113.0/24"])
    }

    /** 超出 Top-N 的脱敏前缀必须折叠到 other。 */
    @Test
    fun `超过TopN的IP前缀折叠为other`() {
        val aggregator = PacketTrafficAggregator(maxIpLabels = 1)
        aggregator.record(PacketDirection.INGRESS, "A", 1, "203.0.113.1")
        aggregator.record(PacketDirection.INGRESS, "A", 1, "203.0.113.2")
        aggregator.record(PacketDirection.INGRESS, "A", 1, "198.51.100.2")

        val report = aggregator.snapshotAndReset(1_000)

        assertEquals(2, report.maskedIpPacketCounts["203.0.113.0/24"])
        assertEquals(1, report.maskedIpPacketCounts["other"])
    }
}
