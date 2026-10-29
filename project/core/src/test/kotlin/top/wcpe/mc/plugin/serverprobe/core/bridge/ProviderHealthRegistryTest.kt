package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.core.bridge.health.HealthProbeRunner
import top.wcpe.mc.plugin.serverprobe.core.config.HealthSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest

/**
 * [ProviderHealthRegistry] 单元测试（Provider 健康规格 §5 的注册表侧验收）。
 *
 * ## 阈值一律注入极端值
 * 每个用例自带 [HealthSettings]，**不读 `ProbeConfig`**（core 单测 classpath 无
 * `basic-configuration`，`conf` 是 `lateinit`）。时间相关阈值注入几十毫秒，
 * 保留/隔离窗口用**假时钟**精确控制，避免墙钟抖动导致 flaky。
 *
 * ## 为什么把周期设得很大（除停滞用例外）
 * `refreshIntervalMs` 设成 60s 后，一轮 sweep 只由 `requestRefresh` 触发，
 * 于是"某域超时、其它域不变"这类断言不会被下一轮自动刷新推翻。
 */
class ProviderHealthRegistryTest {

    /** 已创建的注册表（用例结束时统一 stop，避免线程泄漏到其它用例）。 */
    private val registries = ArrayList<ProviderHealthRegistry>()

    /** 需要在结束时放行的卡死桩（否则 probe 线程会一直挂着）。 */
    private val gatedProviders = ArrayList<GatedProvider>()

    /** 假时钟；`@Volatile` 因为它会被调度线程读到。 */
    @Volatile
    private var nowMs: Long = 1_000L

    /** 收到的 WARN 文本（用于断言"停滞只告警一次"）。 */
    private val warnings = ArrayList<String>()

    @AfterEach
    fun tearDown() {
        // 先放行卡死桩，再停注册表：否则 join 会一直等到超时。
        gatedProviders.forEach { it.release() }
        gatedProviders.clear()
        registries.forEach { runCatching { it.stop() } }
        registries.clear()
        warnings.clear()
    }

    // ---------------------------------------------------------------- 验收：默认兼容

    /**
     * 验收「默认兼容」：不覆写 `health()` 的旧 Provider 编译通过，回执该域 `health=UNKNOWN`、`sinceMs=0`。
     */
    @Test
    fun `未覆写health的旧Provider呈UNKNOWN且sinceMs为零`() {
        val registry = startedRegistry()
        val legacy = LegacyProvider("economy")

        registry.attach("economy", legacy)
        awaitRefreshed(registry, "economy")

        val status = registry.viewOf("economy")
        assertEquals(ProviderHealth.Status.UNKNOWN, status.health.status, "未覆写 health() 应呈 UNKNOWN")
        assertEquals(0L, status.health.sinceMs, "未提供 sinceMs 时必须为 0（探针不替 Provider 推断）")
        assertEquals("", status.health.reason, "无原因时为空串")
        assertTrue(status.registered, "已注册域应标 registered=true")
        assertTrue(status.refreshedAtMs > 0L, "应记录探针采集时刻")
    }

    /** 验收「五等级呈现」：五种自报值逐一对应，`reason` 原样转发（含中文）。 */
    @Test
    fun `五等级自报值逐一对应且原样转发原因`() {
        val registry = startedRegistry()
        val cases = linkedMapOf(
            "lv-available" to (ProviderHealth.available() to ""),
            "lv-unavailable" to (ProviderHealth.unavailable("插件未安装") to "插件未安装"),
            "lv-initializing" to (ProviderHealth.initializing("等待外部插件就绪") to "等待外部插件就绪"),
            "lv-degraded" to (ProviderHealth.degraded("内部状态损坏") to "内部状态损坏"),
            "lv-unknown" to (ProviderHealth.unknown() to ""),
        )
        cases.forEach { (domain, value) ->
            registry.attach(domain, FixedProvider(domain, value.first))
        }

        cases.keys.forEach { awaitRefreshed(registry, it) }

        assertEquals(ProviderHealth.Status.AVAILABLE, registry.viewOf("lv-available").health.status)
        assertEquals(ProviderHealth.Status.UNAVAILABLE, registry.viewOf("lv-unavailable").health.status)
        assertEquals(ProviderHealth.Status.INITIALIZING, registry.viewOf("lv-initializing").health.status)
        assertEquals(ProviderHealth.Status.DEGRADED, registry.viewOf("lv-degraded").health.status)
        assertEquals(ProviderHealth.Status.UNKNOWN, registry.viewOf("lv-unknown").health.status)
        assertEquals("插件未安装", registry.viewOf("lv-unavailable").health.reason, "reason 必须原样转发（中文不得被改写）")
        assertEquals("等待外部插件就绪", registry.viewOf("lv-initializing").health.reason)
    }

    /**
     * 验收「`of(...)` 时间戳」：`sinceMs` 原样转发，且与探针的 `refreshedAtMs` **不相等**
     * （二者语义不同：前者是 Provider 自报"自何时起"，后者是探针采集时刻）。
     */
    @Test
    fun `of的sinceMs与refreshedAtMs互相独立`() {
        val registry = startedRegistry()
        val since = 1_730_000_000_000L
        val reported = ProviderHealth.of(ProviderHealth.Status.DEGRADED, "内部状态损坏", "{\"k\":1}", since)
        registry.attach("economy", FixedProvider("economy", reported))

        awaitRefreshed(registry, "economy")

        val status = registry.viewOf("economy")
        assertEquals(since, status.health.sinceMs, "sinceMs 必须原样转发")
        assertEquals("{\"k\":1}", status.health.detailJson, "detailJson 必须原样转发")
        assertEquals("内部状态损坏", status.health.reason)
        assertNotEquals(status.health.sinceMs, status.refreshedAtMs, "两个时间戳必须独立，不得互相填充")
    }

    // ------------------------------------------------------------ 验收：刷新线程隔离

    /** 验收「刷新线程隔离」：`health()` 内观测到的线程名前缀为 `serverprobe-health-probe`，且不是命令池线程。 */
    @Test
    fun `health只在probe线程上被调用`() {
        val registry = startedRegistry()
        val recorder = ThreadRecordingProvider("economy")
        registry.attach("economy", recorder)

        awaitRefreshed(registry, "economy")

        assertTrue(
            recorder.observedThread.startsWith("${HealthProbeRunner.THREAD_PROBE}"),
            "health() 应在 probe 线程上调用，实际=${recorder.observedThread}",
        )
        assertNotEquals("serverprobe-cmd", recorder.observedThread, "绝不能在命令池线程上调用 Provider")
        assertFalse(
            recorder.observedThread.startsWith("serverprobe-cmd"),
            "命令池线程名不得出现在 health() 内，实际=${recorder.observedThread}",
        )
    }

    // ---------------------------------------------------------------- 验收：超时归类

    /**
     * 验收「超时归类」：`healthTimeoutMs=50`、`health()` 阻塞 200ms →
     * 该域 `DEGRADED` + 原因含 `health() 超时` + `quarantined=true`，**其它域取值不变**。
     */
    @Test
    fun `超时归类为DEGRADED并进入隔离且不影响其它域`() {
        val registry = startedRegistry(settings(timeoutMs = 50))
        val slow = GateOnCallProvider("z-slow", blockMs = 200)
        registry.attach("a-ok", FixedProvider("a-ok", ProviderHealth.available()))
        registry.attach("z-slow", slow)

        // 一轮 sweep：a-ok 先刷新成功，z-slow 随后超时。
        awaitStatus(registry, "z-slow", ProviderHealth.Status.DEGRADED)

        val degraded = registry.viewOf("z-slow")
        assertTrue(degraded.health.reason.contains("health() 超时"), "原因应含 health() 超时，实际=${degraded.health.reason}")
        assertTrue(degraded.quarantined, "超时后必须进入隔离窗口")
        assertEquals(
            ProviderHealth.Status.AVAILABLE,
            registry.viewOf("a-ok").health.status,
            "单域超时不得影响本轮其它域",
        )
        assertTrue(registry.snapshot().quarantined.contains("z-slow"), "quarantined 列表应含该域")
    }

    /** 隔离窗口内不再调用该域的 `health()`（直接沿用上次结论）。 */
    @Test
    fun `隔离窗口内不再调用health`() {
        val registry = startedRegistry(settings(timeoutMs = 50, quarantineMs = 60_000))
        val slow = GateOnCallProvider("z-slow", blockMs = 200)
        val ok = FixedProvider("a-ok", ProviderHealth.available())
        registry.attach("a-ok", ok)
        registry.attach("z-slow", slow)
        awaitStatus(registry, "z-slow", ProviderHealth.Status.DEGRADED)
        val callsAfterTimeout = slow.healthCalls.get()

        // 再触发一轮 sweep（刷新另一个域），已隔离的域不应被再次调用。
        registry.requestRefresh("a-ok")
        awaitAtLeast(ok.healthCalls, 2)

        assertEquals(
            callsAfterTimeout,
            slow.healthCalls.get(),
            "隔离窗口内不得再次调用该域的 health()，实际调用=${slow.healthCalls.get()}",
        )
        assertTrue(registry.viewOf("z-slow").quarantined, "隔离窗口内 quarantined 应保持 true")
    }

    /** 验收「抛异常隔离」：A 抛异常、B 正常 → A 为 DEGRADED（原因含 `health() 抛出异常`）、B 为 AVAILABLE。 */
    @Test
    fun `抛异常被隔离为该域DEGRADED且不影响其它域`() {
        val registry = startedRegistry()
        registry.attach("a-bad", ThrowingProvider("a-bad", IllegalStateException("boom")))
        registry.attach("b-good", FixedProvider("b-good", ProviderHealth.available()))

        awaitStatus(registry, "a-bad", ProviderHealth.Status.DEGRADED)

        val bad = registry.viewOf("a-bad")
        assertTrue(
            bad.health.reason.contains("health() 抛出异常"),
            "原因应含 health() 抛出异常，实际=${bad.health.reason}",
        )
        assertTrue(bad.health.reason.contains("IllegalStateException"), "原因应含异常类名，实际=${bad.health.reason}")
        assertTrue(bad.quarantined, "抛异常后应进入隔离窗口")
        assertEquals(ProviderHealth.Status.AVAILABLE, registry.viewOf("b-good").health.status, "其它域必须不受影响")
    }

    /** 验收「返回 null」：`health()` 返回 null → DEGRADED + 原因含 `health() 返回 null`，不抛 NPE。 */
    @Test
    fun `返回null归类为DEGRADED且不抛NPE`() {
        val registry = startedRegistry()
        registry.attach("a-null", NullHealthProvider("a-null"))

        awaitStatus(registry, "a-null", ProviderHealth.Status.DEGRADED)

        val status = registry.viewOf("a-null")
        assertTrue(
            status.health.reason.contains("health() 返回 null"),
            "原因应含 health() 返回 null，实际=${status.health.reason}",
        )
        // null 那次调用立刻返回、没有占住 probe 线程，故不隔离（规格 §3.2 的三条归类）。
        assertFalse(status.quarantined, "返回 null 不应进入隔离窗口")
    }

    // ------------------------------------------------------------- 验收：隔离与停滞

    /**
     * 验收「隔离与停滞」：卡死 Provider 占住 probe 线程后：
     * - `refresh.stalled=true`；
     * - 其余域仍返回**上次缓存值**；
     * - `threadNames()` 长度恒为 2、**不随轮次增长**（证明卡死不泄漏线程）。
     */
    @Test
    fun `卡死占住probe线程后停滞且其余域沿用缓存且线程数不变`() {
        // 周期取 20ms：让 sweep 自动连续发生，从而在没有竞态的前提下收敛到"停滞"。
        // 本用例用**真实时钟**：这样"refreshedAtMs 不再变化"才真正证明该域没被重新采集。
        val registry = newRegistry(
            settings(intervalMs = 20, timeoutMs = 50, quarantineMs = 60_000),
            clock = { System.currentTimeMillis() },
        )
        val stuck = GatedProvider("a-stall")
        gatedProviders.add(stuck)
        registry.attach("a-stall", stuck)
        registry.attach("b-filler", FixedProvider("b-filler", ProviderHealth.available()))
        registry.attach("c-stable", FixedProvider("c-stable", ProviderHealth.available()))
        registry.start()

        // 先让 c-stable 拿到一份可用状态（这就是"上次缓存值"）。
        awaitStatus(registry, "c-stable", ProviderHealth.Status.AVAILABLE)

        // 之后让 a-stall 永久占住 probe 线程（忽略中断，模拟 Java 无法强杀的挂起）。
        stuck.blocking = true
        awaitUntil("stalled 置位") { registry.snapshot().stalled }
        awaitUntil("c-stable 被跳过") { registry.snapshot().skippedThisSweep.contains("c-stable") }

        val baseline = registry.viewOf("c-stable")
        assertEquals(ProviderHealth.Status.AVAILABLE, baseline.health.status, "停滞期间其余域必须沿用上次缓存值")

        // 连续 3 轮请求：缓存不变、线程数恒定（不随轮次增长，证明卡死不泄漏线程）。
        repeat(3) {
            registry.requestRefresh("a-stall")
            registry.requestRefresh("b-filler")
            registry.requestRefresh("c-stable")
            awaitUntil("第 ${it + 1} 轮 sweep 完成") { registry.snapshot().refreshInFlight.not() }
        }

        val after = registry.viewOf("c-stable")
        assertEquals(baseline.refreshedAtMs, after.refreshedAtMs, "被跳过的域不得被重新采集（refreshedAtMs 必须不变）")
        assertEquals(ProviderHealth.Status.AVAILABLE, after.health.status, "其余域仍返回上次缓存值")
        assertEquals(2, registry.threadNames().size, "刷新线程数必须恒为 2，实际=${registry.threadNames()}")
        assertEquals(listOf(ProviderHealthRegistry.THREAD_SCHEDULER, HealthProbeRunner.THREAD_PROBE), registry.threadNames())
        assertEquals(1, warnings.size, "停滞只应 WARN 一次，实际=${warnings.size} 条")
    }

    // -------------------------------------------------------------- 验收：失败记录

    /** 验收「失败记录」：失败 2 次 → `consecutiveFailures=2`；成功 1 次后清零；`total/failed` 累加正确。 */
    @Test
    fun `失败记录累加并在成功后清零`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))

        registry.recordDispatch("economy", BridgeCommandResult.fail("经济插件未就绪"), 7L)
        registry.recordDispatch("economy", BridgeCommandResult.fail("经济插件未就绪"), 9L)

        val failed = registry.viewOf("economy")
        assertEquals(2L, failed.dispatchTotal, "total 应累加")
        assertEquals(2L, failed.dispatchFailed, "failed 应累加")
        assertEquals(2, failed.lastFailure?.consecutiveFailures, "连续失败次数应为 2")
        assertEquals("经济插件未就绪", failed.lastFailure?.message)
        assertEquals(9L, failed.lastCostMs, "lastCostMs 应为最近一次的耗时")

        registry.recordDispatch("economy", BridgeCommandResult.ok("ok"), 3L)

        val recovered = registry.viewOf("economy")
        assertEquals(3L, recovered.dispatchTotal, "成功也应计入 total")
        assertEquals(2L, recovered.dispatchFailed, "failed 不应因成功而回退")
        assertNull(recovered.lastFailure, "成功一次后失败记录应清零")
        assertEquals(3L, recovered.lastCostMs)
    }

    /** `recordDispatch` 对未注册域 / 空域不抛、不建条目（纯内存 O(1)）。 */
    @Test
    fun `recordDispatch对未知域无副作用`() {
        val registry = newRegistry()

        registry.recordDispatch("nowhere", BridgeCommandResult.fail("x"), 1L)
        registry.recordDispatch("", BridgeCommandResult.fail("x"), 1L)

        assertFalse(registry.viewOf("nowhere").registered, "不应为未知域创建条目")
        assertTrue(registry.snapshot().domains.isEmpty(), "快照应保持为空")
    }

    /**
     * 验收「脱敏」（裸凭据形态）：失败原因含 `token=abc123` → 入库即替换为 `token=***`，明文不出现。
     */
    @Test
    fun `失败原因入库即脱敏`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))

        registry.recordDispatch("economy", BridgeCommandResult.fail("鉴权失败 token=abc123 (来源=面板)"), 1L)

        val message = registry.viewOf("economy").lastFailure?.message ?: fail("应记录失败信息")
        assertTrue(message.contains("token=***"), "凭据值应替换为 ***，实际=$message")
        assertFalse(message.contains("abc123"), "明文凭据绝不得出现，实际=$message")
    }

    /**
     * 验收「脱敏」（URL 形态）：含 query 的 URL **整段去掉 query**——
     * 凭据通常就藏在 query 里，逐键替换挡不住未知键名。
     */
    @Test
    fun `失败原因中的URL去掉query`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))

        registry.recordDispatch(
            "economy",
            BridgeCommandResult.fail("回调失败 https://api.example.com/x?access_key=abc123&k=1"),
            1L,
        )

        val message = registry.viewOf("economy").lastFailure?.message ?: fail("应记录失败信息")
        assertFalse(message.contains("abc123"), "query 里的凭据绝不得出现，实际=$message")
        assertFalse(message.contains("access_key"), "整个 query 都应被去掉，实际=$message")
        assertTrue(message.contains("https://api.example.com/x"), "URL 主体应保留以便定位，实际=$message")
    }

    /** Provider 自报的 `reason` 同样在入库时脱敏（它也是第三方文本）。 */
    @Test
    fun `Provider自报原因入库即脱敏`() {
        val registry = startedRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.degraded("连接失败 password=hunter2")))

        awaitStatus(registry, "economy", ProviderHealth.Status.DEGRADED)

        val reason = registry.viewOf("economy").health.reason
        assertTrue(reason.contains("password=***"), "password 值应替换为 ***，实际=$reason")
        assertFalse(reason.contains("hunter2"), "明文密码绝不得出现，实际=$reason")
    }

    // ------------------------------------------------------------ 验收：未注册域

    /** 验收「未注册域」：未注册域返回 `registered=false` + 原因，不报错。 */
    @Test
    fun `未注册域返回未注册且带原因`() {
        val registry = newRegistry()

        val status = registry.viewOf("inventory")

        assertFalse(status.registered, "未注册域应标 registered=false")
        assertEquals(ProviderHealth.Status.UNKNOWN, status.health.status)
        assertTrue(status.reasonOverride.contains("未注册"), "应给出可读原因，实际=${status.reasonOverride}")
        assertEquals(0L, status.refreshedAtMs, "从未刷新应为 0")
    }

    /** `detach` **按实例比对**：陈旧实例的回调不得抹掉后来注册的新实例。 */
    @Test
    fun `detach按实例比对保护新注册`() {
        val registry = newRegistry()
        val first = FixedProvider("economy", ProviderHealth.available())
        val second = FixedProvider("economy", ProviderHealth.available())

        registry.attach("economy", first)
        registry.attach("economy", second)
        registry.detach("economy", first)

        assertTrue(registry.viewOf("economy").registered, "陈旧实例的 detach 不得注销新实例")

        registry.detach("economy", second)
        assertFalse(registry.viewOf("economy").registered, "当前注册者的 detach 应生效")
        assertEquals(ProviderHealthRegistry.REASON_UNREGISTERED, registry.viewOf("economy").reasonOverride)
    }

    // --------------------------------------------------------- 验收：保留与清理

    /** 验收「保留与清理」（保留窗口）：`retainAfterUnregisterMs=0` → `detach` 后下一次 sweep 该域从全量回执消失。 */
    @Test
    fun `注销超过保留窗口后条目被清除`() {
        val registry = newRegistry(settings(retainAfterUnregisterMs = 0))
        val keeper = FixedProvider("a-keeper", ProviderHealth.available())
        val leaving = FixedProvider("b-leaving", ProviderHealth.available())
        registry.attach("a-keeper", keeper)
        registry.attach("b-leaving", leaving)
        registry.detach("b-leaving", leaving)

        // 推进假时钟越过保留窗口（0ms → 差值 1ms 即"超窗口"）。
        nowMs += 1
        registry.start()
        awaitRefreshed(registry, "a-keeper")
        awaitUntil("已注销域被清理") { registry.snapshot().domains.none { it.domain == "b-leaving" } }

        assertTrue(
            registry.snapshot().domains.any { it.domain == "a-keeper" },
            "已注册域不得被清理",
        )
    }

    /**
     * 验收「保留与清理」（上限）：注入 300 个已注销域 → 条目数 ≤ `maxEntries`，
     * 且**已注册域一个不少**。
     */
    @Test
    fun `超上限时只淘汰已注销条目`() {
        // 上限按规格取 256，显式注入以便将来默认值变化时本用例仍验的是"上限行为"。
        val registry = newRegistry(settings().copy(maxEntries = 256))
        repeat(300) { index ->
            val provider = FixedProvider("gone-$index", ProviderHealth.available())
            registry.attach("gone-$index", provider)
            registry.detach("gone-$index", provider)
        }
        registry.attach("keeper", FixedProvider("keeper", ProviderHealth.available()))

        registry.start()
        awaitRefreshed(registry, "keeper")
        awaitUntil("条目数被裁剪到上限内") { registry.snapshot().domains.size <= 256 }

        val domains = registry.snapshot().domains
        assertTrue(domains.size <= 256, "条目数应 ≤ maxEntries，实际=${domains.size}")
        val keeper = domains.firstOrNull { it.domain == "keeper" }
        assertTrue(keeper != null, "已注册域一个都不能少")
        assertTrue(keeper?.registered == true, "保留下来的已注册域仍应为 registered=true")
    }

    // ------------------------------------------------------------------ 验收：停机

    /** 验收「停机」：`stop()` 幂等；可再次 `start()`；Provider 正常返回时无 `serverprobe-health*` 线程残留。 */
    @Test
    fun `停机幂等且无健康线程残留`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        registry.start()
        awaitRefreshed(registry, "economy")
        assertEquals(2, registry.threadNames().size, "运行期应有两个刷新线程名")

        registry.stop()
        registry.stop()
        awaitUntil("健康线程全部退出", timeoutMs = 5_000) { liveHealthThreads().isEmpty() }

        assertTrue(liveHealthThreads().isEmpty(), "停机后不得残留 serverprobe-health* 线程，实际=${liveHealthThreads()}")

        // 再次 start 应可用（幂等），并仍只有两个线程名。
        registry.start()
        assertEquals(2, registry.threadNames().size)
        registry.stop()
        awaitUntil("二次停机后无残留") { liveHealthThreads().isEmpty() }
    }

    // -------------------------------------------------------------------- 测试设施

    /** 本进程内仍存活的 `serverprobe-health*` 线程。 */
    private fun liveHealthThreads(): List<String> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name.startsWith("serverprobe-health") }
        .map { it.name }

    /** 构造并启动一个注册表（周期 60s：一轮 sweep 只由 `requestRefresh` 触发）。 */
    private fun startedRegistry(settings: HealthSettings = settings()): ProviderHealthRegistry =
        newRegistry(settings).also { it.start() }

    /** 构造一个未启动的注册表，注入阈值、时钟与告警收集器。 */
    private fun newRegistry(
        settings: HealthSettings = settings(),
        clock: () -> Long = { nowMs },
    ): ProviderHealthRegistry {
        val registry = ProviderHealthRegistry()
        registry.settingsProvider = { settings }
        registry.clock = clock
        registry.warn = { warnings.add(it) }
        registries.add(registry)
        return registry
    }

    /**
     * 默认阈值：周期很长（不自动 sweep）、超时 50ms、隔离/保留窗口用假时钟控制。
     *
     * 只暴露时间相关旋钮；截断长度用 [HealthSettings] 的默认值（512 / 2048），
     * 需要改上限的用例在调用处 `copy`，避免一个 7 参数的助手把断言意图埋起来。
     */
    private fun settings(
        intervalMs: Long = 60_000L,
        timeoutMs: Long = 50L,
        quarantineMs: Long = 60_000L,
        retainAfterUnregisterMs: Long = 300_000L,
    ): HealthSettings = HealthSettings(
        refreshIntervalMs = intervalMs,
        healthTimeoutMs = timeoutMs,
        quarantineMs = quarantineMs,
        retainAfterUnregisterMs = retainAfterUnregisterMs,
    )

    /** 等到该域被刷新过（`refreshedAtMs != 0`）。 */
    private fun awaitRefreshed(registry: ProviderHealthRegistry, domain: String) {
        awaitUntil("域 $domain 被刷新") { registry.viewOf(domain).refreshedAtMs != 0L }
    }

    /** 等到该域呈现指定等级。 */
    private fun awaitStatus(registry: ProviderHealthRegistry, domain: String, status: ProviderHealth.Status) {
        awaitUntil("域 $domain 呈 $status") { registry.viewOf(domain).health.status == status }
    }

    /** 等到计数器达到期望值。 */
    private fun awaitAtLeast(counter: AtomicInteger, expected: Int) {
        awaitUntil("计数达到 $expected") { counter.get() >= expected }
    }

    /** 有界轮询直到条件成立；超时即用例失败。 */
    private fun awaitUntil(description: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        fail<Unit>("等待超时：$description")
    }

    /** 不覆写 `health()` 的"旧 Provider"（验收默认兼容）。 */
    private class LegacyProvider(private val name: String) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()
    }

    /** 固定返回某个自报值；同时统计被调用次数（用于断言"隔离窗口内不再调用"）。 */
    private class FixedProvider(
        private val name: String,
        private val reported: ProviderHealth,
    ) : BusinessProvider {
        val healthCalls = AtomicInteger(0)

        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth {
            healthCalls.incrementAndGet()
            return reported
        }
    }

    /** 记录 `health()` 被调用时的线程名。 */
    private class ThreadRecordingProvider(private val name: String) : BusinessProvider {
        @Volatile
        var observedThread: String = ""

        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth {
            observedThread = Thread.currentThread().name
            return ProviderHealth.available()
        }
    }

    /** `health()` 抛异常。 */
    private class ThrowingProvider(
        private val name: String,
        private val error: RuntimeException,
    ) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth = throw error
    }

    /** `health()` 违约返回 null。 */
    private class NullHealthProvider(private val name: String) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth? = null
    }

    /** `health()` 每次阻塞固定时长（可被中断），用于超时归类。 */
    private class GateOnCallProvider(
        private val name: String,
        private val blockMs: Long,
    ) : BusinessProvider {
        val healthCalls = AtomicInteger(0)

        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth {
            healthCalls.incrementAndGet()
            Thread.sleep(blockMs)
            return ProviderHealth.available()
        }
    }

    /**
     * 可切换"卡死"的 Provider：卡死时**忽略中断**，直到 [release] 被调用。
     *
     * 必须忽略中断才能模拟真实场景：`future.cancel(true)` 打不断它，
     * 于是 probe 线程被永久占住——这正是规格 §6 风险 2 登记的取舍。
     */
    private class GatedProvider(private val name: String) : BusinessProvider {
        @Volatile
        var blocking: Boolean = false

        private val gate = CountDownLatch(1)

        /** 放行（用例收尾时调用，避免 probe 线程真的永久挂住）。 */
        fun release() {
            gate.countDown()
        }

        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth {
            if (!blocking) return ProviderHealth.available()
            while (true) {
                try {
                    gate.await()
                    return ProviderHealth.available()
                } catch (_: InterruptedException) {
                    // 故意忽略中断：模拟"不可中断地卡住"（Java 无法强杀线程）。
                }
            }
        }
    }

    private companion object {
        /** 轮询间隔（毫秒）：短轮询让断言尽快收敛，同时不空转。 */
        const val POLL_MS = 10L
    }
}
