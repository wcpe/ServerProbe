package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.api.bridge.RiskLevel
import top.wcpe.mc.plugin.serverprobe.core.bridge.manifest.ManifestDigest
import top.wcpe.mc.plugin.serverprobe.core.config.HealthSettings
import java.util.concurrent.atomic.AtomicInteger

/**
 * 域状态扩展的验收测试（spec §6 的 6 条）。
 *
 * ## 为什么这些用例大多落在 `ProviderHealthRegistry` + `BusinessHostImpl` 上
 * spec 的验收项不是"路由回执里有没有这个键"，而是几条**行为约束**：
 * 查询不触发 Provider 初始化、指纹在注册时算、计数重启归零。
 * 它们全都在"注册 → 缓存 → 查询"这条链上，故用真实的注册表与宿主跑，
 * 只在回执字段形状上借用 `BusinessStatusRoute` 的可注入编码器。
 *
 * ## 时钟
 * 全部用例注入假时钟：`lastDispatchAt` 与 `sinceMs` 的口径差异是本功能的核心，
 * 用真实时钟断言"完成时刻"会得到一个既可能是起始也可能是结束的模糊值。
 */
class BusinessDomainStatusTest {

    private val registries = ArrayList<ProviderHealthRegistry>()
    private val hosts = ArrayList<BusinessHostImpl>()

    @AfterEach
    fun tearDown() {
        hosts.forEach { runCatching { it.shutdown() } }
        hosts.clear()
        registries.forEach { runCatching { it.stop() } }
        registries.clear()
    }

    // ------------------------------------------------------------ 指纹规范化算法

    /**
     * 规范化：**动作顺序不影响指纹**（spec §3.1「动作按名称排序」）。
     *
     * 这是最容易被漏掉的一条：Provider 的 manifest 每次现算，动作列表若来自 `HashMap` 遍历，
     * 同一份内容两次调用可能给出不同顺序，指纹就会抖动。
     */
    @Test
    fun `动作顺序不同时指纹相同`() {
        val a = manifestOf("economy", "alpha", "beta", "gamma")
        val b = manifestOf("economy", "gamma", "alpha", "beta")

        assertEquals(ManifestDigest.of(a), ManifestDigest.of(b), "动作声明顺序不得影响指纹")
    }

    /** 规范化：**属性键顺序不影响指纹**（spec §3.1「键按字典序」）。 */
    @Test
    fun `属性键顺序不同时指纹相同`() {
        val a = DomainManifest.of("economy", "经济", "", emptyList(), linkedMapOf("v" to "1", "a" to "2"))
        val b = DomainManifest.of("economy", "经济", "", emptyList(), linkedMapOf("a" to "2", "v" to "1"))

        assertEquals(ManifestDigest.of(a), ManifestDigest.of(b), "attributes 迭代顺序不得影响指纹")
    }

    /** 规范化：**错误码集合顺序不影响指纹**（Set 无顺序语义）。 */
    @Test
    fun `错误码顺序不同时指纹相同`() {
        val a = DomainManifest.of(
            "economy", "经济", "",
            listOf(ActionSpec.of("apply", "", RiskLevel.WRITE, false, null, null, linkedSetOf("A", "B", "C"))),
            emptyMap(),
        )
        val b = DomainManifest.of(
            "economy", "经济", "",
            listOf(ActionSpec.of("apply", "", RiskLevel.WRITE, false, null, null, linkedSetOf("C", "A", "B"))),
            emptyMap(),
        )

        assertEquals(ManifestDigest.of(a), ManifestDigest.of(b), "errorCodes 是无序集合，不得影响指纹")
    }

    /** 规范化：**schema 首尾空白不影响指纹**（去除空白差异）。 */
    @Test
    fun `schema首尾空白不同时指纹相同`() {
        val a = DomainManifest.of(
            "economy", "经济", "",
            listOf(ActionSpec.of("apply", "", RiskLevel.WRITE, false, "{\"type\":\"object\"}", null, null)),
            emptyMap(),
        )
        val b = DomainManifest.of(
            "economy", "经济", "",
            listOf(ActionSpec.of("apply", "", RiskLevel.WRITE, false, "  {\"type\":\"object\"}\n", null, null)),
            emptyMap(),
        )

        assertEquals(ManifestDigest.of(a), ManifestDigest.of(b), "schema 首尾空白不得影响指纹")
    }

    /** 指纹是 16 位小写十六进制（spec §3.1）。 */
    @Test
    fun `指纹形态为16位小写十六进制`() {
        val digest = ManifestDigest.of(manifestOf("economy", "alpha"))

        assertNotNull(digest, "有清单就应有指纹")
        assertEquals(16, digest!!.length, "指纹必须是 sha256 前 16 位")
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' }, "指纹应为小写十六进制，实际=$digest")
    }

    /** 规范化文本本身是紧凑单行 JSON（无空白差异的载体）。 */
    @Test
    fun `规范化文本为紧凑单行`() {
        val json = ManifestDigest.canonicalJson(manifestOf("economy", "alpha", "beta"))

        assertFalse(json.contains('\n'), "规范化文本不得含换行，实际=$json")
        assertFalse(json.contains("\": "), "字段冒号后不得有空格，实际=$json")
        assertTrue(json.startsWith("{\"actions\":"), "顶层首个键应为 actions（字典序），实际=$json")
    }

    /** **内容变化 → 指纹变化**（spec §6 第 4 条）。 */
    @Test
    fun `manifest变化后指纹改变`() {
        val before = ManifestDigest.of(manifestOf("economy", "alpha"))
        val afterActionAdded = ManifestDigest.of(manifestOf("economy", "alpha", "beta"))
        val afterRiskChanged = ManifestDigest.of(
            DomainManifest.of(
                "economy", "economy", "",
                listOf(ActionSpec.of("alpha", "", RiskLevel.DESTRUCTIVE, false, null, null, null)),
                emptyMap(),
            ),
        )

        assertNotEquals(before, afterActionAdded, "新增动作必须改变指纹")
        assertNotEquals(before, afterRiskChanged, "风险等级变化必须改变指纹")
    }

    // ---------------------------------------------------------------- 注册期缓存

    /**
     * **指纹在注册时算一次，查询不重算**（spec §5「只读快照，不调用 Provider.manifest()」）。
     *
     * 断言 `manifestCalls == 1`：注册期调用一次用于校验与指纹，之后无论查多少次都不再调。
     */
    @Test
    fun `指纹在注册时计算且查询不重算`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        val provider = CountingProvider("economy")
        host.register(provider)
        val callsAfterRegister = provider.manifestCalls.get()
        assertEquals(1, callsAfterRegister, "注册期应恰好取一次清单（校验 + 指纹共用）")

        repeat(20) { host.healthOf("economy") }

        assertEquals(callsAfterRegister, provider.manifestCalls.get(), "查询路径绝不得重新计算 manifest")
        assertNotNull(host.healthOf("economy").manifestDigest, "注册后应能读到指纹")
    }

    /** 指纹与"对同一份清单独立计算一次"的结果一致（证明注册期缓存的是同一个值）。 */
    @Test
    fun `缓存指纹与清单内容一致`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        val provider = CountingProvider("economy")
        host.register(provider)

        assertEquals(
            ManifestDigest.of(provider.manifestContent),
            host.healthOf("economy").manifestDigest,
            "缓存指纹必须与按清单独立算出的值一致",
        )
    }

    /**
     * **查询不触发 Provider 初始化**（spec §6 第 5 条）。
     *
     * 用「构造时抛异常的 Provider」验证：该 Provider 的 `manifest()` / `health()` / `dispatch()`
     * 一旦被调用就抛 `IllegalStateException`。注册时探针会取一次清单（这是显式允许的，
     * 也是"注册"这件事本身），但之后**只查询**绝不能再碰它。
     */
    @Test
    fun `查询不触发Provider初始化`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        val explosive = ExplosiveProvider("economy")
        // 注册本身允许调一次 manifest()（注册期校验），此处先让它"武装"起来。
        host.register(explosive)
        explosive.arm()
        // 注册会请求一次健康刷新；等它跑完（该次调用是刷新线程的合法调用，不算查询触发），
        // 再以"查询前基线"为准断言增量——否则会把刷新线程的调用误记成查询的副作用。
        awaitUntil("economy 被刷新过") { registry.viewOf("economy").refreshedAtMs != 0L }
        val (route, capture) = routeWithCapture(registry)
        val healthBefore = explosive.healthCalls.get()
        val manifestBefore = explosive.postArmManifestCalls.get()

        val result = route.handle(command())

        assertTrue(result.success, "查询应成功（Provider 抛异常不得影响查询）")
        assertNotNull(domainOf(capture.require(), "economy"), "该域仍应出现在回执里")
        assertEquals(healthBefore, explosive.healthCalls.get(), "查询绝不得调用 health()")
        assertEquals(manifestBefore, explosive.postArmManifestCalls.get(), "查询绝不得调用 manifest()")
        assertEquals(0, explosive.dispatchCalls.get(), "查询绝不得派发")
    }

    // ------------------------------------------------------------- 域数量边界

    /** 验收「0 个域 → 返回空数组，不报错」。 */
    @Test
    fun `0个域返回空集合且成功`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        val (route, capture) = routeWithCapture(registry)

        val result = route.handle(command())
        val domains = capture.require()["domains"] as Map<*, *>

        assertTrue(result.success, "0 个域不是错误")
        assertTrue(domains.isEmpty(), "0 个域应返回空集合，实际=$domains")
    }

    /** 验收「N 个域 → 每个都有完整字段」。 */
    @Test
    fun `N个域每个都有完整字段`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        val names = listOf("alpha", "beta", "gamma", "delta", "epsilon")
        names.forEach { host.register(CountingProvider(it)) }
        val (route, capture) = routeWithCapture(registry)

        route.handle(command())
        val domains = capture.require()["domains"] as Map<*, *>

        assertEquals(names.toSet(), domains.keys, "每个已注册域都应出现")
        names.forEach { name ->
            val domain = domains[name] as Map<*, *>
            listOf(
                "available", "degradedReason", "manifestDigest",
                "lastDispatchAt", "dispatchCount", "failureCount",
            ).forEach { key ->
                assertTrue(domain.containsKey(key), "域 $name 缺少 字段 $key")
            }
            assertNotNull(domain["manifestDigest"], "域 $name 应有指纹")
            assertEquals(0L, domain["lastDispatchAt"], "未派发过时 lastDispatchAt 应为 0")
            assertEquals(0L, domain["dispatchCount"], "未派发过时 dispatchCount 应为 0")
            assertEquals(0L, domain["failureCount"], "未派发过时 failureCount 应为 0")
        }
    }

    /**
     * 验收「100 个域时响应 < 100ms」（spec §6 第 6 条）。
     *
     * 阈值取自 spec 原文。本仓的惯例是"不用墙钟阈值断言性能"（见 `FrameSenderTest` 类注释），
     * 但这一条是 spec 明确写死的验收项，且 100ms 对纯内存 O(域数) 遍历有 <b>两个数量级</b> 的余量
     * （实测个位数毫秒），因此不会因 CI 抖动而假失败。真正的结构性保证是：
     * 查询路径不调 Provider、不做 IO（其余用例已逐条断言）。
     */
    @Test
    fun `100个域响应小于100毫秒`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        repeat(DOMAIN_STRESS_COUNT) { host.register(CountingProvider("d%03d".format(it))) }
        val (route, capture) = routeWithCapture(registry)
        // 先查一次，把首次的类加载 / JIT 预热排除在计时之外。
        route.handle(command())

        val startNanos = System.nanoTime()
        val result = route.handle(command())
        val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS

        assertTrue(result.success, "100 个域的查询不应失败")
        val domains = capture.require()["domains"] as Map<*, *>
        assertEquals(DOMAIN_STRESS_COUNT, domains.size, "应返回全部 100 个域")
        assertTrue(elapsedMs < 100, "100 个域的查询应 < 100ms，实际=${elapsedMs}ms")
    }

    // ------------------------------------------------------------- 降级与计数

    /** 验收「某域降级 → available=false + degradedReason」（spec §6 第 3 条）。 */
    @Test
    fun `降级域带available为false与降级原因`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        host.register(FixedProvider("economy", ProviderHealth.unavailable("插件被禁用")))
        awaitUntil("economy 降级") {
            registry.viewOf("economy").health.status == ProviderHealth.Status.UNAVAILABLE
        }
        val (route, capture) = routeWithCapture(registry)

        route.handle(command())
        val domain = domainOf(capture.require(), "economy")

        assertEquals(false, domain["available"], "不可用域应 available=false")
        assertEquals("插件被禁用", domain["degradedReason"], "降级原因应取自 Provider 自报")
    }

    /** 健康域的 `available=true` 且 `degradedReason` 为 null。 */
    @Test
    fun `健康域available为true且降级原因为null`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        host.register(FixedProvider("economy", ProviderHealth.available()))
        awaitUntil("economy 可用") {
            registry.viewOf("economy").health.status == ProviderHealth.Status.AVAILABLE
        }
        val (route, capture) = routeWithCapture(registry)

        route.handle(command())
        val domain = domainOf(capture.require(), "economy")

        assertEquals(true, domain["available"], "健康域应 available=true")
        assertNull(domain["degradedReason"], "健康域的 degradedReason 应为 null（不是空串）")
    }

    /** 未注册域的 `degradedReason` 与 `reason` 同值（面板不必再拼一次）。 */
    @Test
    fun `未注册域带降级原因`() {
        val registry = newRegistry()
        wiredHost(registry)
        val (route, capture) = routeWithCapture(registry)

        route.handle(command(payload = """{"domain":"ghost"}"""))
        val domain = domainOf(capture.require(), "ghost")

        assertEquals(false, domain["available"], "未注册域应 available=false")
        assertEquals(ProviderHealthRegistry.REASON_NOT_REGISTERED, domain["degradedReason"])
        assertNull(domain["manifestDigest"], "未注册域没有指纹")
    }

    /** 计数与最近派发时刻由真实派发驱动，且 `dispatchCount == dispatchTotal`。 */
    @Test
    fun `派发后计数与最近时刻更新`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        val now = 1_730_000_500_000L
        registry.clock = { now }
        host.register(FlakyProvider("economy"))
        val (route, capture) = routeWithCapture(registry, clock = { now })

        host.dispatch(ActionRequest("economy", "ok", "", "req-1", null))
        host.dispatch(ActionRequest("economy", "boom", "", "req-2", null))

        val status = host.healthOf("economy")
        assertEquals(2L, status.dispatchCount, "两次派发都应计入")
        assertEquals(1L, status.failureCount, "其中一次失败")
        assertEquals(status.dispatchTotal, status.dispatchCount, "dispatchCount 与 dispatchTotal 应恒等")
        assertEquals(status.dispatchFailed, status.failureCount, "failureCount 与 dispatchFailed 应恒等")
        assertEquals(now, status.lastDispatchAt, "lastDispatchAt 应为最近一次派发的完成时刻")

        route.handle(command())
        val domain = domainOf(capture.require(), "economy")
        assertEquals(2L, domain["dispatchCount"])
        assertEquals(1L, domain["failureCount"])
        assertEquals(now, domain["lastDispatchAt"])
    }

    /**
     * **计数是运行期指标，重启归零**（spec §3.2）。
     *
     * 用"同一域重新注册"模拟：`reset` 会清空计数与最近时刻，但**指纹按新清单重算**——
     * 两件事必须分开，否则一次热重载会让面板永远看不到调用次数。
     */
    @Test
    fun `重新注册后计数归零`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        host.register(FlakyProvider("economy"))
        host.dispatch(ActionRequest("economy", "boom", "", "req-1", null))
        assertEquals(1L, host.healthOf("economy").dispatchCount, "前置：应已记录一次派发")

        host.register(FlakyProvider("economy"))

        val status = host.healthOf("economy")
        assertEquals(0L, status.dispatchCount, "重新注册后计数应归零（运行期指标）")
        assertEquals(0L, status.failureCount, "失败计数同样归零")
        assertEquals(0L, status.lastDispatchAt, "最近派发时刻归零")
        assertNotNull(status.manifestDigest, "指纹应随新清单重算，而不是一并清空")
    }

    /** 旧契约（legacy 适配）没有可稳定比对的指纹 → `manifestDigest` 为 null。 */
    @Test
    fun `旧契约域没有指纹`() {
        val registry = newRegistry()
        val host = wiredHost(registry)
        host.registerLegacy(LegacyEchoProvider("economy"))

        assertNull(host.healthOf("economy").manifestDigest, "旧契约清单由适配器合成，不应给出指纹")
    }

    // -------------------------------------------------------------------- 测试设施

    /** 造一份动作名给定、其余为空的最小清单。 */
    private fun manifestOf(domain: String, vararg actions: String): DomainManifest = DomainManifest.of(
        domain,
        domain,
        "",
        actions.map { ActionSpec.read(it, "") },
        emptyMap(),
    )

    /** 构造并启动一个注册表（阈值注入小值并登记以便收尾）。 */
    private fun newRegistry(): ProviderHealthRegistry {
        val registry = ProviderHealthRegistry()
        registry.settingsProvider = { settings() }
        registry.clock = { System.currentTimeMillis() }
        registries.add(registry)
        registry.start()
        return registry
    }

    /** 装配一个注入了注册表的宿主。 */
    private fun wiredHost(registry: ProviderHealthRegistry): BusinessHostImpl {
        val host = BusinessHostImpl()
        host.healthRegistry = registry
        hosts.add(host)
        return host
    }

    /** 构造路由与回执捕获器（编码器不依赖 JSON 库，见 `BusinessStatusRouteTest` 的说明）。 */
    private fun routeWithCapture(
        registry: ProviderHealthRegistry,
        clock: () -> Long = { System.currentTimeMillis() },
    ): Pair<BusinessStatusRoute, PayloadCapture> {
        val capture = PayloadCapture()
        val route = BusinessStatusRoute(
            registry = registry,
            encode = { payload ->
                capture.set(payload)
                "ok"
            },
            decode = DECODE_DOMAIN,
            clock = clock,
        )
        return route to capture
    }

    private fun domainOf(payload: Map<String, Any?>, domain: String): Map<*, *> {
        @Suppress("UNCHECKED_CAST")
        val domains = payload["domains"] as? Map<String, Any?> ?: fail("回执缺少 domains")
        return domains[domain] as? Map<*, *> ?: fail("回执缺少域 $domain")
    }

    private fun command(
        action: String = BusinessStatusRoute.ACTION,
        domain: String = BusinessStatusRoute.DOMAIN_CORE,
        payload: String = "",
    ): BridgeCommand = BridgeCommand(
        action = action,
        target = "",
        reason = "",
        requestId = "req-domain-status",
        domain = domain,
        payload = payload,
    )

    private fun awaitUntil(description: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        fail<Unit>("等待超时：$description")
    }

    /** 统计调用次数的最小 Provider。 */
    private class CountingProvider(private val name: String) : BusinessProvider {
        val manifestCalls = AtomicInteger(0)

        /** 稳定内容：每次现算都返回相等但不相同实例的清单，指纹应与之一致。 */
        val manifestContent: DomainManifest = DomainManifest.of(
            name, name, "",
            listOf(ActionSpec.read("query", "查询")),
            linkedMapOf("version" to "1.0.0"),
        )

        override fun domain(): String = name

        override fun manifest(): DomainManifest {
            manifestCalls.incrementAndGet()
            return manifestContent
        }

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth = ProviderHealth.available()
    }

    /** 固定自报值。 */
    private class FixedProvider(
        private val name: String,
        private val reported: ProviderHealth,
    ) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth = reported
    }

    /** 按动作名决定成败（用于驱动失败计数）。 */
    private class FlakyProvider(private val name: String) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.withActions(domain(), "ok", "boom")

        override fun dispatch(request: ActionRequest): ActionResult =
            if (request.action == "boom") ActionResult.fail("BOOM", "故意失败") else ActionResult.ok()

        override fun health(): ProviderHealth = ProviderHealth.available()
    }

    /**
     * 「构造时抛异常的 Provider」（spec §6 第 5 条的验证形态）。
     *
     * 注册期被调一次 `manifest()` 是允许的（那是注册本身）；[arm] 之后任何调用都抛。
     * 异常类型用 `IllegalStateException` 是刻意的：它正是真实插件"未初始化就被访问"的典型形态，
     * 探针必须能兜住。
     */
    private class ExplosiveProvider(private val name: String) : BusinessProvider {
        val healthCalls = AtomicInteger(0)
        val postArmManifestCalls = AtomicInteger(0)
        val dispatchCalls = AtomicInteger(0)

        @Volatile
        private var armed = false

        fun arm() {
            armed = true
        }

        override fun domain(): String = name

        override fun manifest(): DomainManifest {
            if (armed) {
                postArmManifestCalls.incrementAndGet()
                throw NotInitialized()
            }
            return TestManifests.empty(name)
        }

        override fun dispatch(request: ActionRequest): ActionResult {
            dispatchCalls.incrementAndGet()
            throw NotInitialized()
        }

        override fun health(): ProviderHealth {
            healthCalls.incrementAndGet()
            throw NotInitialized()
        }

        /** 模拟"Provider 尚未初始化"（`IllegalStateException` 的子类，语义更明确）。 */
        private class NotInitialized : IllegalStateException("Provider 尚未初始化，不应被调用")
    }

    /** 旧契约 Provider（裸 Map manifest + 旧 dispatch）。 */
    private class LegacyEchoProvider(private val name: String) : LegacyBusinessProvider {
        override fun domain(): String = name

        override fun manifest(): MutableMap<String, Any> = mutableMapOf("displayName" to name)

        override fun dispatch(action: String, payloadJson: String): BridgeCommandResult = BridgeCommandResult.ok()
    }

    private companion object {
        const val POLL_MS = 10L
        const val NANOS_PER_MS = 1_000_000L

        /** spec §6 第 6 条的域数量。 */
        const val DOMAIN_STRESS_COUNT = 100


        fun settings(): HealthSettings = HealthSettings(
            refreshIntervalMs = 60_000L,
            healthTimeoutMs = 500L,
            quarantineMs = 60_000L,
            retainAfterUnregisterMs = 300_000L,
            maxEntries = 512,
            reasonMaxChars = 512,
            detailMaxChars = 2_048,
        )
    }
}
