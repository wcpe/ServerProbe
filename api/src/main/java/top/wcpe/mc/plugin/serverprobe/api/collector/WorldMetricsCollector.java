package top.wcpe.mc.plugin.serverprobe.api.collector;

import top.wcpe.mc.plugin.serverprobe.api.model.WorldMetrics;

/**
 * 世界指标采集器契约(FR2.3)。
 *
 * 负责采集当前时刻所有世界的 {@link WorldMetrics}(已加载区块数、实体数、方块实体数等)。
 * 该类指标依赖平台 API,其实现位于 platform-bukkit 模块;读取 Bukkit 世界数据必须在主线程,
 * 故实现侧通常以限频主线程任务采样并缓存,{@link #collect} 仅返回最近一次缓存结果(供异步编排读取)。
 */
public interface WorldMetricsCollector {

    /**
     * 采集当前所有世界的指标。
     *
     * @return 各世界指标列表;尚无缓存时为空列表。
     */
    java.util.List<WorldMetrics> collect();
}
