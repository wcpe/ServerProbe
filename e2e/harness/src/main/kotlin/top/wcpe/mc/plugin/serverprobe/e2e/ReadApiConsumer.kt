package top.wcpe.mc.plugin.serverprobe.e2e

import top.wcpe.mc.plugin.serverprobe.ServerProbeStorageApi
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.ObservedRegionMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStoreRegistration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 的真实第三方消费者。
 *
 * 此类只有在 ServerProbe 已加载后才被 harness 调用，以兼容必须先启动回环 fixture 的加载顺序。
 */
object ReadApiConsumer {

    /**
     * 读取 Incision 织入证据；尚未就绪时返回 null。
     *
     * <p>这是“切点真的被织入并执行了”的**唯一真机判据**：
     * `incisionEnabled=true` 只说明配置开了，`incisionActive=true` 才说明 Incision 真的把
     * `SimplePluginManager#enablePlugin` 织上了、而且至少执行过一次切点。</p>
     *
     * <p>返回 null（而非直接失败）的两种情形：画像还没产出、或画像里 `incisionActive` 仍为 false
     * ——后者留给轮询窗口内的后续尝试，因为织入生效可能晚于首个画像。</p>
     */
    fun readIncisionEvidence(): Map<String, String>? {
        val profile = ServerProbeAPI.read().lastStartupProfile() ?: return null
        if (profile.incisionEnabled != true) {
            // 配置档就没打开：这是部署错误，不是“还没就绪”，故直接抛而不是静默重试。
            error("启动画像显示 incisionEnabled=false，模板未开启 Incision")
        }
        if (profile.incisionActive != true) {
            return null
        }
        val timings = profile.incisionPluginEnableTimings
        check(!timings.isNullOrEmpty()) { "incisionActive=true 但逐插件耗时为空，切点未真正记录样本" }
        check(timings.all { it.enableMs >= 0L }) { "存在负的 onEnable 耗时:${timings.map { it.name to it.enableMs }}" }
        return mapOf(
            "incisionEnabled" to "true",
            "incisionActive" to "true",
            "plugins" to timings.size.toString(),
            // 取最慢一条作为可视证据，便于人工核对名字与量级合理。
            "slowest" to timings.maxByOrNull { it.enableMs }?.let { "${it.name}@${it.enableMs}ms" }.orEmpty(),
        )
    }

    /** 读取公开门面；数据尚未产出时返回 null，完成后严格校验 TPS、MSPT 与启动画像。 */
    fun readEvidence(): Map<String, String>? {
        val api = ServerProbeAPI.read()
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
        val api = ServerProbeAPI.read() ?: return null
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
    fun readObservedRegionEvidence(): Map<String, String>? {
        val server = ServerProbeAPI.read().latestSnapshot()?.server ?: return null
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

    /**
     * 按「运行期 region id + chunk 坐标」读取两个稳定观测对象。
     *
     * **不能只用 region id**：一个 Folia region 可以覆盖多 chunk，同一次 tick 会为它产生多条
     * `ObservedRegionMetrics`（生产侧用 internalId+worldName+centerChunkX+centerChunkZ 四元组做身份，
     * 见 `ObservedRegionTracker.RegionKey`）。只用 id 取 `firstOrNull` 会拿到任意一条—
     * 很可能是别的 chunk 的样本，该 chunk 并未被我们的负载命中，p95 抬升就被摊薄。
     */
    fun observedRegionPair(
        targetId: Long,
        controlId: Long,
        targetChunkX: Int = Int.MIN_VALUE,
        targetChunkZ: Int = Int.MIN_VALUE,
        controlChunkX: Int = Int.MIN_VALUE,
        controlChunkZ: Int = Int.MIN_VALUE,
    ): FoliaRegionPair? {
        if (targetId == controlId && targetChunkX == controlChunkX && targetChunkZ == controlChunkZ) {
            return null
        }
        val regions = ServerProbeAPI.read().latestSnapshot()?.server?.observedRegions.orEmpty()
        val target = regions.firstOrNull {
            it.foliaRegionId == targetId && it.matchesChunk(targetChunkX, targetChunkZ) && it.isStableObservedRegion()
        } ?: return null
        val control = regions.firstOrNull {
            it.foliaRegionId == controlId && it.matchesChunk(controlChunkX, controlChunkZ) && it.isStableObservedRegion()
        } ?: return null
        return FoliaRegionPair(target, control)
    }

    /** 未提供坐标（默认哨兵值）时退化为只比 id；提供了就必须严格匹配。 */
    private fun ObservedRegionMetrics.matchesChunk(chunkX: Int, chunkZ: Int): Boolean =
        (chunkX == Int.MIN_VALUE || centerChunkX == chunkX) &&
            (chunkZ == Int.MIN_VALUE || centerChunkZ == chunkZ)

    /** 场景超时时输出最小化 region 状态，便于区分身份任务与公开快照未形成。 */
    fun observedRegionDiagnostic(targetId: Long, controlId: Long): String {
        val server = ServerProbeAPI.read().latestSnapshot()?.server ?: return "公开服务端快照不可用"
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
        val tick = ServerProbeAPI.read().latestSnapshot()?.server?.tick ?: return null
        return tick.tps1m == null && tick.tps5m == null && tick.tps15m == null &&
            tick.msptAvg == null && tick.msptP95 == null && tick.msptP99 == null
    }

    /** 已观测 region 的保留窗口过期后，明细与世界汇总应同时清空。 */
    fun observedRegionsExpired(): Boolean {
        val server = ServerProbeAPI.read().latestSnapshot()?.server ?: return false
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
