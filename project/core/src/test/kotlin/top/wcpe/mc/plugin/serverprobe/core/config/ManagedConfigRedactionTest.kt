package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 托管生效值脱敏（协议规格 §3.4.3 的 `effective` 标注为“脱敏”）。
 *
 * 这些用例锁的是**漏遮路径**，不是“正常情况能遮”：过去只处理 `Map` 与 `String`，
 * `List` 里的地址、超长串里的查询参数都会整块漏遮，而两者在真实配置里都存在
 * （`alert.channels` 天然是列表）。
 */
class ManagedConfigRedactionTest {

    /** 敏感键名的值整块遮掉。 */
    @Test
    fun `敏感键的值被遮`() {
        val redacted = ManagedConfigRedaction.redact(mapOf("mcp.secret" to "s3cr3t", "server-name" to "srv"))

        assertEquals(ManagedConfigRedaction.MASK, redacted["mcp.secret"])
        assertEquals("srv", redacted["server-name"], "普通键不得误遮")
    }

    /** 嵌套对象递归处理，子键的敏感度按它自己的名字判。 */
    @Test
    fun `嵌套对象的敏感子键被遮`() {
        val redacted = ManagedConfigRedaction.redact(
            mapOf("alert" to mapOf("channels" to mapOf("token" to "t", "name" to "n"))),
        )

        @Suppress("UNCHECKED_CAST")
        val alert = redacted["alert"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val channels = alert["channels"] as Map<String, Any?>
        assertEquals(ManagedConfigRedaction.MASK, channels["token"])
        assertEquals("n", channels["name"])
    }

    /** 列表里的字符串仍要遮查询参数（过去整块漏遮）。 */
    @Test
    fun `列表里的查询参数被遮`() {
        val redacted = ManagedConfigRedaction.redact(
            mapOf("alert.channels.webhook.url" to listOf("https://h/hook?token=abc&x=1", "https://h/ok")),
        )

        @Suppress("UNCHECKED_CAST")
        val urls = redacted["alert.channels.webhook.url"] as List<Any?>
        assertFalse(urls[0].toString().contains("abc"), "列表元素里的 token 必须被遮，实际:${urls[0]}")
        assertTrue(urls[0].toString().contains("https://h/hook?token=***"), "参数名与其余部分保留，实际:${urls[0]}")
        assertEquals("https://h/ok", urls[1])
    }

    /** 列表里的对象按其自身键判敏感度（`[{ "token": "x" }]` 这种形态）。 */
    @Test
    fun `列表里的对象敏感键被遮`() {
        val redacted = ManagedConfigRedaction.redact(
            mapOf("channels" to listOf(mapOf("token" to "abc", "name" to "n"))),
        )

        @Suppress("UNCHECKED_CAST")
        val channels = redacted["channels"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val first = channels[0] as Map<String, Any?>
        assertEquals(ManagedConfigRedaction.MASK, first["token"])
        assertEquals("n", first["name"])
    }

    /** 超长串不再“整体跳过”：首尾两段都扫，密钥不得原文输出。 */
    @Test
    fun `超长串里的密钥仍被遮`() {
        val padding = "a".repeat(4_000)
        val redacted = ManagedConfigRedaction.redact(
            mapOf(
                "big" to "https://h/hook?token=HEAD_SECRET&p=$padding",
                "big-tail" to "$padding&secret=TAIL_SECRET",
            ),
        )

        val head = redacted["big"].toString()
        val tail = redacted["big-tail"].toString()
        assertFalse(head.contains("HEAD_SECRET"), "超长串头部的密钥必须被遮")
        assertFalse(tail.contains("TAIL_SECRET"), "超长串尾部的密钥必须被遮")
    }
}
