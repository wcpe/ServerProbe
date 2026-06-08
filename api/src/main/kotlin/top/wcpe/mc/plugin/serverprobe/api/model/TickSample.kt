package top.wcpe.mc.plugin.serverprobe.api.model

import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource

/**
 * 服务器 tick 采样数据(TPS/MSPT)。
 *
 * TPS 为每秒 tick 数(理想 20),MSPT 为单 tick 耗时(毫秒,>50ms 即掉 tick)。
 * 所有数值字段均可空:**为 null 表示该项 N/A**(例如 Folia 无全局 TPS,采用 per-region 语义),
 * 具体不可用原因可结合 [source] 判断。
 *
 * per-region 明细将于 M2 以纯增字段补充,本对象表示全局视角(Folia 下全字段为 null)。
 *
 * @property tps1m 最近 1 分钟平均 TPS;N/A 时为 null。
 * @property tps5m 最近 5 分钟平均 TPS;N/A 时为 null。
 * @property tps15m 最近 15 分钟平均 TPS;N/A 时为 null。
 * @property msptAvg MSPT 平均值(毫秒);N/A 时为 null。
 * @property msptP95 MSPT p95 分位(毫秒);N/A 时为 null。
 * @property msptP99 MSPT p99 分位(毫秒);N/A 时为 null。
 * @property source 本次采样的数据来源。
 */
data class TickSample(
    val tps1m: Double?,
    val tps5m: Double?,
    val tps15m: Double?,
    val msptAvg: Double?,
    val msptP95: Double?,
    val msptP99: Double?,
    val source: TickSampleSource
)
