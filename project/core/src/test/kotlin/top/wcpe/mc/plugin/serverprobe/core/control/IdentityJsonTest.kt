package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [IdentityJson] 编解码单元测试（协议规格 §3.2 的身份文件契约）。
 *
 * 覆盖：字段往返、`capabilities` 缺省语义、版本未知、字段缺失、半写/截断防御，以及
 * **`credential` 完全不透明**（原样往返、不被拆分解析、不出现在不可用原因文案里）。
 */
class IdentityJsonTest {

    /** 正常身份：序列化后再解析应逐字段相等。 */
    @Test
    fun `编解码往返保真`() {
        val identity = sample()

        val decoded = IdentityJson.decode(IdentityJson.encode(identity))

        assertTrue(decoded is IdentityLoadResult.Loaded, "合法身份应解析成功，实际:$decoded")
        assertEquals(identity, (decoded as IdentityLoadResult.Loaded).identity, "逐字段应完全一致")
    }

    /** 字段名必须与协议规格 §3.2 完全一致（写成驼峰，错一个字母对端就不认）。 */
    @Test
    fun `编码字段名符合协议`() {
        val json = IdentityJson.encode(sample())

        listOf("schemaVersion", "provider", "endpoint", "instanceId", "credential", "capabilities", "createdAt")
            .forEach { key -> assertTrue(json.contains("\"$key\""), "缺少协议字段 $key，实际:$json") }
    }

    /**
     * `credential` 必须完全不透明：给它一个"看起来像 JWT"的字符串，读回来必须**逐字节相同**，
     * 且不得被任何形式解析（不拆段、不读内嵌字段）。
     */
    @Test
    fun `凭证原样往返且不被解析`() {
        // 形如 JWT 的凭证：若实现偷偷解析 claims，这里的 payload 内容会泄漏进其它字段
        val jwtLike = "eyJhbGciOiJIUzI1NiJ9.eyJpbnN0YW5jZUlkIjoiZmFrZSJ9.signature-part"
        val identity = sample().copy(credential = jwtLike, provider = "any-vendor", instanceId = "inst-1")

        val loaded = IdentityJson.decode(IdentityJson.encode(identity)) as IdentityLoadResult.Loaded

        assertEquals(jwtLike, loaded.identity.credential, "凭证必须原样往返，不得被改写或解析")
        assertEquals("inst-1", loaded.identity.instanceId, "实例标识只能来自 instanceId 字段，不得从凭证推导")
        assertEquals("any-vendor", loaded.identity.provider, "provider 是数据，不得被凭证内容影响")
    }

    /** 凭证含引号/反斜杠/中文等特殊字符时仍需逐字节往返（转义正确性）。 */
    @Test
    fun `凭证含特殊字符仍原样往返`() {
        val tricky = "to\"ken\\with\n中文\t和\\反斜杠"
        val identity = sample().copy(credential = tricky)

        val loaded = IdentityJson.decode(IdentityJson.encode(identity)) as IdentityLoadResult.Loaded

        assertEquals(tricky, loaded.identity.credential, "转义必须可逆，凭证不得被改写")
    }

    /** `capabilities` 缺失 = 对端未声明能力，按规格"只用基础帧"，不视为损坏。 */
    @Test
    fun `能力位缺失时按空列表处理`() {
        val json = """{"schemaVersion":1,"provider":"p","endpoint":"ws://h:1/ws","instanceId":"i","credential":"c"}"""

        val loaded = IdentityJson.decode(json)

        assertTrue(loaded is IdentityLoadResult.Loaded, "缺少 capabilities 不应判为损坏，实际:$loaded")
        assertEquals(emptyList<String>(), (loaded as IdentityLoadResult.Loaded).identity.capabilities)
    }

    /** 版本未知：视为不可用，并明确报"版本未知"（不允许静默）。 */
    @Test
    fun `未知版本判定为版本未知`() {
        val json = """{"schemaVersion":99,"provider":"p","endpoint":"ws://h:1/ws","instanceId":"i","credential":"c"}"""

        val unusable = IdentityJson.decode(json) as IdentityLoadResult.Unusable

        assertEquals(IdentityUnusableReason.UNKNOWN_SCHEMA_VERSION, unusable.reason)
        assertTrue(unusable.detail.contains("99"), "告警应带上读到的版本号，实际:${unusable.detail}")
    }

    /** 必填字段缺失：判为不可用（字段名可用于告警，但不得带出凭证）。 */
    @Test
    fun `必填字段缺失判定为不可用`() {
        val json = """{"schemaVersion":1,"provider":"p","endpoint":"ws://h:1/ws","instanceId":"i","credential":"  "}"""

        val unusable = IdentityJson.decode(json) as IdentityLoadResult.Unusable

        assertEquals(IdentityUnusableReason.MISSING_FIELD, unusable.reason)
        assertTrue(unusable.detail.contains("credential"), "应指出缺失字段，实际:${unusable.detail}")
    }

    /** 半写/截断文件：解析失败但不抛异常（半写防御）。 */
    @Test
    fun `截断内容判定为格式非法`() {
        val truncated = """{"schemaVersion":1,"provider":"p","endpoint":"ws://h:1/ws","insta"""

        val unusable = IdentityJson.decode(truncated) as IdentityLoadResult.Unusable

        assertEquals(IdentityUnusableReason.MALFORMED, unusable.reason)
    }

    /** 非对象顶层（例如对端误写数组）：判为格式非法。 */
    @Test
    fun `顶层非对象判定为格式非法`() {
        val unusable = IdentityJson.decode("""[1,2,3]""") as IdentityLoadResult.Unusable

        assertEquals(IdentityUnusableReason.MALFORMED, unusable.reason)
    }

    /** 解析失败的原因文案**不得**包含凭证内容（防止把坏文件原文带进日志）。 */
    @Test
    fun `不可用原因不泄漏凭证`() {
        val secret = "super-secret-credential"
        // 故意构造:credential 合法但 schemaVersion 非法,验证告警文案不提凭证
        val json = """{"schemaVersion":2,"provider":"p","endpoint":"ws://h:1/ws","instanceId":"i","credential":"$secret"}"""

        val unusable = IdentityJson.decode(json) as IdentityLoadResult.Unusable

        assertFalse(unusable.detail.contains(secret), "告警文案不得包含凭证内容，实际:${unusable.detail}")
    }

    /** 身份的文本表示必须掩码凭证（防止被顺手打进日志）。 */
    @Test
    fun `身份的文本表示掩码凭证`() {
        val identity = sample().copy(credential = "plain-secret")

        val text = identity.toString()

        assertFalse(text.contains("plain-secret"), "toString 不得包含凭证，实际:$text")
        assertTrue(text.contains("已隐藏"), "toString 应显式说明凭证已隐藏，实际:$text")
    }

    /** 构造一份合法身份样本。 */
    private fun sample(): ControlPlaneIdentity = ControlPlaneIdentity(
        schemaVersion = ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION,
        provider = "sample-vendor",
        endpoint = "ws://127.0.0.1:31001/ws/plugin-bridge",
        instanceId = "0b6f-instance",
        credential = "opaque-credential",
        capabilities = listOf("config_push", "metric_push", "config_state"),
        createdAt = "2026-10-19T03:12:44Z",
    )
}
