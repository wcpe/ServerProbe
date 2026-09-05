package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.instrument.Instrumentation
import java.lang.reflect.Proxy
import java.nio.file.Paths

/** 三级 Instrumentation 获取链:premain → self-attach → helper 子进程,按序自动选择。 */
class InstrumentationChainTest {

    private fun fakeInstrumentation(): Instrumentation = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Instrumentation::class.java),
    ) { _, _, _ -> null } as Instrumentation

    /** 记录调用序的假 attacher:attach 时记录标签、执行副作用、返回固定结果。 */
    private fun recordingAttacher(
        label: String,
        order: MutableList<String>,
        onAttach: () -> DynamicAttachResult,
    ): DynamicAttacher = object : DynamicAttacher {
        override fun attach(agentJar: java.nio.file.Path): DynamicAttachResult {
            order.add(label)
            return onAttach()
        }
    }

    @Test
    fun `self-attach 被拒后自动尝试 helper 子进程注入`() {
        var current: Instrumentation? = null
        val expected = fakeInstrumentation()
        val order = mutableListOf<String>()
        val access = ArthasInstrumentationAccess(
            { current },
            listOf(
                recordingAttacher("self", order) { DynamicAttachResult(false, "Can not attach to current VM") },
                recordingAttacher("helper", order) {
                    current = expected
                    DynamicAttachResult(true, "helper 子进程注入成功")
                },
            ),
        )

        val result = access.retry(Paths.get("ServerProbe.jar"))

        assertEquals(InstrumentationSource.DYNAMIC_ATTACH, result.source)
        assertTrue(expected === result.instrumentation)
        assertEquals(listOf("self", "helper"), order.toList())
        assertTrue(result.message.contains("helper"), result.message)
    }

    @Test
    fun `helper 也失败时降级原生诊断且消息汇总两级原因`() {
        val order = mutableListOf<String>()
        val access = ArthasInstrumentationAccess(
            { null },
            listOf(
                recordingAttacher("self", order) { DynamicAttachResult(false, "Can not attach to current VM") },
                recordingAttacher("helper", order) { DynamicAttachResult(false, "helper 进程启动失败") },
            ),
        )

        val result = access.retry(Paths.get("ServerProbe.jar"))

        assertEquals(InstrumentationSource.UNAVAILABLE, result.source)
        assertTrue(result.message.contains("Can not attach to current VM"))
        assertTrue(result.message.contains("helper 进程启动失败"))
    }

    @Test
    fun `首个来源成功即自动选择且不执行后续来源`() {
        var current: Instrumentation? = null
        val expected = fakeInstrumentation()
        val order = mutableListOf<String>()
        val access = ArthasInstrumentationAccess(
            { current },
            listOf(
                recordingAttacher("self", order) {
                    current = expected
                    DynamicAttachResult(true, "agentmain 已调用")
                },
                recordingAttacher("helper", order) { throw IllegalStateException("不应执行") },
            ),
        )

        val result = access.retry(Paths.get("ServerProbe.jar"))

        assertEquals(InstrumentationSource.DYNAMIC_ATTACH, result.source)
        assertEquals(listOf("self"), order.toList())
    }
}
