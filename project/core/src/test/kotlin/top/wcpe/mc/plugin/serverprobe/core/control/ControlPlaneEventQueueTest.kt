package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [ControlPlaneEventQueue] 单元测试。
 *
 * 覆盖"迁移事件不能丢"这条要求的两半：入队后能取出（桥连上后补发的依据）、有界队列满时丢最旧并保留最新。
 * 队列是全局单例，故每个用例前后清空，避免相互污染。
 */
class ControlPlaneEventQueueTest {

    @BeforeEach
    fun clearBefore() {
        ControlPlaneEventQueue.clear()
    }

    @AfterEach
    fun clearAfter() {
        ControlPlaneEventQueue.clear()
    }

    /** 入队后按顺序取出，取空后队列归零（补发只做一次）。 */
    @Test
    fun `入队后按顺序取出且取空`() {
        ControlPlaneEventQueue.enqueue("identity_migrated", linkedMapOf("instanceId" to "a"))
        ControlPlaneEventQueue.enqueue("identity_migrated", linkedMapOf("instanceId" to "b"))

        val drained = ControlPlaneEventQueue.drain()

        assertEquals(2, drained.size, "应取到两条事件")
        assertEquals("identity_migrated", drained[0].event)
        assertEquals("a", drained[0].data["instanceId"], "应按入队顺序取出")
        assertEquals("b", drained[1].data["instanceId"])
        assertEquals(0, ControlPlaneEventQueue.size(), "取出后队列应清空")
        assertTrue(ControlPlaneEventQueue.drain().isEmpty(), "再次取出应为空")
    }

    /** 队列有界：满后丢最旧一条并保留最新（防止长期离线时无限堆积）。 */
    @Test
    fun `队列满时丢最旧并保留最新`() {
        repeat(CAPACITY + 2) { index ->
            ControlPlaneEventQueue.enqueue("identity_migrated", linkedMapOf("seq" to index))
        }

        val drained = ControlPlaneEventQueue.drain()

        assertEquals(CAPACITY, drained.size, "队列长度应被上限截断")
        assertEquals(
            CAPACITY + 1,
            drained.last().data["seq"],
            "最新事件必须保留（迁移事实不能被旧事件挤掉）",
        )
    }

    private companion object {

        /** 与实现上限保持一致：够覆盖迁移/治理这类低频事件。 */
        private const val CAPACITY = 16
    }
}
