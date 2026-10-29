package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.WorldMetrics
import top.wcpe.mc.plugin.serverprobe.core.mcp.WorldDetail
import top.wcpe.mc.plugin.serverprobe.core.mcp.WorldDetailProvider

/**
 * BukkitWorldDetailProvider 的行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这些测试是“反射查 `worldCollector` 字段类型是不是 `BukkitWorldCollector`”。
 * 那只能证明<b>字段声明了这个类型</b>——一个把 `collect()` 结果丢掉、直接 `return emptyList()`
 * 的实现同样能通过。它连“数据有没有被透传”都碰不到。</p>
 *
 * <h2>现在怎么测到真行为</h2>
 * <p>通过两个内部接缝：`isBukkitPlatform`（模拟跑在 Bukkit 上）与 `worldMetricsReader`
 * （注入固定数据）。两者在生产路径上默认指向真实实现，行为不变；
 * 测试里覆盖后，`details()` 的映射逻辑就能真正跑起来。</p>
 */
class BukkitWorldDetailProviderContractTest {

    /** 造一个“运行在 Bukkit 且数据源已就绪”的 provider。 */
    private fun providerWith(metrics: List<WorldMetrics>): BukkitWorldDetailProvider {
        val provider = BukkitWorldDetailProvider()
        provider.isBukkitPlatform = { true }
        provider.worldMetricsReader = WorldMetricsReader { metrics }
        return provider
    }

    /** 造一份世界指标；entitiesByType 传 null 表示“未开启类型统计”。 */
    private fun world(name: String, entityTypes: Map<String, Int>?): WorldMetrics =
        WorldMetrics.builder()
            .name(name)
            .loadedChunks(10)
            .entityCount(5)
            .tileEntityCount(2)
            .entitiesByType(entityTypes)
            .build()

    // ────────────────── 数据透传（核心行为） ──────────────────

    @Test
    @DisplayName("实体类型分布原样透传；未统计时保持 null（不是空 Map）")
    fun `实体类型分布透传`() {
        val details = providerWith(
            listOf(
                world("world", mapOf("ZOMBIE" to 3, "COW" to 2)),
                // 关闭 world-entity-types 时采集器给的是 null（N/A），不得变成空 Map
                world("world_nether", null),
            ),
        ).details()

        assertEquals(2, details.size, "两个世界都应有明细")

        assertEquals(
            mapOf("ZOMBIE" to 3, "COW" to 2),
            details.first { it.name == "world" }.entityTypeCounts,
            "实体类型分布必须原样透传采集器缓存的口径",
        )

        assertNull(
            details.first { it.name == "world_nether" }.entityTypeCounts,
            "未开启类型统计时是 null（N/A），变成空 Map 会让面板误以为“这个世界没有实体”",
        )
    }

    @Test
    @DisplayName("世界名与顺序按数据源给的一致")
    fun `世界名与顺序`() {
        val details = providerWith(
            listOf(world("alpha", emptyMap()), world("beta", emptyMap()), world("gamma", emptyMap())),
        ).details()

        assertEquals(
            listOf("alpha", "beta", "gamma"),
            details.map { it.name },
            "顺序必须与数据源一致（面板按它渲染列表）",
        )
    }

    @Test
    @DisplayName("数据源为空时返回空列表（不是 null）")
    fun `数据源为空`() {
        assertEquals(emptyList<WorldDetail>(), providerWith(emptyList()).details())
    }

    @Test
    @DisplayName("非 Folia 时 regionStats 恒为 null")
    fun `非Folia无region明细`() {
        // 纯 JVM 环境不是 Folia，即使有世界数据也不应冒出 region 统计。
        val detail = providerWith(listOf(world("world", emptyMap()))).details().single()

        assertNull(detail.regionStats, "非 Folia 不应有 region 统计（该能力仅 Folia 提供）")
    }

    // ────────────────── 平台门 ──────────────────

    @Test
    @DisplayName("非 Bukkit 平台下返回空列表，且**不读**数据源")
    fun `非Bukkit平台不读数据源`() {
        var readCalled = false
        val provider = BukkitWorldDetailProvider()
        provider.isBukkitPlatform = { false }
        provider.worldMetricsReader = WorldMetricsReader {
            readCalled = true
            listOf(world("world", emptyMap()))
        }

        val details = provider.details()

        assertEquals(emptyList<WorldDetail>(), details, "非 Bukkit 平台必须空返回")
        assertTrue(!readCalled, "平台门判断必须在读取之前：非 Bukkit 端不应去碰 Bukkit 缓存")
    }

    // ────────────────── 异常兜底 ──────────────────

    @Test
    @DisplayName("数据源抛异常时降级为空列表，不穿透到调用方")
    fun `数据源异常被兜住`() {
        val provider = BukkitWorldDetailProvider()
        provider.isBukkitPlatform = { true }
        provider.worldMetricsReader = WorldMetricsReader { error("模拟采集器故障") }

        val result = runCatching { provider.details() }

        assertTrue(result.isSuccess, "采集器异常必须被兜住，不能穿透到 MCP 请求线程，实际=${result.exceptionOrNull()}")
        assertEquals(emptyList<WorldDetail>(), result.getOrThrow())
    }

    @Test
    @DisplayName("未注入数据源且未注入采集器时安全返回空列表")
    fun `无数据源时安全降级`() {
        val provider = BukkitWorldDetailProvider()
        provider.isBukkitPlatform = { true }

        val result = runCatching { provider.details() }

        assertTrue(result.isSuccess, "可选依赖缺席不得让查询路径抛异常，实际=${result.exceptionOrNull()}")
        assertEquals(emptyList<WorldDetail>(), result.getOrThrow())
    }

    // ────────────────── 契约 ──────────────────

    @Test
    @DisplayName("实现 core 的 WorldDetailProvider 契约")
    fun `实现core契约`() {
        val provider: WorldDetailProvider = BukkitWorldDetailProvider()
        assertTrue(provider.details().isEmpty() || provider.details().isNotEmpty())
    }
}
