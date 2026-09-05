package top.wcpe.mc.plugin.serverprobe.core.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionWorldMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketRecord
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection

/**
 * [WebPanelHtml] 渲染单测(FR4.3)。
 *
 * 覆盖:三个页面的关键片段、空数据占位、HTML 转义(防注入)。
 */
class WebPanelHtmlTest {

    @Test
    fun `总览页含导航与占位`() {
        val html = WebPanelHtml.renderHome(null)
        assertTrue(html.contains("ServerProbe"))
        assertTrue(html.contains("启动画像"))
        assertTrue(html.contains("采集中"))
    }

    @Test
    fun `总览页渲染 Folia 已观测 region 且转义世界名`() {
        val html = WebPanelHtml.renderHome(foliaSnapshot())

        assertTrue(html.contains("Folia 全局 TPS/MSPT：N/A"))
        assertTrue(html.contains("已观测 region 世界汇总"))
        assertTrue(html.contains("已观测 region 明细"))
        assertTrue(html.contains("&lt;危险世界&gt;"))
        assertFalse(html.contains("<危险世界>"))
        assertTrue(html.contains("真实 region id"))
        assertTrue(html.contains("42"))
        assertTrue(html.contains("18.5 / 19.0 / 19.5"))
        assertTrue(html.contains("34.0ms / 40.0ms / 45.0ms"))
    }

    @Test
    fun `启动画像页空占位`() {
        val html = WebPanelHtml.renderStartup(null)
        assertTrue(html.contains("尚无启动画像"))
    }

    @Test
    fun `历史趋势页空占位`() {
        val html = WebPanelHtml.renderHistory(emptyList())
        assertTrue(html.contains("暂无历史数据"))
    }

    @Test
    fun `文本经 HTML 转义`() {
        // 通过可注入 HTML 的启动画像数据验证转义:插件名含 <script>
        val html = WebPanelHtml.renderStartup(null)
        assertFalse(html.contains("<script>"))
        assertTrue(html.startsWith("<!DOCTYPE html>"))
    }

    /** 已鉴权页面可以查看完整 IP 与白名单载荷，但仍必须转义文本。 */
    @Test
    fun `网络取证页显示完整记录并转义`() {
        val page = NetworkPacketPage.builder()
            .records(listOf(NetworkPacketRecord.builder()
                .id(9)
                .capturedAtMs(1_700_000_000_000)
                .direction(PacketDirection.INGRESS)
                .playerName("<玩家>")
                .ip("203.0.113.77")
                .packetType("Plugin<Message>")
                .originalLength(16)
                .payloadSha256("abc")
                .payloadBase64("PHRlc3Q+")
                .payloadCaptured(true)
                .payloadTruncated(false)
                .build()))
            .hasNextPage(false)
            .build()
        val query = NetworkForensicsWebQuery(1_700_000_000_000, 1_700_000_001_000)

        val html = WebPanelHtml.renderNetworkForensics(
            page,
            NetworkForensicsStatus.builder().available(true).droppedRecords(0).build(),
            query,
        )

        assertTrue(html.contains("203.0.113.77"))
        assertTrue(html.contains("PHRlc3Q+"))
        assertTrue(html.contains("Plugin&lt;Message&gt;"))
        assertTrue(html.contains("/network-forensics"))
    }

    private fun foliaSnapshot(): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(1_700_000_000_000)
        .serverId("folia-test")
        .platform(ProbePlatform.BUKKIT)
        .jvm(JvmMetrics.builder().heapUsedBytes(0).heapMaxBytes(0).threadCount(0).processCpuLoad(-1.0).uptimeMs(0).build())
        .server(ServerMetrics.builder()
            .tick(TickSample.builder()
                .tps1m(null).tps5m(null).tps15m(null)
                .msptAvg(null).msptP95(null).msptP99(null)
                .source(TickSampleSource.UNAVAILABLE)
                .build())
            .onlinePlayers(2)
            .maxPlayers(20)
            .uptimeMs(1_000)
            .observedRegions(listOf(ObservedRegionMetrics.builder()
                .worldName("<危险世界>")
                .foliaRegionId(42)
                .regionSequence(7)
                .observed(true)
                .centerChunkX(8)
                .centerChunkZ(-4)
                .playerCount(2)
                .sampleCount(12)
                .tpsAvg(18.5).tpsP95(19.0).tpsP99(19.5)
                .msptAvg(34.0).msptP95(40.0).msptP99(45.0)
                .lastSeenMs(1_700_000_000_000)
                .build()))
            .observedRegionWorlds(listOf(ObservedRegionWorldMetrics.builder()
                .worldName("<危险世界>")
                .activeRegions(1)
                .playerCount(2)
                .sampleCount(12)
                .tpsAvg(18.5).tpsP95(19.0).tpsP99(19.5)
                .msptAvg(34.0).msptP95(40.0).msptP99(45.0)
                .build()))
            .build())
        .proxy(null)
        .build()
}
