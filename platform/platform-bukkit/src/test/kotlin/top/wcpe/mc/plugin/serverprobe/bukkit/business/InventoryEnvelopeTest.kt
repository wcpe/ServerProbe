package top.wcpe.mc.plugin.serverprobe.bukkit.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.allininventorysync.api.model.BasicAttrsDto
import top.wcpe.mc.plugin.allininventorysync.api.model.InventoryViewDto
import top.wcpe.mc.plugin.allininventorysync.api.model.ItemDto
import top.wcpe.mc.plugin.allininventorysync.api.model.WriteResult

/**
 * [InventoryEnvelope] 纯逻辑单元测试(JBIS FR-125)。
 *
 * 覆盖不触 [top.wcpe.mc.plugin.serverprobe.core.json.Json] 编解码的纯函数:manifest 契约 / 读写参数校验 /
 * 操作者回退 / 物品 / 属性解码 / 视图 · 物品 · 回执编码(均返回纯 Map,可断言)。真实读写落盘正确性属真机维度,另行真机验收。
 */
class InventoryEnvelopeTest {

    private fun item(material: String, nbt: String, displayName: String? = null): ItemDto =
        ItemDto(material = material, amount = 1, displayName = displayName, lore = null, enchantments = null, nbtBase64 = nbt)

    private fun attrs(): BasicAttrsDto = BasicAttrsDto(20.0, 18, 30, 0.5f, 1395, "SURVIVAL")

    /** manifest 应声明 1 只读 view + 3 写动作,readOnly 标志正确且各带 note。 */
    @Test
    fun `manifest 声明 4 个动作且 readOnly 正确`() {
        val actions = (InventoryEnvelope.manifest()["actions"] as List<*>).map { it as Map<*, *> }
        assertEquals(4, actions.size, "应为 view + 3 写动作")
        val byName = actions.associateBy { it["action"] }
        assertTrue(
            byName.keys.containsAll(listOf("view", "writeInventory", "writeEnderChest", "writeBasicAttrs")),
            "缺动作:${byName.keys}"
        )
        assertEquals(true, byName["view"]!!["readOnly"], "view 应只读")
        assertEquals(false, byName["writeInventory"]!!["readOnly"], "writeInventory 应为写")
        assertEquals(false, byName["writeBasicAttrs"]!!["readOnly"], "writeBasicAttrs 应为写")
        actions.forEach { assertTrue((it["note"] as String).isNotBlank(), "每个动作应带 note:${it["action"]}") }
    }

    /** 读参数校验:player 非空通过(null),空白拒绝。 */
    @Test
    fun `requireReadCommon 空白 player 拒绝`() {
        assertNull(InventoryEnvelope.requireReadCommon("uuid-1"), "非空应通过")
        val blank = InventoryEnvelope.requireReadCommon("  ")
        assertNotNull(blank)
        assertFalse(blank!!.success, "空白 player 应失败")
        assertTrue(blank.error.contains("player"), "原因应点名 player")
    }

    /** 写参数校验:齐全通过;缺 player / taskId 各自拒绝且原因可读。 */
    @Test
    fun `requireWriteCommon 齐全通过缺失拒绝`() {
        assertNull(InventoryEnvelope.requireWriteCommon("uuid-1", "task-1"), "齐全应通过")
        assertFalse(InventoryEnvelope.requireWriteCommon("", "task-1")!!.success, "缺 player 应失败")
        val blankTask = InventoryEnvelope.requireWriteCommon("uuid-1", "  ")
        assertNotNull(blankTask)
        assertFalse(blankTask!!.success, "taskId 空白应失败")
        assertTrue(blankTask.error.contains("taskId"), "原因应点名 taskId:${blankTask.error}")
    }

    /** 操作者:空白回退 JianManager,非空原样。 */
    @Test
    fun `operatorOf 空白回退默认`() {
        assertEquals("JianManager", InventoryEnvelope.operatorOf(""), "空回退默认")
        assertEquals("JianManager", InventoryEnvelope.operatorOf("   "), "空白回退默认")
        assertEquals("m3admin", InventoryEnvelope.operatorOf("m3admin"), "非空原样")
    }

    /** 物品解码:有效 nbt 构造 ItemDto(nbt 为准、UI 字段留痕、lore/ench 置 null);空 nbt 返回 null 跳过。 */
    @Test
    fun `itemFromParts 有效构造无效跳过`() {
        val entry = InventoryEnvelope.itemFromParts(3, "AQID", "DIAMOND_SWORD", 2, "锋利之刃")
        assertNotNull(entry)
        assertEquals(3, entry!!.first, "槽位")
        assertEquals("AQID", entry.second.nbtBase64, "nbt 全保真真源")
        assertEquals("DIAMOND_SWORD", entry.second.material)
        assertEquals(2, entry.second.amount)
        assertEquals("锋利之刃", entry.second.displayName)
        assertNull(entry.second.lore, "写入不需 lore")
        assertNull(entry.second.enchantments, "写入不需 enchantments")

        assertNull(InventoryEnvelope.itemFromParts(0, "", "AIR", 0, null), "空 nbt 应跳过")
        assertNull(
            InventoryEnvelope.itemFromParts(1, "X", "S", 1, "  ")?.second?.displayName,
            "空白 displayName 应归一为 null"
        )
    }

    /** 基础属性解码:逐字段构造。 */
    @Test
    fun `basicAttrs 逐字段构造`() {
        val a = InventoryEnvelope.basicAttrs(19.5, 17, 12, 0.25f, 130, "CREATIVE")
        assertEquals(19.5, a.health)
        assertEquals(17, a.foodLevel)
        assertEquals(12, a.xpLevel)
        assertEquals(0.25f, a.xpProgress)
        assertEquals(130, a.xpTotal)
        assertEquals("CREATIVE", a.gameMode)
    }

    /** 玩家无数据:exists=false,区分「空背包」。 */
    @Test
    fun `encodeNotFound 标 exists false`() {
        val m = InventoryEnvelope.encodeNotFound("uuid-x")
        assertEquals(false, m["exists"])
        assertEquals("uuid-x", m["player"])
    }

    /** 物品编码:含槽位 + UI 字段;空 lore/enchantments 不写入,非空写入。 */
    @Test
    fun `encodeItem 携槽位与可选字段`() {
        val bare = InventoryEnvelope.encodeItem(2, item("STONE", "bbbb"))
        assertEquals(2, bare["slot"])
        assertEquals("STONE", bare["material"])
        assertEquals("bbbb", bare["nbtBase64"])
        assertFalse(bare.containsKey("displayName"), "无 displayName 不写入")
        assertFalse(bare.containsKey("lore"), "无 lore 不写入")

        val rich = ItemDto("SWORD", 1, "名剑", listOf("第一行"), mapOf("minecraft:sharpness" to 5), "cccc")
        val m = InventoryEnvelope.encodeItem(0, rich)
        assertEquals("名剑", m["displayName"])
        assertEquals(listOf("第一行"), m["lore"])
        assertEquals(mapOf("minecraft:sharpness" to 5), m["enchantments"])
    }

    /** 视图编码:exists/online/dataVersion + 物品数组按槽位升序 + 基础属性。 */
    @Test
    fun `encodeView 槽位升序且字段齐全`() {
        val view = InventoryViewDto(
            inventory = mapOf(5 to item("B", "b5"), 0 to item("A", "a0")),
            enderChest = mapOf(1 to item("E", "e1")),
            basicAttrs = attrs(),
            online = true,
            dataVersion = 42L,
        )
        val m = InventoryEnvelope.encodeView("uuid-1", view)
        assertEquals(true, m["exists"])
        assertEquals("uuid-1", m["player"])
        assertEquals(true, m["online"])
        assertEquals(42L, m["dataVersion"])

        @Suppress("UNCHECKED_CAST")
        val inv = m["inventory"] as List<Map<String, Any?>>
        assertEquals(listOf(0, 5), inv.map { it["slot"] }, "背包应按槽位升序")
        assertEquals("a0", inv[0]["nbtBase64"], "升序后首件为槽 0")

        @Suppress("UNCHECKED_CAST")
        val attrsMap = m["basicAttrs"] as Map<String, Any?>
        assertEquals(20.0, attrsMap["health"])
        assertEquals("SURVIVAL", attrsMap["gameMode"])
    }

    /** 回执编码:成功携新版本号(字符串化);失败 newDataVersion 空、errorCode 透传。 */
    @Test
    fun `encodeWriteResult 成功失败字段映射`() {
        val ok = InventoryEnvelope.encodeWriteResult(WriteResult.success(online = true, newDataVersion = 7L))
        assertEquals(true, ok["success"])
        assertEquals(true, ok["online"])
        assertEquals("7", ok["newDataVersion"], "版本号字符串化")
        assertEquals("", ok["errorCode"], "成功无错误码")

        val fail = InventoryEnvelope.encodeWriteResult(WriteResult.failure("OWNED_ELSEWHERE", "他服在线持有"))
        assertEquals(false, fail["success"])
        assertEquals("", fail["newDataVersion"], "失败无版本号")
        assertEquals("OWNED_ELSEWHERE", fail["errorCode"], "错误码透传")
        assertEquals("他服在线持有", fail["message"])
    }
}
