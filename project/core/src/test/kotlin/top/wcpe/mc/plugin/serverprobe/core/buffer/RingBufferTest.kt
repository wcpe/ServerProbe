package top.wcpe.mc.plugin.serverprobe.core.buffer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [RingBuffer] 单元测试。
 *
 * [RingBuffer] 为纯逻辑(定容环形 + 新→旧快照),不依赖任何平台/框架,可直接构造验证。
 * 覆盖:空缓冲、未写满、容量滚动覆盖、snapshot(limit) 的顺序与数量、limit 边界,以及并发写入不丢失。
 */
class RingBufferTest {

    /** 容量必须为正:非正容量构造应抛出。 */
    @Test
    fun `非正容量构造抛异常`() {
        assertTrue(
            runCatching { RingBuffer<Int>(0) }.isFailure,
            "容量为 0 应构造失败"
        )
        assertTrue(
            runCatching { RingBuffer<Int>(-1) }.isFailure,
            "容量为负应构造失败"
        )
    }

    /** 空缓冲:size 为 0,任意 limit 的快照均为空。 */
    @Test
    fun `空缓冲快照为空`() {
        val buffer = RingBuffer<Int>(4)
        assertEquals(0, buffer.size(), "空缓冲 size 应为 0")
        assertTrue(buffer.snapshot(10).isEmpty(), "空缓冲快照应为空")
    }

    /** 未写满:snapshot 按新→旧返回全部已写元素。 */
    @Test
    fun `未写满按新到旧返回全部`() {
        val buffer = RingBuffer<Int>(5)
        buffer.add(1)
        buffer.add(2)
        buffer.add(3)
        assertEquals(3, buffer.size(), "已写 3 个,size 应为 3")
        // 最近写入的是 3,故新→旧为 [3,2,1]
        assertEquals(listOf(3, 2, 1), buffer.snapshot(10), "应按新→旧返回 [3,2,1]")
    }

    /** snapshot(limit) 数量:limit 小于已存数时只返回最近 limit 个(新→旧)。 */
    @Test
    fun `limit 小于已存数时只返回最近若干`() {
        val buffer = RingBuffer<Int>(10)
        for (i in 1..6) {
            buffer.add(i)
        }
        // 最近 3 个为 6,5,4
        assertEquals(listOf(6, 5, 4), buffer.snapshot(3), "limit=3 应返回最近三个 [6,5,4]")
    }

    /** limit 非正:返回空列表。 */
    @Test
    fun `limit 非正返回空`() {
        val buffer = RingBuffer<Int>(5)
        buffer.add(1)
        assertTrue(buffer.snapshot(0).isEmpty(), "limit=0 应返回空")
        assertTrue(buffer.snapshot(-3).isEmpty(), "limit<0 应返回空")
    }

    /** 容量滚动:写入超过容量后,最旧元素被覆盖,size 封顶为容量,快照仅含最近 capacity 个。 */
    @Test
    fun `超容量后旧元素被覆盖`() {
        val buffer = RingBuffer<Int>(3)
        // 写入 1..5,容量 3,最终窗口应为最近三个 {3,4,5}
        for (i in 1..5) {
            buffer.add(i)
        }
        assertEquals(3, buffer.size(), "写满后 size 应封顶为容量 3")
        // 新→旧:5,4,3(1、2 已被覆盖)
        assertEquals(listOf(5, 4, 3), buffer.snapshot(10), "滚动后应仅含最近三个 [5,4,3]")
    }

    /** 容量为 1:始终只保留最后一个元素。 */
    @Test
    fun `容量为一始终只留最后一个`() {
        val buffer = RingBuffer<String>(1)
        buffer.add("a")
        buffer.add("b")
        buffer.add("c")
        assertEquals(1, buffer.size(), "容量 1 的 size 恒为 1")
        assertEquals(listOf("c"), buffer.snapshot(5), "应只保留最后写入的 c")
    }

    /**
     * 并发写入不丢失:多线程并发 add 共 (线程数 × 每线程次数) 个互不相同的值,
     * 当总写入量不超过容量时,最终快照应恰好包含全部写入值(无丢失、无重复)。
     */
    @Test
    fun `并发写入不丢失`() {
        val threadCount = 8
        val perThread = 500
        val total = threadCount * perThread
        // 容量足够容纳全部写入,便于校验"不丢失"
        val buffer = RingBuffer<Int>(total)
        val pool = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        try {
            for (t in 0 until threadCount) {
                pool.submit {
                    // 各线程写入互不重叠的整数区间
                    val base = t * perThread
                    startLatch.await()
                    for (i in 0 until perThread) {
                        buffer.add(base + i)
                    }
                    doneLatch.countDown()
                }
            }
            // 同时放行,加大并发竞争
            startLatch.countDown()
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "并发写入应在限时内完成")
        } finally {
            pool.shutdownNow()
        }

        val snapshot = buffer.snapshot(total)
        assertEquals(total, snapshot.size, "并发写入后总数应为 $total(无丢失)")
        // 去重后仍为 total 个,且恰为 [0, total) 全集,证明既不丢也不串
        val distinct = ConcurrentHashMap.newKeySet<Int>().apply { addAll(snapshot) }
        assertEquals(total, distinct.size, "写入值应互不重复")
        assertEquals((0 until total).toSet(), distinct, "应恰好覆盖 [0, total) 全部值")
    }
}
