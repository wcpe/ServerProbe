package top.wcpe.mc.plugin.serverprobe.core.jmx

import java.lang.management.ManagementFactory

/**
 * JMX CPU 指标读取支持(反射封装,容忍 JDK 差异)。
 *
 * 进程/系统 CPU 占用率并非 JDK 标准 [java.lang.management.OperatingSystemMXBean] 的能力,
 * 而是其平台扩展接口 `com.sun.management.OperatingSystemMXBean` 提供的:
 * - `getProcessCpuLoad()`:当前 JVM 进程 CPU 占用率;
 * - `getSystemCpuLoad()`:系统整体 CPU 占用率(JDK 14+ 标记为 `@Deprecated` 但仍存在可用,
 *   并新增同义的 `getCpuLoad` 作为推荐替代)。
 *
 * 由于 `com.sun.management` 并非所有 JDK 实现都对外开放(如部分定制运行时),为避免在不提供该
 * 扩展的环境下出现 `NoClassDefFoundError` / 链接错误,本类一律通过**反射**在 MXBean 实例上调用,
 * 任何异常、方法缺失或返回值非 [Double] 的情况都统一回退为 -1.0。
 *
 * 约定:**返回 -1.0 表示当前 JDK 不提供该指标**;其余返回值落在 0.0–1.0 区间(占用率)。
 *
 * 本类为无副作用、无状态的纯反射工具,以 `object` 实现且不纳入 IOC 容器。
 */
object JmxSupport {

    /** 表示"当前 JDK 不提供该指标"的哨兵值。 */
    private const val UNAVAILABLE = -1.0

    /** 操作系统 MXBean 实例;运行期具体类型通常为 `com.sun.management.OperatingSystemMXBean` 的实现。 */
    private val osBean = ManagementFactory.getOperatingSystemMXBean()

    /**
     * 读取当前 JVM 进程的 CPU 占用率。
     *
     * @return 进程 CPU 占用率(0.0–1.0);当前 JDK 不提供时为 -1.0。
     */
    fun processCpuLoad(): Double = invokeDoubleMethod("getProcessCpuLoad")

    /**
     * 读取系统整体的 CPU 占用率。
     *
     * 优先调用 `getSystemCpuLoad`——该方法自 JDK 14+ 虽被标记 `@Deprecated`,但仍存在且可正常返回有效值,
     * 因此实际首选它即可拿到结果;仅当它确实不可用(方法缺失或调用异常)时,才回退到 JDK 14+ 新增的
     * 同义方法 `getCpuLoad`(下方回退分支在主流 JDK 上几乎为死代码,仅作极端环境的防御)。
     *
     * @return 系统 CPU 占用率(0.0–1.0);当前 JDK 不提供时为 -1.0。
     */
    fun systemCpuLoad(): Double {
        val legacy = invokeDoubleMethod("getSystemCpuLoad")
        if (legacy != UNAVAILABLE) {
            return legacy
        }
        // 旧名确实不可用(方法缺失或异常)时才走到这里,回退 JDK 14+ 的新名作极端防御
        return invokeDoubleMethod("getCpuLoad")
    }

    /**
     * 反射调用 [osBean] 上指定的无参方法并取其 [Double] 返回值。
     *
     * 任何阶段失败(方法不存在、调用异常、返回值非 Double)均回退为 [UNAVAILABLE],
     * 以保证在不提供 `com.sun.management` 扩展的运行时上调用方仍能安全取数。
     *
     * @param methodName 目标无参方法名。
     * @return 方法返回的占用率;不可用时为 -1.0。
     */
    private fun invokeDoubleMethod(methodName: String): Double {
        return try {
            val method = osBean.javaClass.getMethod(methodName)
            // 扩展接口的实现类多为非 public,需放开访问以便反射调用
            method.isAccessible = true
            val result = method.invoke(osBean)
            (result as? Double) ?: UNAVAILABLE
        } catch (ignored: Throwable) {
            // 该 JDK 不提供此扩展指标:吞掉链接/反射异常并以哨兵值表达"不可用",属预期容错而非错误
            UNAVAILABLE
        }
    }
}
