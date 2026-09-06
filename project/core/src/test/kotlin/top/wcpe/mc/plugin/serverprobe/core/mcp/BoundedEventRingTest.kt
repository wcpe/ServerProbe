package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BoundedEventRing] 有界环形事件缓冲单测（FR-20）。
 *
 * 覆盖：默认容量、容量有界（只保留最近事件）、按过滤条件返回最近 N 条且保持时间顺序、
 * 过滤后超出上限时截断、零上限返回空、非法容量拒绝。
 */
class BoundedEventRingTest {

    @Test
    fun `默认容量为2000`() {
        assertEquals(2000, BoundedEventRing<Any>().capacity)
    }

    @Test
    fun `容量有界只保留最近事件`() {
        val ring = BoundedEventRing<String>(capacity = 3)
        ring.add("a")
        ring.add("b")
        ring.add("c")
        ring.add("d")

        assertEquals(listOf("b", "c", "d"), ring.recent(10))
        assertEquals(3, ring.size())
    }

    @Test
    fun `按过滤条件返回最近N条且保持时间顺序`() {
        val ring = BoundedEventRing<String>(capacity = 20)
        repeat(10) { ring.add("other-$it") }
        ring.add("me-1")
        ring.add("other-10")
        ring.add("me-2")

        assertEquals(listOf("me-1", "me-2"), ring.recent(20) { it.startsWith("me-") })
    }

    @Test
    fun `过滤结果超过上限时截断为最近N条`() {
        val ring = BoundedEventRing<String>(capacity = 100)
        repeat(5) { ring.add("other-$it") }
        repeat(30) { ring.add("me-$it") }

        val recent = ring.recent(20) { it.startsWith("me-") }
        assertEquals(20, recent.size)
        // 截断后保留的是最新 20 条（me-10..me-29），且仍按时间旧→新返回
        assertEquals("me-10", recent.first())
        assertEquals("me-29", recent.last())
    }

    @Test
    fun `零上限返回空列表`() {
        val ring = BoundedEventRing<String>(capacity = 3)
        ring.add("a")

        assertTrue(ring.recent(0).isEmpty())
    }

    @Test
    fun `非法容量拒绝创建`() {
        assertThrows(IllegalArgumentException::class.java) { BoundedEventRing<String>(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { BoundedEventRing<String>(capacity = -1) }
    }
}
