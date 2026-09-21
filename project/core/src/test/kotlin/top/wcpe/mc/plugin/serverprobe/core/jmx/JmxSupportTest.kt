package top.wcpe.mc.plugin.serverprobe.core.jmx

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * [JmxSupport] 取方法路径回归:CPU 指标必须经**导出接口** `com.sun.management.OperatingSystemMXBean` 读取。
 *
 * 该接口的实现类位于未导出包(JDK 9+ 为 `com.sun.management.internal.*`),对实现类方法调用
 * `setAccessible(true)` 会被模块系统以 `InaccessibleObjectException` 拒绝,指标恒为哨兵 -1.0
 * (JDK 21 实机已证)。用例以"运行时是否真的提供该指标"为基准:基准可用时必须取到真实值。
 */
class JmxSupportTest {

    @Test
    fun `进程 CPU 指标不得因 JPMS 阻断回退为哨兵值`() {
        val reference = exportedInterfaceProcessCpuLoad()
        assumeTrue(reference >= 0.0, "当前运行时不提供进程 CPU 指标,跳过")

        val actual = JmxSupport.processCpuLoad()
        assertTrue(actual >= 0.0, "导出接口可读时 processCpuLoad 不得为哨兵 -1.0,实际 $actual")
    }

    /** 经导出接口直接读同一指标,作为"当前运行时是否提供该指标"的基准。 */
    private fun exportedInterfaceProcessCpuLoad(): Double = runCatching {
        val type = Class.forName("com.sun.management.OperatingSystemMXBean")
        type.getMethod("getProcessCpuLoad").invoke(ManagementFactory.getOperatingSystemMXBean()) as Double
    }.getOrDefault(-1.0)
}
