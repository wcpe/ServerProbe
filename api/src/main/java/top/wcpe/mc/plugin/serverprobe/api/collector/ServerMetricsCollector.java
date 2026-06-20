package top.wcpe.mc.plugin.serverprobe.api.collector;

import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics;

/**
 * 服务器指标采集器契约。
 *
 * 负责采集一份当前时刻的 {@link ServerMetrics}(TPS/MSPT、在线人数、运行时长等)。
 * 该类指标依赖平台 API,其实现位于 platform-bukkit 模块。
 */
public interface ServerMetricsCollector {

    /**
     * 采集当前服务器指标。
     *
     * @return 当前时刻的服务器指标快照。
     */
    ServerMetrics collect();
}
