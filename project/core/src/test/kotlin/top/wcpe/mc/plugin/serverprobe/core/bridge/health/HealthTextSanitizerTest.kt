package top.wcpe.mc.plugin.serverprobe.core.bridge.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [HealthTextSanitizer] 单元测试（Provider 健康规格 §3.3 的脱敏规则 + §5「脱敏」「回执截断」）。
 *
 * 这类文本来自第三方 Provider，会进回执、日志与面板；规则一旦退化就是凭据泄漏，
 * 因此单独用直白的用例把每条规则钉住。
 */
class HealthTextSanitizerTest {

    /** `token=<值>` → `token=***`。 */
    @Test
    fun `token值被替换`() {
        val output = HealthTextSanitizer.sanitize("连接失败 token=abc123 请检查", 512)

        assertEquals("连接失败 token=*** 请检查", output)
        assertFalse(output.contains("abc123"))
    }

    /** `password=<值>` → `password=***`。 */
    @Test
    fun `password值被替换`() {
        val output = HealthTextSanitizer.sanitize("jdbc:xxx password=hunter2", 512)

        assertEquals("jdbc:xxx password=***", output)
    }

    /** `authorization: <值>` → `authorization: ***`（该值可能含空格，须整段吃掉）。 */
    @Test
    fun `authorization头被替换`() {
        val output = HealthTextSanitizer.sanitize("请求头 authorization: Bearer abc.def.ghi", 512)

        assertEquals("请求头 authorization: ***", output)
        assertFalse(output.contains("Bearer"))
    }

    /** 含 query 的 URL **整段去掉 query**（凭据通常藏在 query，逐键替换挡不住未知键名）。 */
    @Test
    fun `URL的query被整段去掉`() {
        val output = HealthTextSanitizer.sanitize("回调 https://api.example.com/hook?sign=deadbeef&t=1 失败", 512)

        assertEquals("回调 https://api.example.com/hook 失败", output)
    }

    /** 一条文本里的**多个** URL 都要处理（只处理第一个会把后面的凭据原样留下）。 */
    @Test
    fun `多个URL的query都被去掉`() {
        val output = HealthTextSanitizer.sanitize(
            "主 https://a.example.com/x?token=1 备 https://b.example.com/y?token=2",
            512,
        )

        assertEquals("主 https://a.example.com/x 备 https://b.example.com/y", output)
    }

    /** 无 query 的 URL 原样保留。 */
    @Test
    fun `无query的URL原样保留`() {
        assertEquals("回调 https://api.example.com/hook 结束", HealthTextSanitizer.sanitize("回调 https://api.example.com/hook 结束", 512))
    }

    /** 截断按**字符**计并补单字符省略号，结果总长不超过上限。 */
    @Test
    fun `超长文本按字符截断并补省略号`() {
        val output = HealthTextSanitizer.sanitize("幂".repeat(2_000), 512)

        assertEquals(512, output.length, "截断后长度应恰好等于上限")
        assertTrue(output.endsWith("…"), "截断应带省略号")
    }

    /** 未超长时原样返回（不补省略号）。 */
    @Test
    fun `未超长时不截断`() {
        val text = "短原因"

        assertEquals(text, HealthTextSanitizer.sanitize(text, 512))
        assertEquals(text, HealthTextSanitizer.truncateOnly(text, 512))
    }

    /** `maxChars <= 0` 表示不截断（只脱敏）。 */
    @Test
    fun `上限为零表示不截断`() {
        val text = "x".repeat(1_000)

        assertEquals(text, HealthTextSanitizer.sanitize(text, 0))
        assertEquals(text, HealthTextSanitizer.truncateOnly(text, 0))
    }

    /** null / 空串 → 空串（绝不返回 null，回执不必判空）。 */
    @Test
    fun `空值返回空串`() {
        assertEquals("", HealthTextSanitizer.sanitize(null, 512))
        assertEquals("", HealthTextSanitizer.sanitize("", 512))
        assertEquals("", HealthTextSanitizer.truncateOnly(null, 512))
    }

    /**
     * `truncateOnly` 只截断、不脱敏。
     *
     * 用于 `detailJson`：它是结构化 JSON，改写其中内容可能破坏结构，故只做长度约束。
     */
    @Test
    fun `truncateOnly不改写内容`() {
        assertEquals("{\"token\":\"abc123\"}", HealthTextSanitizer.truncateOnly("{\"token\":\"abc123\"}", 512))
    }

    /** 大小写不敏感（Provider 可能写 `TOKEN=` / `Token=`）。 */
    @Test
    fun `键名大小写不敏感`() {
        assertFalse(HealthTextSanitizer.sanitize("TOKEN=abc123", 512).contains("abc123"))
        assertFalse(HealthTextSanitizer.sanitize("Password=abc123", 512).contains("abc123"))
    }
}
