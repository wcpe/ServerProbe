package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LocalListenerGate] 单测（协议规格 §3.6「零监听端口」）。
 *
 * 只验证纯判定：**托管模式**下三个监听面全部关断，**独立模式**下全部放行。
 * "真的没有 bind"由 `ManagedListenerZeroBindTest` 经可注入监听器工厂断言。
 */
class LocalListenerGateTest {

    /** 托管模式：metrics / mcp / web 三个监听面一律不 bind。 */
    @Test
    fun `托管模式下三个监听面一律关断`() {
        LocalListenerGate.Surface.values().forEach { surface ->
            assertFalse(LocalListenerGate.allows(surface, ProbeMode.MANAGED), "${surface.label} 在托管模式下不得 bind")
        }
    }

    /** 独立模式：三个监听面照旧按配置启停（闸门恒放行，零回归）。 */
    @Test
    fun `独立模式下三个监听面一律放行`() {
        LocalListenerGate.Surface.values().forEach { surface ->
            assertTrue(LocalListenerGate.allows(surface, ProbeMode.STANDALONE), "${surface.label} 在独立模式下应放行")
        }
    }

    /** 判定读的是**运行模式**：切换模式立即改变结论（配置值不参与判断）。 */
    @Test
    fun `判定依据是运行模式`() {
        ControlPlaneRuntime.reset()
        assertEquals(ProbeMode.STANDALONE, ControlPlaneRuntime.mode())
        assertTrue(LocalListenerGate.allows(LocalListenerGate.Surface.METRICS))

        ControlPlaneRuntime.publish(ProbeMode.MANAGED, null)
        assertFalse(LocalListenerGate.allows(LocalListenerGate.Surface.METRICS), "发布托管模式后应立即关断")

        ControlPlaneRuntime.reset()
        assertTrue(LocalListenerGate.allows(LocalListenerGate.Surface.METRICS), "回落独立模式后应恢复放行")
    }
}
