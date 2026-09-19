package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 校验 FR-24 的 Arthas 运行期启停契约：未注册降级、注册转发、按引用注销与幂等。 */
class ArthasRuntimeRegistryTest {

    @Test
    fun `未注册实现时启停降级为安全失败`() {
        val registry = ArthasRuntimeRegistry()

        val started = registry.startRuntime()

        assertFalse(started.available)
        assertTrue(started.message.contains("未注册"))
        assertFalse(registry.runtimeReady)
        // 未注册时卸载必须回报"未卸载"而非抛异常，保证命令在缺装配的平台仍可执行。
        assertFalse(registry.stopRuntime())
    }

    @Test
    fun `注册实现后转发启停并暴露运行状态`() {
        val registry = ArthasRuntimeRegistry()
        val runtime = FakeArthasRuntime()
        registry.register(runtime)

        // 注册本身不触发加载（加载只由显式 startRuntime 驱动）。
        assertEquals(0, runtime.starts)
        assertFalse(registry.runtimeReady)

        assertTrue(registry.startRuntime().available)
        assertTrue(registry.runtimeReady)

        assertTrue(registry.stopRuntime())

        assertEquals(1, runtime.stops)
        assertFalse(registry.runtimeReady)
        // 已卸载后重复卸载回报"未卸载"，供命令层区分"已卸载"与"本就未加载"。
        assertFalse(registry.stopRuntime())
    }

    @Test
    fun `按引用注销后不再转发`() {
        val registry = ArthasRuntimeRegistry()
        val runtime = FakeArthasRuntime()
        registry.register(runtime)
        registry.startRuntime()

        // 非当前实例的注销必须无效（CAS 语义），避免误清他人注册。
        registry.unregister(FakeArthasRuntime())
        assertTrue(registry.runtimeReady)

        registry.unregister(runtime)
        assertFalse(registry.runtimeReady)
        assertFalse(registry.startRuntime().available)
    }

    @Test
    fun `重复注册后者生效`() {
        val registry = ArthasRuntimeRegistry()
        val first = FakeArthasRuntime()
        val second = FakeArthasRuntime()
        registry.register(first)
        registry.register(second)
        registry.startRuntime()

        assertTrue(registry.runtimeReady)
        registry.stopRuntime()

        assertEquals(0, first.starts)
        assertEquals(0, first.stops)
        assertEquals(1, second.starts)
        assertEquals(1, second.stops)
    }

    /** 最小替身：仅记录启停调用次数，供契约转发断言使用。 */
    private class FakeArthasRuntime : ArthasRuntime {

        var starts = 0
        var stops = 0

        override fun startRuntime(): ArthasInstrumentationSnapshot {
            starts += 1
            return ArthasInstrumentationSnapshot(true, "PREMAIN", "已复用 premain Instrumentation")
        }

        override fun stopRuntime(): Boolean {
            if (starts <= stops) return false
            stops += 1
            return true
        }

        override val runtimeReady: Boolean
            get() = starts > stops
    }
}
