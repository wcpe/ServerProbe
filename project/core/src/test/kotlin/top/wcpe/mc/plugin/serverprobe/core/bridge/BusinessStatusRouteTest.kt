package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.core.config.HealthSettings
import java.util.concurrent.atomic.AtomicInteger
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest

/**
 * [BusinessStatusRoute] 单元测试（Provider 健康规格 §3.4 回执 + §5 的查询侧验收）。
 *
 * ## 为什么注入编码器与 payload 解析器
 * core 单测 classpath 无 nightconfig，`Json.encode` / `Json.parse` 会 `NoClassDefFoundError`
 * （与 [SubscribeRouteTest] 同一处理方式）。故：
 * - `encode` 注入"捕获 Map 并回固定串"的实现 → 断言直接打在**结构化字段**上；
 * - `decode` 注入等价语义的纯文本实现（取 `domain` 字段，缺失返回 null）。
 *
 * 于是"生产解码器未单测"与 `SubscribeRoute.parseTopics` 的现状同源：那一层只做 JSON 取值，
 * 语义由注入实现的用例覆盖。
 *
 * ## 三条"查询不得做的事"各有用例
 * 不派发（dispatch 计数恒 0）、不新建线程（线程名集合无新增）、不做 IO
 * （配置读取次数为 0 + 编码器调用次数恰等于查询次数）。
 */
class BusinessStatusRouteTest {

    private val registries = ArrayList<ProviderHealthRegistry>()

    @AfterEach
    fun tearDown() {
        registries.forEach { runCatching { it.stop() } }
        registries.clear()
    }

    // ------------------------------------------------------- 序位 / claims / 分配表

    /** 序位与执行池严格按 `bridge-external-api` §3.5 的分配表：110 / COMMAND。 */
    @Test
    fun `序位与执行池符合分配表`() {
        val (route, _) = routeWithCapture(newRegistry())

        assertEquals(110, route.order, "business_status 路由序位必须是 110")
        assertEquals(ExecutorKind.COMMAND, route.executor, "查询在命令池执行")
        assertEquals("business_status", BusinessStatusRoute.ACTION)
        assertEquals("core", BusinessStatusRoute.DOMAIN_CORE)
        assertNotEquals(BridgeStatusRoute.ORDER, route.order, "不得与 bridge_status 同序位")
    }

    /** `claims` 只认 `domain=core` + `action=business_status`。 */
    @Test
    fun `claims只认领core的业务状态查询`() {
        val (route, _) = routeWithCapture(newRegistry())

        assertTrue(route.claims(command()), "core.business_status 应命中")
        assertFalse(route.claims(command(domain = "economy")), "业务域不得命中（那是业务派发）")
        assertFalse(route.claims(command(domain = "")), "空域不得命中（那是治理兜底）")
        assertFalse(route.claims(command(action = "bridge_status")), "其它 core 动作不得命中")
    }

    /**
     * 验收「不碰 `bridge_status`」：两条路由的认领范围与回执字段**互不重叠**。
     *
     * `bridge_status` 的 schema 唯一归属线路治理规格，本功能不得往里加任何字段。
     */
    @Test
    fun `不与bridge_status争抢动作或字段`() {
        val (route, capture) = routeWithCapture(newRegistry())

        assertFalse(route.claims(command(action = "bridge_status")), "business_status 不得认领 bridge_status")

        route.handle(command())
        val payload = capture.require()

        assertFalse(payload.containsKey("pools"), "不得输出 bridge_status 的 pools 字段")
        assertFalse(payload.containsKey("sender"), "不得输出 bridge_status 的 sender 字段")
        assertFalse(payload.containsKey("inbound"), "不得输出 bridge_status 的 inbound 字段")
        assertTrue(payload.containsKey("domains"), "应输出自己的 domains")
        assertTrue(payload.containsKey("refresh"), "应输出自己的 refresh")
    }

    // -------------------------------------------------------------- 回执字段形状

    /** 回执顶层结构与 §3.4 一致：`domains` / `refresh` / `timestamp`。 */
    @Test
    fun `回执顶层结构与规格一致`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        val (route, capture) = routeWithCapture(registry, clock = { 1_730_000_001_000L })

        route.handle(command())
        val payload = capture.require()

        assertEquals(setOf("domains", "refresh", "timestamp"), payload.keys, "顶层字段集必须与规格一致")
        assertEquals(1_730_000_001_000L, payload["timestamp"], "timestamp 取自注入时钟")
    }

    /** 逐域字段集与 §3.4 + §4 一致。 */
    @Test
    fun `逐域字段集与规格一致`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        awaitRefreshed(registry, "economy")
        val (route, capture) = routeWithCapture(registry)

        route.handle(command())
        val domain = domainOf(capture.require(), "economy")

        assertEquals(
            setOf(
                "registered", "health", "reason", "detailJson", "sinceMs", "refreshedAtMs", "quarantined", "dispatch",
                // 的新增字段
                "available", "degradedReason", "manifestDigest", "lastDispatchAt", "dispatchCount", "failureCount",
            ),
            domain.keys,
            "逐域字段集必须与规格逐字一致（lastFailure 未发生时应缺席）",
        )
        assertEquals("AVAILABLE", domain["health"], "health 用等级名")
        assertEquals(true, domain["registered"])
        assertEquals(false, domain["quarantined"])
        val dispatch = domain["dispatch"] as Map<*, *>
        assertEquals(setOf("total", "failed", "lastCostMs"), dispatch.keys, "dispatch 子结构字段集应一致")
    }

    /** `refresh` 子结构字段集与 §3.4 一致。 */
    @Test
    fun `refresh子结构字段集与规格一致`() {
        val (route, capture) = routeWithCapture(newRegistry())

        route.handle(command())
        val refresh = capture.require()["refresh"] as Map<*, *>

        assertEquals(
            setOf("inFlight", "stalled", "quarantined", "lastSweepAtMs", "skippedThisSweep"),
            refresh.keys,
            "refresh 字段集必须与规格逐字一致",
        )
        assertEquals(false, refresh["inFlight"])
        assertEquals(false, refresh["stalled"])
        assertEquals(emptyList<String>(), refresh["quarantined"])
        assertEquals(emptyList<String>(), refresh["skippedThisSweep"])
    }

    /** `lastFailure` 有失败时出现，成功一次后整个字段缺席（而不是空对象）。 */
    @Test
    fun `lastFailure有则出现无则缺席`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        val (route, capture) = routeWithCapture(registry)
        registry.recordDispatch("economy", BridgeCommandResult.fail("插件未就绪"), 5L)

        route.handle(command())
        val failed = domainOf(capture.require(), "economy")
        val lastFailure = failed["lastFailure"] as Map<*, *>
        assertEquals("插件未就绪", lastFailure["message"])
        assertEquals(1, lastFailure["consecutiveFailures"])
        assertEquals(5L, (failed["dispatch"] as Map<*, *>)["lastCostMs"])

        registry.recordDispatch("economy", BridgeCommandResult.ok(), 2L)
        route.handle(command())
        assertFalse(domainOf(capture.require(), "economy").containsKey("lastFailure"), "成功一次后 lastFailure 应整个缺席")
    }

    // ------------------------------------------------------------------ 未注册域

    /** 验收「未注册域」：查询未注册域 → `registered=false` + 原因，整体 `success=true`，不报错。 */
    @Test
    fun `未注册域查询成功且标注未注册`() {
        val (route, capture) = routeWithCapture(newRegistry())

        val result = route.handle(command(payload = """{"domain":"inventory"}"""))

        assertTrue(result.success, "查询未注册域不应失败")
        val domain = domainOf(capture.require(), "inventory")
        assertEquals(false, domain["registered"], "显式查询未注册域应带上 registered=false")
        assertTrue(domain["reason"].toString().contains("未注册"), "应给出可读原因，实际=${domain["reason"]}")
    }

    /** 全量查询只列「已注册域 + 保留窗口内的已注销域」，不含从未注册的域。 */
    @Test
    fun `全量查询不含从未注册的域`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        val (route, capture) = routeWithCapture(registry)

        route.handle(command())
        val domains = capture.require()["domains"] as Map<*, *>

        assertTrue(domains.containsKey("economy"), "已注册域必须出现")
        assertFalse(domains.containsKey("inventory"), "从未注册的域不得出现在全量回执里")
    }

    /** payload 指定 `domain` 时只返回该域（可选过滤，字段形状不变）。 */
    @Test
    fun `payload指定域时只返回该域`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        registry.attach("activity", FixedProvider("activity", ProviderHealth.available()))
        val (route, capture) = routeWithCapture(registry)

        route.handle(command(payload = """{"domain":"activity"}"""))
        val domains = capture.require()["domains"] as Map<*, *>

        assertEquals(setOf("activity"), domains.keys, "应按 payload 过滤到单域")
    }

    // ------------------------------------------------ 查询不派发 / 不开线程 / 不做 IO

    /**
     * 验收「查询不派发」：桩 Provider 的 `dispatch` 自增计数；注册后等一次刷新完成，
     * 再连续查询 10 次 → `dispatch` 计数**始终为 0**。
     */
    @Test
    fun `连续查询不触发任何业务派发`() {
        val registry = newRegistry()
        val provider = CountingProvider("economy")
        registry.attach("economy", provider)
        registry.start()
        awaitRefreshed(registry, "economy")
        val (route, _) = routeWithCapture(registry)

        repeat(10) {
            val result = route.handle(command())
            assertTrue(result.success, "第 ${it + 1} 次查询应成功")
        }

        assertEquals(0, provider.dispatches.get(), "查询路径绝不得派发业务命令")
        assertEquals(0, provider.manifestCalls.get(), "查询路径不得读取 manifest")
        assertEquals(1, provider.healthCalls.get(), "查询路径绝不得调用 health()（只由刷新线程调用）")
    }

    /** 验收「查询不新建线程」：查询前后 `Thread.getAllStackTraces()` 的名字集合**无新增**。 */
    @Test
    fun `连续查询不新建任何线程`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        registry.start()
        awaitRefreshed(registry, "economy")
        val (route, _) = routeWithCapture(registry)
        // 先查一次，把"首查询可能触发的一次性初始化"排除在基线之外。
        route.handle(command())
        val before = threadNames()

        repeat(10) { route.handle(command()) }

        val added = threadNames() - before
        assertTrue(added.isEmpty(), "查询不得新建线程，新增=$added")
    }

    /**
     * 验收「不做 IO」：查询 10 次期间 `bridge.health.*` 的读取次数**为 0**，
     * 且编码器恰被调用 10 次——说明查询路径上没有任何别的出口。
     *
     * 说明：本路由的构造签名里**没有任何 IO 接缝**（无 FileSystem、无 BridgeConfigSource），
     * "不做 IO" 因此是结构性的；这里以"阈值读取次数为 0"作为额外的可断言证据。
     */
    @Test
    fun `连续查询不读取配置也不做IO`() {
        val settingsReads = AtomicInteger(0)
        val registry = ProviderHealthRegistry()
        registry.settingsProvider = {
            settingsReads.incrementAndGet()
            testSettings()
        }
        registry.clock = { System.currentTimeMillis() }
        registries.add(registry)
        registry.attach("economy", FixedProvider("economy", ProviderHealth.available()))
        registry.start()
        awaitRefreshed(registry, "economy")
        val encodeCalls = AtomicInteger(0)
        val route = BusinessStatusRoute(
            registry = registry,
            encode = { payload -> encodeCalls.incrementAndGet(); payload.toString() },
            decode = DECODE_DOMAIN,
            clock = { System.currentTimeMillis() },
        )
        val readsBefore = settingsReads.get()

        repeat(10) { route.handle(command()) }

        assertEquals(10, encodeCalls.get(), "编码器只应被每次查询调用一次（没有额外的 IO 出口）")
        assertEquals(readsBefore, settingsReads.get(), "查询期间不得读取配置（那是刷新线程的事）")
    }

    // ------------------------------------------------------------------ 截断与失败呈现

    /** 验收「回执截断」：`degraded(reason)` 传 2000 字符 → 回执内 `reason` ≤ 512。 */
    @Test
    fun `超长原因在回执内被截断`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.degraded("幂" + "x".repeat(2_000))))
        val (route, capture) = routeWithCapture(registry)
        awaitRefreshed(registry, "economy")

        route.handle(command())
        val reason = domainOf(capture.require(), "economy")["reason"].toString()

        assertTrue(reason.length <= 512, "reason 应 ≤ 512 字符，实际=${reason.length}")
        assertTrue(reason.endsWith("…"), "截断应带省略号，实际尾部=${reason.takeLast(5)}")
    }

    /** 验收「回执截断」：`detailJson` 传 10000 字符 → 回执内 ≤ 2048。 */
    @Test
    fun `超长详情在回执内被截断`() {
        val registry = newRegistry()
        val detail = "y".repeat(10_000)
        registry.attach(
            "economy",
            FixedProvider("economy", ProviderHealth.of(ProviderHealth.Status.DEGRADED, "损坏", detail, 0L)),
        )
        val (route, capture) = routeWithCapture(registry)
        awaitRefreshed(registry, "economy")

        route.handle(command())
        val rendered = domainOf(capture.require(), "economy")["detailJson"].toString()

        assertTrue(rendered.length <= 2_048, "detailJson 应 ≤ 2048 字符，实际=${rendered.length}")
    }

    /** 验收「脱敏」：回执内不出现凭据明文。 */
    @Test
    fun `回执内不出现凭据明文`() {
        val registry = newRegistry()
        registry.attach("economy", FixedProvider("economy", ProviderHealth.unavailable("未安装 token=secret-value")))
        val (route, capture) = routeWithCapture(registry)
        awaitRefreshed(registry, "economy")

        route.handle(command())
        val reason = domainOf(capture.require(), "economy")["reason"].toString()

        assertFalse(reason.contains("secret-value"), "回执绝不得出现凭据明文，实际=$reason")
        assertTrue(reason.contains("token=***"), "凭据值应替换为 ***，实际=$reason")
    }

    /** 验收「抛异常隔离（回执侧）」：单域降级时**整段回执仍 `success=true`**，其它域取值不变。 */
    @Test
    fun `单域降级不影响整段回执成功`() {
        val registry = newRegistry()
        registry.attach("a-good", FixedProvider("a-good", ProviderHealth.available()))
        registry.attach("b-bad", ThrowingProvider("b-bad"))
        val (route, capture) = routeWithCapture(registry)
        awaitUntil("b-bad 降级") { registry.viewOf("b-bad").health.status == ProviderHealth.Status.DEGRADED }

        val result = route.handle(command())
        route.handle(command())
        val domains = capture.require()["domains"] as Map<*, *>

        assertTrue(result.success, "单域降级不得让整段查询失败")
        assertEquals("AVAILABLE", (domains["a-good"] as Map<*, *>)["health"], "其它域取值不变")
        assertEquals("DEGRADED", (domains["b-bad"] as Map<*, *>)["health"], "异常域应呈 DEGRADED")
        assertTrue(
            (domains["b-bad"] as Map<*, *>)["reason"].toString().contains("health() 抛出异常"),
            "原因应说明是 health() 抛异常",
        )
    }

    /** 五个等级都按名称呈现，`reason` 原样转发（含中文）。 */
    @Test
    fun `五个等级都按名称呈现`() {
        val registry = newRegistry()
        val cases = mapOf(
            "lv-a" to ProviderHealth.available(),
            "lv-u" to ProviderHealth.unavailable("插件未安装"),
            "lv-i" to ProviderHealth.initializing("等待外部插件就绪"),
            "lv-d" to ProviderHealth.degraded("内部状态损坏"),
            "lv-n" to ProviderHealth.unknown(),
        )
        cases.forEach { (domain, health) -> registry.attach(domain, FixedProvider(domain, health)) }
        cases.keys.forEach { awaitRefreshed(registry, it) }
        val (route, capture) = routeWithCapture(registry)

        route.handle(command())
        val domains = capture.require()["domains"] as Map<*, *>

        assertEquals("AVAILABLE", (domains["lv-a"] as Map<*, *>)["health"])
        assertEquals("UNAVAILABLE", (domains["lv-u"] as Map<*, *>)["health"])
        assertEquals("INITIALIZING", (domains["lv-i"] as Map<*, *>)["health"])
        assertEquals("DEGRADED", (domains["lv-d"] as Map<*, *>)["health"])
        assertEquals("UNKNOWN", (domains["lv-n"] as Map<*, *>)["health"])
        assertEquals("插件未安装", (domains["lv-u"] as Map<*, *>)["reason"], "reason 原样转发（含中文）")
    }

    // -------------------------------------------------------------------- 测试设施

    /** 捕获回执的结构化 payload。 */
    /** 构造路由与配套的 payload 捕获器。 */
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

    /** 取回执里的单域子结构。 */
    private fun domainOf(payload: Map<String, Any?>, domain: String): Map<*, *> {
        @Suppress("UNCHECKED_CAST")
        val domains = payload["domains"] as? Map<String, Any?> ?: fail("回执缺少 domains")
        return domains[domain] as? Map<*, *> ?: fail("回执缺少域 $domain")
    }

    /** 构造一条 `core.business_status` 命令。 */
    private fun command(
        action: String = BusinessStatusRoute.ACTION,
        domain: String = BusinessStatusRoute.DOMAIN_CORE,
        payload: String = "",
    ): BridgeCommand = BridgeCommand(
        action = action,
        target = "",
        reason = "",
        requestId = "req-business-status",
        domain = domain,
        payload = payload,
    )

    /** 构造并启动一个注册表，阈值注入极端小值并登记以便收尾。 */
    private fun newRegistry(): ProviderHealthRegistry {
        val registry = ProviderHealthRegistry()
        registry.settingsProvider = { testSettings() }
        registry.clock = { System.currentTimeMillis() }
        registries.add(registry)
        registry.start()
        return registry
    }

    /** 本进程内线程名集合。 */
    private fun threadNames(): Set<String> = Thread.getAllStackTraces().keys.map { it.name }.toSet()

    /** 等到该域被刷新过。 */
    private fun awaitRefreshed(registry: ProviderHealthRegistry, domain: String) {
        awaitUntil("域 $domain 被刷新") { registry.viewOf(domain).refreshedAtMs != 0L }
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

    /** 统计 dispatch / manifest / health 调用次数。 */
    private class CountingProvider(private val name: String) : BusinessProvider {
        val dispatches = AtomicInteger(0)
        val manifestCalls = AtomicInteger(0)
        val healthCalls = AtomicInteger(0)

        override fun domain(): String = name

        override fun manifest(): DomainManifest {
            manifestCalls.incrementAndGet()
            return TestManifests.empty(domain())
        }

        override fun dispatch(request: ActionRequest): ActionResult {
            dispatches.incrementAndGet()
            return ActionResult.ok()
        }

        override fun health(): ProviderHealth {
            healthCalls.incrementAndGet()
            return ProviderHealth.available()
        }
    }

    /** 固定返回某个自报值。 */
    private class FixedProvider(
        private val name: String,
        private val reported: ProviderHealth,
    ) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth = reported
    }

    /** `health()` 抛异常（用 `UnsupportedOperationException` 以示"实现方不支持"，不涉及契约自检）。 */
    private class ThrowingProvider(private val name: String) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = TestManifests.empty(domain())

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.ok()

        override fun health(): ProviderHealth = throw UnsupportedOperationException("boom")
    }

    private companion object {
        /** 轮询间隔（毫秒）。 */
        const val POLL_MS = 10L

        /**
         * 测试用 payload 解析器：取 `domain` 字段，缺失/空串返回 null（= 全量）。
         *
         * 与生产 `parseDomain` 的**输出约定一致**，只是不依赖 JSON 库。
         */

        /** 测试阈值：超时注入 50ms，其余按规格默认（截断用例依赖 512/2048）。 */
        fun testSettings(): HealthSettings = HealthSettings(
            refreshIntervalMs = 60_000L,
            healthTimeoutMs = 50L,
            quarantineMs = 60_000L,
            retainAfterUnregisterMs = 300_000L,
            maxEntries = 256,
            reasonMaxChars = 512,
            detailMaxChars = 2_048,
        )
    }
}
