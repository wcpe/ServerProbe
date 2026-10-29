package top.wcpe.mc.plugin.serverprobe.core.bridge.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.RiskLevel
import top.wcpe.mc.plugin.serverprobe.core.bridge.TestManifests

/**
 * [BusinessManifestRules] 的专项测试。
 *
 * <h2>为什么要单独测这个对象</h2>
 * <p>它的类注释自己说了：“这里全是**无副作用的纯函数**，可以逐条单测”。
 * 但此前它一条直接测试都没有——四个关键行为只经 [BusinessHostImpl] 间接、部分触发。
 * 尤其是 <b>聚合期二次降级</b>（类注释点名的静默失败场景）完全没人守。</p>
 *
 * <h2>聚合期二次降级为什么重要</h2>
 * <p>Provider 的 `manifest()` 是<b>每次现算</b>的。注册时 `apiVersion=1`、
 * 之后却开始返回 `apiVersion=2` 的实现，如果只在注册期判定，就会把
 * “读不懂的清单”当作原生规格交给面板——正是 spec §5.2 要防的静默失败。</p>
 */
class BusinessManifestRulesTest {

    // ────────────────── 域名校验 ──────────────────

    @Test
    @DisplayName("域名正则：合法/非法边界")
    fun `域名校验`() {
        // 合法：小写、数字、连字符，1–32 位
        assertTrue(BusinessManifestRules.isValidDomain("a"))
        assertTrue(BusinessManifestRules.isValidDomain("abc-123"))
        assertTrue(BusinessManifestRules.isValidDomain("a".repeat(32)), "32 位是上限，应合法")

        // 非法：null、空串、大写、下划线、超长、含空格
        assertFalse(BusinessManifestRules.isValidDomain(null), "null 非法")
        assertFalse(BusinessManifestRules.isValidDomain(""), "空串非法")
        assertFalse(BusinessManifestRules.isValidDomain("Abc"), "大写非法")
        assertFalse(BusinessManifestRules.isValidDomain("a_b"), "下划线非法")
        assertFalse(BusinessManifestRules.isValidDomain("a".repeat(33)), "33 位超长，应非法")
        assertFalse(BusinessManifestRules.isValidDomain("a b"), "含空格非法")
    }

    // ────────────────── 重复动作名 ──────────────────

    @Test
    @DisplayName("重复动作名：只报重复项，空名不参与判定")
    fun `重复动作名`() {
        val manifest = DomainManifest.of(
            "demo", "D", "",
            listOf(
                ActionSpec.read("a", ""),
                ActionSpec.read("b", ""),
                ActionSpec.read("a", ""), // 重复
                ActionSpec.read("", ""),  // 空名：不算重复
                ActionSpec.read("", ""),
            ),
            emptyMap(),
        )

        assertEquals(setOf("a"), BusinessManifestRules.duplicateActionNames(manifest))
    }

    @Test
    @DisplayName("重复动作名：null 清单返回空集合")
    fun `重复动作名null清单`() {
        // 这条分支以前从未被测：manifest() 抛异常时整条链会传 null 进来。
        assertTrue(BusinessManifestRules.duplicateActionNames(null).isEmpty())
    }

    @Test
    @DisplayName("重复动作名：无重复时返回空")
    fun `无重复返回空`() {
        val manifest = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("a", ""), ActionSpec.read("b", "")),
            emptyMap(),
        )
        assertTrue(BusinessManifestRules.duplicateActionNames(manifest).isEmpty())
    }

    // ────────────────── specDeclared 判定 ──────────────────

    @Test
    @DisplayName("specDeclared：null 清单或版本不匹配都为 false")
    fun `specDeclared判定`() {
        val provider = stubProvider("demo")

        assertFalse(
            BusinessManifestRules.isSpecDeclared(provider, null),
            "manifest() 取不到东西时规格不可信",
        )

        val current = DomainManifest.of("demo", "D", "", emptyList(), emptyMap())
        assertTrue(BusinessManifestRules.isSpecDeclared(provider, current), "当前版本应可信")

        // 构造一个不支持的版本号
        val future = DomainManifest(
            DomainManifest.CURRENT_API_VERSION + 1,
            "demo", "D", "", emptyList(), emptyMap(),
        )
        assertFalse(
            BusinessManifestRules.isSpecDeclared(provider, future),
            "版本不匹配的清单必须标为不可信，否则面板会按它渲染错误表单",
        )
    }

    // ────────────────── 降级 ──────────────────

    @Test
    @DisplayName("降级：保留动作名，风险保守为 WRITE，清空幂等/schema/错误码")
    fun `降级形态`() {
        val original = DomainManifest.of(
            "demo", "原名称", "原描述",
            listOf(
                ActionSpec.of(
                    "danger", "危险动作", RiskLevel.DESTRUCTIVE, true,
                    "{\"type\":\"object\"}", "{\"type\":\"string\"}",
                    setOf("ERR_A", "ERR_B"),
                ),
            ),
            mapOf("version" to "1.0"),
        )

        val degraded = BusinessManifestRules.degradeToLegacy(original)

        // 动作名与数量保留（面板至少知道“有哪些动作”）
        assertEquals(1, degraded.actions.size)
        assertEquals("danger", degraded.actions[0].name)

        // 但所有“读不懂的”字段都被保守化
        assertEquals(RiskLevel.WRITE, degraded.actions[0].risk, "未声明/不可读的风险一律保守为 WRITE")
        assertFalse(degraded.actions[0].idempotent, "不可读的幂等声明不能保留")
        // 注意语义细节：degradeToLegacy 用 ActionSpec.of(..., RiskLevel.WRITE, ...) **重建**规格，
        // 所以 declaredRisk 是显式 WRITE（不是 null）。这是正确的——降级后的规格确实“声明”了 WRITE，
        // 面板按它渲染就能得到“需二次确认”的效果。要防的是“把 DESTRUCTIVE 原样带过去”。
        assertEquals(RiskLevel.WRITE, degraded.actions[0].declaredRisk, "原始 DESTRUCTIVE 声明必须被覆盖")
        assertEquals("", degraded.actions[0].inputSchema, "schema 不可信，必须清空")
        assertEquals("", degraded.actions[0].outputSchema)
        assertTrue(degraded.actions[0].errorCodes.isEmpty(), "错误码不可信，必须清空")
        assertTrue(degraded.attributes.isEmpty(), "扩展位不可信，必须清空")

        // 版本号归一为当前支持版本（避免下游再判一次）
        assertEquals(DomainManifest.CURRENT_API_VERSION, degraded.apiVersion)
    }

    @Test
    @DisplayName("降级：保留动作声明顺序（面板按序渲染）")
    fun `降级保留顺序`() {
        val original = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("z", ""), ActionSpec.read("a", ""), ActionSpec.read("m", "")),
            emptyMap(),
        )

        val names = BusinessManifestRules.degradeToLegacy(original).actions.map { it.name }

        assertEquals(listOf("z", "a", "m"), names, "声明顺序是面板渲染依据，降级不得重排")
    }

    // ────────────────── 编码 ──────────────────

    @Test
    @DisplayName("单域编码：字段集与 spec 一致，空 schema 写 null 而非空串")
    fun `单域编码`() {
        val manifest = DomainManifest.of(
            "demo", "显示名", "描述",
            listOf(ActionSpec.read("query", "查询")),
            mapOf("k" to "v"),
        )

        val encoded = BusinessManifestRules.encodeDomain(manifest, specDeclared = true)

        assertEquals(setOf("displayName", "description", "specDeclared", "actions", "attributes"), encoded.keys)
        assertEquals("显示名", encoded["displayName"])
        assertEquals(true, encoded["specDeclared"])

        @Suppress("UNCHECKED_CAST")
        val actions = encoded["actions"] as List<Map<String, Any?>>
        assertEquals(1, actions.size)

        // 未声明 schema → null（不是空串）
        assertNull(actions[0]["inputSchema"], "未声明应用 null，空串会让下游误以为声明了空 schema")
        assertNull(actions[0]["outputSchema"])
        assertEquals(emptyList<String>(), actions[0]["errorCodes"], "未声明错误码应为空数组")
        assertEquals("READ", actions[0]["risk"])
    }

    @Test
    @DisplayName("信封编码：含 apiVersion 且保持域插入顺序")
    fun `信封编码保留顺序`() {
        val domains = linkedMapOf(
            "zebra" to mapOf<String, Any?>("a" to 1),
            "alpha" to mapOf<String, Any?>("a" to 2),
            "middle" to mapOf<String, Any?>("a" to 3),
        )

        val envelope = BusinessManifestRules.encodeEnvelope(domains)

        assertEquals(DomainManifest.CURRENT_API_VERSION, envelope["apiVersion"])

        @Suppress("UNCHECKED_CAST")
        val out = envelope["domains"] as Map<String, Any?>
        assertEquals(
            listOf("zebra", "alpha", "middle"),
            out.keys.toList(),
            "信封必须保持传入顺序（调用方已排好序，重排会打乱面板呈现）",
        )
    }

    @Test
    @DisplayName("错误占位：字段集恰为 {error}，不多不少")
    fun `错误占位字段集`() {
        val encoded = BusinessManifestRules.encodeDomainError("boom")

        // 以前只断言 containsKey("error")，多出字段也不会失败。
        // 这里钉死字段集：多一个字段会让下游误以为该域还有可用规格。
        assertEquals(setOf("error"), encoded.keys, "错误占位只能有 error 一个字段")
        assertEquals("boom", encoded["error"])
    }

    // ────────────────── 辅助 ──────────────────

    private fun stubProvider(domainName: String): BusinessProvider = object : BusinessProvider {
        override fun domain(): String = domainName

        override fun manifest(): DomainManifest = TestManifests.empty(domainName)

        override fun dispatch(request: ActionRequest): ActionResult = ActionResult.fail("UNUSED", "仅用于规则测试")
    }
}
