package top.wcpe.mc.plugin.serverprobe.bukkit.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 启动画像两条装配路径的防重行为测试。
 *
 * <h2>为什么不靠反射、也不读源码</h2>
 * <p>以前这里用反射查 `assemblyTriggered` 字段是不是 `AtomicBoolean`、
 * 再用 `getDeclaredMethod` 证明“方法存在且无参”——那些都是编译器或反射已经保证的恒真式，
 * 一个把防重删掉的实现同样能通过。</p>
 *
 * <h2>真正要守的不变量</h2>
 * <p>1.12+ 走 `ServerLoadEvent`，1.8–1.11 走 ACTIVE 首帧兜底。两条路径必须
 * <b>只装配一次</b>——否则启动画像被写两遍，历史对比全部失真。</p>
 *
 * <p>本类通过 `snapshotInputs` / `dispatchAssembly` 两个接缝把 Bukkit 依赖与调度
 * 换成可控实现，从而真正驱动两条入口、数它们各自派发了多少次装配。</p>
 */
class StartupProfileFallbackContractTest {

    /** 记录装配被派发了几次，并同步执行（不依赖 TabooLib 调度器）。 */
    private class CountingSetup(private val listener: StartupLoadListener) {
        var dispatched = 0

        fun install() {
            // 本类是 object，防重标记是进程级状态；每个用例开始前必须重置，
            // 否则第一条用例装配后其余用例永远拿到 0。
            listener.resetAssemblyTriggeredForTest()
            listener.snapshotInputs = { Triple(1000L, listOf("world"), "1.20.1") }
            // 同步执行，让 buildAndStore 的“被调用次数”可数；
            // buildAndStore 失败无妨（无 MetricStore 注入时会降级），我们只数次数。
            listener.dispatchAssembly = { task ->
                dispatched++
                runCatching { task() }
            }
        }
    }

    @Test
    @DisplayName("事件路径触发一次装配")
    fun `事件路径触发一次`() {
        val listener = StartupLoadListener
        val setup = CountingSetup(listener)
        setup.install()

        listener.onServerLoadEvent(startup = true)

        assertEquals(1, setup.dispatched, "STARTUP 事件应触发一次装配")
    }

    @Test
    @DisplayName("RELOAD 事件不触发装配（热重载不得被当成一次启动）")
    fun `RELOAD不触发`() {
        val listener = StartupLoadListener
        val setup = CountingSetup(listener)
        setup.install()

        listener.onServerLoadEvent(startup = false)

        assertEquals(0, setup.dispatched, "RELOAD 不得触发装配，否则启动画像会被热重载污染")
    }

    @Test
    @DisplayName("事件路径已装配后，ACTIVE 兜底不再重复装配（防重核心）")
    fun `两条路径只装配一次`() {
        val listener = StartupLoadListener
        val setup = CountingSetup(listener)
        setup.install()

        // 模拟 1.12+：事件路径先到
        listener.onServerLoadEvent(startup = true)
        // 随后 ACTIVE 首帧兜底也触发
        listener.onActiveFallback()

        assertEquals(
            1,
            setup.dispatched,
            "两条路径必须共享同一防重标记，总共只装配一次；重复装配会让启动画像被写两遍",
        )
    }

    @Test
    @DisplayName("重复的事件回调不会重复装配")
    fun `重复事件不重复装配`() {
        val listener = StartupLoadListener
        val setup = CountingSetup(listener)
        setup.install()

        listener.onServerLoadEvent(startup = true)
        listener.onServerLoadEvent(startup = true)
        listener.onServerLoadEvent(startup = true)

        assertEquals(1, setup.dispatched, "同一进程内启动只能装配一次")
    }

    @Test
    @DisplayName("装配输入来自 snapshotInputs 接缝（耗时/世界名/版本可被替换）")
    fun `装配输入来自接缝`() {
        val listener = StartupLoadListener
        var capturedTotalMs = -1L
        var capturedWorlds: List<String> = emptyList()

        listener.snapshotInputs = {
            capturedTotalMs = 12345L
            capturedWorlds = listOf("world", "world_nether")
            Triple(12345L, capturedWorlds, "1.20.1")
        }
        listener.dispatchAssembly = { task -> runCatching { task() } }

        listener.onServerLoadEvent(startup = true)

        assertEquals(12345L, capturedTotalMs, "总耗时应由接缝提供（生产环境是就绪时刻实测值）")
        assertEquals(listOf("world", "world_nether"), capturedWorlds, "世界名应由接缝提供")
    }
}
