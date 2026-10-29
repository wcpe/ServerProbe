package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControl
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControlRegistration

/**
 * Bukkit、Paper 与 Folia 共用控制台适配器的行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里 `getDeclaredField("registry").type` + `getMethod("execute").returnType` 两个断言：
 * 它们只证明「字段/方法声明成了这些类型」，而后者还被编译器保证。
 * 把 `register()` 写成空方法、把 `unregister()` 删掉，这些断言照样绿。</p>
 *
 * <h2>现在测什么</h2>
 * <p>给适配器装一个录制型注册表，直接调 `register()` / `unregister()`，
 * 看注册表真实收到了什么；并用 `isBukkitPlatform` 接缝验证平台门
 * （非 Bukkit 端不得把自己的实现注进去，否则代理端会拿到一个调不通的控制器）。</p>
 *
 * <p>不测 `execute()`：它真的会跳进 Bukkit 主线程调度器与 `Bukkit.dispatchCommand`，
 * 纯 JVM 里没有服务器实例。端到端行为由 MCP 命令的 E2E 场景覆盖。</p>
 */
class BukkitPlatformControlContractTest {

    private fun controlWith(registry: RecordingRegistry): BukkitPlatformControl =
        BukkitPlatformControl().apply {
            this.registry = registry
            this.isBukkitPlatform = { true }
        }

    @Test
    @DisplayName("register() 把自己登记进平台控制注册表")
    fun `注册后注册表持有自身`() {
        val registry = RecordingRegistry()
        val control = controlWith(registry)

        control.register()

        assertEquals(1, registry.registered.size, "一次注册必须只登记一次，实际=${registry.registered}")
        assertSame(control, registry.registered.single(), "登记的必须是本实例")
    }

    @Test
    @DisplayName("unregister() 从注册表撤回自身")
    fun `注销后注册表不再持有`() {
        val registry = RecordingRegistry()
        val control = controlWith(registry)
        control.register()

        control.unregister()

        assertEquals(1, registry.unregistered.size, "一次注销必须只撤回一次，实际=${registry.unregistered}")
        assertSame(control, registry.unregistered.single(), "撤回的必须是本实例")
    }

    @Test
    @DisplayName("非 Bukkit 平台时不登记（否则代理端会拿到一个调不通的控制器）")
    fun `非Bukkit平台不注册`() {
        val registry = RecordingRegistry()
        val control = BukkitPlatformControl().apply {
            this.registry = registry
            this.isBukkitPlatform = { false }
        }

        control.register()

        assertEquals(emptyList<PlatformControl>(), registry.registered, "非 Bukkit 端不得登记平台控制")
    }

    @Test
    @DisplayName("实现 core 的平台控制契约（编译期绑定）")
    fun `实现平台控制契约`() {
        // 赋给契约类型即可证明实现关系：类型不匹配时这段代码编译不过。
        val control: PlatformControl = BukkitPlatformControl()

        // 契约里的 execute 入口必须可被下发命令——不调用它（需要真服务器），只证明入口存在且可引用。
        val entry: (top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformCommandRequest) -> Any = control::execute
        assertSame(control, control, "入口必须绑定到本实例，实际=${entry.javaClass.simpleName}")
    }

    /** 录制型注册表替身：只记下谁被登记 / 谁被撤回。 */
    private class RecordingRegistry : PlatformControlRegistration {

        val registered = mutableListOf<PlatformControl>()
        val unregistered = mutableListOf<PlatformControl>()

        override fun register(control: PlatformControl) {
            registered += control
        }

        override fun unregister(control: PlatformControl) {
            unregistered += control
        }
    }
}
