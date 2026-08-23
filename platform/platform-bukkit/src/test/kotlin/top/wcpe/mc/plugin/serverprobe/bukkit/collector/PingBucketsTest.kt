package top.wcpe.mc.plugin.serverprobe.bukkit.collector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [PingBuckets] 分桶逻辑单测(FR2.4)。
 *
 * 覆盖:边界值(0/49/50/99/100/199/200/499/500)、非法值过滤、空列表、恒 5 桶口径。
 */
class PingBucketsTest {

    @Test
    fun `边界值命中正确桶`() {
        val pings = listOf(0, 49, 50, 99, 100, 199, 200, 499, 500, 10000)
        val buckets = PingBuckets.bucket(pings)

        assertEquals(5, buckets.size)
        // 顺序固定:<50, 50-100, 100-200, 200-500, 500+
        assertEquals(listOf("<50ms", "50-100ms", "100-200ms", "200-500ms", "500ms+"), buckets.map { it.label })
        assertEquals(listOf(2, 2, 2, 2, 2), buckets.map { it.count })
    }

    @Test
    fun `非法负值被过滤`() {
        val buckets = PingBuckets.bucket(listOf(-1, -5, 10, 60))
        assertEquals(listOf(1, 1, 0, 0, 0), buckets.map { it.count })
    }

    @Test
    fun `空列表产出全零五桶`() {
        val buckets = PingBuckets.bucket(emptyList())
        assertEquals(5, buckets.size)
        assertEquals(listOf(0, 0, 0, 0, 0), buckets.map { it.count })
        assertEquals(0, buckets.first().minMs)
        assertEquals(-1, buckets.last().maxMs)
    }

    @Test
    fun `桶区间元数据正确`() {
        val buckets = PingBuckets.bucket(listOf(30))
        assertEquals(0, buckets[0].minMs)
        assertEquals(50, buckets[0].maxMs)
        assertEquals(50, buckets[1].minMs)
        assertEquals(100, buckets[1].maxMs)
        assertEquals(500, buckets[4].minMs)
        assertEquals(-1, buckets[4].maxMs)
    }
}
