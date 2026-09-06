package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.util.UUID

/** PlayerDiagnosticsToolProvider 单测：在线/离线/非法输入/平台降级。 */
class PlayerDiagnosticsToolProviderTest {

    private fun provider(impl: PlayerDiagnosticsProvider? = null): PlayerDiagnosticsToolProvider =
        PlayerDiagnosticsToolProvider().apply { provider = impl }

    private fun arguments(vararg values: Pair<String, Any?>): JsonObject = object : JsonObject {
        private val map = values.toMap()
        override fun getRaw(key: String): Any? = map[key]
        override fun getString(key: String, default: String): String = map[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = map[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = emptyList()
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun getObject(key: String): JsonObject? = null
    }

    @Test
    fun `在线玩家返回完整明细`() {
        val impl = FakeProvider(PlayerLookupResult(
            name = "Alice", uuid = UUID.randomUUID().toString(), online = true,
            world = "world", health = 20.0, foodLevel = 20,
            inventory = listOf(InventorySummary("DIAMOND_SWORD", 1, 0)),
            chunk = mapOf("x" to 1, "z" to 2),
        ))
        val result = provider(impl).call("player_lookup", arguments("name" to "Alice"))

        assertEquals("Alice", result["name"])
        assertEquals(true, result["online"])
        assertEquals("world", result["world"])
        assertEquals(20.0, result["health"])
        assertEquals(1, (result["chunk"] as Map<*, *>)["x"])
    }

    @Test
    fun `离线玩家返回 online false`() {
        val impl = FakeProvider(PlayerLookupResult(
            name = "Bob", uuid = UUID.randomUUID().toString(), online = false, reason = "玩家不在线",
        ))
        val result = provider(impl).call("player_lookup", arguments("name" to "Bob"))

        assertEquals(false, result["online"])
        assertTrue(result.containsKey("reason"))
    }

    @Test
    fun `平台不支持时返回结构化降级`() {
        val result = provider(null).call("player_lookup", arguments("name" to "Alice"))

        assertEquals(false, result["available"])
        assertTrue(result["reason"].toString().contains("平台不支持"))
    }

    @Test
    fun `非法 uuid 与缺少参数拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            provider().call("player_lookup", arguments("uuid" to "not-a-uuid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider().call("player_lookup", arguments())
        }
    }

    @Test
    fun `工具目录只包含 player_lookup`() {
        val names = provider().tools().map { it.name }

        assertEquals(listOf("player_lookup"), names)
    }

    private class FakeProvider(private val result: PlayerLookupResult?) : PlayerDiagnosticsProvider {
        override fun lookup(request: PlayerLookupRequest): PlayerLookupResult? = result
    }
}
