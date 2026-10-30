package top.wcpe.mc.plugin.serverprobe.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore

/**
 * [MetricStore] 区间读取默认方法的委托口径测试。
 *
 * 只验证接口默认实现如何转调 [MetricStore.readHistory]（哨兵、透传、时间源），
 * 不依赖运行期 JSON / 文件后端，故可在 core 裸单测类路径直接运行。
 */
class MetricStoreReadHistoryDefaultsTest {

    /** readHistoryUntil 默认实现必须以 Long.MIN_VALUE 作为「无下界」哨兵，并原样透传上界与条数。 */
    @Test
    fun `readHistoryUntil 默认实现以 Long_MIN_VALUE 为下界委托`() {
        val store = RecordingStore()

        store.readHistoryUntil(1_234L, 7)

        assertEquals(
            listOf(HistoryCall(Long.MIN_VALUE, 1_234L, 7)),
            store.historyCalls,
            "默认实现应以 Long.MIN_VALUE 为下界，且 untilMs/limit 原样透传"
        )
    }

    /** readHistoryUntil 的 limit 也必须原样透传（含非正值），截断策略交由具体实现决定。 */
    @Test
    fun `readHistoryUntil 默认实现原样透传非正 limit`() {
        val store = RecordingStore()

        store.readHistoryUntil(9_999L, 0)

        assertEquals(listOf(HistoryCall(Long.MIN_VALUE, 9_999L, 0)), store.historyCalls, "limit 应原样透传")
    }

    /** readHistorySince 既有语义保持不变：下界与条数原样透传，上界取调用时的当前时刻。 */
    @Test
    fun `readHistorySince 默认实现以下界与当前时刻委托`() {
        val store = RecordingStore()
        val before = System.currentTimeMillis()

        store.readHistorySince(555L, 5)

        val after = System.currentTimeMillis()
        val call = store.historyCalls.single()
        assertEquals(555L, call.sinceMs, "下界应原样透传")
        assertEquals(5, call.limit, "limit 应原样透传")
        assertTrue(
            call.untilMs in before..after,
            "上界应取调用时刻，实际 ${call.untilMs} 不在 [$before, $after] 内"
        )
    }

    /** 记录 readHistory 入参的存储桩，避免依赖任何运行期后端。 */
    private class RecordingStore : MetricStore {

        val historyCalls = mutableListOf<HistoryCall>()

        override fun saveStartupProfile(profile: StartupProfile) = Unit

        override fun lastStartupProfile(): StartupProfile? = null

        override fun appendHistory(snapshot: MetricSnapshot) = Unit

        override fun readHistory(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot> {
            historyCalls += HistoryCall(sinceMs, untilMs, limit)
            return emptyList()
        }
    }

    /** readHistory 的一次入参快照。 */
    private data class HistoryCall(val sinceMs: Long, val untilMs: Long, val limit: Int)
}
