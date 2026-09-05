package top.wcpe.mc.plugin.serverprobe.core.forensics

import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 平台采集器向核心暴露的只读流量窗口。 */
interface PacketTrafficSource {

    /** 提取并清空指定时间窗口内的聚合结果。 */
    fun snapshotTraffic(elapsedMillis: Long): PacketTrafficReport
}

/** 将当前平台的网络聚合统一提供给 Prometheus，绝不接触完整 IP 或载荷。 */
@Service
class PacketTrafficService {

    private val sources = ConcurrentHashMap.newKeySet<PacketTrafficSource>()
    private val lastCollectedAtMs = AtomicLong(0)

    @Volatile
    private var report = EMPTY_REPORT

    private var task: PlatformExecutor.PlatformTask? = null

    /** 注册平台采集源；重复注册由集合自动去重。 */
    fun register(source: PacketTrafficSource) {
        sources += source
    }

    /** 平台卸载时取消注册，避免旧实例继续参与聚合。 */
    fun unregister(source: PacketTrafficSource) {
        sources -= source
    }

    /** 返回最近一个采样窗口的聚合快照，供 Prometheus 渲染。 */
    fun currentReport(): PacketTrafficReport = report

    /** 统一在异步采集周期中提取各平台数据，网络 EventLoop 不参与排序或渲染。 */
    @PostEnable
    fun start() {
        task?.cancel()
        task = submit(period = ProbeConfig.collectPeriodTicks(), async = true) {
            runCatching(::refresh).onFailure { ProbeLogger.warn("刷新网络流量聚合失败:${it.message}") }
        }
    }

    /** 停止任务并移除所有来源引用。 */
    @PreDestroy
    fun stop() {
        task?.cancel()
        task = null
        sources.clear()
        report = EMPTY_REPORT
    }

    /** 仅供同模块单测指定时间窗口，生产路径按真实经过时间计算。 */
    internal fun refreshForTest(elapsedMillis: Long) {
        report = merge(sources.map { it.snapshotTraffic(elapsedMillis.coerceAtLeast(MIN_ELAPSED_MILLIS)) })
    }

    /** 按真实间隔刷新，避免调度漂移导致速率失真。 */
    private fun refresh() {
        val now = System.currentTimeMillis()
        val previous = lastCollectedAtMs.getAndSet(now)
        val elapsed = if (previous == 0L) DEFAULT_ELAPSED_MILLIS else now - previous
        refreshForTest(elapsed)
    }

    /** 合并多个来源后再次执行全局 Top-100，防止跨来源标签总数膨胀。 */
    private fun merge(reports: List<PacketTrafficReport>): PacketTrafficReport {
        val typeCounts = HashMap<String, Long>()
        val ipCounts = HashMap<String, Long>()
        var ingressBytes = 0L
        var egressBytes = 0L
        var ingressPackets = 0L
        var egressPackets = 0L
        reports.forEach { source ->
            ingressBytes += source.ingressBytesPerSecond
            egressBytes += source.egressBytesPerSecond
            ingressPackets += source.ingressPacketsPerSecond
            egressPackets += source.egressPacketsPerSecond
            source.packetTypeCounts.forEach { (key, value) -> typeCounts.merge(key, value, Long::plus) }
            source.maskedIpPacketCounts.forEach { (key, value) -> ipCounts.merge(key, value, Long::plus) }
        }
        return PacketTrafficReport(
            ingressBytes,
            egressBytes,
            ingressPackets,
            egressPackets,
            typeCounts,
            topIps(ipCounts),
        )
    }

    /** 超出全局标签上限的前缀归入 other。 */
    private fun topIps(counts: Map<String, Long>): Map<String, Long> {
        val carriedOther = counts[OTHER_IP_LABEL] ?: 0L
        val sorted = counts.filterKeys { it != OTHER_IP_LABEL }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        val top = sorted.take(MAX_IP_LABELS).associate { it.key to it.value }.toMutableMap()
        val other = carriedOther + sorted.drop(MAX_IP_LABELS).sumOf { it.value }
        if (other > 0) top[OTHER_IP_LABEL] = other
        return top
    }

    private companion object {
        private const val MIN_ELAPSED_MILLIS = 1L
        private const val DEFAULT_ELAPSED_MILLIS = 1_000L
        private const val MAX_IP_LABELS = 100
        private const val OTHER_IP_LABEL = "other"
        private val EMPTY_REPORT = PacketTrafficReport(0, 0, 0, 0, emptyMap(), emptyMap())
    }
}
