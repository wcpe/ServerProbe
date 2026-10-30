package top.wcpe.mc.plugin.serverprobe.bukkit.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.RiskLevel
import top.wcpe.mc.plugin.serverprobe.core.mcp.InventorySummary
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerDiagnosticsProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerLookupRequest
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerLookupResult
import java.util.UUID

/**
 * 原版背包业务域的契约测试。
 *
 * <p>本类只测 <b>纯逻辑</b>部分：入参解析、清单声明、结果序列化、失败分支。
 * 不碰 Bukkit API（背包数据由注入的 [PlayerDiagnosticsProvider] 给出，这里用 fake 替）。</p>
 *
 * <p><b>为什么这些断言值得写</b>：该类手工切 JSON 取字段、手工拼 JSON 输出，
 * 两处都不经过任何序列化库。字段改名或转义处理漏掉时，编译器不会报错，
 * 而是安静地产出一个坏名字/坏值，最后表现为「查不到这个玩家」而看不出原因。</p>
 */
class BukkitInventoryBusinessProviderTest {

    private fun providerWith(fake: PlayerDiagnosticsProvider?): BukkitInventoryBusinessProvider {
        val provider = BukkitInventoryBusinessProvider()
        provider.diagnostics = fake
        return provider
    }

    // ────────────────── 清单声明 ──────────────────

    @Test
    @DisplayName("域名合规且动作为只读")
    fun `manifest声明只读动作`() {
        val manifest = providerWith(null).manifest()

        assertEquals(BukkitInventoryBusinessProvider.DOMAIN, manifest.domain)
        assertEquals(1, manifest.actions.size)
        val action = manifest.actions[0]
        assertEquals(BukkitInventoryBusinessProvider.ACTION_DETAIL, action.name)
        assertEquals(RiskLevel.READ, action.risk, "背包查询无副作用，必须声明 READ")
        assertFalse(action.idempotent, "只读动作无副作用，幂等声明无意义")
        assertEquals("bukkit-vanilla", manifest.attributes["source"], "来源应可被面板识别")
    }

    @Test
    @DisplayName("域名符合桥的命名约束")
    fun `域名符合命名约束`() {
        assertTrue(
            Regex("^[a-z0-9-]{1,32}$").matches(BukkitInventoryBusinessProvider.DOMAIN),
            "域名不合法会被注册期直接拒绝",
        )
    }

    // ────────────────── 入参解析 ──────────────────

    @Test
    @DisplayName("按 name 解析")
    fun `解析name`() {
        val request = providerWith(null).parsePlayer("""{"name":"alice"}""")
        assertNotNull(request)
        assertEquals("alice", request!!.name)
        assertNull(request.uuid)
    }

    @Test
    @DisplayName("按 uuid 解析")
    fun `解析uuid`() {
        val id = UUID.randomUUID()
        val request = providerWith(null).parsePlayer("""{"uuid":"$id"}""")
        assertNotNull(request)
        assertEquals(id, request!!.uuid)
        assertNull(request.name)
    }

    @Test
    @DisplayName("name 与 uuid 同时给出时都保留")
    fun `同时解析name与uuid`() {
        val id = UUID.randomUUID()
        val request = providerWith(null).parsePlayer("""{"name":"alice","uuid":"$id"}""")
        assertNotNull(request)
        assertEquals("alice", request!!.name)
        assertEquals(id, request.uuid)
    }

    @Test
    @DisplayName("空 payload / 两个字段都缺 → 返回 null（不可当空串玩家")
    fun `空payload返回null`() {
        val provider = providerWith(null)
        assertNull(provider.parsePlayer(""))
        assertNull(provider.parsePlayer("   "))
        assertNull(provider.parsePlayer("{}"))
        assertNull(provider.parsePlayer("""{"other":"x"}"""))
    }

    @Test
    @DisplayName("非法 uuid 不被当成玩家名")
    fun `非法uuid回退为无uuid`() {
        // 非法 uuid 且无 name → 两者皆无 → null
        assertNull(providerWith(null).parsePlayer("""{"uuid":"not-a-uuid"}"""))

        // 非法 uuid 但有 name → 仍可按名字查（uuid 丢弃而不报错）
        val request = providerWith(null).parsePlayer("""{"name":"bob","uuid":"not-a-uuid"}""")
        assertNotNull(request)
        assertEquals("bob", request!!.name)
        assertNull(request.uuid, "非法 uuid 应被丢弃，而不是把整条请求判死")
    }

    @Test
    @DisplayName("值里的转义引号不被当成结束符")
    fun `转义引号不被截断`() {
        // 这是真实的坑：`indexOf('"')` 会把 `a\"b` 截成 `a\`，
        // 然后拿这个坏名字去查玩家，得到「查无此人」却看不出原因。
        val request = providerWith(null).parsePlayer("""{"name":"a\"b"}""")
        assertNotNull(request)
        assertEquals("a\"b", request!!.name)
    }

    @Test
    @DisplayName("空串值视为未提供")
    fun `空串值视为未提供`() {
        assertNull(providerWith(null).parsePlayer("""{"name":""}"""))
    }

    // ────────────────── 分发：失败分支 ──────────────────

    @Test
    @DisplayName("payload 无玩家标识时返回 INVALID_PAYLOAD")
    fun `无效payload报错码`() {
        val result = providerWith(null).dispatch(ActionRequest("inventory", "detail", "{}", "r1", null))

        assertFalse(result.success)
        assertEquals("INVALID_PAYLOAD", result.errorCode, "面板需按码分流，提示“需要 name 或 uuid”")
    }

    @Test
    @DisplayName("诊断提供者缺失时返回 UNAVAILABLE")
    fun `提供者缺失报不可用`() {
        val result = providerWith(null)
            .dispatch(ActionRequest("inventory", "detail", """{"name":"alice"}""", "r2", null))

        assertFalse(result.success)
        assertEquals("UNAVAILABLE", result.errorCode)
    }

    @Test
    @DisplayName("诊断无结果时返回 UNAVAILABLE")
    fun `诊断无结果报不可用`() {
        val provider = providerWith(object : PlayerDiagnosticsProvider {
            override fun lookup(request: PlayerLookupRequest) = null
        })
        val result = provider.dispatch(ActionRequest("inventory", "detail", """{"name":"alice"}""", "r3", null))

        assertFalse(result.success)
        assertEquals("UNAVAILABLE", result.errorCode)
    }

    // ────────────────── 分发：成功分支与序列化 ──────────────────

    @Test
    @DisplayName("在线玩家：三份视图与物品字段都序列化出来")
    fun `在线玩家序列化完整`() {
        val fake = object : PlayerDiagnosticsProvider {
            override fun lookup(request: PlayerLookupRequest): PlayerLookupResult? {
                assertEquals("alice", request.name)
                return PlayerLookupResult(
                    name = "alice",
                    uuid = "11111111-2222-3333-4444-555555555555",
                    online = true,
                    inventory = listOf(InventorySummary("DIAMOND", 3, 0)),
                    enderChest = listOf(InventorySummary("EMERALD", 1, 5)),
                    equipment = emptyList(),
                )
            }
        }
        val result = providerWith(fake)
            .dispatch(ActionRequest("inventory", "detail", """{"name":"alice"}""", "r4", null))

        assertTrue(result.success)
        val json = result.outputJson
        assertTrue(json.contains("\"online\":true"), "应标明在线，实际=$json")
        assertTrue(json.contains("\"source\":\"vanilla\""), "应标明数据来源，实际=$json")
        assertTrue(json.contains("\"material\":\"DIAMOND\""), "物品名应原样输出，实际=$json")
        assertTrue(json.contains("\"amount\":3"), "数量应输出，实际=$json")
        assertTrue(json.contains("\"inventory\":["), "背包数组应存在，实际=$json")
        assertTrue(json.contains("\"enderChest\":["), "末影箱数组应存在，实际=$json")
        assertTrue(json.contains("\"equipment\":[]"), "空装备栏应为空数组而不是 null，实际=$json")
    }

    @Test
    @DisplayName("离线玩家：三份视图为空，但仍是成功结果")
    fun `离线玩家不是错误`() {
        val fake = object : PlayerDiagnosticsProvider {
            override fun lookup(request: PlayerLookupRequest) = PlayerLookupResult(
                name = "bob",
                uuid = "99999999-8888-7777-6666-555555555555",
                online = false,
                reason = "玩家不在线",
            )
        }
        val result = providerWith(fake)
            .dispatch(ActionRequest("inventory", "detail", """{"name":"bob"}""", "r5", null))

        assertTrue(result.success, "离线是事实而不是故障，不能算失败")
        assertTrue(result.outputJson.contains("\"online\":false"))
        assertTrue(result.outputJson.contains("\"inventory\":[]"), "离线时三份视图应为空数组")
        assertTrue(result.outputJson.contains("\"reason\":\"玩家不在线\""), "原因应透传")
    }

    @Test
    @DisplayName("含引号的玩家名在输出里被转义，不破坏 JSON")
    fun `输出转义引号`() {
        val fake = object : PlayerDiagnosticsProvider {
            override fun lookup(request: PlayerLookupRequest) = PlayerLookupResult(
                name = "a\"b", uuid = "u", online = true, inventory = emptyList(),
            )
        }
        val result = providerWith(fake)
            .dispatch(ActionRequest("inventory", "detail", """{"name":"a"}""", "r6", null))

        assertTrue(result.success)
        assertTrue(
            result.outputJson.contains("\"name\":\"a\\\"b\""),
            "名字里的引号必须转义，否则面板解析会断，实际=${result.outputJson}",
        )
    }

    @Test
    @DisplayName("健康：不查诊断也返回可用的快照（不在查询路径上进第三方代码）")
    fun `健康不触发诊断`() {
        var called = false
        val provider = providerWith(object : PlayerDiagnosticsProvider {
            override fun lookup(request: PlayerLookupRequest): PlayerLookupResult? {
                called = true
                return null
            }
        })

        val health = provider.health()

        assertEquals(top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth.Status.AVAILABLE, health.status)
        assertFalse(called, "health() 不得触发任何查询，否则命令线程会被第三方代码卡住")
    }
}
