package top.wcpe.mc.plugin.serverprobe.e2e

import top.wcpe.mc.plugin.serverprobe.ServerProbeApi
import top.wcpe.mc.plugin.serverprobe.ServerProbeStorageApi
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStoreRegistration
import java.util.concurrent.atomic.AtomicInteger

/**
 * FR8 的真实第三方消费者。
 *
 * 此类只有在 ServerProbe 已加载后才被 harness 调用，以兼容 FR9 必须先启动回环 fixture 的加载顺序。
 */
object Fr8Consumer {

    /** 读取公开门面；数据尚未产出时返回 null，完成后严格校验 TPS、MSPT 与启动画像。 */
    fun readEvidence(): Map<String, String>? {
        val api = ServerProbeApi.read() ?: return null
        val snapshot = api.latestSnapshot() ?: return null
        val profile = api.lastStartupProfile() ?: return null
        val tick = checkNotNull(snapshot.server?.tick) { "快照缺少服务端 Tick 指标" }
        checkNotNull(tick.tps1m) { "快照缺少 TPS 指标" }
        checkNotNull(tick.msptAvg) { "快照缺少 MSPT 指标" }
        check(profile.createdAtMs > 0L) { "启动画像创建时间非法" }
        return mapOf("profileAt" to profile.createdAtMs.toString(), "tps" to tick.tps1m.toString(), "mspt" to tick.msptAvg.toString())
    }

    /**
     * FR-26 历史回读证据：在**异步上下文**调用 `historySnapshots`（该 API 明示"可能读盘、宜异步"，
     * 主线程调用不进 E2E），并与**落盘文件**逐条对照——由新到旧、条数上限、时间戳必须能在文件里找到。
     *
     * 与文件对照而非只查非空，是为了把"回读确实来自落盘"证死：只看非空无法区分内存回读与磁盘回读，
     * 而本场景要验的正是落盘侧。
     *
     * @param metricsRoot 探针指标历史根目录（`plugins/ServerProbe/data/metrics`）。
     * @param limit 回读条数上限。
     * @return 证据键值；历史尚未落盘（采集周期未到）或回读为空时返回 null，由 harness 继续轮询。
     */
    fun readHistoryEvidence(metricsRoot: java.io.File, limit: Int): Map<String, String>? {
        val api = ServerProbeApi.read() ?: return null
        val latest = api.latestSnapshot() ?: return null
        val persisted = api.historySnapshots(0L, System.currentTimeMillis(), limit)
        if (persisted.isEmpty()) {
            return null
        }
        val timestamps = persisted.map { it.timestampMs }
        check(timestamps == timestamps.sortedDescending()) { "historySnapshots 未按由新到旧返回：$timestamps" }
        check(persisted.size <= limit) { "historySnapshots 返回条数超出 limit：${persisted.size} > $limit" }
        check(persisted.all { it.serverId == latest.serverId }) { "historySnapshots 混入了其它实例的快照" }
        val onDisk = persistedTimestamps(metricsRoot, latest.serverId)
        check(onDisk.isNotEmpty()) { "未找到落盘的指标历史文件：$metricsRoot" }
        // 只落了一份时"由新到旧"是空校验：要求至少两份再判定，不足则按未就绪继续轮询（等下一个采集周期）。
        if (onDisk.size < MIN_HISTORY_SNAPSHOTS) {
            return null
        }
        check(timestamps.all(onDisk::contains)) { "回读时间戳不在落盘文件中：回读=$timestamps 落盘=$onDisk" }
        return mapOf(
            "historyCount" to persisted.size.toString(),
            "historyNewestAt" to timestamps.first().toString(),
            "historyFileSnapshots" to onDisk.size.toString(),
            "historyMatched" to timestamps.count(onDisk::contains).toString(),
        )
    }

    /** 读取该实例当天指标历史文件里的快照时间戳（由新到旧）；目录/文件缺失时返回空列表。 */
    private fun persistedTimestamps(metricsRoot: java.io.File, serverId: String): List<Long> {
        val directory = java.io.File(metricsRoot, serverId)
        val file = directory.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith("metrics-") && candidate.name.endsWith(".jsonl")
        }?.maxByOrNull { it.name } ?: return emptyList()
        return file.readLines()
            .mapNotNull { line -> TIMESTAMP_PATTERN.find(line)?.groupValues?.get(1)?.toLongOrNull() }
            .sortedDescending()
    }

    /** 读取真实 Folia 已观测 region，要求区域明细和世界聚合均已形成。 */
    fun readObservedRegionEvidence(): Map<String, String>? {        val server = ServerProbeApi.read()?.latestSnapshot()?.server ?: return null
        val regions = server.observedRegions.orEmpty()
        val worlds = server.observedRegionWorlds.orEmpty()
        val stableRegions = regions.filter {
            it.isObserved && it.playerCount > 0 && it.sampleCount >= 2L && it.msptAvg != null && it.tpsAvg != null
        }
        if (stableRegions.distinctBy { "${it.worldName}:${it.centerChunkX}:${it.centerChunkZ}" }.size < 2) return null
        val stableWorlds = worlds.filter { it.activeRegions >= 1 && it.sampleCount >= MIN_REGION_SAMPLES && it.msptAvg != null && it.tpsAvg != null }
        if (stableWorlds.size < REQUIRED_OBSERVED_WORLDS) return null
        return mapOf(
            "regions" to stableRegions.size.toString(),
            "worlds" to stableWorlds.size.toString(),
            "samples" to stableWorlds.sumOf { it.sampleCount }.toString(),
        )
    }

    /** 按 Folia 运行期 region id 读取两个稳定观测对象，避免用坐标猜测归属。 */
    fun observedRegionPair(targetId: Long, controlId: Long): FoliaRegionPair? {
        if (targetId == controlId) {
            return null
        }
        val regions = ServerProbeApi.read()?.latestSnapshot()?.server?.observedRegions.orEmpty()
        val target = regions.firstOrNull { it.foliaRegionId == targetId && it.isStableObservedRegion() } ?: return null
        val control = regions.firstOrNull { it.foliaRegionId == controlId && it.isStableObservedRegion() } ?: return null
        return FoliaRegionPair(target, control)
    }

    /** 场景超时时输出最小化 region 状态，便于区分身份任务与公开快照未形成。 */
    fun observedRegionDiagnostic(targetId: Long, controlId: Long): String {
        val server = ServerProbeApi.read()?.latestSnapshot()?.server ?: return "公开服务端快照不可用"
        val regions = server.observedRegions.orEmpty()
        val samples = regions.take(MAX_DIAGNOSTIC_REGIONS).joinToString(";") {
            "id=${it.foliaRegionId},玩家=${it.playerCount},样本=${it.sampleCount}"
        }
        return "目标=$targetId,对照=$controlId,公开region数=${regions.size},样本=[$samples]"
    }

    private fun ObservedRegionMetrics.isStableObservedRegion(): Boolean =
        isObserved && playerCount > 0 && sampleCount >= MIN_REGION_SAMPLES && msptP95 != null && tpsAvg != null

    /** 同一轮验收中目标 region 与对照 region 的公开快照。 */
    data class FoliaRegionPair(
        val target: ObservedRegionMetrics,
        val control: ObservedRegionMetrics,
    )

    /** Folia 的全局 Tick 值必须保持 N/A，不能退回 Paper 的伪全局读数。 */
    fun foliaGlobalTickUnavailable(): Boolean? {
        val tick = ServerProbeApi.read()?.latestSnapshot()?.server?.tick ?: return null
        return tick.tps1m == null && tick.tps5m == null && tick.tps15m == null &&
            tick.msptAvg == null && tick.msptP95 == null && tick.msptP99 == null
    }

    /** 已观测 region 的保留窗口过期后，明细与世界汇总应同时清空。 */
    fun observedRegionsExpired(): Boolean {
        val server = ServerProbeApi.read()?.latestSnapshot()?.server ?: return false
        return server.observedRegions.orEmpty().isEmpty() && server.observedRegionWorlds.orEmpty().isEmpty()
    }

    /** 安装记录型 MetricStore，返回供 harness 观察真实调用与关闭注册的会话。 */
    fun installStorage(): Any {
        val store = RecordingMetricStore()
        return StorageSession(store, ServerProbeStorageApi.install(store))
    }

    /** 将会话保持为无 API 类型的句柄，避免 harness 在 ServerProbe 加载前解析公开 SPI。 */
    fun startupWrites(session: Any): Int = requireSession(session).startupWrites()

    /** 读取记录型存储收到的指标历史写入次数。 */
    fun historyWrites(session: Any): Int = requireSession(session).historyWrites()

    /** 关闭第三方注册，使 ServerProbe 立即恢复默认本地存储。 */
    fun closeStorage(session: Any) {
        requireSession(session).close()
    }

    /** 只接受本测试创建的会话，避免把无效对象误当作存储注册。 */
    private fun requireSession(session: Any): StorageSession = session as? StorageSession
        ?: error("第三方存储会话尚未创建")

    /** 记录型存储不落测试磁盘，只计数 ServerProbe 通过 SPI 发生的真实调用。 */
    class RecordingMetricStore : MetricStore {

        val startupWrites = AtomicInteger()
        val historyWrites = AtomicInteger()

        override fun saveStartupProfile(profile: StartupProfile) {
            startupWrites.incrementAndGet()
        }

        override fun lastStartupProfile(): StartupProfile? = null

        override fun appendHistory(snapshot: MetricSnapshot) {
            historyWrites.incrementAndGet()
        }
    }

    /** 将第三方存储实例与其注册句柄收敛为最小的 E2E 观察接口。 */
    class StorageSession(
        private val store: RecordingMetricStore,
        private val registration: MetricStoreRegistration,
    ) : AutoCloseable {

        fun startupWrites(): Int = store.startupWrites.get()

        fun historyWrites(): Int = store.historyWrites.get()

        override fun close() {
            registration.close()
        }
    }

    /** 历史回读判定所需的最少落盘快照数:一份时"由新到旧"是空校验。 */
    private const val MIN_HISTORY_SNAPSHOTS = 2

    /** 指标历史 JSONL 行的时间戳取值;快照的顶层字段先于嵌套对象出现,取首个匹配即可。 */
    private val TIMESTAMP_PATTERN = Regex("\"timestampMs\"\\s*:\\s*(\\d+)")

    private const val MIN_REGION_SAMPLES = 2L
    private const val REQUIRED_OBSERVED_WORLDS = 2
    private const val MAX_DIAGNOSTIC_REGIONS = 8
}
