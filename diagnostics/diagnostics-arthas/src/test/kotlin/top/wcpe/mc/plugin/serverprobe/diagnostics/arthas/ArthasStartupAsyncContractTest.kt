package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasInstrumentationSnapshot
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasRuntime
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasRuntimeRegistration

/**
 * Arthas 启动期加载的异步契约(见 Issue #18)。
 *
 * 加载含运行包解包与 Instrumentation 附加(必要时 spawn helper 子进程注入,等待上限 30s);
 * 若在 `@PostEnable start()`(插件启用线程 = 服务器主线程)同步执行,即违反“主线程禁止阻塞
 * 磁盘 IO / 外部进程”的项目红线——命令路径早已异步化,启动路径必须在同一范式内。
 *
 * ## 为什么是行为断言
 * 这两个契约曾被写成“把源码读成文本、用 contains 比对某一行”——改个缩进就假失败，
 * 而真正的语义（启动不阻塞、卸载后不加载）一点没验。现在改为直接驱动行为：
 * 注入捕获型调度器与计数型加载器，断言的是**调用序列**与**返回值**，与代码长什么样无关。
 */
class ArthasStartupAsyncContractTest {

    /** 受测对象；每个用例新建，避免字段级接缝互相污染。 */
    private lateinit var lifecycle: ArthasDiagnosticsLifecycle

    /** 调度器实际交出的任务（捕获后由用例决定何时执行）。 */
    private lateinit var dispatched: MutableList<() -> Unit>

    /** 真实加载动作被调用了几次。 */
    private var loadCount = 0

    @BeforeEach
    fun setUp() {
        lifecycle = ArthasDiagnosticsLifecycle()
        lifecycle.runtimeRegistration = RecordingRuntimeRegistration()
        lifecycle.autoLoadGate = { true }
        // 配置就绪必须也打桩：真实实现是 ProbeConfig.awaitLoaded()，
        // 无平台环境下 conf 永远注入不了，会空转 5 秒后返回 false，
        // 导致 loadAfterStartup 根本不执行——测试就会把「加载 0 次」
        // 误报成「实现没异步化」。
        lifecycle.configReadyGate = { true }
        dispatched = mutableListOf()
        lifecycle.autoLoadScheduler = { task -> dispatched += task }
        loadCount = 0
        lifecycle.runtimeLoader = {
            loadCount++
            ArthasInstrumentationSnapshot(true, "ATTACHED", "测试桩加载成功")
        }
    }

    @Test
    fun `启动期只把加载任务交给异步调度器 不在启用线程上执行`() {
        lifecycle.start()

        assertEquals(1, dispatched.size, "启动期必须把加载交给调度器一次")
        assertEquals(0, loadCount, "start() 不得在调用线程上同步执行加载（启用线程 = 服务器主线程）")

        dispatched.single().invoke()

        assertEquals(1, loadCount, "调度器执行后应真正加载一次")
    }

    @Test
    fun `卸载后调度到的加载必须被拒绝`() {
        lifecycle.start()
        lifecycle.stop()

        val snapshot = lifecycle.loadAfterStartup()

        assertFalse(snapshot.available, "卸载后不得报告加载成功")
        assertEquals(0, loadCount, "卸载后不得再触碰运行时加载（避免残留非 daemon 线程阻止 JVM 退出）")
    }

    @Test
    fun `未卸载时同一入口正常加载`() {
        val snapshot = lifecycle.loadAfterStartup()

        assertTrue(snapshot.available, "未卸载时应实际加载")
        assertEquals(1, loadCount)
    }

    /** 只记录注册/注销调用的运行时注册表：`stop()` 会走到 `unregister`。 */
    private class RecordingRuntimeRegistration : ArthasRuntimeRegistration {

        /** 注册过的运行时（按出现顺序）。 */
        val registered = mutableListOf<ArthasRuntime>()

        override fun register(runtime: ArthasRuntime) {
            registered += runtime
        }

        override fun unregister(runtime: ArthasRuntime) {
            registered -= runtime
        }
    }
}
