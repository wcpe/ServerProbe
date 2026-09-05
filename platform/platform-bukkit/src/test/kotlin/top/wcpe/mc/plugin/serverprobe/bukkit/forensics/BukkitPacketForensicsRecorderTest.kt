package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketCapturePolicy
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketForensicsObservation
import top.wcpe.mc.plugin.serverprobe.core.forensics.PacketTrafficAggregator

/** Netty EventLoop 写入器的无 Bukkit 依赖测试。 */
class BukkitPacketForensicsRecorderTest {

    /** 写入器应同时更新聚合和有界取证记录。 */
    @Test
    @DisplayName("记录包时更新双向聚合与取证队列")
    fun recordsTrafficAndForensics() {
        val records = ArrayList<PacketForensicsObservation>()
        val aggregator = PacketTrafficAggregator()
        val recorder = BukkitPacketForensicsRecorder(
            enqueue = { records += it; true },
            aggregator = aggregator,
            capturePolicy = PacketCapturePolicy(setOf("bukkit.PluginMessage"), setOf("probe:test"), 64),
            nowMs = { 123L },
        )

        recorder.record(
            BukkitPacketIdentity("uuid", "玩家", "203.0.113.7"),
            PacketDirection.INGRESS,
            "bukkit.PluginMessage",
            "probe:test",
            RawPacketCapture(5, "hash", byteArrayOf(1, 2, 3), "bukkit.raw.ingress.1"),
        )

        assertEquals(1, records.size)
        assertEquals("玩家", records.single().playerName)
        assertTrue(records.single().payload.payloadBase64 != null)
        assertEquals(1, aggregator.snapshotAndReset(1_000).ingressPacketsPerSecond)
    }

    /** 无法解析频道的 Plugin Message 不得绕过双白名单。 */
    @Test
    @DisplayName("未解析插件频道不保存完整载荷")
    fun rejectsUnresolvedPluginChannelPayload() {
        val records = ArrayList<PacketForensicsObservation>()
        val pairing = BukkitPacketPairing(
            BukkitPacketIdentity(null, null, null),
            BukkitPacketForensicsRecorder(
                enqueue = { records += it; true },
                aggregator = PacketTrafficAggregator(),
                capturePolicy = PacketCapturePolicy(setOf("bukkit.ServerboundCustomPayloadPacket"), setOf("probe:test"), 64),
            ),
        ).apply { decodedHandlerAvailable = true }

        pairing.onInboundRaw(RawPacketCapture(1, "hash", byteArrayOf(1), "bukkit.raw.ingress.1"))
        pairing.onInboundPacket(ServerboundCustomPayloadPacket())

        assertEquals(1, records.size)
        assertNull(records.single().payload.payloadBase64)
    }

    private class ServerboundCustomPayloadPacket
}
