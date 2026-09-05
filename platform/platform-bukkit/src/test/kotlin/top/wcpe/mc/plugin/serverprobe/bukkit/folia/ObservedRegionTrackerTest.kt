package top.wcpe.mc.plugin.serverprobe.bukkit.folia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObservedRegionTrackerTest {

    @Test
    fun `仅记录含在线玩家的真实 region tick 并计算分位指标`() {
        val tracker = ObservedRegionTracker(sampleCapacity = 8, expireAfterMs = 60_000)
        val region = context(internalId = 11L, playerCount = 2)

        tracker.onTickStart(region, 0L, 1_000L)
        tracker.onTickEnd(region, 20.0, 1_020L)
        tracker.onTickStart(region, 50_000_000L, 1_050L)
        tracker.onTickEnd(region, 30.0, 1_080L)

        val metrics = tracker.snapshot(1_080L).regions.single()
        assertEquals(11L, metrics.foliaRegionId)
        assertEquals(2L, metrics.sampleCount)
        assertTrue(metrics.isObserved)
        assertEquals(2, metrics.playerCount)
        assertEquals(20.0, metrics.tpsAvg)
        assertEquals(20.0, metrics.tpsP95)
        assertEquals(25.0, metrics.msptAvg)
        assertEquals(30.0, metrics.msptP95)
        assertEquals(30.0, metrics.msptP99)
    }

    @Test
    fun `无在线玩家的 region 不会进入已观测结果`() {
        val tracker = ObservedRegionTracker(sampleCapacity = 8, expireAfterMs = 60_000)
        val region = context(internalId = 12L, playerCount = 0)

        tracker.onTickStart(region, 0L, 1_000L)
        tracker.onTickEnd(region, 20.0, 1_020L)

        assertEquals(emptyList<Any>(), tracker.snapshot(1_020L).regions)
    }

    @Test
    fun `世界汇总按实际 tick 样本加权而非按 region 平均`() {
        val tracker = ObservedRegionTracker(sampleCapacity = 8, expireAfterMs = 60_000)
        val first = context(internalId = 21L, playerCount = 1)
        val second = context(internalId = 22L, playerCount = 1)

        recordTick(tracker, first, 0L, 10.0)
        recordTick(tracker, first, 50_000_000L, 10.0)
        recordTick(tracker, second, 0L, 30.0)

        val world = tracker.snapshot(1_100L).worlds.single()
        assertEquals(2, world.activeRegions)
        assertEquals(3L, world.sampleCount)
        assertEquals(16.666666666666668, world.msptAvg)
        assertEquals(30.0, world.msptP95)
        assertEquals(30.0, world.msptP99)
    }

    @Test
    fun `超出保留时间的 region 会过期`() {
        val tracker = ObservedRegionTracker(sampleCapacity = 8, expireAfterMs = 60_000)
        val region = context(internalId = 31L, playerCount = 1)

        recordTick(tracker, region, 0L, 20.0)

        assertNull(tracker.snapshot(61_101L).regions.firstOrNull())
    }

    @Test
    fun `region 坐标变更后保留旧序列直到自然过期`() {
        val tracker = ObservedRegionTracker(sampleCapacity = 8, expireAfterMs = 60_000)
        val oldRegion = context(internalId = 41L, playerCount = 1, chunkX = 8)
        val newRegion = context(internalId = 41L, playerCount = 1, chunkX = 40)

        recordTick(tracker, oldRegion, 0L, 20.0)
        recordTick(tracker, newRegion, 50_000_000L, 30.0)

        val regions = tracker.snapshot(1_100L).regions
        assertEquals(2, regions.size)
        assertEquals(listOf(8, 40), regions.map { it.centerChunkX })
        assertTrue(regions[0].regionSequence < regions[1].regionSequence)
    }

    private fun recordTick(
        tracker: ObservedRegionTracker,
        region: FoliaRegionContext,
        startNanos: Long,
        durationMs: Double
    ) {
        tracker.onTickStart(region, startNanos, 1_000L)
        tracker.onTickEnd(region, durationMs, 1_100L)
    }

    private fun context(internalId: Long, playerCount: Int, chunkX: Int = 8) = FoliaRegionContext(
        internalId = internalId,
        worldName = "world",
        centerChunkX = chunkX,
        centerChunkZ = -4,
        playerCount = playerCount
    )
}
