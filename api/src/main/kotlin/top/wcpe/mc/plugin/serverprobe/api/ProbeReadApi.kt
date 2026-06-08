package top.wcpe.mc.plugin.serverprobe.api

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile

/**
 * 只读开放接口(FR8.1)。
 *
 * 面向第三方插件的只读数据访问出口,供其经 TabooLib 服务获取探针数据
 * (最新指标快照、近期指标历史、最近一次启动画像)。
 *
 * 本接口**只读**:仅暴露查询能力,不暴露任何写入/控制能力,确保外部消费方无法影响探针运行。
 */
interface ProbeReadApi {

    /**
     * 获取最新一份指标快照。
     *
     * @return 最新指标快照;尚无任何采样时为 null。
     */
    fun latestSnapshot(): MetricSnapshot?

    /**
     * 获取最近的若干份指标快照。
     *
     * @param limit 期望返回的最大条数。
     * @return 最近的指标快照列表(按时间倒序或正序由实现约定);无数据时为空列表。
     */
    fun recentSnapshots(limit: Int): List<MetricSnapshot>

    /**
     * 获取最近一次的启动画像。
     *
     * @return 最近一次启动画像;无历史记录时为 null。
     */
    fun lastStartupProfile(): StartupProfile?

    /**
     * 获取最近一次启动相对上一次的对比摘要。
     *
     * 由启动监听器在服务器就绪时算出并写入内存(单行人类可读摘要:总时长变化 + 慢插件变化)。
     *
     * @return 最近一次启动相对上一次的对比摘要;首次启动或无上一份画像时为 null。
     */
    fun lastStartupComparisonSummary(): String?
}
