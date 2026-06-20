package top.wcpe.mc.plugin.serverprobe.core.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.BackendServer
import top.wcpe.mc.plugin.serverprobe.api.model.GcCollectorMetric
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MemoryPoolMetric
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.model.WorldMetrics

/**
 * [PrometheusTextFormatter] 单元测试。
 *
 * 格式化器为纯函数,给定 [MetricSnapshot] 即可断言其 Prometheus 文本输出,无需 IOC/平台环境。
 * 覆盖:① 全字段服务端快照的关键指标行/label/类型/单位换算;② 不可用字段(null/-1/-1.0)不导出;
 * ③ 代理端快照仅含 jvm+proxy;④ snapshot=null 返回空串;⑤ label 值转义。
 */
class PrometheusTextFormatterTest {

    /** ① 全字段服务端快照:关键指标行、label、类型声明、ms→s 换算均正确。 */
    @Test
    fun `全字段服务端快照导出关键指标与正确单位`() {
        val text = PrometheusTextFormatter.format(fullServerSnapshot())

        // 公共 label 出现在每条序列上(取堆已用为代表)
        assertContainsLine(text, """serverprobe_heap_used_bytes{serverId="srv-1",platform="BUKKIT"} 1024""")
        // heap_max 非 -1 应导出
        assertContainsLine(text, """serverprobe_heap_max_bytes{serverId="srv-1",platform="BUKKIT"} 4096""")
        // 类型声明:gauge / counter 各择一验证
        assertTrue(text.contains("# TYPE serverprobe_heap_used_bytes gauge"), "应声明 heap_used_bytes 为 gauge")
        assertTrue(text.contains("# TYPE serverprobe_gc_count_total counter"), "应声明 gc_count_total 为 counter")

        // 内存池:label pool;max 非 -1 应导出
        assertContainsLine(
            text,
            """serverprobe_memory_pool_used_bytes{serverId="srv-1",platform="BUKKIT",pool="G1 Eden Space"} 100"""
        )
        assertContainsLine(
            text,
            """serverprobe_memory_pool_max_bytes{serverId="srv-1",platform="BUKKIT",pool="G1 Eden Space"} 500"""
        )

        // GC 原始明细:counter,耗时 ms→s(2500ms → 2.5)
        assertContainsLine(
            text,
            """serverprobe_gc_count_total{serverId="srv-1",platform="BUKKIT",gc="G1 Young Generation"} 10"""
        )
        assertContainsLine(
            text,
            """serverprobe_gc_time_seconds_total{serverId="srv-1",platform="BUKKIT",gc="G1 Young Generation"} 2.5"""
        )
        // GC young/old 派生
        assertContainsLine(text, """serverprobe_gc_young_count_total{serverId="srv-1",platform="BUKKIT"} 10""")
        assertContainsLine(text, """serverprobe_gc_old_count_total{serverId="srv-1",platform="BUKKIT"} 3""")

        // 线程 / 类加载
        assertContainsLine(text, """serverprobe_threads{serverId="srv-1",platform="BUKKIT"} 42""")
        assertContainsLine(text, """serverprobe_classes_loaded_total{serverId="srv-1",platform="BUKKIT"} 20000""")

        // CPU 可用(>=0)应导出
        assertContainsLine(text, """serverprobe_process_cpu_load{serverId="srv-1",platform="BUKKIT"} 0.25""")

        // uptime ms→s(60000ms → 60)
        assertContainsLine(text, """serverprobe_uptime_seconds{serverId="srv-1",platform="BUKKIT"} 60""")

        // 服务器:TPS(label window)、MSPT(label quantile;ms→s,50ms→0.05)、在线/容量
        assertContainsLine(text, """serverprobe_tps{serverId="srv-1",platform="BUKKIT",window="1m"} 19.5""")
        assertContainsLine(text, """serverprobe_mspt_seconds{serverId="srv-1",platform="BUKKIT",quantile="p99"} 0.05""")
        assertContainsLine(text, """serverprobe_players_online{serverId="srv-1",platform="BUKKIT"} 7""")

        // 世界:label world,各计数 >=0 应导出
        assertContainsLine(
            text,
            """serverprobe_world_loaded_chunks{serverId="srv-1",platform="BUKKIT",world="world"} 256"""
        )
        assertContainsLine(
            text,
            """serverprobe_world_entities{serverId="srv-1",platform="BUKKIT",world="world"} 80"""
        )

        // 服务端快照不应出现代理指标
        assertFalse(text.contains("serverprobe_proxy_players_online"), "服务端快照不应含代理指标")
    }

    /** ② 不可用字段:Folia TPS=null、CPU=-1.0、max=-1、世界计数=-1 等一律不导出该行。 */
    @Test
    fun `不可用字段不导出对应时间序列`() {
        val text = PrometheusTextFormatter.format(unavailableFieldsSnapshot())

        // heap_max=-1 不导出(但 heap_used 仍在)
        assertTrue(text.contains("serverprobe_heap_used_bytes"), "heap_used 应仍导出")
        assertFalse(text.contains("serverprobe_heap_max_bytes"), "heap_max=-1 不应导出")
        // nonheap_max=-1 不导出
        assertFalse(text.contains("serverprobe_nonheap_max_bytes"), "nonheap_max=-1 不应导出")

        // 内存池 max=-1 不导出 _max_bytes(但 _used_bytes 仍在)
        assertTrue(text.contains("serverprobe_memory_pool_used_bytes"), "内存池 used 应仍导出")
        assertFalse(text.contains("serverprobe_memory_pool_max_bytes"), "内存池 max=-1 不应导出")

        // CPU=-1.0 不导出
        assertFalse(text.contains("serverprobe_process_cpu_load"), "process_cpu_load=-1.0 不应导出")
        assertFalse(text.contains("serverprobe_system_cpu_load"), "system_cpu_load=-1.0 不应导出")

        // Folia 全局 TPS/MSPT 均 null:不出现 tps / mspt 任意行
        assertFalse(text.contains("serverprobe_tps"), "Folia TPS=null 不应导出")
        assertFalse(text.contains("serverprobe_mspt_seconds"), "Folia MSPT=null 不应导出")

        // 世界计数 -1(Folia 受限)不导出 entities/tile_entities;loaded_chunks 仍在
        assertTrue(text.contains("serverprobe_world_loaded_chunks"), "世界已加载区块应仍导出")
        assertFalse(text.contains("serverprobe_world_entities"), "世界 entities=-1 不应导出")
        assertFalse(text.contains("serverprobe_world_tile_entities"), "世界 tile_entities=-1 不应导出")
    }

    /** ③ 代理端快照(server=null,proxy 非空):仅含 jvm + proxy,无 tps/mspt/world。 */
    @Test
    fun `代理端快照仅含 JVM 与代理指标`() {
        val text = PrometheusTextFormatter.format(proxySnapshot())

        // jvm 指标存在(平台 label = BUNGEE)
        assertContainsLine(text, """serverprobe_heap_used_bytes{serverId="proxy-1",platform="BUNGEE"} 2048""")
        // 代理指标:总在线 + 各后端(label backend)
        assertContainsLine(text, """serverprobe_proxy_players_online{serverId="proxy-1",platform="BUNGEE"} 15""")
        assertContainsLine(
            text,
            """serverprobe_proxy_backend_players_online{serverId="proxy-1",platform="BUNGEE",backend="lobby"} 9"""
        )

        // 不应含服务端/世界指标
        assertFalse(text.contains("serverprobe_tps"), "代理端不应含 TPS")
        assertFalse(text.contains("serverprobe_mspt_seconds"), "代理端不应含 MSPT")
        assertFalse(text.contains("serverprobe_players_online{"), "代理端不应含服务端在线人数")
        assertFalse(text.contains("serverprobe_world_loaded_chunks"), "代理端不应含世界指标")
    }

    /** ④ snapshot=null:返回空串。 */
    @Test
    fun `空快照返回空串`() {
        assertEquals("", PrometheusTextFormatter.format(null))
    }

    /** ⑤ label 值含特殊字符:反斜杠、双引号、换行均被转义。 */
    @Test
    fun `label 值按规范转义特殊字符`() {
        val snapshot = fullServerSnapshot().toBuilder()
            .jvm(baseJvm().toBuilder()
                .memoryPools(listOf(MemoryPoolMetric.builder().name("weird\"pool\\\nname").usedBytes(1).maxBytes(-1).build()))
                .build())
            .build()
        val text = PrometheusTextFormatter.format(snapshot)

        // 双引号→\" 反斜杠→\\ 换行→\n
        assertTrue(
            text.contains("""pool="weird\"pool\\\nname""""),
            "label 值应转义双引号/反斜杠/换行,实际输出:\n$text"
        )
    }

    // —— 测试夹具 ——

    /** 基础 JVM 指标:各字段为可导出的正常值(max 非 -1、CPU 非 -1.0)。 */
    private fun baseJvm(): JvmMetrics = JvmMetrics.builder()
        .heapUsedBytes(1024)
        .heapCommittedBytes(2048)
        .heapMaxBytes(4096)
        .nonHeapUsedBytes(512)
        .nonHeapCommittedBytes(768)
        .nonHeapMaxBytes(1024)
        .memoryPools(listOf(MemoryPoolMetric.builder().name("G1 Eden Space").usedBytes(100).maxBytes(500).build()))
        .gcYoungCount(10)
        .gcYoungTimeMs(2500)
        .gcOldCount(3)
        .gcOldTimeMs(1000)
        .gcCollectors(listOf(GcCollectorMetric.builder().name("G1 Young Generation").collectionCount(10).collectionTimeMs(2500).build()))
        .threadCount(42)
        .daemonThreadCount(30)
        .peakThreadCount(50)
        .deadlockedThreadCount(0)
        .loadedClassCount(15000)
        .totalLoadedClassCount(20000)
        .processCpuLoad(0.25)
        .systemCpuLoad(0.5)
        .uptimeMs(60000)
        .startTimeMs(1_700_000_000_000)
        .jvmArgs(listOf("-Xmx4G"))
        .build()

    /** 全字段服务端快照:JVM + 服务器(含世界)齐全且均为可导出值。 */
    private fun fullServerSnapshot(): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(1_700_000_000_000)
        .serverId("srv-1")
        .platform(ProbePlatform.BUKKIT)
        .jvm(baseJvm())
        .server(
            ServerMetrics.builder()
                .tick(
                    TickSample.builder()
                        .tps1m(19.5)
                        .tps5m(19.8)
                        .tps15m(20.0)
                        .msptAvg(30.0)
                        .msptP95(45.0)
                        .msptP99(50.0)
                        .source(TickSampleSource.PAPER_API)
                        .build()
                )
                .onlinePlayers(7)
                .maxPlayers(100)
                .uptimeMs(120000)
                .worlds(listOf(
                    WorldMetrics.builder().name("world").loadedChunks(256).entityCount(80).tileEntityCount(40).entitiesByType(null).build()
                ))
                .build()
        )
        .proxy(null)
        .build()

    /** 不可用字段快照:max=-1、CPU=-1.0、Folia TPS/MSPT 全 null、世界 entity/tile=-1。 */
    private fun unavailableFieldsSnapshot(): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(1_700_000_000_000)
        .serverId("srv-2")
        .platform(ProbePlatform.BUKKIT)
        .jvm(baseJvm().toBuilder()
            .heapMaxBytes(-1)
            .nonHeapMaxBytes(-1)
            .memoryPools(listOf(MemoryPoolMetric.builder().name("Metaspace").usedBytes(100).maxBytes(-1).build()))
            .processCpuLoad(-1.0)
            .systemCpuLoad(-1.0)
            .build())
        .server(
            ServerMetrics.builder()
                .tick(
                    TickSample.builder()
                        .tps1m(null)
                        .tps5m(null)
                        .tps15m(null)
                        .msptAvg(null)
                        .msptP95(null)
                        .msptP99(null)
                        .source(TickSampleSource.UNAVAILABLE)
                        .build()
                )
                .onlinePlayers(0)
                .maxPlayers(100)
                .uptimeMs(1000)
                .worlds(listOf(
                    WorldMetrics.builder().name("world").loadedChunks(16).entityCount(-1).tileEntityCount(-1).entitiesByType(null).build()
                ))
                .build()
        )
        .proxy(null)
        .build()

    /** 代理端快照:server=null,proxy 非空。 */
    private fun proxySnapshot(): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(1_700_000_000_000)
        .serverId("proxy-1")
        .platform(ProbePlatform.BUNGEE)
        .jvm(baseJvm().toBuilder().heapUsedBytes(2048).build())
        .server(null)
        .proxy(
            ProxyMetrics.builder()
                .totalOnline(15)
                .backends(listOf(
                    BackendServer.builder().name("lobby").online(9).build(),
                    BackendServer.builder().name("survival").online(6).build()
                ))
                .build()
        )
        .build()

    /**
     * 断言文本中存在与 [expected] 完全相等的一行(按换行切分后精确匹配),
     * 比子串包含更严格:避免 label 顺序/多余字符造成的误判。
     */
    private fun assertContainsLine(text: String, expected: String) {
        val present = text.lineSequence().any { it == expected }
        assertTrue(present, "应包含行:\n$expected\n实际输出:\n$text")
    }
}
