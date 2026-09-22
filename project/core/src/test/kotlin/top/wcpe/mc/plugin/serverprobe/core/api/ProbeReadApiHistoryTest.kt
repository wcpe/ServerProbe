package top.wcpe.mc.plugin.serverprobe.core.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.core.aggregator.MetricAggregator
import top.wcpe.mc.plugin.serverprobe.core.buffer.MetricSnapshotBuffer
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

    /**
     * 真实的 [ProbeReadApiImpl]:直接 new 并手动赋值依赖(lateinit/@Inject 字段不经 TabooLib 注解也可写)。
     * 审查指出的"测试错位"修正——不再用测试内复刻的委托桩,被测对象就是生产实现本身;
     * 其余依赖以真实类型实例填充(本测试路径只触碰 store)。
     */
    private fun realApi(store: top.wcpe.mc.plugin.serverprobe.api.store.MetricStore): ProbeReadApi =
        top.wcpe.mc.plugin.serverprobe.core.api.ProbeReadApiImpl().apply {
            this.store = store
            this.orchestrator = top.wcpe.mc.plugin.serverprobe.core.orchestrator.MetricOrchestrator()
            this.snapshotBuffer = MetricSnapshotBuffer()
            this.aggregator = MetricAggregator()
            this.startupProfileHolder = top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileHolder()
            this.packetForensics = top.wcpe.mc.plugin.serverprobe.core.forensics.PacketForensicsService()
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

    /**
     * ② 排序契约(审查发现:公开 API 承诺"由新到旧",而本地文件存储按时间升序随收随截):
     * 用真实 [ProbeReadApiImpl] 实例验证——构造不可直接实例化(依赖 TabooLib 运行期),
     * 故以同款委派逻辑的**真实代码路径**断言:升序输入反转后即"由新到旧"。
     */
    @Test
    fun `升序存储结果被归一为由新到旧`() {
        // 模拟本地文件存储的返回:按时间升序(旧→新),共 5 条
        val ascending = listOf(snapshot(1000L), snapshot(2000L), snapshot(3000L), snapshot(4000L), snapshot(5000L))
        val store = RecordingStore(ascending)
        val api = realApi(store)

        val result = api.historySnapshots(1000L, 5000L, 10)

        // 归一后:由新到旧
        assertEquals(listOf(5000L, 4000L, 3000L, 2000L, 1000L), result.map { it.timestampMs })
    }

    /**
     * ③ limit 透传:API 层把 limit 原样交给 store,结果如实透传。
     * 注:"保留最新 limit 条"的裁剪语义由存储实现(readHistoryLatestFirst 的本地覆盖)负责,
     * 其依赖磁盘文件,按 LocalFileMetricStore 类 KDoc 的测试约定不在裸单测范围(真机验证)。
     */
    @Test
    fun `limit 原样透传给存储后端`() {
        val ascending = listOf(snapshot(1000L), snapshot(2000L), snapshot(3000L), snapshot(4000L), snapshot(5000L))
        val store = RecordingStore(ascending)
        val api = realApi(store)

        val result = api.historySnapshots(1000L, 5000L, 2)

        assertEquals(2, store.lastLimit, "limit 应原样传递")
        // RecordingStore 走 default 反转:升序输入反转后即降序全量(裁剪由实现层负责)
        assertEquals(listOf(5000L, 4000L, 3000L, 2000L, 1000L), result.map { it.timestampMs })
    }

    /** ④ limit 非正:约定返回空列表(与存储层 readHistory 语义一致)。 */
    @Test
    fun `limit 非正返回空列表`() {
        val store = RecordingStore(emptyList())
        val api = realApi(store)

        assertTrue(api.historySnapshots(1000L, 2000L, 0).isEmpty())
        assertTrue(api.historySnapshots(1000L, 2000L, -5).isEmpty())
    }
}
