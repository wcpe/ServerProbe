package top.wcpe.mc.plugin.serverprobe.api.collector;

import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics;

/**
 * JVM 指标采集器契约。
 *
 * 负责采集一份当前时刻的 {@link JvmMetrics}。由于 JVM 指标全部基于 {@code java.lang.management.*},
 * 全版本 + 全平台通用,其实现位于 core 模块(通用,无需平台分流)。
 */
public interface JvmMetricsCollector {

    /**
     * 采集当前 JVM 指标。
     *
     * @return 当前时刻的 JVM 指标快照。
     */
    JvmMetrics collect();
}
