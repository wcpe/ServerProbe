package top.wcpe.mc.plugin.serverprobe.integration.allininventorysync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [InventoryEventEnvelope] 纯逻辑单元测试(JBIS FR-125)。
 *
 * 覆盖去重键合成(含会话单调序号去歧义)与追踪事件信封字段编码。无 Bukkit / AllinInventorySync 运行期依赖。
 */
class InventoryEventEnvelopeTest {

    /** 去重键:playerUuid:action:occurredAtMs:seq;同毫秒同动作靠 seq 区分(瞬时观测无插件侧持久 ID)。 */
    @Test
    fun `dedupKey 含会话序号去歧义`() {
        assertEquals("u1:DROP:1000:1", InventoryEventEnvelope.dedupKey("u1", "DROP", 1000L, 1L))
        assertNotEquals(
            InventoryEventEnvelope.dedupKey("u1", "DROP", 1000L, 1L),
            InventoryEventEnvelope.dedupKey("u1", "DROP", 1000L, 2L),
            "同毫秒同动作不同 seq 应得不同键"
        )
    }

    /** 信封编码:字段齐全、amount / occurredAt 字符串化、键名与约定一致。 */
    @Test
    fun `encode 字段齐全且字符串化`() {
        val f = InventoryEventEnvelope.encode(
            playerName = "Steve",
            playerUuid = "uuid-steve",
            action = "PICKUP",
            ruleId = "rule-diamond",
            ruleDescription = "钻石追踪",
            material = "DIAMOND",
            amount = 3,
            displayName = "闪耀",
            occurredAtMs = 1717000000000L,
        )
        assertEquals("Steve", f[InventoryEventEnvelope.FIELD_PLAYER_NAME])
        assertEquals("uuid-steve", f[InventoryEventEnvelope.FIELD_PLAYER_UUID])
        assertEquals("PICKUP", f[InventoryEventEnvelope.FIELD_ACTION])
        assertEquals("rule-diamond", f[InventoryEventEnvelope.FIELD_RULE_ID])
        assertEquals("钻石追踪", f[InventoryEventEnvelope.FIELD_RULE_DESC])
        assertEquals("DIAMOND", f[InventoryEventEnvelope.FIELD_MATERIAL])
        assertEquals("3", f[InventoryEventEnvelope.FIELD_AMOUNT], "数量字符串化")
        assertEquals("闪耀", f[InventoryEventEnvelope.FIELD_DISPLAY_NAME])
        assertEquals("1717000000000", f[InventoryEventEnvelope.FIELD_OCCURRED_AT], "时刻字符串化")
    }

    /** 域名与背包域一致。 */
    @Test
    fun `DOMAIN 与背包域一致`() {
        assertEquals(InventoryEnvelope.DOMAIN, InventoryEventEnvelope.DOMAIN)
        assertEquals("inventory", InventoryEventEnvelope.DOMAIN)
    }

    /** 可选 API 事件按公开 getter 读取，动态注册器无需在类签名中引用 AIS 事件类型。 */
    @Test
    fun `公开追踪事件可转为上报字段`() {
        val data = InventoryTrackedEventReader.read(
            FakeTrackedEvent(
                player = FakePlayer(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "SpInventory"),
                action = FakeAction.PICKUP,
                rule = FakeRule("fr10-diamond", "FR10 真实事件验收物品"),
                item = FakeItem(FakeMaterial.DIAMOND, 1, FakeItemMeta("闪耀钻石")),
            )
        )

        assertEquals("SpInventory", data.playerName)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", data.playerUuid)
        assertEquals("PICKUP", data.action)
        assertEquals("fr10-diamond", data.ruleId)
        assertEquals("FR10 真实事件验收物品", data.ruleDescription)
        assertEquals("DIAMOND", data.material)
        assertEquals(1, data.amount)
        assertEquals("闪耀钻石", data.displayName)
    }

    private data class FakeTrackedEvent(
        val player: FakePlayer,
        val action: FakeAction,
        val rule: FakeRule,
        val item: FakeItem,
    )

    private data class FakePlayer(val uniqueId: UUID, val name: String)

    private enum class FakeAction { PICKUP }

    private data class FakeRule(val id: String, val description: String)

    private data class FakeItem(val type: FakeMaterial, val amount: Int, val itemMeta: FakeItemMeta)

    private enum class FakeMaterial { DIAMOND }

    private data class FakeItemMeta(val displayName: String) {
        fun hasDisplayName(): Boolean = displayName.isNotEmpty()
    }
}
