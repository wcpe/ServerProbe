package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.GcCollectorMetric
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.ProxyMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.TickSample
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.WorldMetrics

/**
 * [MetricSnapshotFrame] 单测（FR-34，协议规格 §3.4.4 帧结构 + §7.2 字段对照表）。
 *
 * 断言口径取自**协议规格**而不是实现：
 * - 帧头：`type=event` / `event=metric_snapshot` / `dedupKey=metric:<instanceId>:<ts>`；
 * - payload：`ts` / `sequence` / `metrics{...}`；
 * - `metrics` 逐字段与 §7.2 的 `/metrics` 对照表一一对应，且**不可用项按 `/metrics` 惯例不产出**。
 *
 * 重点锁定单位口径：`msptAvg`/`msptP95` 与 `/metrics` 的 `serverprobe_mspt_seconds` 同源（**秒**），
 * 规格 §7.2 明确"秒 → 毫秒换算在 Worker 侧进行，保持原逻辑"；`gcTimeMillis` 为毫秒（字段名自带单位）。
 */
class MetricSnapshotFrameTest {

    /** 帧结构与字段对照表：服务器端快照的全部可用字段都应出现且取值一致。 */
    @Test
    fun `metrics 与协议规格 7_2 对照表逐字段对应`() {
        val metrics = MetricSnapshotFrame.metrics(bukkitSnapshot())

        // 允许出现的键集合（多一个或少一个都算漂移）
        val expectedKeys = setOf(
            "tps", "msptAvg", "msptP95", "playersOnline", "heapUsed", "heapMax",
            "threads", "cpuLoad", "uptimeSeconds", "worlds", "gcCountTotal", "gcTimeMillis",
        )
        assertEquals(expectedKeys, metrics.keys, "metrics 字段集合必须与 §7.2 对照表一致")

        // serverprobe_tps{window="1m"} → tps（只取 1m 窗口）
        assertEquals(19.98, metrics["tps"], "tps 取 1m 窗口")
        // serverprobe_mspt_seconds{quantile="avg"/"p95"} → msptAvg/msptP95（秒，毫秒换算在 Worker 侧）
        assertEquals(0.00321, metrics["msptAvg"], "msptAvg 与 /metrics 的 mspt_seconds 同口径（秒）")
        assertEquals(0.0074, metrics["msptP95"], "msptP95 与 /metrics 的 mspt_seconds 同口径（秒）")
        // serverprobe_players_online → playersOnline
        assertEquals(42, metrics["playersOnline"])
        // serverprobe_heap_used_bytes / heap_max_bytes → heapUsed / heapMax（字节）
        assertEquals(1_234_567L, metrics["heapUsed"])
        assertEquals(4_294_967_296L, metrics["heapMax"])
        // serverprobe_threads → threads
        assertEquals(187, metrics["threads"])
        // serverprobe_system_cpu_load → cpuLoad（0~1）
        assertEquals(0.31, metrics["cpuLoad"])
        // serverprobe_uptime_seconds → uptimeSeconds（JVM 运行时长，秒）
        assertEquals(98_765.4, metrics["uptimeSeconds"])
        // serverprobe_gc_count_total / gc_time_seconds_total 汇总 → gcCountTotal / gcTimeMillis
        assertEquals(1_234L, metrics["gcCountTotal"], "各收集器累计次数求和")
        assertEquals(5_678L, metrics["gcTimeMillis"], "各收集器累计耗时求和（毫秒）")
    }

    /** 世界维度必须保留：`worlds.<世界名>.{loadedChunks,entities,tileEntities}` 与 `world_*{world=}` 对应。 */
    @Test
    fun `worlds 保留世界维度与三项计数`() {
        val metrics = MetricSnapshotFrame.metrics(bukkitSnapshot())

        val worlds = objectOf(metrics["worlds"])
        val overworld = objectOf(worlds["world"])
        assertEquals(1_024, overworld["loadedChunks"])
        assertEquals(210, overworld["entities"])
        assertEquals(88, overworld["tileEntities"])
        // 多世界时各自独立成项
        val nether = objectOf(worlds["world_nether"])
        assertEquals(7, nether["loadedChunks"])
    }

    /** 代理端没有 ServerMetrics：在线人数回退 `proxy_players_online`，不产出 TPS/MSPT/世界。 */
    @Test
    fun `代理端回退 proxy_players_online`() {
        val snapshot = MetricSnapshot.builder()
            .schemaVersion(1)
            .timestampMs(1_760_842_364_000L)
            .serverId("proxy-1")
            .platform(ProbePlatform.BUNGEE)
            .jvm(jvm())
            .proxy(ProxyMetrics.builder().totalOnline(7).build())
            .build()

        val metrics = MetricSnapshotFrame.metrics(snapshot)

        assertEquals(7, metrics["playersOnline"], "代理端应回退 proxy_players_online")
        assertFalse(metrics.containsKey("tps"), "代理端无 TPS，不得产出该字段")
        assertFalse(metrics.containsKey("worlds"), "代理端无世界概念，不得产出该字段")
    }

    /** 不可用项按 `/metrics` 惯例**不产出字段**，绝不填误导性的 0。 */
    @Test
    fun `不可用指标不产出字段`() {
        val snapshot = MetricSnapshot.builder()
            .schemaVersion(1)
            .timestampMs(1_760_842_364_000L)
            .serverId("folia-1")
            .platform(ProbePlatform.BUKKIT)
            .jvm(
                JvmMetrics.builder()
                    .heapUsedBytes(1L)
                    // -1 表示无上限：/metrics 不导出该序列
                    .heapMaxBytes(-1L)
                    // -1.0 表示当前 JDK 不提供
                    .systemCpuLoad(-1.0)
                    .threadCount(10)
                    .uptimeMs(1_000L)
                    // 无收集器明细时整个 GC 汇总不产出
                    .gcCollectors(null)
                    .build(),
            )
            .server(
                ServerMetrics.builder()
                    // Folia 全局 TPS/MSPT 为 null
                    .tick(TickSample.builder().build())
                    .onlinePlayers(0)
                    .maxPlayers(100)
                    .uptimeMs(1_000L)
                    // Folia 受限：实体/方块实体为 -1
                    .worlds(listOf(WorldMetrics.builder().name("world").loadedChunks(5).entityCount(-1).tileEntityCount(-1).build()))
                    .build(),
            )
            .build()

        val metrics = MetricSnapshotFrame.metrics(snapshot)

        assertFalse(metrics.containsKey("tps"), "TPS 为 null 时不得产出")
        assertFalse(metrics.containsKey("msptAvg"), "MSPT 为 null 时不得产出")
        assertFalse(metrics.containsKey("heapMax"), "heapMax=-1（无上限）时不得产出")
        assertFalse(metrics.containsKey("cpuLoad"), "CPU=-1.0（不支持）时不得产出")
        assertFalse(metrics.containsKey("gcCountTotal"), "无 GC 明细时不得产出汇总")
        assertEquals(0, metrics["playersOnline"], "在线人数 0 是有效值，应保留")
        val world = objectOf(objectOf(metrics["worlds"])["world"])
        assertEquals(5, world["loadedChunks"])
        assertFalse(world.containsKey("entities"), "实体数 -1（Folia 受限）时不得产出")
        assertFalse(world.containsKey("tileEntities"), "方块实体数 -1 时不得产出")
    }

    /** 帧头与载荷：type/event/dedupKey 与 payload 的 ts/sequence 逐项断言。 */
    @Test
    fun `帧头与载荷结构符合协议规格`() {
        val frame = MetricSnapshotFrame.of("server-1", 1_760_842_364L, 918_273L, bukkitSnapshot())

        assertEquals("event", frame["type"])
        assertEquals("metric_snapshot", frame["event"])
        assertEquals("metric:server-1:1760842364", frame["dedupKey"])
        assertEquals("server-1", frame["instance"], "与其它上行事件一致地自报实例")

        val payload = objectOf(frame["payload"])
        assertEquals(1_760_842_364L, payload["ts"], "ts 为 epoch 秒")
        assertEquals(918_273L, payload["sequence"])
        // metrics 必须是嵌套对象（不是字符串化的 JSON）
        val metrics = objectOf(payload["metrics"])
        assertEquals(19.98, metrics["tps"])
        assertEquals(42, metrics["playersOnline"])
        assertEquals(1_024, objectOf(objectOf(metrics["worlds"])["world"])["loadedChunks"])
    }

    /** 快照为 null 时返回空投影（调用方据此不推帧，避免发空指标）。 */
    @Test
    fun `快照为空时投影为空对象`() {
        assertTrue(MetricSnapshotFrame.metrics(null).isEmpty())
    }

    /** 断言用：把帧树中的嵌套对象取成 Map。 */
    @Suppress("UNCHECKED_CAST")
    private fun objectOf(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: throw AssertionError("期望嵌套对象，实际:$value")

    /** 构造一份"全字段可用"的 Bukkit 快照，数值与协议规格 §3.4.4 的示例对齐。 */
    private fun bukkitSnapshot(): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(1_760_842_364_000L)
        .serverId("server-1")
        .platform(ProbePlatform.BUKKIT)
        .jvm(jvm())
        .server(
            ServerMetrics.builder()
                .tick(
                    TickSample.builder()
                        .tps1m(19.98)
                        // 5m/15m 窗口不进入 metric_snapshot（对照表只取 1m）
                        .tps5m(19.5)
                        .tps15m(19.1)
                        .msptAvg(3.21)
                        .msptP95(7.4)
                        .msptP99(9.9)
                        .build(),
                )
                .onlinePlayers(42)
                .maxPlayers(100)
                .uptimeMs(3_600_000L)
                .worlds(
                    listOf(
                        WorldMetrics.builder().name("world").loadedChunks(1_024).entityCount(210).tileEntityCount(88).build(),
                        WorldMetrics.builder().name("world_nether").loadedChunks(7).entityCount(1).tileEntityCount(0).build(),
                    ),
                )
                .build(),
        )
        .build()

    /** 一份字段齐全的 JVM 指标（GC 明细刻意让汇总值落在 1234 次 / 5678 ms）。 */
    private fun jvm(): JvmMetrics = JvmMetrics.builder()
        .heapUsedBytes(1_234_567L)
        .heapCommittedBytes(2_000_000L)
        .heapMaxBytes(4_294_967_296L)
        .nonHeapUsedBytes(10L)
        .nonHeapCommittedBytes(20L)
        .nonHeapMaxBytes(-1L)
        .gcCollectors(
            listOf(
                GcCollectorMetric.builder().name("G1 Young Generation").collectionCount(1_000L).collectionTimeMs(4_000L).build(),
                GcCollectorMetric.builder().name("G1 Old Generation").collectionCount(234L).collectionTimeMs(1_678L).build(),
            ),
        )
        .threadCount(187)
        .daemonThreadCount(100)
        .peakThreadCount(200)
        .deadlockedThreadCount(0)
        .loadedClassCount(1_000)
        .totalLoadedClassCount(2_000L)
        .processCpuLoad(0.2)
        .systemCpuLoad(0.31)
        .uptimeMs(98_765_400L)
        .build()
}
