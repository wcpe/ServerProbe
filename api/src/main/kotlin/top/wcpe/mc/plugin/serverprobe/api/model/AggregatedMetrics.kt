package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 指标聚合结果(FR3.3):对**近 N 份指标快照**做跨快照统计后的派生值。
 *
 * 与单份 [MetricSnapshot](某一时刻的瞬时采样)不同,本对象描述一个**时间窗口**内的趋势/速率:
 * - 均值/分位类字段(`tpsAvg`/`msptP95`/`msptP99`)对窗口内**各份快照的代表值**做统计;
 * - 速率类字段(`gc*RatePerSec`)取窗口**最旧与最新两端**的单调累计量做差分,再除以时间跨度(秒)。
 *
 * 所有数值字段均可空:**为 null 表示该项在本窗口内不可计算或无数据**(例如窗口内无任一快照含 TPS、
 * 或速率差分所需的样本不足 2 份)。呈现层据此降级为 N/A。
 *
 * 口径说明(为何这样取,便于排查):
 * - `tpsAvg`:窗口内各快照 `server.tick.tps1m` 非 null 者的算术平均——即"近 N 个周期 1 分钟 TPS 的均值",
 *   平滑单点抖动,反映窗口内的整体流畅度。
 * - `msptP95`/`msptP99`:把窗口内各快照已算好的 `server.tick.msptP95`/`msptP99`(每个周期的代表分位)
 *   组成样本,再对**这些"周期代表值"**取分位。注意:这是"分位的分位",并非对原始逐 tick 样本重新求分位
 *   (原始 tick 样本不跨快照保留),用于回看"近 N 个周期里偏慢周期的水平"。
 * - `gc*RatePerSec`:GC 次数/耗时为自 JVM 启动以来的单调累计量;取窗口两端差分 ÷ 时间差(秒)得到
 *   "该窗口内的平均 GC 速率"。窗口跨进程重启时累计量会回绕变小,差分为负——此时按不可计算返回 null。
 *
 * @property windowSampleCount 实际参与聚合的快照份数(`<= 请求的 windowSize`);为 0 表示窗口内无任何快照。
 * @property tpsAvg 窗口内 1 分钟 TPS 的算术平均;无可用 TPS 样本时为 null。
 * @property msptP95 窗口内"各周期 MSPT p95 代表值"的 p95 分位(毫秒);无可用样本时为 null。
 * @property msptP99 窗口内"各周期 MSPT p99 代表值"的 p99 分位(毫秒);无可用样本时为 null。
 * @property gcYoungRatePerSec 年轻代 GC 次数速率(次/秒);样本不足或不可计算时为 null。
 * @property gcOldRatePerSec 老年代 GC 次数速率(次/秒);样本不足或不可计算时为 null。
 * @property gcYoungTimeRatePerSec 年轻代 GC 耗时速率(毫秒/秒,即每秒 GC 暂停毫秒数);不可计算时为 null。
 * @property gcOldTimeRatePerSec 老年代 GC 耗时速率(毫秒/秒,即每秒 GC 暂停毫秒数);不可计算时为 null。
 */
data class AggregatedMetrics(
    val windowSampleCount: Int,
    val tpsAvg: Double?,
    val msptP95: Double?,
    val msptP99: Double?,
    val gcYoungRatePerSec: Double?,
    val gcOldRatePerSec: Double?,
    val gcYoungTimeRatePerSec: Double?,
    val gcOldTimeRatePerSec: Double?
)
