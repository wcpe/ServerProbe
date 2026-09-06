package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.WorldMetrics

/**
 * FR-17：`server_status` 的 worlds 输出层增强（entityTypeCounts/regionStats）合并逻辑。
 *
 * 覆盖三类行为：provider 缺失降级（保持既有快照输出）、字段合并（每世界按名对齐，输出层
 * 以同名字段 Map 承载新字段，不改动 [WorldMetrics] 模型）、向后兼容（快照无 worlds 或
 * 明细缺失世界/明细为空时均保持原输出结构）。
 */
class McpControlPlaneWorldDetailTest {

    @Test
    fun `provider 缺失时输出保持既有快照模型`() {
        val status = controlWithDefaultSnapshot().serverStatus()

        // provider 缺失时 latestMetricSnapshot 仍是原快照对象（非增强 Map）
        val snapshot = status["latestMetricSnapshot"] as MetricSnapshot
        val worlds = snapshot.server.worlds!!
        assertEquals(listOf("world_a", "world_b"), worlds.map { it.name })
        assertEquals(-1, worlds[0].entityCount)
        assertNull(worlds[0].entitiesByType)
    }

    @Test
    fun `provider 存在时按世界名合并明细字段`() {
        val provider = FakeWorldDetailProvider(listOf(
            WorldDetail(
                name = "world_a",
                entityTypeCounts = mapOf("Zombie" to 5, "Cow" to 2),
                regionStats = listOf(regionMetric("world_a")),
            ),
            WorldDetail(
                name = "world_b",
                entityTypeCounts = null,
                regionStats = null,
            ),
        ))
        val status = controlWithDefaultSnapshot().apply { worldDetailProvider = provider }.serverStatus()

        val snapshot = status["latestMetricSnapshot"] as Map<*, *>
        val server = snapshot["server"] as Map<*, *>
        val worlds = server["worlds"] as List<*>
        // 命中明细的世界以同名字段 Map 输出，承载增强字段
        val worldA = worlds[0] as Map<*, *>
        assertEquals("world_a", worldA["name"])
        assertEquals(-1, worldA["entityCount"])
        assertEquals(mapOf("Zombie" to 5, "Cow" to 2), worldA["entityTypeCounts"])
        assertEquals(listOf(regionMetric("world_a")), worldA["regionStats"])
        // 明细字段为 null 时保持既有聚合值
        val worldB = worlds[1] as Map<*, *>
        assertEquals(-1, worldB["entityCount"])
        assertNull(worldB["entityTypeCounts"])
        assertNull(worldB["regionStats"])
    }

    @Test
    fun `快照无世界数据时增强不改变输出`() {
        val emptySnapshot = snapshotWithWorlds(null)
        val control = McpControlPlane().apply { readApi = FakeReadApi(emptySnapshot) }
        control.worldDetailProvider = FakeWorldDetailProvider(listOf(
            WorldDetail("world_a", mapOf("Zombie" to 1), null),
        ))

        val status = control.serverStatus()
        // 快照无 worlds 时保持原快照对象，增强不生效
        assertSame(emptySnapshot, status["latestMetricSnapshot"])
    }

    @Test
    fun `明细中缺失的世界保持原对象语义不变`() {
        val provider = FakeWorldDetailProvider(listOf(
            WorldDetail("unknown_world", mapOf("Zombie" to 9), listOf(regionMetric("unknown_world"))),
        ))
        val status = controlWithDefaultSnapshot().apply { worldDetailProvider = provider }.serverStatus()

        val snapshot = status["latestMetricSnapshot"] as Map<*, *>
        val worlds = snapshot["server"]?.let { it as Map<*, *> }?.get("worlds") as List<*>
        // 明细缺失的世界保持原 WorldMetrics 对象（非 Map），名字仍可对齐
        assertEquals(listOf("world_a", "world_b"), worlds.map { worldName(it) })
        assertSame(defaultSnapshot.server.worlds!![0], worlds[0])
    }

    /** 兼容增强 Map 与原 WorldMetrics 对象两种世界形态的名称读取。 */
    private fun worldName(value: Any?): String = when (value) {
        is Map<*, *> -> value["name"] as String
        is WorldMetrics -> value.name
        else -> error("意外世界形态：${value?.javaClass?.simpleName}")
    }

    @Test
    fun `provider 明细为空列表时输出保持既有快照结构`() {
        val status = controlWithDefaultSnapshot().apply { worldDetailProvider = FakeWorldDetailProvider(emptyList()) }.serverStatus()

        // 明细为空时保持原快照对象（与 provider 缺失同语义）
        assertSame(defaultSnapshot, status["latestMetricSnapshot"])
    }

    /** 构造注入默认快照的 control（readApi 为 lateinit，测试必须显式装配）。 */
    private fun controlWithDefaultSnapshot(): McpControlPlane =
        McpControlPlane().apply { readApi = FakeReadApi(defaultSnapshot) }

    private fun regionMetric(worldName: String): ObservedRegionMetrics = ObservedRegionMetrics.builder()
        .worldName(worldName)
        .foliaRegionId(1L)
        .regionSequence(1L)
        .observed(true)
        .centerChunkX(0)
        .centerChunkZ(0)
        .playerCount(1)
        .sampleCount(1L)
        .build()

    private fun snapshotWithWorlds(worlds: List<WorldMetrics>?): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(1L)
        .serverId("test")
        .platform(ProbePlatform.BUKKIT)
        .jvm(JvmMetrics.builder().build())
        .server(ServerMetrics.builder().worlds(worlds).build())
        .build()

    private companion object {
        /** 默认快照：两个世界，实体数均为 -1（N/A 口径），无类型分布。 */
        val defaultSnapshot: MetricSnapshot = MetricSnapshot.builder()
            .schemaVersion(1)
            .timestampMs(1L)
            .serverId("test")
            .platform(ProbePlatform.BUKKIT)
            .jvm(JvmMetrics.builder().build())
            .server(ServerMetrics.builder().worlds(listOf(
                WorldMetrics.builder().name("world_a").loadedChunks(3).entityCount(-1).tileEntityCount(-1).build(),
                WorldMetrics.builder().name("world_b").loadedChunks(7).entityCount(-1).tileEntityCount(-1).build(),
            )).build())
            .build()
    }
}

/** 测试用最小只读 API 桩（仅实现快照读取，其余接口返回空值）。 */
private class FakeReadApi(private val snapshot: MetricSnapshot) : ProbeReadApi {
    override fun latestSnapshot(): MetricSnapshot = snapshot
    override fun recentSnapshots(limit: Int): List<MetricSnapshot> = emptyList()
    override fun recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot> = emptyList()
    override fun aggregated(windowSize: Int): top.wcpe.mc.plugin.serverprobe.api.model.AggregatedMetrics =
        top.wcpe.mc.plugin.serverprobe.api.model.AggregatedMetrics.builder().windowSampleCount(0).build()
    override fun lastStartupProfile(): top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile? = null
    override fun historyStartupProfiles(limit: Int): List<top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile> = emptyList()
    override fun lastStartupComparisonSummary(): String? = null
}

/** 测试专用世界明细提供者桩。 */
private class FakeWorldDetailProvider(private val result: List<WorldDetail>) : WorldDetailProvider {
    override fun details(): List<WorldDetail> = result
}
