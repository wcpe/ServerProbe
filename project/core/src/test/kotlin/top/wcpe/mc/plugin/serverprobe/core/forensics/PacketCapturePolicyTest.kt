package top.wcpe.mc.plugin.serverprobe.core.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/** 数据包载荷白名单、截断与哈希的纯逻辑测试。 */
class PacketCapturePolicyTest {

    /** 普通白名单包应保存至上限、保留完整长度与原包哈希。 */
    @Test
    fun `白名单包按上限截断并保留原始哈希`() {
        val payload = "abcdef".toByteArray()
        val result = PacketCapturePolicy(setOf("LoginPayload"), emptySet(), 3)
            .capture("LoginPayload", null, payload)

        assertNotNull(result.payloadBase64)
        assertEquals(Base64.getEncoder().encodeToString("abc".toByteArray()), result.payloadBase64)
        assertEquals(6, result.originalLength)
        assertTrue(result.truncated)
        assertEquals(sha256(payload), result.sha256)
    }

    /** 非白名单包只有元数据，不得保留完整载荷。 */
    @Test
    fun `非白名单包不保存载荷`() {
        val result = PacketCapturePolicy(setOf("LoginPayload"), emptySet(), 64)
            .capture("KeepAlive", null, byteArrayOf(1, 2))

        assertNull(result.payloadBase64)
        assertFalse(result.truncated)
    }

    /** Plugin Message 必须同时命中类型和通道双白名单。 */
    @Test
    fun `插件消息要求双白名单`() {
        val policy = PacketCapturePolicy(setOf("PluginMessage"), setOf("serverprobe:debug"), 64)

        assertNull(policy.capture("PluginMessage", "other:channel", byteArrayOf(1)).payloadBase64)
        assertNotNull(policy.capture("PluginMessage", "serverprobe:debug", byteArrayOf(1)).payloadBase64)
    }

    /** 网络线程分块计算摘要后，策略入口只保留有界候选载荷。 */
    @Test
    fun `预计算摘要入口不要求复制完整原始包`() {
        val original = "abcdef".toByteArray()
        val result = PacketCapturePolicy(setOf("LoginPayload"), emptySet(), 3)
            .capture("LoginPayload", null, original.size, sha256(original), "abc".toByteArray())

        assertEquals(Base64.getEncoder().encodeToString("abc".toByteArray()), result.payloadBase64)
        assertEquals(original.size, result.originalLength)
        assertEquals(sha256(original), result.sha256)
        assertTrue(result.truncated)
    }

    /** 默认 64KiB 上限仍须保留完整原始长度与 SHA-256。 */
    @Test
    fun `64KiB 默认上限截断时保留完整长度与哈希`() {
        val original = ByteArray(65_537) { 0x5a }
        val result = PacketCapturePolicy(setOf("PluginMessage"), setOf("serverprobe:test"), 65_536)
            .capture("PluginMessage", "serverprobe:test", original)

        assertEquals(65_537, result.originalLength)
        assertTrue(result.truncated)
        assertEquals(sha256(original), result.sha256)
    }

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }
}
