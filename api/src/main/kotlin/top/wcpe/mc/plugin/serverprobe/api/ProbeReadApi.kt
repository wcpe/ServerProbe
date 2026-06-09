package top.wcpe.mc.plugin.serverprobe.api

import top.wcpe.mc.plugin.serverprobe.api.model.AggregatedMetrics
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
     * 获取**内存近期缓冲**中时间戳不早于 [sinceMs] 的指标快照(M2 FR8 扩面)。
     *
     * 仅在内存近期历史缓冲上按时间下界筛选([MetricSnapshot.timestampMs] >= [sinceMs]),
     * **不触发任何采集、不读盘**,故为轻量内存操作,可在主线程安全调用。
     * 能回看的时间跨度受近期缓冲容量限制:早于缓冲最旧一份的数据不在此返回(需读历史归档见落盘 SPI)。
     *
     * @param sinceMs 时间下界(epoch 毫秒,含);早于此刻的快照被过滤掉。
     * @return 缓冲内不早于 [sinceMs] 的快照列表(顺序由实现约定);无满足项时为空列表。
     */
    fun recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot>

    /**
     * 对最近 [windowSize] 份指标快照做跨快照聚合(FR3.3)。
     *
     * 在内存近期历史上就近计算趋势/速率(TPS 均值、MSPT 分位、GC 速率),不触发任何采集或落盘 IO。
     * 聚合口径见 [AggregatedMetrics] 各字段的 KDoc。
     *
     * @param windowSize 聚合窗口大小(期望纳入的最近快照份数);非正时按无数据处理。
     * @return 聚合结果(恒非空);窗口内无任何快照时 [AggregatedMetrics.windowSampleCount] 为 0。
     *  单项不可计算(如无 TPS 样本、速率差分样本不足)时对应字段为 null。
     */
    fun aggregated(windowSize: Int): AggregatedMetrics

    /**
     * 获取最近一次的启动画像。
     *
     * @return 最近一次启动画像;无历史记录时为 null。
     */
    fun lastStartupProfile(): StartupProfile?

    /**
     * 读取**历史归档**的若干份启动画像(M2 FR8 扩面)。
     *
     * 从落盘存储后端读取历次启动画像归档,按时间由新到旧返回至多 [limit] 份。
     * **可能读盘**(本地文件后端会遍历归档目录并逐份反序列化),开销随归档份数增长;
     * 因此**调用方宜在异步上下文调用**,避免阻塞主线程(规范 R7)。
     * 无可用后端或无归档时返回空列表。
     *
     * @param limit 期望返回的最大份数;非正时返回空列表。
     * @return 历史启动画像列表(由新到旧);无数据或不支持时为空列表。
     */
    fun historyStartupProfiles(limit: Int): List<StartupProfile>

    /**
     * 获取最近一次启动相对上一次的对比摘要。
     *
     * 由启动监听器在服务器就绪时算出并写入内存(单行人类可读摘要:总时长变化 + 慢插件变化)。
     *
     * @return 最近一次启动相对上一次的对比摘要;首次启动或无上一份画像时为 null。
     */
    fun lastStartupComparisonSummary(): String?
}
