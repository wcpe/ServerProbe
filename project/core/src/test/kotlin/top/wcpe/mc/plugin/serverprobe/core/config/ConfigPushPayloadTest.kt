package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ConfigPushPayload] 解析单测（FR-32，协议规格 §3.4.1 的载荷形态）。
 *
 * 载荷来自控制面、可能残缺，故"解析失败 → 中文拒绝原因"本身就是一条要守的行为；
 * 本测同时锁定 `values` / `removals` 的落型（数字统一为 Long，数组只保留字符串项）。
 */
class ConfigPushPayloadTest {

    /** 规格 §3.4.1 的完整示例：字段逐个还原。 */
    @Test
    fun `解析完整载荷`() {
        val payload = ConfigPushPayload.parse(
            """
            {"configVersion":7,"baseVersion":6,
             "values":{"collect-period-ticks":100,"history-capacity":360},
             "removals":["legacy.key.to.drop"],
             "reason":"调整采集节奏","operator":"wxys233"}
            """.trimIndent(),
        )

        assertEquals(7L, payload.configVersion)
        assertEquals(6L, payload.baseVersion)
        assertEquals(linkedMapOf("collect-period-ticks" to 100L, "history-capacity" to 360L), payload.values)
        assertEquals(listOf("legacy.key.to.drop"), payload.removals)
        assertEquals("调整采集节奏", payload.reason)
        assertEquals("wxys233", payload.operator)
    }

    /** `values` 支持嵌套对象（如 `alert.rules` 整块下发）。 */
    @Test
    fun `values 支持嵌套对象`() {
        val payload = ConfigPushPayload.parse(
            """{"configVersion":1,"baseVersion":0,"values":{"alert.rules":{"tps-warn":{"threshold":18}}}}""",
        )

        @Suppress("UNCHECKED_CAST")
        val rules = payload.values["alert.rules"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val tpsWarn = rules["tps-warn"] as Map<String, Any?>
        assertEquals(18L, tpsWarn["threshold"])
    }

    /** 缺 `removals` / `reason` / `operator` 属正常裁剪，不应导致解析失败。 */
    @Test
    fun `可选字段缺失时给空值`() {
        val payload = ConfigPushPayload.parse("""{"configVersion":2,"baseVersion":1}""")

        assertTrue(payload.values.isEmpty())
        assertTrue(payload.removals.isEmpty())
        assertEquals("", payload.reason)
        assertEquals("", payload.operator)
    }

    /** 缺 `configVersion`：解析失败（调用方据此回中文失败回执）。 */
    @Test
    fun `缺少 configVersion 时抛异常`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigPushPayload.parse("""{"baseVersion":0}""")
        }
        assertTrue(error.message!!.contains("configVersion"), "实际:${error.message}")
    }

    /** 缺 `baseVersion`：解析失败（乐观并发不能靠猜）。 */
    @Test
    fun `缺少 baseVersion 时抛异常`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigPushPayload.parse("""{"configVersion":1}""")
        }
        assertTrue(error.message!!.contains("baseVersion"), "实际:${error.message}")
    }

    /** 空载荷 / 非对象 / 截断：一律解析失败，不做"尽力修复"。 */
    @Test
    fun `非法载荷解析失败`() {
        listOf("", "   ", "[1,2,3]", """{"configVersion":1,"baseVersion":0""").forEach { text ->
            assertThrows(IllegalArgumentException::class.java, { ConfigPushPayload.parse(text) }, "应拒绝:$text")
        }
    }

    /** `removals` 里的非字符串项被丢弃（不让脏数据进删除链路）。 */
    @Test
    fun `removals 只保留字符串项`() {
        val payload = ConfigPushPayload.parse(
            """{"configVersion":1,"baseVersion":0,"removals":["a.b",1,null,"c"]}""",
        )

        assertEquals(listOf("a.b", "c"), payload.removals)
    }
}
