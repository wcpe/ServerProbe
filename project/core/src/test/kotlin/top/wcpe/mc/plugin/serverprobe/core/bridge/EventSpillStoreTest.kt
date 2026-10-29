package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.io.File

/**
 * [EventSpillStore] 单元测试（线路治理「不可丢事件落盘兜底」）。
 *
 * 覆盖**提交位点**这一核心语义：只有确认写出的记录才算提交，未提交的必须能被重放。
 *
 * 注意：本测试的帧负载是纯文本（不经 JSON 编码的断言），因为 `core` 的裸单测类路径没有
 * nightconfig，`Json.encode` 在 [EventSpillStore] 内部会失败——那属于运行期依赖，
 * 本测试聚焦位点推进逻辑本身。
 */
class EventSpillStoreTest {

    /** 构造指向临时目录的配置。 */
    private fun settings(dir: File, maxBytes: Long = 8L * 1024 * 1024): LineSettings = LineSettings(
        spillEnabled = true,
        spillDir = dir.absolutePath,
        spillMaxBytes = maxBytes,
    )

    /** 造一条可靠事件帧。 */
    private fun frame(index: Int, tag: String = "event"): OutboundFrame = OutboundFrame(
        type = OutboundFrame.TYPE_EVENT,
        event = "${tag}_change",
        domain = "demo",
        payload = """{"index":$index}""",
        priority = OutboundFrame.Priority.RELIABLE_EVENT,
        requestId = "",
    )

    /**
     * 提交位点只随确认推进：未确认的记录在回放时仍可读到。
     *
     * 断言链：append → 落盘 → 读取（未确认）→ 确认一条 → 位点前进 → 剩余仍可读到。
     */
    @Test
    fun `未确认记录可被重放`(@TempDir dir: File) {
        val store = EventSpillStore(settings(dir))
        store.load()
        try {
            store.append(frame(0))
            store.append(frame(1))
            store.flushForTest()

            // 两条都应能读出
            val first = store.next()
            val second = store.next()
            assertNotNull(first, "未确认的第一条应可回放")
            assertNotNull(second, "未确认的第二条应可回放")

            // 只确认第一条:位点应前进,但第二条仍未提交
            store.onSent(first!!)
            assertEquals(1, store.pending(), "应剩一条未确认")

            // 重置回放位置后,应重新读到未提交的第二条
            store.resetReplay()
            val replayed = store.next()
            assertNotNull(replayed, "未确认记录必须能被重放")
            assertTrue(replayed!!.payload.contains("\"index\":1"), "重放的应是未确认的那条")
        } finally {
            store.shutdown()
        }
    }

    /** 全部确认后文件被清理,回放不再返回数据。 */
    @Test
    fun `全部确认后不再回放`(@TempDir dir: File) {
        val store = EventSpillStore(settings(dir))
        store.load()
        try {
            store.append(frame(0))
            store.flushForTest()

            val only = store.next()
            assertNotNull(only)
            store.onSent(only!!)

            store.resetReplay()
            assertNull(store.next(), "全部提交后不应再有可回放记录")
            assertEquals(0, store.pending(), "无未确认记录")
        } finally {
            store.shutdown()
        }
    }

    /** 确认非本类回放出的帧(引用不等)不得推进位点——防止误把普通事件当已提交。 */
    @Test
    fun `非回放帧不推进位点`(@TempDir dir: File) {
        val store = EventSpillStore(settings(dir))
        store.load()
        try {
            store.append(frame(0))
            store.flushForTest()
            assertNotNull(store.next())

            // 另一个实例(内容相同但引用不同)
            store.onSent(frame(0))
            assertEquals(1, store.pending(), "引用不等的帧不得推进位点")
        } finally {
            store.shutdown()
        }
    }

    /** 崩溃残留的半截尾行在装载时被截断,不污染后续回放。 */
    @Test
    fun `装载时截断半截尾行`(@TempDir dir: File) {
        val data = File(dir, "spill.jsonl")
        data.writeText("{\"type\":\"event\"}\n{\"type\":\"ev")
        val store = EventSpillStore(settings(dir))
        store.load()
        try {
            // 截断后只剩完整的一行(该行内容不是本类可解码的形态则 next 返回 null,但不崩)
            assertTrue(data.length() > 0, "完整行应保留")
            assertTrue(data.readText().endsWith("\n"), "文件应以换行结尾")
        } finally {
            store.shutdown()
        }
    }

    /** 磁盘缓冲有界:超量追加不抛,且能报告被丢弃数。 */
    @Test
    fun `磁盘缓冲有界不抛`(@TempDir dir: File) {
        // 故意把上限调得极小（1 KiB），让 5000 条帧必然溢出。
        //
        // 原先这条只断言 `pending() >= 0` / `droppedBeyondDisk() >= 0`，
        // 而这两个都是非负累加器——`>= 0` **永远成立**，等于什么都没验，
        // 它声称的“有界”完全没被检查。
        val store = EventSpillStore(settings(dir, maxBytes = 1024L))
        store.load()
        try {
            repeat(5_000) { store.append(frame(it)) }

            // 真正的不变量（一）：溢出后必须有丢弃记录。
            // 若实现里“有界”失效（比如上限没生效、无限追加），这里会得到 0。
            assertTrue(
                store.droppedBeyondDisk() > 0L,
                "1 KiB 上限写 5000 条必然溢出，应记录丢弃；实际=${store.droppedBeyondDisk()}",
            )

            // 真正的不变量（二）：落盘文件本身不得无限增长。
            // 给一个宽松但有限的边界：上限 1 KiB，允许格式化/行头开销，但不得到几百 KB。
            val spillFile = File(dir, "spill.jsonl")
            val sizeOnDisk = if (spillFile.isFile) spillFile.length() else 0L
            assertTrue(
                sizeOnDisk < 200L * 1024,
                "落盘文件应有界（上限 1 KiB + 开销），实际 ${sizeOnDisk}B",
            )
        } finally {
            store.shutdown()
        }
    }
}
