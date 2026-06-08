package top.wcpe.mc.plugin.serverprobe.core.collector

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [JvmMetricsCollector] 单元测试。
 *
 * [JvmMetricsCollector.collect] 仅依赖 `java.lang.management.*` 与反射,不依赖注入的 registry,
 * 故可直接实例化、不经 IOC 容器进行验证。断言聚焦于"在任意可运行 JVM 上都应成立"的稳定不变量,
 * 不对 CPU 占用率、GC 次数等取值波动的指标做具体数值约束。
 */
class JvmMetricsCollectorTest {

    /** collect() 应返回一份各核心字段满足基本不变量的快照。 */
    @Test
    fun `collect 返回满足基本不变量的快照`() {
        val metrics = JvmMetricsCollector().collect()

        // 堆已用/已提交在任意运行中的 JVM 上必为正(heapMaxBytes 可能为 -1,故改以已用/已提交断言)
        assertTrue(metrics.heapUsedBytes > 0, "堆已使用字节应大于 0")
        assertTrue(metrics.heapCommittedBytes > 0, "堆已提交字节应大于 0")

        // 非堆已用为累计量,任意运行中的 JVM 上恒非负
        assertTrue(metrics.nonHeapUsedBytes >= 0, "非堆已使用字节应不小于 0")

        // JVM 启动参数列表恒非 null(可能为空列表)
        assertNotNull(metrics.jvmArgs, "JVM 启动参数列表不应为 null")

        // 内存池与 GC 收集器在标准 JVM 上恒非空
        assertTrue(metrics.memoryPools.isNotEmpty(), "内存池明细不应为空")
        assertTrue(metrics.gcCollectors.isNotEmpty(), "GC 收集器明细不应为空")

        // young/old 为按收集器名归类的派生聚合,其总次数必等于逐收集器明细之和(锁核心不变量)
        assertTrue(
            metrics.gcYoungCount + metrics.gcOldCount == metrics.gcCollectors.sumOf { it.collectionCount },
            "young+old 派生聚合次数应等于 gcCollectors 明细之和"
        )

        // 线程与类加载计数必为正
        assertTrue(metrics.threadCount > 0, "线程数应大于 0")
        assertTrue(metrics.loadedClassCount > 0, "已加载类数应大于 0")

        // 运行时:启动时刻为绝对时间戳应为正,运行时长恒非负
        assertTrue(metrics.startTimeMs > 0, "JVM 启动时刻应大于 0")
        assertTrue(metrics.uptimeMs >= 0, "JVM 运行时长应不小于 0")

        // CPU 占用率:有数据时为占用率,无数据时为哨兵值 -1.0,故下界恒为 -1.0
        assertTrue(metrics.processCpuLoad >= -1.0, "进程 CPU 占用率应不小于 -1.0")
        assertTrue(metrics.systemCpuLoad >= -1.0, "系统 CPU 占用率应不小于 -1.0")
    }

    /** 连续两次采集均不应抛出异常。 */
    @Test
    fun `连续两次 collect 不抛异常`() {
        val collector = JvmMetricsCollector()
        collector.collect()
        collector.collect()
    }
}
