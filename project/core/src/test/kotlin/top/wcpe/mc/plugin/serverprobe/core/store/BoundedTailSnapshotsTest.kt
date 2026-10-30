package top.wcpe.mc.plugin.serverprobe.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot

/**
 * 「取最近 N 条」的尾部语义护栏。
 *
 * ## 为什么需要它
 * `readHistoryUntil` 的语义是「`untilMs` 及之前**最近的** N 条」，而底层历史文件是
 * **按日期升序、行内时间递增**写入的。若照普通读取那样由旧到新累计到 N 停手，
 * 拿到的是「范围内**最早** N 条」——对「崩溃前最后 100 条」这类回看，结果正好相反。
 * 这段逻辑不涉及 JSON 反序列化，因此可以脱离 nightconfig 直接测。
 */
class BoundedTailSnapshotsTest {

    /** 构造只带时间戳的快照，够本类断言用。 */
    private fun snapshot(ts: Long): MetricSnapshot = MetricSnapshot.builder()
        .timestampMs(ts)
        .build()

    /** 未超容量时原样保留，顺序与输入一致（时间正序）。 */
    @Test
    fun `未超容量时按原顺序保留`() {
        val tail = BoundedTailSnapshots(5)
        listOf(1L, 2L, 3L).forEach { tail.add(snapshot(it)) }

        assertEquals(listOf(1L, 2L, 3L), tail.map { it.timestampMs })
        assertFalse(tail.isFull(), "3 < 5 不应算装满")
    }

    /** 超出容量时丢最旧、留最新——这正是「最近 N 条」与「最早 N 条」的分界。 */
    @Test
    fun `超出容量时丢弃最旧并保留最新`() {
        val tail = BoundedTailSnapshots(3)
        listOf(1L, 2L, 3L, 4L, 5L).forEach { tail.add(snapshot(it)) }

        assertEquals(3, tail.size, "容量恒为 3")
        assertEquals(listOf(3L, 4L, 5L), tail.map { it.timestampMs }, "应保留最后三条（最新），而非最早三条")
    }

    /** 装满即 isFull：调用方据此停止扫描更早的文件。 */
    @Test
    fun `装满后 isFull 为真`() {
        val tail = BoundedTailSnapshots(2)
        assertFalse(tail.isFull())
        tail.add(snapshot(1))
        assertFalse(tail.isFull())
        tail.add(snapshot(2))
        assertTrue(tail.isFull())
    }

    /** 容量为 1 的边界：永远只留最新一条。 */
    @Test
    fun `容量为一时只留最新一条`() {
        val tail = BoundedTailSnapshots(1)
        listOf(10L, 20L, 30L).forEach { tail.add(snapshot(it)) }

        assertEquals(listOf(30L), tail.map { it.timestampMs })
    }
}
