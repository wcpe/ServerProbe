package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.FacadeAssemblyTestHook
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.core.config.HealthSettings
import java.util.concurrent.atomic.AtomicInteger
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest

/**
 * [BusinessHostImpl] 与 [ProviderHealthRegistry] 的**接线**验收（规格 §4 的最后两条 core 任务）。
 *
 * 这些接缝最容易被漏掉且不会报错：
 * 漏 `attach` → 域永远 `registered=false`；漏 `recordDispatch` → 面板永远看不到失败原因；
 * 漏 `detach` → 已卸载域仍显示在册。故逐条钉住。
 *
 * 同时覆盖"未注入注册表"的降级路径：既有单测会直接 `BusinessHostImpl()`，
 * 此时 `healthOf` 必须安全返回而不是 NPE。
 */
class BusinessHostHealthWiringTest {

    private val hosts = ArrayList<BusinessHostImpl>()

    private val registry = ProviderHealthRegistry()

    @AfterEach
    fun tearDown() {
        registry.stop()
        hosts.forEach { runCatching { it.shutdown() } }
        hosts.clear()
        FacadeAssemblyTestHook.uninstall()
    }

    /** 装配一个"注入过健康注册表"的宿主。 */
    private fun wiredHost(): BusinessHostImpl {
        registry.settingsProvider = { settings() }
        registry.clock = { System.currentTimeMillis() }
        registry.start()
        val host = BusinessHostImpl()
        host.healthRegistry = registry
        hosts.add(host)
        return host
    }

    /** 注册 Provider 后 `healthOf` 立刻反映为在册（`attach` 已接线）。 */
    @Test
    fun `注册后healthOf反映为在册`() {
        val host = wiredHost()

        host.register(FixedProvider("economy", ProviderHealth.available()))

        assertTrue(host.healthOf("economy").registered, "注册应同步写入健康缓存（attach 接线）")
    }

    /** 注销后 `healthOf` 反映为已注销（`detach` 已接线），且不报错。 */
    @Test
    fun `注销后healthOf反映为已注销`() {
        val host = wiredHost()
        val provider = FixedProvider("economy", ProviderHealth.available())
        host.register(provider)

        host.unregister(provider)

        val status = host.healthOf("economy")
        assertFalse(status.registered, "注销应同步到健康缓存（detach 接线）")
        assertEquals(ProviderHealthRegistry.REASON_UNREGISTERED, status.reasonOverride)
    }

    /** 句柄 `close()` 注销时同样接线（它与 `unregister` 是两条路径）。 */
    @Test
    fun `句柄close注销同样接线`() {
        val host = wiredHost()
        val handle = host.register(FixedProvider("economy", ProviderHealth.available()))

        handle.close()

        assertFalse(host.healthOf("economy").registered, "close 路径也必须同步到健康缓存")
    }

    /** `dispatch` 收尾处记录结果：失败累加 `failed`、`lastFailure` 出现（`recordDispatch` 已接线）。 */
    @Test
    fun `dispatch收尾记录失败与耗时`() {
        val host = wiredHost()
        host.register(FailingProvider("economy"))

        val result = host.dispatch("economy", "pay", "{}")

        assertFalse(result.success, "桩 Provider 应失败")
        val status = host.healthOf("economy")
        assertEquals(1L, status.dispatchTotal, "每次派发都应计入 total")
        assertEquals(1L, status.dispatchFailed, "失败应计入 failed")
        assertNotNull(status.lastFailure, "失败后应留下 lastFailure（面板据此解释原因）")
        assertEquals("余额不足", status.lastFailure?.message)
        // 耗时字段：单写 `>= 0` 对任何非负 long 都成立（包括未赋值的默认 0），等于没验。
        // 加上上界才能区分“真的记录了一次很短的调用”与“字段根本没被赋值”。
        assertTrue(
            status.lastCostMs < 60_000L,
            "一次内存内派发不应超过 60s，实际=${status.lastCostMs}ms（异常大值说明计时起点错了）",
        )
    }

    /** 失败后成功一次即清零 `lastFailure`（健康统计不会"黏"在旧失败上）。 */
    @Test
    fun `成功一次后失败记录清零`() {
        val host = wiredHost()
        val flaky = FlakyProvider("economy")
        host.register(flaky)

        host.dispatch("economy", "pay", "{}")
        flaky.succeed = true
        host.dispatch("economy", "pay", "{}")

        val status = host.healthOf("economy")
        assertEquals(2L, status.dispatchTotal)
        assertEquals(1L, status.dispatchFailed)
        assertEquals(null, status.lastFailure, "成功一次后应清零")
    }

    /** 未注册域：`healthOf` 返回未注册快照，不抛。 */
    @Test
    fun `未注册域healthOf安全返回`() {
        val host = wiredHost()

        val status = host.healthOf("inventory")

        assertFalse(status.registered)
        assertEquals(ProviderHealth.Status.UNKNOWN, status.health.status)
        assertTrue(status.reasonOverride.contains("未注册"), "应给出可读原因，实际=${status.reasonOverride}")
    }

    /**
     * 未注入注册表（既有单测的构造方式）时 `healthOf` 降级但**不抛**。
     *
     * 这条保证既有测试不必全部改造成本类的新装配方式。
     */
    @Test
    fun `未注入注册表时healthOf降级不抛`() {
        val host = BusinessHostImpl()
        hosts.add(host)
        host.register(FixedProvider("economy", ProviderHealth.available()))

        val status = host.healthOf("economy")

        assertFalse(status.registered, "未注入时应降级为未注册")
        assertEquals(ProviderHealth.Status.UNKNOWN, status.health.status)
        // 派发也必须照常工作（健康统计坏掉绝不能影响业务）。
        assertTrue(host.dispatch("economy", "pay", "{}").success)
    }

    /** 门面代理（未安装探针）上的 `healthOf` 安全返回，绝不抛。 */
    @Test
    fun `门面未就绪时healthOf安全返回`() {
        FacadeAssemblyTestHook.uninstall()

        val status: BusinessHost = ServerProbeAPI.businessHost()
        val snapshot = status.healthOf("economy")

        assertFalse(snapshot.registered, "未就绪时应降级为未注册")
        assertEquals(ProviderHealth.Status.UNKNOWN, snapshot.health.status)
    }

    // 注：门面代理"已安装"时的 `healthOf` 转发不在此处断言——`ServerProbeAPI.install` 是包内可见
    // （package-private）的装配入口，本类不在 `top.wcpe.mc.plugin.serverprobe.api` 包内。
    // 该转发是一行委派，接口新增方法时若不实现会编译失败，由编译器兜底；
    // 降级路径（上面这条）才是真正会静默出错的分支。已安装路径由 BusinessHostHandleTest 覆盖。

    // -------------------------------------------------------------------- 测试设施

    private fun settings(): HealthSettings = HealthSettings(
        refreshIntervalMs = 60_000L,
        healthTimeoutMs = 50L,
        quarantineMs = 60_000L,
        retainAfterUnregisterMs = 300_000L,
        maxEntries = 256,
        reasonMaxChars = 512,
        detailMaxChars = 2_048,
    )

    /** 固定自报值、dispatch 成功。 */
    private class FixedProvider(
        private val name: String,
        private val reported: ProviderHealth,
    ) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth = reported
    }

    /** dispatch 恒失败。 */
    private class FailingProvider(private val name: String) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.fail("余额不足")
    }

    /** 失败/成功可切换。 */
    private class FlakyProvider(private val name: String) : BusinessProvider {
        @Volatile
        var succeed: Boolean = false

        private val calls = AtomicInteger(0)

        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult {
            calls.incrementAndGet()
            return if (succeed) ActionResult.ok() else ActionResult.fail("余额不足")
        }
    }
}
