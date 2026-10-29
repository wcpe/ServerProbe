package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.BridgeServices
import top.wcpe.mc.plugin.serverprobe.api.FacadeAssemblyTestHook
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.AggregatedMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile

/**
 * `ServerProbeAPI.read` 稳定代理的契约验收（门面收敛）。
 *
 * 只读门面与业务门面同级：**永不返回 null、可缓存为字段、任何线程调用都不抛**。
 * 这里逐条钉住九个方法的降级返回值——它们的返回契约各不相同（列表承诺空列表、
 * `aggregated` 承诺恒非空、取证状态承诺对象），统一返回 null 会让调用方在
 * "探针未就绪"这一最常见场景下直接 NPE。
 */
class ReadFacadeProxyTest {

    private val hosts = ArrayList<BusinessHostImpl>()

    @AfterEach
    fun tearDown() {
        FacadeAssemblyTestHook.uninstall()
        hosts.forEach { runCatching { it.shutdown() } }
        hosts.clear()
    }

    /** 只实现业务宿主的最小探针实现；只读子门面故意不覆写，用于验证 default 降级单例。 */
    private inner class HostOnlyServices : BridgeServices {
        override fun businessHost(): BusinessHost = newHost()
    }

    /** 覆写了 read() 的探针实现，用于验证代理确实委派到真实实现。 */
    private inner class ReadServices(private val read: ProbeReadApi) : BridgeServices {
        override fun businessHost(): BusinessHost = newHost()
        override fun read(): ProbeReadApi = read
    }

    /** 九个方法全部可用的探针实现，仅用于"委派到真实实现"的断言。 */
    private class FullReadApi : ProbeReadApi {
        override fun latestSnapshot(): MetricSnapshot? = null
        override fun recentSnapshots(limit: Int): List<MetricSnapshot> = emptyList()
        override fun recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot> = emptyList()
        override fun aggregated(windowSize: Int): AggregatedMetrics =
            AggregatedMetrics.builder().windowSampleCount(1).build()
        override fun lastStartupProfile(): StartupProfile? = null
        override fun historyStartupProfiles(limit: Int): List<StartupProfile> = emptyList()
        override fun lastStartupComparisonSummary(): String = SUMMARY
        override fun queryNetworkPackets(query: NetworkPacketQuery): NetworkPacketPage = NetworkPacketPage.empty()
        override fun networkForensicsStatus(): NetworkForensicsStatus = NetworkForensicsStatus.unavailable(REASON)
    }

    /** 九个方法全部抛异常的实现，验证代理的异常兜底不放行到调用方。 */
    private class ThrowingReadApi : ProbeReadApi {
        override fun latestSnapshot(): MetricSnapshot? = boom<MetricSnapshot?>()
        override fun recentSnapshots(limit: Int): List<MetricSnapshot> = boom()
        override fun recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot> = boom()
        override fun aggregated(windowSize: Int): AggregatedMetrics = boom()
        override fun lastStartupProfile(): StartupProfile? = boom()
        override fun historyStartupProfiles(limit: Int): List<StartupProfile> = boom()
        override fun lastStartupComparisonSummary(): String = boom()
        override fun queryNetworkPackets(query: NetworkPacketQuery): NetworkPacketPage = boom()
        override fun networkForensicsStatus(): NetworkForensicsStatus = boom()

        private fun <T> boom(): T = error("实现违约")
    }

    /** 取一个受管的业务宿主，避免测试结束后遗留线程。 */
    private fun newHost(): BusinessHostImpl = BusinessHostImpl().also { hosts.add(it) }

    /** 同一引用恒等于自身：门面返回的是稳定代理，调用方可安全缓存为字段。 */
    @Test
    fun `read返回稳定代理且可缓存`() {
        val first = ServerProbeAPI.read()

        assertSame(first, ServerProbeAPI.read(), "read() 必须返回同一代理实例")
        assertTrue(first is ProbeReadApi, "代理必须实现 ProbeReadApi")
    }

    /** 未安装时九个方法逐条给出安全返回值，且都不是 null 集合。 */
    @Test
    fun `未就绪时九个方法逐条降级且不抛`() {
        val api = ServerProbeAPI.read()

        assertNull(api.latestSnapshot(), "最新快照降级为 null")
        assertTrue(api.recentSnapshots(10).isEmpty(), "近期快照降级为空列表")
        assertTrue(api.recentSnapshotsSince(0L).isEmpty(), "按时间下界筛的近期快照降级为空列表")

        val aggregated = api.aggregated(5)
        assertNotNull(aggregated, "聚合结果契约恒非空，降级也不得为 null")
        assertEquals(0, aggregated.windowSampleCount, "空窗口聚合份数为 0")
        assertNull(aggregated.tpsAvg, "空窗口聚合不产出 TPS 均值")

        assertNull(api.lastStartupProfile(), "最近启动画像降级为 null")
        assertTrue(api.historyStartupProfiles(3).isEmpty(), "历史启动画像降级为空列表")
        assertNull(api.lastStartupComparisonSummary(), "启动对比摘要降级为 null")

        val page = api.queryNetworkPackets(query())
        assertNotNull(page, "取证查询契约返回对象，降级也不得为 null")
        assertTrue(page.records.isEmpty(), "取证查询降级为空页")
        assertFalse(page.getHasNextPage(), "空页没有下一页")

        val status = api.networkForensicsStatus()
        assertNotNull(status, "取证状态契约返回对象，降级也不得为 null")
        assertFalse(status.getAvailable(), "未就绪时取证状态必须明确报告不可用")
    }

    /** 已安装且覆写 read() 时，代理委派到真实实现。 */
    @Test
    fun `已就绪时委派到真实实现`() {
        FacadeAssemblyTestHook.install(ReadServices(FullReadApi()))

        val api = ServerProbeAPI.read()

        assertEquals(1, api.aggregated(5).windowSampleCount, "应委派到真实实现的聚合")
        assertEquals(SUMMARY, api.lastStartupComparisonSummary(), "应委派到真实实现的摘要")
        assertEquals(REASON, api.networkForensicsStatus().getUnavailableReason(), "应委派到真实实现的取证状态")
    }

    /** 实现违约抛出时，代理逐方法收敛为安全值，绝不把异常放行给调用方。 */
    @Test
    fun `实现违约抛出时逐方法兜底`() {
        FacadeAssemblyTestHook.install(ReadServices(ThrowingReadApi()))
        val api = ServerProbeAPI.read()

        assertNull(api.latestSnapshot())
        assertTrue(api.recentSnapshots(1).isEmpty())
        assertTrue(api.recentSnapshotsSince(0L).isEmpty())
        assertEquals(0, api.aggregated(2).windowSampleCount)
        assertNull(api.lastStartupProfile())
        assertTrue(api.historyStartupProfiles(1).isEmpty())
        assertNull(api.lastStartupComparisonSummary())
        assertTrue(api.queryNetworkPackets(query()).records.isEmpty())
        assertFalse(api.networkForensicsStatus().getAvailable())
    }

    /** 未覆写 read() 的探针实现走 api 的 default 降级单例，而不是递归回门面自身。 */
    @Test
    fun `未覆写read时走独立降级单例而非递归`() {
        FacadeAssemblyTestHook.install(HostOnlyServices())

        val api = ServerProbeAPI.read()

        assertTrue(api.recentSnapshots(1).isEmpty(), "未接线时查询一律无数据，且不得 StackOverflowError")
        assertEquals(0, api.aggregated(1).windowSampleCount, "未接线时聚合为空窗口")
    }

    /** 卸载后立刻回到降级形态，缓存引用的调用方同样感知。 */
    @Test
    fun `卸载后缓存引用立即降级`() {
        FacadeAssemblyTestHook.install(ReadServices(FullReadApi()))
        val cached = ServerProbeAPI.read()
        assertEquals(SUMMARY, cached.lastStartupComparisonSummary())

        FacadeAssemblyTestHook.uninstall()

        assertNull(cached.lastStartupComparisonSummary(), "卸载后同一引用必须立刻降级")
    }

    /** 构造一个合法的最小取证查询。 */
    private fun query(): NetworkPacketQuery = NetworkPacketQuery.builder()
        .sinceMs(0L)
        .untilMs(1L)
        .limit(1)
        .build()

    private companion object {
        /** 测试用对比摘要。 */
        const val SUMMARY = "对比摘要"

        /** 测试用不可用原因。 */
        const val REASON = "测试不可用"
    }
}
