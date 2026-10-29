package top.wcpe.mc.plugin.serverprobe.api.read;

import top.wcpe.mc.plugin.serverprobe.api.model.metrics.AggregatedMetrics;
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot;

/**
 * 指标只读能力(子能力)。
 *
 * 本接口是**指标快照**一类只读能力:取最新一份快照、回看内存近期历史、
 * 以及对近期历史做跨快照聚合。数据源均为内存中的采样缓冲,不做任何写入。
 *
 * 消费者一般不必直接依赖本接口,而是经聚合接口
 * {@link top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi} 或运行期门面
 * `top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI#read()` 取得同一实例。
 *
 * 本接口**只读**:仅暴露查询能力,不暴露任何写入/控制能力。
 */
public interface MetricsReadApi {

    /**
     * 获取最新一份指标快照。
     *
     * @return 最新指标快照;尚无任何采样时为 null。
     */
    MetricSnapshot latestSnapshot();

    /**
     * 获取最近的若干份指标快照。
     *
     * @param limit 期望返回的最大条数。
     * @return 最近的指标快照列表(按时间倒序或正序由实现约定);无数据时为空列表。
     */
    java.util.List<MetricSnapshot> recentSnapshots(int limit);

    /**
     * 获取**内存近期缓冲**中时间戳不早于 {@code sinceMs} 的指标快照(M2 扩面)。
     *
     * 仅在内存近期历史缓冲上按时间下界筛选({@code MetricSnapshot.getTimestampMs() >= sinceMs}),
     * **不触发任何采集、不读盘**,故为轻量内存操作,可在主线程安全调用。
     * 能回看的时间跨度受近期缓冲容量限制:早于缓冲最旧一份的数据不在此返回(需读历史归档见落盘 SPI)。
     *
     * @param sinceMs 时间下界(epoch 毫秒,含);早于此刻的快照被过滤掉。
     * @return 缓冲内不早于 {@code sinceMs} 的快照列表(顺序由实现约定);无满足项时为空列表。
     */
    java.util.List<MetricSnapshot> recentSnapshotsSince(long sinceMs);

    /**
     * 对最近 {@code windowSize} 份指标快照做跨快照聚合。
     *
     * 在内存近期历史上就近计算趋势/速率(TPS 均值、MSPT 分位、GC 速率),不触发任何采集或落盘 IO。
     * 聚合口径见 {@link AggregatedMetrics} 各字段的 KDoc。
     *
     * @param windowSize 聚合窗口大小(期望纳入的最近快照份数);非正时按无数据处理。
     * @return 聚合结果(恒非空);窗口内无任何快照时 {@code AggregatedMetrics.getWindowSampleCount()} 为 0。
     *  单项不可计算(如无 TPS 样本、速率差分样本不足)时对应字段为 null。
     */
    AggregatedMetrics aggregated(int windowSize);

    /**
     * 读取指定时间范围内的**历史归档**指标快照。
     *
     * 从历史后端(本地文件或第三方存储 SPI)读取 {@code MetricSnapshot.getTimestampMs()} 落在
     * {@code [sinceMs, untilMs]}(闭区间)内的快照,**由新到旧**排序、保留范围内最新的至多 {@code limit} 条
     * (内置本地文件后端按时间升序遍历,此处统一反转为由新到旧;第三方后端无论返回顺序,实现层同样归一)。
     * 与 {@link #recentSnapshotsSince(long)} 的区别:本方法读的是**落盘历史**(可回看超出内存缓冲的跨度),
     * 后者仅筛内存近期缓冲(轻量、不读盘)。
     *
     * **可能读盘**(本地文件后端会定位日期范围内的历史文件并逐行解析),因此**调用方宜在异步上下文调用**,
     * 避免阻塞主线程(规范 R7)。
     *
     * 默认返回空列表,以保持既有第三方实现本接口时的二进制与源码兼容(与网络取证查询同款范式)。
     *
     * @param sinceMs 时间范围下界(epoch 毫秒,含)。
     * @param untilMs 时间范围上界(epoch 毫秒,含)。
     * @param limit 期望返回的最大条数;非正时返回空列表。
     * @return 范围内历史快照列表(由新到旧);无数据或后端不支持时为空列表。
     */
    default java.util.List<MetricSnapshot> historySnapshots(long sinceMs, long untilMs, int limit) {
        return java.util.Collections.emptyList();
    }
}
