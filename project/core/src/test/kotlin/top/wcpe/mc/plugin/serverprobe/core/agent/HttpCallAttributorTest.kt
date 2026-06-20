package top.wcpe.mc.plugin.serverprobe.core.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.HttpCall

/**
 * [HttpCallAttributor] 单元测试:校验 ClassLoader 身份优先、包前缀兜底的归因。
 */
class HttpCallAttributorTest {

    private fun call(frames: List<String>, loaderHashes: List<Int> = emptyList()) = HttpCall.builder()
        .seq(1L).startRelNanos(0L).durationMs(1L).method("TCP").responseCode(-1).error(false)
        .host("h").url("h:1").headers(emptyList()).callerFrames(frames).loaderHashes(loaderHashes)
        .build()

    /** ClassLoader 身份命中时优先返回该插件(即便包前缀也能匹配到别的)。 */
    @Test
    fun `ClassLoader 身份优先`() {
        val c = call(
            frames = listOf("com.mysql.cj.protocol.StandardSocketFactory#connect"),
            loaderHashes = listOf(111, 222)
        )
        val byHash = mapOf(222 to "CoreLib")
        assertEquals("CoreLib", HttpCallAttributor.attribute(c, byHash, emptyList()), "应据 CL 身份归因到 CoreLib")
    }

    /** ClassLoader 未命中时回退到包前缀匹配。 */
    @Test
    fun `回退包前缀`() {
        val c = call(
            frames = listOf("java.net.Socket#connect", "com.foo.bar.Db#query"),
            loaderHashes = listOf(999)
        )
        val prefixes = listOf("FooPlugin" to "com.foo")
        assertEquals("FooPlugin", HttpCallAttributor.attribute(c, emptyMap(), prefixes), "CL 未命中应回退包前缀")
    }

    /** 两者皆不命中返回空串。 */
    @Test
    fun `皆不命中返回空串`() {
        val c = call(frames = listOf("io.netty.X#run"), loaderHashes = listOf(7))
        assertEquals("", HttpCallAttributor.attribute(c, mapOf(8 to "X"), listOf("Y" to "com.y")), "皆不命中应为空串")
    }

    /** attributeByFrames:自栈顶向下命中首个前缀;更长前缀(调用方降序传入)优先。 */
    @Test
    fun `按帧前缀匹配`() {
        val frames = listOf("java.net.URL#openStream", "me.foo.bar.sub.X#m")
        val prefixes = listOf("Sub" to "me.foo.bar.sub", "Bar" to "me.foo.bar")
        assertEquals("Sub", HttpCallAttributor.attributeByFrames(frames, prefixes), "更长前缀应优先")
        assertEquals("", HttpCallAttributor.attributeByFrames(frames, emptyList()), "空前缀表应为空串")
    }
}
