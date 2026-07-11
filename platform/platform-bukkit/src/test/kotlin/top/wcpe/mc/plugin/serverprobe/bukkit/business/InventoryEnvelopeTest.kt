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
 * [InventoryEnvelope] 纯逻辑单元测试(JBIS FR-125,见 ServerProbe ADR-0017 取代 ADR-0016)。
 *
 * 覆盖不触 [top.wcpe.mc.plugin.serverprobe.core.json.Json] 编解码的纯函数:manifest 契约 / 物品写降级 / 读写参数校验 /
 * 操作者回退 / 基础属性解码 / 视图 · 物品 · 回执编码(均返回纯 Map,可断言)。真实读写落盘正确性属真机维度,另行真机验收。
 *
 * AllinInventorySync 2.0.0 起对外 DTO 为纯 Java(Lombok),Kotlin 不允许对 Java 方法 / 构造器用具名实参,
 * 故本测试构造 DTO 一律按字段声明顺序传位置参。
 */
class InventoryEnvelopeTest {

    // ItemDto(material, amount, displayName, lore, enchantments, nbtBase64) —— Java DTO 位置参构造。
    private fun item(material: String, nbt: String, displayName: String? = null): ItemDto =
        ItemDto(material, 1, displayName, null, null, nbt)

    // BasicAttrsDto(health, foodLevel, xpLevel, xpProgress, xpTotal, gameMode)。
    private fun attrs(): BasicAttrsDto = BasicAttrsDto(20.0, 18, 30, 0.5f, 1395, "SURVIVAL")

    /** manifest 应声明 1 只读 view + 1 基础属性写,且不含物品写(2.0.0 写门面分区字节不可外部消费,ADR-0017)。 */
    @Test
    fun `manifest 声明 view 与 writeBasicAttrs 且不含物品写`() {
        val actions = (InventoryEnvelope.manifest()["actions"] as List<*>).map { it as Map<*, *> }
        assertEquals(2, actions.size, "应为 view + writeBasicAttrs 两动作")
        val byName = actions.associateBy { it["action"] }
        assertTrue(byName.keys.containsAll(listOf("view", "writeBasicAttrs")), "缺动作:${byName.keys}")
        assertFalse(byName.containsKey("writeInventory"), "物品写不应进 manifest")
        assertFalse(byName.containsKey("writeEnderChest"), "末影箱写不应进 manifest")
        assertEquals(true, byName["view"]!!["readOnly"], "view 应只读")
        assertEquals(false, byName["writeBasicAttrs"]!!["readOnly"], "writeBasicAttrs 应为写")
        actions.forEach { assertTrue((it["note"] as String).isNotBlank(), "每个动作应带 note:${it["action"]}") }
    }

    /** 物品写降级:writeInventory / writeEnderChest 返回失败且原因点名不透明分区字节。 */
    @Test
    fun `itemWriteUnsupported 明确降级`() {
        val r = InventoryEnvelope.itemWriteUnsupported("writeInventory")
        assertFalse(r.success, "物品写应降级失败")
        assertTrue(r.error.contains("writeInventory"), "原因应点名动作:${r.error}")
        assertTrue(r.error.contains("分区字节"), "原因应说明分区字节不可外部消费:${r.error}")
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

    /** 基础属性解码:逐字段构造。 */
    @Test
    fun `basicAttrs 逐字段构造`() {
        val a = InventoryEnvelope.basicAttrs(19.5, 17, 12, 0.25f, 130, "CREATIVE") as BasicAttrsDto
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

        // ItemDto(material, amount, displayName, lore, enchantments, nbtBase64) —— 位置参,带 lore/附魔。
        val rich = ItemDto("SWORD", 1, "名剑", listOf("第一行"), mapOf("minecraft:sharpness" to 5), "cccc")
        val m = InventoryEnvelope.encodeItem(0, rich)
        assertEquals("名剑", m["displayName"])
        assertEquals(listOf("第一行"), m["lore"])
        assertEquals(mapOf("minecraft:sharpness" to 5), m["enchantments"])
    }

    /** 视图编码:exists/online/dataVersion + 物品数组按槽位升序 + 基础属性。 */
    @Test
    fun `encodeView 槽位升序且字段齐全`() {
        // InventoryViewDto(inventory, enderChest, basicAttrs, online, dataVersion) —— Java DTO 位置参构造。
        val view = InventoryViewDto(
            mapOf(5 to item("B", "b5"), 0 to item("A", "a0")),
            mapOf(1 to item("E", "e1")),
            attrs(),
            true,
            42L,
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
        // WriteResult.success(online, newDataVersion) —— Java 静态工厂,位置参。
        val ok = InventoryEnvelope.encodeWriteResult(WriteResult.success(true, 7L))
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

    /**
     * 基础属性解码——JianManager 前端真实契约 payload(FR-125/126/127):
     * base/edited 为 `{dataVersion, basicAttrs:{...}}` 嵌套容器,数值字段为 JSON 数值(非字符串)。
     * 回归:曾直读容器顶层 + 仅按字符串解析 → 全字段回退默认(血量 0.0),在线玩家被写死。
     * 桩语义按最严苛情形:数值节点 getString 类型不符返回 default(与接口约定一致),数值只能经 getDouble/getInt 取。
     */
    @Test
    fun `decodeBasicAttrs 解析前端契约嵌套数值容器`() {
        val base = MapJsonObject(
            mapOf(
                "dataVersion" to 15,
                "basicAttrs" to mapOf(
                    "health" to 20.0, "foodLevel" to 20, "xpLevel" to 10,
                    "xpProgress" to 0.0, "xpTotal" to 0, "gameMode" to "SURVIVAL",
                ),
            )
        )
        val edited = MapJsonObject(
            mapOf(
                "dataVersion" to 15,
                "basicAttrs" to mapOf(
                    "health" to 19.5, "foodLevel" to 18, "xpLevel" to 7,
                    "xpProgress" to 0.25, "xpTotal" to 130, "gameMode" to "CREATIVE",
                ),
            )
        )

        val b = InventoryEnvelope.decodeBasicAttrs(base) as BasicAttrsDto
        assertEquals(20.0, b.health, "base 血量应为 20.0 而非默认 0.0")
        assertEquals(20, b.foodLevel)
        assertEquals(10, b.xpLevel)
        assertEquals("SURVIVAL", b.gameMode)

        val e = InventoryEnvelope.decodeBasicAttrs(edited) as BasicAttrsDto
        assertEquals(19.5, e.health, "edited 血量应为 19.5 而非默认 0.0")
        assertEquals(18, e.foodLevel)
        assertEquals(7, e.xpLevel)
        assertEquals(0.25f, e.xpProgress)
        assertEquals(130, e.xpTotal)
        assertEquals("CREATIVE", e.gameMode)
    }

    /** 基础属性解码兼容:扁平直发(无 basicAttrs 嵌套)与字符串承载数值同样可解。 */
    @Test
    fun `decodeBasicAttrs 兼容扁平与字符串承载`() {
        val flatStrings = MapJsonObject(
            mapOf(
                "health" to "19.5", "foodLevel" to "17", "xpLevel" to "12",
                "xpProgress" to "0.25", "xpTotal" to "130", "gameMode" to "CREATIVE",
            )
        )
        val a = InventoryEnvelope.decodeBasicAttrs(flatStrings) as BasicAttrsDto
        assertEquals(19.5, a.health)
        assertEquals(17, a.foodLevel)
        assertEquals(12, a.xpLevel)
        assertEquals(0.25f, a.xpProgress)
        assertEquals(130, a.xpTotal)
        assertEquals("CREATIVE", a.gameMode)
    }

    /**
     * 只读 JSON 树桩:嵌套 Map 承载,取值语义对齐 [top.wcpe.mc.plugin.serverprobe.core.json.JsonObject]
     * 接口约定(缺失或类型不符返回默认值)——即数值节点 getString 拿不到、字符串节点 getDouble/getInt 拿不到,
     * 复现"数值承载 + 仅按字符串解析必回退默认"的最严苛情形。
     */
    private class MapJsonObject(private val map: Map<String, Any?>) :
        top.wcpe.mc.plugin.serverprobe.core.json.JsonObject {
        override fun getString(key: String, default: String): String = map[key] as? String ?: default
        override fun getInt(key: String, default: Int): Int = (map[key] as? Number)?.toInt() ?: default
        override fun getLong(key: String, default: Long): Long = (map[key] as? Number)?.toLong() ?: default
        override fun getDouble(key: String, default: Double): Double = (map[key] as? Number)?.toDouble() ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> =
            (map[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        override fun contains(key: String): Boolean = map.containsKey(key)

        @Suppress("UNCHECKED_CAST")
        override fun getObject(key: String): top.wcpe.mc.plugin.serverprobe.core.json.JsonObject? =
            (map[key] as? Map<String, Any?>)?.let { MapJsonObject(it) }
    }
}
