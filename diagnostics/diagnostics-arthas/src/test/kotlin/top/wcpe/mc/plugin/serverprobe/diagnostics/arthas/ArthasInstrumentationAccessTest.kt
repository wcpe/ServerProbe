package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.instrument.Instrumentation
import java.lang.reflect.Proxy
import java.nio.file.Paths

class ArthasInstrumentationAccessTest {

    @Test
    fun `优先复用 premain 已保存的 Instrumentation`() {
        val instrumentation = instrumentation()
        var attachments = 0
        val access = ArthasInstrumentationAccess({ instrumentation }, attacher { attachments += 1; DynamicAttachResult(false, "不应执行") })

        val result = access.acquire(Paths.get("ServerProbe.jar"))

        assertEquals(InstrumentationSource.PREMAIN, result.source)
        assertSame(instrumentation, result.instrumentation)
        assertEquals(0, attachments)
    }

    @Test
    fun `动态 attach 失败后降级且普通读取不重复尝试`() {
        var attachments = 0
        val access = ArthasInstrumentationAccess({ null }, attacher { attachments += 1; DynamicAttachResult(false, "当前 JVM 禁止自挂载") })

        val first = access.acquire(Paths.get("ServerProbe.jar"))
        val second = access.acquire(Paths.get("ServerProbe.jar"))

        assertEquals(InstrumentationSource.UNAVAILABLE, first.source)
        assertEquals(InstrumentationSource.UNAVAILABLE, second.source)
        assertTrue(first.message.contains("当前 JVM 禁止自挂载"))
        assertEquals(1, attachments)
    }

    @Test
    fun `显式重试在 agentmain 回填 Instrumentation 后恢复`() {
        var current: Instrumentation? = null
        var attachments = 0
        val expected = instrumentation()
        val access = ArthasInstrumentationAccess({ current }, attacher {
            attachments += 1
            current = expected
            DynamicAttachResult(true, "agentmain 已调用")
        })

        val result = access.retry(Paths.get("ServerProbe.jar"))

        assertEquals(InstrumentationSource.DYNAMIC_ATTACH, result.source)
        assertSame(expected, result.instrumentation)
        assertEquals(1, attachments)
    }

    private fun instrumentation(): Instrumentation = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Instrumentation::class.java),
    ) { _, _, _ -> null } as Instrumentation

    private fun attacher(block: () -> DynamicAttachResult): DynamicAttacher = object : DynamicAttacher {
        override fun attach(agentJar: java.nio.file.Path): DynamicAttachResult = block()
    }
}
