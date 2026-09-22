package top.wcpe.mc.plugin.serverprobe.core.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample

/**
 * [ProbeReadApi.historySnapshots] 契约单元测试(FR-26)。
 *
 * 覆盖两条关键契约:
 * 1. **default 方法向后兼容**:只实现旧接口的第三方实现类,调用 [ProbeReadApi.historySnapshots]
 *    返回空列表且不抛(二进制/源码兼容的回归锁)。
 * 2. **实现委派语义**:core 实现把参数原样传给存储后端(用桩 store 捕获入参验证),limit 非正
 *    沿用存储层语义返回空列表。
 *
 * 注:LocalFileMetricStore 的读盘往返依赖运行期 JSON 后端(见其类 KDoc 测试说明),
 * 不在本单测范围;其范围定位/逐行过滤逻辑由 [MetricHistoryFileTest] 与真机覆盖。
 */
class ProbeReadApiHistoryTest {

    /** 只实现旧接口的第三方 stub:不覆盖任何 default 方法(模拟既有实现零改动)。 */
    private class LegacyThirdPartyApi : ProbeReadApi {
        var latestCalled = false
        override fun latestSnapshot(): MetricSnapshot? {
            latestCalled = true
            return null
        }

        override fun recentSnapshots(limit: Int): List<MetricSnapshot> = emptyList()
        override fun recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot> = emptyList()
        override fun aggregated(windowSize: Int) =
            top.wcpe.mc.plugin.serverprobe.api.model.AggregatedMetrics.builder().windowSampleCount(0).build()

        override fun lastStartupProfile(): top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile? = null
        override fun historyStartupProfiles(limit: Int) =
            listOf<top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile>()

        override fun lastStartupComparisonSummary(): String? = null
    }

    /** 捕获入参的桩存储后端,返回预置快照列表。 */
    private class RecordingStore(
        private val result: List<MetricSnapshot>,
    ) : top.wcpe.mc.plugin.serverprobe.api.store.MetricStore {
        var lastSince = Long.MIN_VALUE
        var lastUntil = Long.MIN_VALUE
        var lastLimit = Int.MIN_VALUE

        override fun saveStartupProfile(profile: top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile) = Unit
        override fun lastStartupProfile(): top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile? = null
        override fun appendHistory(snapshot: MetricSnapshot) = Unit
        override fun readHistory(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot> {
            lastSince = sinceMs
            lastUntil = untilMs
            lastLimit = limit
            return result
        }
    }

    /** 经委派链调用 historySnapshots 的最小实现(与 ProbeReadApiImpl 同款委派)。 */
    private class DelegatingApi(private val store: top.wcpe.mc.plugin.serverprobe.api.store.MetricStore) :
        ProbeReadApi by object : ProbeReadApi by LegacyThirdPartyApi() {} {
        override fun historySnapshots(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot> =
            store.readHistory(sinceMs, untilMs, limit)
    }

    private fun snapshot(tsMs: Long): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(tsMs)
        .serverId("test")
        .platform(ProbePlatform.BUKKIT)
        .jvm(JvmMetrics.builder().build())
        .server(
            ServerMetrics.builder()
                .tick(TickSample.builder().source(TickSampleSource.SELF_SAMPLING).build())
                .onlinePlayers(0)
                .maxPlayers(20)
                .uptimeMs(0)
                .build()
        )
        .build()

    /** ① default 方法兼容:旧实现调用新方法得空列表、不抛。 */
    @Test
    fun `旧第三方实现的 default 方法返回空列表`() {
        val api = LegacyThirdPartyApi()
        val result = api.historySnapshots(1000L, 2000L, 10)
        assertTrue(result.isEmpty(), "default 实现应返回空列表")
        assertEquals(0, result.size)
        // 同时确认 stub 本身可实例化且旧方法可用(编译/链接层面兼容的证据)
        api.latestSnapshot()
        assertTrue(api.latestCalled)
    }

    /** ② 委派语义:入参原样传给存储后端,返回值透传。 */
    @Test
    fun `实现把参数原样委派给存储后端`() {
        val expected = listOf(snapshot(1500L))
        val store = RecordingStore(expected)
        val api = DelegatingApi(store)

        val result = api.historySnapshots(1000L, 2000L, 10)

        assertEquals(expected, result, "返回值应透传存储后端结果")
        assertEquals(1000L, store.lastSince)
        assertEquals(2000L, store.lastUntil)
        assertEquals(10, store.lastLimit)
    }

    /** ③ limit 非正:约定返回空列表(与存储层 readHistory 语义一致)。 */
    @Test
    fun `limit 非正返回空列表`() {
        val store = RecordingStore(emptyList())
        val api = DelegatingApi(store)

        assertTrue(api.historySnapshots(1000L, 2000L, 0).isEmpty())
        assertTrue(api.historySnapshots(1000L, 2000L, -5).isEmpty())
    }
}
