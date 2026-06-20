package top.wcpe.mc.plugin.serverprobe.api.collector;

import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics;

/**
 * 代理端指标采集器契约。
 *
 * 负责采集一份当前时刻的 {@link ProxyMetrics}(代理总在线、各子服在线)。
 * 该类指标依赖代理端平台 API,其实现位于 platform-bungee 模块。
 */
public interface ProxyMetricsCollector {

    /**
     * 采集当前代理端指标。
     *
     * @return 当前时刻的代理端指标快照。
     */
    ProxyMetrics collect();
}
