package top.wcpe.mc.plugin.serverprobe.core.json

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * [Json] 门面的可换性单测（ADR-14）。
 *
 * 默认实现 [ConfigJsonCodec] 依赖运行期 nightconfig（重定位后不在裸单测类路径），其 encode/parse 的正确性
 * 由真机验证（插件桥连接 + 治理命令往返）；本测只覆盖不依赖 nightconfig 的纯逻辑——
 * **换 [Json.codec] 后 encode/parse 改走新实现、调用点不变**，即"随时换"这一核心设计能力。
 */
class JsonFacadeTest {

    @AfterEach
    fun restoreDefault() {
        // 测试间互不污染:恢复默认后端。
        Json.codec = ConfigJsonCodec
    }

    @Test
    fun `默认后端为 ConfigJsonCodec`() {
        assertSame(ConfigJsonCodec, Json.codec)
    }

    @Test
    fun `换 codec 后 encode 改走新实现`() {
        Json.codec = FakeJsonCodec
        assertEquals("encoded:{a=1}", Json.encode(linkedMapOf("a" to 1)))
    }

    @Test
    fun `换 codec 后 parse 改走新实现且取字段透传`() {
        Json.codec = FakeJsonCodec
        val node = Json.parse("""{"type":"command"}""")
        assertEquals("command", node.getString("type"))
        assertEquals("缺省", node.getString("missing", "缺省"))
    }

    /** 仅用于验证门面委托的假实现:不连任何 JSON 库,直接记账/回固定值。 */
    private object FakeJsonCodec : JsonCodec {
        override fun encode(value: Any?): String = "encoded:$value"
        override fun parse(json: String): JsonObject = FakeJsonObject(json)
    }

    /** 假只读树:把 type 固定回 "command",其余键回默认值,验证取字段路径透传。 */
    private class FakeJsonObject(private val raw: String) : JsonObject {
        override fun getString(key: String, default: String): String =
            if (key == "type" && raw.contains("command")) "command" else default

        override fun getInt(key: String, default: Int): Int = default
        override fun getLong(key: String, default: Long): Long = default
        override fun getDouble(key: String, default: Double): Double = default
        override fun getBoolean(key: String, default: Boolean): Boolean = default
        override fun getStringList(key: String): List<String> = emptyList()
        override fun contains(key: String): Boolean = false
        override fun getObject(key: String): JsonObject? = null
    }
}
