package top.wcpe.mc.plugin.serverprobe.api.model

import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform

/**
 * 指标快照:某时刻一组运维指标的采样值。
 *
 * 是探针对外呈现与历史落盘的核心数据单元,聚合了 JVM 指标与(Bukkit 端的)服务器指标。
 *
 * @property schemaVersion 落盘格式版本号,当前(M1)= 1;用于格式演进与向后兼容。
 * @property timestampMs 采样时刻(epoch 毫秒)。
 * @property serverId 实例标识(自动生成或配置覆盖的 server-name)。
 *  实现保证恒有值:配置未指定时自动生成实例 ID(故为非空)。
 * @property platform 采样来源平台。
 * @property jvm JVM 指标(全平台通用,恒不为 null)。
 * @property server 服务器指标;在代理端(BUNGEE 等)为 null —— 代理端无世界/TPS/MSPT 概念。
 * @property proxy 代理端指标;仅代理端非 null,服务端为 null。默认 null 以保持
 *  gson/Configuration 序列化向后兼容(服务端快照不含该字段)。
 */
data class MetricSnapshot(
    val schemaVersion: Int,
    val timestampMs: Long,
    val serverId: String,
    val platform: ProbePlatform,
    val jvm: JvmMetrics,
    val server: ServerMetrics?,
    val proxy: ProxyMetrics? = null
)
