package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.management.ObjectName

/** GcDiff 纯函数单测：首采/无变化/增量差分/并发回退钳制/新收集器/堆信息透出。 */
class GcDiffTest {

    private fun bean(name: String, count: Long, timeMs: Long, lastGcUsed: Long? = null): java.lang.management.GarbageCollectorMXBean =
        ProxyBean(name, count, timeMs, lastGcUsed)

    @Test
    fun `首采无历史基线返回零增量`() {
        val beans = listOf(bean("G1 Young Generation", 5L, 120L))

        val diff = GcDiff.diff(GcDiff.sample(beans), previous = null)

        val event = diff.single()
        assertEquals("G1 Young Generation", event.collector)
        assertEquals(0L, event.countDelta)
        assertEquals(0L, event.timeDeltaMs)
        assertNull(event.heapAfterGcKb)
    }

    @Test
    fun `无变化时所有 delta 均为零且事件非空`() {
        val beans = listOf(bean("Copy", 3L, 10L), bean("MarkSweepCompact", 1L, 40L))
        val before = GcDiff.sample(beans)
        val after = GcDiff.sample(beans)

        val diff = GcDiff.diff(after, before)

        assertTrue(diff.isNotEmpty())
        diff.forEach { event ->
            assertEquals(0L, event.countDelta)
            assertEquals(0L, event.timeDeltaMs)
        }
    }

    @Test
    fun `增量计数与耗时逐收集器差分`() {
        val before = GcDiff.sample(listOf(
            bean("G1 Young Generation", 5L, 120L),
            bean("G1 Old Generation", 2L, 80L),
        ))
        val after = GcDiff.sample(listOf(
            bean("G1 Young Generation", 8L, 165L),
            bean("G1 Old Generation", 3L, 200L),
        ))

        val diff = GcDiff.diff(after, before)

        assertEquals(3L, diff.single { it.collector == "G1 Young Generation" }.countDelta)
        assertEquals(45L, diff.single { it.collector == "G1 Young Generation" }.timeDeltaMs)
        assertEquals(1L, diff.single { it.collector == "G1 Old Generation" }.countDelta)
        assertEquals(120L, diff.single { it.collector == "G1 Old Generation" }.timeDeltaMs)
    }

    @Test
    fun `并发采样计数回退时 delta 钳制归零`() {
        val before = GcDiff.sample(listOf(bean("Copy", 100L, 1000L)))
        val after = GcDiff.sample(listOf(bean("Copy", 99L, 950L)))

        val diff = GcDiff.diff(after, before)

        assertEquals(0L, diff.single().countDelta)
        assertEquals(0L, diff.single().timeDeltaMs)
    }

    @Test
    fun `单调性破坏时耗时差分也钳制为零`() {
        val before = GcDiff.sample(listOf(bean("MarkSweepCompact", 5L, 100L)))
        val after = GcDiff.sample(listOf(bean("MarkSweepCompact", 4L, 150L)))

        val diff = GcDiff.diff(after, before)

        assertEquals(0L, diff.single().countDelta)
        assertEquals(0L, diff.single().timeDeltaMs)
    }

    @Test
    fun `新出现收集器无历史基线时增量为零`() {
        val before = GcDiff.sample(listOf(bean("Copy", 3L, 10L)))
        val after = GcDiff.sample(listOf(bean("Copy", 4L, 20L), bean("MarkSweepCompact", 1L, 5L)))

        val diff = GcDiff.diff(after, before)

        assertEquals(1L, diff.single { it.collector == "Copy" }.countDelta)
        assertEquals(0L, diff.single { it.collector == "MarkSweepCompact" }.countDelta)
        assertEquals(0L, diff.single { it.collector == "MarkSweepCompact" }.timeDeltaMs)
    }

    @Test
    fun `heapAfterGcKb 在 lastGcInfo 可用时透出`() {
        val beans = listOf(bean("G1 Young Generation", 5L, 120L, lastGcUsed = 512L * 1024))

        val sample = GcDiff.sample(beans)

        assertEquals(512L, sample.single().heapAfterGcKb)
    }

    @Test
    fun `heapAfterGcKb 在 lastGcInfo 为 null 时缺省`() {
        val beans = listOf(bean("G1 Young Generation", 5L, 120L))

        val sample = GcDiff.sample(beans)

        assertNull(sample.single().heapAfterGcKb)
    }

    @Test
    fun `首采时 heapAfterGcKb 随事件透出`() {
        val beans = listOf(bean("G1 Young Generation", 5L, 120L, lastGcUsed = 1024L * 1024))

        val event = GcDiff.diff(GcDiff.sample(beans), null).single()

        assertEquals(1024L, event.heapAfterGcKb)
    }
}

/** 最小实现：仅提供 GcDiff.sample 需要的 java.lang 接口成员 + 可选 getLastGcInfo() 供反射读取堆用量。 */
private class ProxyBean(
    private val collectorName: String,
    private val count: Long,
    private val timeMs: Long,
    private val lastGcHeapUsed: Long?,
) : java.lang.management.GarbageCollectorMXBean {

    override fun getName(): String = collectorName
    override fun getCollectionCount(): Long = count
    override fun getCollectionTime(): Long = timeMs
    override fun getMemoryPoolNames(): Array<String> = emptyArray()
    override fun isValid(): Boolean = true
    override fun getObjectName(): ObjectName = ObjectName("java.lang:type=GarbageCollector,name=$collectorName")

    /** 反射读取的目标：返回含 getMemoryUsageAfterGc 的普通对象（非 com.sun GcInfo）。 */
    fun getLastGcInfo(): Any? = lastGcHeapUsed?.let { ProxyGcInfo(it) }
}

/** 假 GcInfo：GcInfo 抽象类无法直接实例化，故以普通对象模拟反射读取的 lastGcInfo 结构。 */
private class ProxyGcInfo(private val afterGcUsed: Long) {

    fun getMemoryUsageAfterGc(): Map<String, java.lang.management.MemoryUsage> =
        mapOf("Heap" to java.lang.management.MemoryUsage(0, afterGcUsed, afterGcUsed, afterGcUsed))
}
