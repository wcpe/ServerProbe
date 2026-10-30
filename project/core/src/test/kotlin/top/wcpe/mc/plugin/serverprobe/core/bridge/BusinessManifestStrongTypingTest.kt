package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.Registration
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProviderAdapter
import top.wcpe.mc.plugin.serverprobe.api.bridge.Principal
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.api.bridge.RiskLevel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * / 的强类型 manifest 与分发契约单测。
 *
 * 逐条对应 `docs/specs/business-manifest-strong-typing/spec.md` §8 的验收项：
 * 新 Provider 注册与清单暴露、旧 Provider 适配为 `specDeclared=false`、
 * apiVersion 不匹配降级而非拒绝、动作名重复拒绝注册、`requestId` 恒非空、`Principal` 透传。
 */
class BusinessManifestStrongTypingTest {

    private val hosts = ArrayList<BusinessHostImpl>()

    @AfterEach
    fun tearDown() {
        hosts.forEach { runCatching { it.shutdown() } }
        hosts.clear()
    }

    /** 建一个静音宿主（校验用例会走 ERROR/WARN 分支，默认日志出口会往测试输出里刷中文）。 */
    private fun newHost(): BusinessHostImpl = BusinessHostImpl().also {
        it.warn = { }
        it.error = { }
        hosts.add(it)
    }

    /**
     * 强类型 Provider 桩：清单与派发行为都可配置。
     *
     * 参数顺序把 [manifestFactory] 放最后，是为了让最常见的 `TypedProvider("x") { 清单 }`
     * 用尾随 lambda 直接写——若把它放中间，"只有清单"的调用点会被迫写一个用不上的行为 lambda。
     */
    private class TypedProvider(
        private val name: String,
        private val behavior: (ActionRequest) -> ActionResult = { ActionResult.ok() },
        private val manifestFactory: () -> DomainManifest = { TestManifests.empty(name) },
    ) : BusinessProvider {
        override fun domain(): String = name

        override fun manifest(): DomainManifest = manifestFactory()

        override fun dispatch(request: ActionRequest): ActionResult = behavior(request)
    }

    // ------------------------------------------------- 新 Provider：注册与清单暴露

    /**
     * 验收 1：新 Provider 注册成功，manifest 正确暴露全部强类型字段。
     *
     * 断言打在**聚合信封**上而不是"Provider 自己返回的对象"上：下游消费的是信封，
     * 自测 Provider 的返回值只能证明它自己没写错。
     */
    @Test
    fun `新 Provider 注册成功且清单完整暴露`() {
        val host = newHost()
        val provider = TypedProvider("activity") {
            DomainManifest.of(
                "activity",
                "活动系统",
                "活动进度与结算",
                listOf(
                    ActionSpec.of(
                        "settle",
                        "结算奖励",
                        RiskLevel.DESTRUCTIVE,
                        true,
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}",
                        linkedSetOf("ACTIVITY_NOT_FOUND", "ALREADY_SETTLED"),
                    ),
                    ActionSpec.read("progress", "查询进度"),
                ),
                mapOf("version" to "1.2.0"),
            )
        }

        val handle = host.register(provider)

        assertTrue(handle.getActive(), "新 Provider 应注册成功：${handle.getInactiveDetail()}")
        val envelope = host.manifest()
        assertEquals(DomainManifest.CURRENT_API_VERSION, envelope["apiVersion"], "信封须带 apiVersion")
        val domain = domainOf(envelope, "activity")
        assertEquals("活动系统", domain["displayName"], "displayName 应原样透出")
        assertEquals("活动进度与结算", domain["description"])
        assertEquals(true, domain["specDeclared"], "新 Provider 应标 specDeclared=true")
        assertEquals(mapOf("version" to "1.2.0"), domain["attributes"], "attributes 是透传逃生舱")

        val settle = actionOf(domain, "settle")
        assertEquals("DESTRUCTIVE", settle["risk"], "risk 应作为枚举名透出")
        assertEquals(true, settle["idempotent"])
        assertEquals("{\"type\":\"object\"}", settle["inputSchema"])
        assertEquals(listOf("ACTIVITY_NOT_FOUND", "ALREADY_SETTLED"), settle["errorCodes"])

        val progress = actionOf(domain, "progress")
        assertEquals("READ", progress["risk"])
        assertNull(progress["inputSchema"], "未声明 schema 应写 null，而不是空串")
        assertEquals(emptyList<String>(), progress["errorCodes"])
    }

    /** 未声明 schema 的动作不应把空串当成"声明了一个空 schema"发出去。 */
    @Test
    fun `未声明 schema 时聚合结果为 null`() {
        val host = newHost()
        host.register(TypedProvider("demo") { TestManifests.withActions("demo", "ping") })

        val ping = actionOf(domainOf(host.manifest(), "demo"), "ping")

        assertNull(ping["inputSchema"])
        assertNull(ping["outputSchema"])
    }

    // ------------------------------------------------------ 旧 Provider：适配与标记

    /** 旧契约 Provider 桩：裸 Map manifest + 双字符串 dispatch。 */
    private class LegacyProvider(
        private val name: String,
        private val actions: List<Any>,
        private val behavior: (String, String) -> BridgeCommandResult = { _, p -> BridgeCommandResult.ok(p) },
    ) : LegacyBusinessProvider {
        override fun domain(): String = name

        override fun manifest(): Map<String, Any> = mapOf(LegacyBusinessProvider.ACTIONS_KEY to actions)

        override fun dispatch(action: String, payloadJson: String): BridgeCommandResult =
            behavior(action, payloadJson)
    }

    /**
     * 验收 2：旧 Provider（裸 Map）注册成功，聚合清单里标 `specDeclared=false`，
     * 动作风险一律按 `WRITE` 保守处理。
     */
    @Test
    fun `旧 Provider 注册成功并被标记为未声明规格`() {
        val host = newHost()
        // 旧 manifest 的 readOnly=true 是作者自填的、无契约约束，不得被采信为 READ。
        val legacy = LegacyProvider(
            "economy",
            listOf(
                mapOf("name" to "balance", "readOnly" to true),
                "grant",
            ),
        )

        val handle = host.registerLegacy(legacy)

        assertTrue(handle.getActive(), "旧 Provider 应注册成功：${handle.getInactiveDetail()}")
        val domain = domainOf(host.manifest(), "economy")
        assertEquals(false, domain["specDeclared"], "旧 Provider 必须标 specDeclared=false")

        val names = (domain["actions"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("balance", "grant"), names, "两种旧元素形态都要提出动作名")

        (domain["actions"] as List<*>).forEach { raw ->
            val action = raw as Map<*, *>
            assertEquals("WRITE", action["risk"], "旧契约动作风险一律 WRITE：${action["name"]}")
            assertEquals(false, action["idempotent"], "旧契约无幂等声明")
            assertNull(action["inputSchema"], "旧契约无 schema")
            assertEquals(emptyList<String>(), action["errorCodes"])
        }
    }

    /** 旧 Provider 的派发仍正常工作，且结果被折成新类型（无错误码）。 */
    @Test
    fun `旧 Provider 的派发结果被折为 ActionResult`() {
        val host = newHost()
        host.registerLegacy(
            LegacyProvider("economy", listOf("balance")) { action, payload ->
                if (action == "balance") BridgeCommandResult.ok("{\"amount\":7}") else BridgeCommandResult.fail("未知动作")
            },
        )

        val ok = host.dispatch(ActionRequest("economy", "balance", "", "req-1", Principal.unknown()))
        assertTrue(ok.success, "旧 Provider 成功应映射为 success")
        assertEquals("{\"amount\":7}", ok.outputJson)
        assertEquals("", ok.errorCode, "旧契约没有错误码，不得凭空编一个")

        val fail = host.dispatch(ActionRequest("economy", "missing", "", "req-2", Principal.unknown()))
        assertFalse(fail.success)
        assertTrue(fail.errorMessage.contains("未知动作"), "失败原因应透传：${fail.errorMessage}")
    }

    /** 旧 Provider 的 `health()` 也要转派（否则健康面板对这个域永远显示 UNKNOWN）。 */
    @Test
    fun `旧 Provider 的健康状态被转派`() {
        val host = newHost()
        host.registerLegacy(object : LegacyBusinessProvider {
            override fun domain(): String = "economy"

            override fun manifest(): Map<String, Any> = emptyMap()

            override fun dispatch(action: String, payloadJson: String): BridgeCommandResult =
                BridgeCommandResult.ok()

            override fun health(): ProviderHealth = ProviderHealth.unavailable("依赖插件未装载")
        })

        // 健康查询走缓存；注册时会 attach，但刷新线程未起，故这里直接验证适配器契约本身。
        val adapted = LegacyBusinessProviderAdapter.adapt(object : LegacyBusinessProvider {
            override fun domain(): String = "economy"

            override fun manifest(): Map<String, Any> = emptyMap()

            override fun dispatch(action: String, payloadJson: String): BridgeCommandResult =
                BridgeCommandResult.ok()

            override fun health(): ProviderHealth = ProviderHealth.unavailable("依赖插件未装载")
        })
        assertEquals("依赖插件未装载", adapted.health().reason, "health() 必须转派到旧实现")
        assertEquals(setOf("economy"), host.domains())
    }

    // ------------------------------------------------------------ 注册期校验规则

    /** 验收 3：domain 格式非法被拒绝（大写、下划线、超长、含空格、非 ASCII、空）。 */
    @Test
    fun `domain 格式非法被拒绝注册`() {
        val host = newHost()
        // 注意 "-" 是合法域名：spec §4.1 的正则 `^[a-z0-9-]{1,32}$` 里连字符就是字面量，
        // 只有"长度 0"与"含集合外字符"才是非法。
        val illegal = listOf("Economy", "eco_nomy", "a".repeat(33), "econ omy", "经济", "")

        illegal.forEach { domain ->
            val handle = host.register(TypedProvider(domain))
            assertFalse(handle.getActive(), "非法域名应被拒绝：\"$domain\"")
            assertEquals(
                Registration.InactiveReason.INVALID,
                handle.getInactiveReason(),
                "拒绝原因应为 INVALID：\"$domain\"",
            )
            assertTrue(
                handle.getInactiveDetail().contains("格式"),
                "失效原因应点明格式问题：${handle.getInactiveDetail()}",
            )
        }
        assertTrue(host.domains().isEmpty(), "被拒的域不得出现在注册表里")
    }

    /** 合法域名的边界：1 字符、32 字符、含连字符与数字。 */
    @Test
    fun `合法域名边界值可注册`() {
        val host = newHost()
        listOf("a", "a".repeat(32), "eco-nomy-2").forEach { domain ->
            assertTrue(host.register(TypedProvider(domain)).getActive(), "合法域名应可注册：$domain")
        }
        assertEquals(3, host.domains().size)
    }

    /** 验收 4：动作名重复时拒绝注册，并记 ERROR。 */
    @Test
    fun `动作名重复被拒绝注册并记错误`() {
        val host = newHost()
        val errors = ArrayList<String>()
        host.error = { errors.add(it) }

        val handle = host.register(
            TypedProvider("activity") {
                TestManifests.withActions("activity", "start", "stop", "start")
            },
        )

        assertFalse(handle.getActive(), "动作名重复应拒绝注册")
        assertEquals(Registration.InactiveReason.INVALID, handle.getInactiveReason())
        assertTrue(handle.getInactiveDetail().contains("start"), "失效原因应点名重复的动作：${handle.getInactiveDetail()}")
        assertTrue(errors.any { it.contains("动作名重复") }, "必须记 ERROR：$errors")
        assertTrue(host.domains().isEmpty(), "被拒的域不得写入注册表")
    }

    /**
     * 空动作名不算"重复"：spec §7 只把重复列为拒绝条件，替 Provider 判定空名非法
     * 会让一个只是漏填名字的域整体不可用。
     */
    @Test
    fun `空动作名不触发重复拒绝`() {
        val host = newHost()
        val handle = host.register(
            TypedProvider("demo") {
                DomainManifest.of(
                    "demo",
                    "demo",
                    "",
                    listOf(ActionSpec.read("", ""), ActionSpec.read("", "")),
                    emptyMap(),
                )
            },
        )

        assertTrue(handle.getActive(), "空名重复不应被当成动作名重复：${handle.getInactiveDetail()}")
    }

    /**
     * 验收 3（版本）：apiVersion 不匹配时**降级而非拒绝**（spec §5.2 / §7）。
     *
     * 降级形态：仍可注册、`specDeclared=false`、动作名保留但风险按 WRITE 保守处理。
     */
    @Test
    fun `apiVersion 不匹配时降级而非拒绝`() {
        val host = newHost()
        val warnings = ArrayList<String>()
        host.warn = { warnings.add(it) }

        val future = DomainManifest(
            DomainManifest.CURRENT_API_VERSION + 1,
            "activity",
            "活动系统",
            "",
            listOf(ActionSpec.read("progress", "查询进度")),
            mapOf("version" to "9"),
        )

        val handle = host.register(TypedProvider("activity") { future })

        assertTrue(handle.getActive(), "版本不支持不得拒绝注册：${handle.getInactiveDetail()}")
        assertTrue(warnings.any { it.contains("apiVersion") }, "必须记 WARN：$warnings")

        val domain = domainOf(host.manifest(), "activity")
        assertEquals(false, domain["specDeclared"], "降级后应标 specDeclared=false")
        val progress = actionOf(domain, "progress")
        assertEquals("WRITE", progress["risk"], "降级后风险按 WRITE 处理，不得沿用 READ")
        assertEquals(emptyMap<String, String>(), domain["attributes"], "降级后不采信原清单的 attributes")
    }

    /** 未声明风险的动作按 WRITE 生效并记 WARN（spec §7 的 risk 为 null 一行）。 */
    @Test
    fun `未声明风险的动作按 WRITE 处理并告警`() {
        val host = newHost()
        val warnings = ArrayList<String>()
        host.warn = { warnings.add(it) }

        host.register(
            TypedProvider("demo") {
                DomainManifest.of(
                    "demo",
                    "demo",
                    "",
                    listOf(ActionSpec.of("op", "", null, false, null, null, null)),
                    emptyMap(),
                )
            },
        )

        assertEquals("WRITE", actionOf(domainOf(host.manifest(), "demo"), "op")["risk"], "未声明风险默认 WRITE")
        assertTrue(warnings.any { it.contains("风险") }, "必须记 WARN：$warnings")
    }

    /** manifest() 抛异常的域：注册不失败（动作往往仍可用），聚合里呈现 error 且隔离于其它域。 */
    @Test
    fun `manifest 抛异常的域仍可注册且不影响其它域`() {
        val host = newHost()
        val broken = host.register(TypedProvider("broken") { error("boom") })
        host.register(TypedProvider("healthy"))

        assertTrue(broken.getActive(), "manifest 抛异常不应导致注册失败")
        val domains = domainsOf(host.manifest())
        assertTrue(domains["broken"]?.containsKey("error") == true, "坏域应在聚合里呈现 error")
        assertFalse(domains["healthy"]?.containsKey("error") == true, "健康域不受影响")
    }

    // ------------------------------------------------------------- 分发契约

    /** 验收 5：requestId 在下发时恒非空（调用方没给就由探针补）。 */
    @Test
    fun `requestId 为空时由探针补齐`() {
        val host = newHost()
        val seen = AtomicReference<String>()
        host.register(
            TypedProvider("demo", behavior = { request ->
                seen.set(request.requestId)
                ActionResult.ok()
            }),
        )

        host.dispatch(ActionRequest("demo", "op", "", "", Principal.unknown()))

        val requestId = seen.get()
        assertNotNull(requestId, "Provider 必须被调用")
        assertTrue(requestId!!.isNotEmpty(), "Provider 侧 requestId 必须非空")
    }

    /** 调用方给了 requestId 时必须原样透传（幂等去重依赖它跨重发保持一致）。 */
    @Test
    fun `requestId 原样透传给 Provider`() {
        val host = newHost()
        val seen = AtomicReference<String>("")
        host.register(
            TypedProvider("demo", behavior = { request ->
                seen.set(request.requestId)
                ActionResult.ok()
            }),
        )

        host.dispatch(ActionRequest("demo", "op", "{}", "req-fixed", Principal.unknown()))

        assertEquals("req-fixed", seen.get(), "平台已给的 requestId 不得被改写")
    }

    /** 验收 6：Principal 各字段正确透传（含 operatorId 这种数字字段）。 */
    @Test
    fun `Principal 各字段正确透传`() {
        val host = newHost()
        val seen = AtomicReference<Principal>(Principal.unknown())
        host.register(
            TypedProvider("demo", behavior = { request ->
                seen.set(request.principal)
                ActionResult.ok()
            }),
        )
        val principal = Principal("alice", 42L, "node-uuid-1", "活动奖励补发")

        host.dispatch(ActionRequest("demo", "grant", "{}", "req-1", principal))

        val actual = seen.get()
        assertEquals("alice", actual.operator, "operator 应透传")
        assertEquals(42L, actual.operatorId, "operatorId 应透传")
        assertEquals("node-uuid-1", actual.nodeId, "nodeId 应透传")
        assertEquals("活动奖励补发", actual.reason, "reason 应透传")
    }

    /** Principal 为 null 时降级为 unknown，Provider 不必判空。 */
    @Test
    fun `Principal 为 null 时降级为 unknown`() {
        val request = ActionRequest("demo", "op", "", "req-1", null)

        assertNotNull(request.principal)
        assertEquals(Principal.unknown(), request.principal)
    }

    /** 成功结果携带 outputJson 与 metadata，失败结果携带 errorCode/errorMessage（分流依据）。 */
    @Test
    fun `ActionResult 的成功与失败形态`() {
        val host = newHost()
        host.register(
            TypedProvider("demo", { request ->
                when (request.action) {
                    "ok" -> ActionResult.ok("{\"n\":1}", mapOf("costMs" to "7"))
                    else -> ActionResult.fail("PLAYER_NOT_FOUND", "玩家不存在")
                }
            }),
        )

        val ok = host.dispatch(ActionRequest("demo", "ok", "", "r1", Principal.unknown()))
        assertTrue(ok.success)
        assertEquals("{\"n\":1}", ok.outputJson)
        assertEquals(mapOf("costMs" to "7"), ok.metadata, "metadata 应透传诊断信息")
        assertEquals("", ok.errorCode, "成功时不得带错误码")

        val fail = host.dispatch(ActionRequest("demo", "missing", "", "r2", Principal.unknown()))
        assertFalse(fail.success)
        assertEquals("PLAYER_NOT_FOUND", fail.errorCode, "错误码是面板分流的唯一依据")
        assertEquals("玩家不存在", fail.errorMessage)
    }

    /** 未注册域与 null 请求都降级为失败，不抛异常（契约要求）。 */
    @Test
    fun `未注册域与空请求降级为失败`() {
        val host = newHost()

        assertFalse(host.dispatch(ActionRequest("nowhere", "op", "", "r1", Principal.unknown())).success)
        assertFalse(host.dispatch(null).success, "null 请求应降级为失败而非 NPE")
        assertFalse(host.dispatchAsync(null as ActionRequest?).get().success, "异步入口同样不得抛")
    }

    /** 兼容入口（字符串形态）仍可用，并且会把 ActionResult 折回 BridgeCommandResult。 */
    @Test
    fun `兼容入口保持旧语义`() {
        val host = newHost()
        host.register(
            TypedProvider("demo", { request ->
                if (request.action == "ok") ActionResult.ok("out") else ActionResult.fail("CODE", "原因")
            }),
        )

        val ok = host.dispatch("demo", "ok", "{}")
        assertTrue(ok.success)
        assertEquals("out", ok.output)

        val fail = host.dispatch("demo", "bad", "{}")
        assertFalse(fail.success)
        assertTrue(fail.error.contains("原因"), "失败原因应折回：${fail.error}")
    }

    /** Provider 抛异常 / 超时仍然只降级该次回执（事故域隔离不回退）。 */
    @Test
    fun `Provider 抛异常时降级为失败结果`() {
        val host = newHost()
        host.register(
            TypedProvider("demo", { error("boom") }),
        )

        val result = host.dispatch(ActionRequest("demo", "op", "", "r1", Principal.unknown()))

        assertFalse(result.success, "抛异常应降级为失败而非上抛")
        assertTrue(result.errorMessage.contains("异常"), "原因应点明异常：${result.errorMessage}")
    }

    /** 注销旧契约 Provider 只能用句柄（适配器每次产生新实例，按实例比对必然不匹配）。 */
    @Test
    fun `旧契约域用句柄注销`() {
        val host = newHost()
        val handle = host.registerLegacy(LegacyProvider("economy", listOf("balance")))

        handle.close()

        assertTrue(host.domains().isEmpty(), "句柄 close 应真正移除该域")
        assertFalse(handle.getActive())
    }

    /** 同一旧契约实例注册两次会各自产生新包装：docs 里明确要求用句柄，这里固化该事实。 */
    @Test
    fun `旧契约按实例注销不生效`() {
        val host = newHost()
        val legacy = LegacyProvider("economy", listOf("balance"))
        host.registerLegacy(legacy)

        assertFalse(host.unregister(null), "null 应返回 false")
        assertEquals(setOf("economy"), host.domains(), "域仍在（包装实例无法从外部取得）")
    }

    /** dispatch 计数：一次请求只应打到一个 Provider（避免"多域都被调用"的错路由）。 */
    @Test
    fun `派发只命中目标域`() {
        val host = newHost()
        val counter = AtomicInteger(0)
        host.register(TypedProvider("a", { counter.incrementAndGet(); ActionResult.ok() }))
        host.register(TypedProvider("b", { counter.incrementAndGet(); ActionResult.ok() }))

        host.dispatch(ActionRequest("b", "op", "", "r1", Principal.unknown()))

        assertEquals(1, counter.get(), "只应有一个 Provider 被调用")
    }

    // ------------------------------------------------------------------ 小工具

    @Suppress("UNCHECKED_CAST")
    private fun domainsOf(envelope: Map<String, Any?>): Map<String, Map<String, Any?>> =
        envelope["domains"] as Map<String, Map<String, Any?>>

    private fun domainOf(envelope: Map<String, Any?>, domain: String): Map<String, Any?> =
        domainsOf(envelope)[domain] ?: error("聚合清单缺少域 $domain：${domainsOf(envelope).keys}")

    @Suppress("UNCHECKED_CAST")
    private fun actionOf(domain: Map<String, Any?>, action: String): Map<String, Any?> =
        (domain["actions"] as List<Map<String, Any?>>).firstOrNull { it["name"] == action }
            ?: error("域清单缺少动作 $action")
}
