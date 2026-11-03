package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.core.json.Json

/**
 * `metric_snapshot` 上行帧的编码器（协议规格 §3.4.4，字段对照见 §7.2）。
 *
 * ## 只做投影，不新写采集
 * `metrics` 的每一项都从**现有指标快照** [MetricSnapshot] 取——这正是 `/metrics` 端点
 * （[top.wcpe.mc.plugin.serverprobe.core.prometheus.PrometheusTextFormatter]）渲染同一份快照的来源。
 * 因此推送值与抓取值天然一致，不会出现"两边各算一套"的漂移（协议规格 §3.4.4 关键约束）。
 *
 * ## 输出是帧树而不是文本
 * [of] 返回 `Map` 帧树，JSON 编码由调用方经项目统一适配器 [Json] 完成（ADR-14：不手写 JSON）。
 * 这样帧结构可被单测直接逐字段断言，不依赖运行期 JSON 后端。
 *
 * ## 缺失即不产出（与 `/metrics` 同惯例）
 * `/metrics` 对不可用字段（null 的 TPS/MSPT、`-1`/`-1.0` 哨兵）**不输出该时间序列**；
 * 本帧沿用同一惯例，缺项直接不写该键，绝不填一个误导性的 0。
 *
 * ## 单位口径
 * - `msptAvg` / `msptP95` 与 `/metrics` 的 `serverprobe_mspt_seconds` **同口径（秒）**：
 *   规格 §7.2 明确"秒 → 毫秒换算在 Worker 侧进行，保持原逻辑"，故探针侧不做换算。
 * - `uptimeSeconds` 取 JVM 运行时长（对应 `serverprobe_uptime_seconds`）。
 * - `heapUsed` / `heapMax` 为字节；`gcTimeMillis` 为毫秒；`cpuLoad` 为 0~1。
 */
object MetricSnapshotFrame {

    /** 帧内 `event` 子类型。 */
    const val EVENT = "metric_snapshot"

    /** 毫秒 → 秒换算因子（与 `/metrics` 的 `*_seconds` 口径一致）。 */
    private const val MILLIS_PER_SECOND = 1000.0

    /**
     * 构造一帧 `metric_snapshot` 的**帧树**（编码为 JSON 由调用方按项目统一适配器 [Json] 完成）。
     *
     * @param instance 实例标识（同时用于 `dedupKey`）。
     * @param tsSeconds 采样时刻（epoch **秒**，协议规格 §3.4.4 的 `ts` 口径）。
     * @param sequence 进程内单调递增序号。
     * @param snapshot 指标快照；调用方应保证非空（为空时不推帧，避免发空指标）。
     * @return 有序帧树（可被 [Json.encode] 直接序列化为紧凑单行 JSON）。
     */
    fun of(
        instance: String,
        tsSeconds: Long,
        sequence: Long,
        snapshot: MetricSnapshot?,
        senderStatus: SenderStatus? = null,
    ): Map<String, Any?> = linkedMapOf(
        "type" to "event",
        "event" to EVENT,
        // 与其它上行事件一致地自报实例；Worker 侧亦可直接用连接上的实例
        "instance" to instance,
        // 去重锚点：协议规格 §3.4.4 的 `metric:<instanceId>:<ts>`
        "dedupKey" to "metric:$instance:$tsSeconds",
        "payload" to linkedMapOf(
            "ts" to tsSeconds,
            "sequence" to sequence,
            "metrics" to metrics(snapshot, senderStatus),
        ),
    )

    /**
     * 按协议规格 §7.2 的对照表把快照投影为 `metrics` 对象。
     *
     * 逐项对应关系：
     * | `/metrics` 指标 | 本帧字段 |
     * |---|---|
     * | `serverprobe_tps{window="1m"}` | [KEY_TPS] |
     * | `serverprobe_mspt_seconds{quantile="avg"}` | [KEY_MSPT_AVG]（秒） |
     * | `serverprobe_mspt_seconds{quantile="p95"}` | [KEY_MSPT_P95]（秒） |
     * | `serverprobe_players_online`（代理端回退 `proxy_players_online`） | [KEY_PLAYERS_ONLINE] |
     * | `serverprobe_heap_used_bytes` / `serverprobe_heap_max_bytes` | [KEY_HEAP_USED] / [KEY_HEAP_MAX] |
     * | `serverprobe_threads` | [KEY_THREADS] |
     * | `serverprobe_system_cpu_load` | [KEY_CPU_LOAD] |
     * | `serverprobe_uptime_seconds` | [KEY_UPTIME_SECONDS] |
     * | `serverprobe_world_loaded_chunks` / `_entities` / `_tile_entities`{world=} | [KEY_WORLDS] |
     * | `serverprobe_gc_count_total` / `serverprobe_gc_time_seconds_total` 汇总 | [KEY_GC_COUNT_TOTAL] / [KEY_GC_TIME_MILLIS] |
     *
     * @param snapshot 指标快照；null 时返回空对象。
     * @return 有序的 `metrics` 键值表。
     */
    fun metrics(snapshot: MetricSnapshot?, senderStatus: SenderStatus? = null): Map<String, Any?> {
        val metrics = linkedMapOf<String, Any?>()
        if (snapshot == null) {
            return metrics
        }
        appendTick(metrics, snapshot)
        appendPlayers(metrics, snapshot)
        appendJvm(metrics, snapshot)
        appendGc(metrics, snapshot)
        // 桥自身的中转指标：与上面几项不同，它不来自 MetricSnapshot，而是发送器的运行状态。
        // 不并进 snapshot 是因为后者是“服务器 / JVM 的快照”，而这是“通道的快照”，
        // 两者的生命周期、采样时机都不同，混在一起会让采集层职责变浑。
        senderStatus?.let { appendBridge(metrics, it) }
        return metrics
    }

    /**
     * 桥（业务通道）自身的中转指标。
     *
     * ## 为何这几项必须上报
     *
     * 本通道的约定是「不可靠 / 幂等 / 不堵塞」：丢弃是**设计内的降级**，
     * 但「丢了多少、什么时候开始丢」必须可观测——静默丢数据比丢数据本身更危险。
     * `bridge_status` 只能看当下这一瞬，回答不了「下午三点那次高峰堵了没有」。
     *
     * ## 为什么计数传累计值而不是速率
     *
     * 速率需要时间窗口，而探针的推送间隔可能变化；在探针侧算速率会把
     * 「间隔变化」误读成「流量变化」。差分交给 CP —— 它有固定的心跳节奏。
     */
    private fun appendBridge(metrics: MutableMap<String, Any?>, status: SenderStatus) {
        metrics[KEY_BRIDGE_RELIABLE_QUEUE] = status.reliableQueue
        metrics[KEY_BRIDGE_BEST_EFFORT_QUEUE] = status.bestEffortQueue
        metrics[KEY_BRIDGE_RELIABLE_REJECTED_TOTAL] = status.reliableRejected
        metrics[KEY_BRIDGE_BEST_EFFORT_DROPPED_TOTAL] = status.bestEffortDropped
        metrics[KEY_BRIDGE_RECEIPT_REJECTED_TOTAL] = status.commandResultRejected
        metrics[KEY_BRIDGE_SPILL_DROPPED_TOTAL] = status.spillDropped
        metrics[KEY_BRIDGE_WRITE_FAILURES_TOTAL] = status.writeFailures
        metrics[KEY_BRIDGE_WRITE_TIMEOUTS_TOTAL] = status.writeTimeouts
    }

    /** TPS（取 1m 窗口）与 MSPT 分位（秒，与 `/metrics` 同口径）。 */
    private fun appendTick(metrics: MutableMap<String, Any?>, snapshot: MetricSnapshot) {
        val tick = snapshot.server?.tick ?: return
        // TPS 只取 1m 窗口：对照表明确 `serverprobe_tps{window="1m"}`
        tick.tps1m?.let { metrics[KEY_TPS] = it }
        // MSPT：快照是毫秒，/metrics 导出秒；此处保持 /metrics 的口径，换算交给 Worker
        tick.msptAvg?.let { metrics[KEY_MSPT_AVG] = it / MILLIS_PER_SECOND }
        tick.msptP95?.let { metrics[KEY_MSPT_P95] = it / MILLIS_PER_SECOND }
    }

    /** 在线人数：服务端优先，代理端回退 `proxy_players_online`。 */
    private fun appendPlayers(metrics: MutableMap<String, Any?>, snapshot: MetricSnapshot) {
        val online = snapshot.server?.onlinePlayers ?: snapshot.proxy?.totalOnline ?: return
        metrics[KEY_PLAYERS_ONLINE] = online
    }

    /** JVM 指标：堆、线程、CPU、运行时长，以及各世界计数。 */
    private fun appendJvm(metrics: MutableMap<String, Any?>, snapshot: MetricSnapshot) {
        val jvm = snapshot.jvm
        metrics[KEY_HEAP_USED] = jvm.heapUsedBytes
        // -1 表示无上限：/metrics 对该哨兵不导出序列，这里同样不写字段
        if (jvm.heapMaxBytes >= 0) {
            metrics[KEY_HEAP_MAX] = jvm.heapMaxBytes
        }
        metrics[KEY_THREADS] = jvm.threadCount
        // -1.0 表示当前 JDK 不提供该指标
        if (jvm.systemCpuLoad >= 0) {
            metrics[KEY_CPU_LOAD] = jvm.systemCpuLoad
        }
        metrics[KEY_UPTIME_SECONDS] = jvm.uptimeMs / MILLIS_PER_SECOND
        appendWorlds(metrics, snapshot)
    }

    /** 各世界计数（保留 world 维度）；-1 表示该计数不可用（如 Folia 受限），不写该字段。 */
    private fun appendWorlds(metrics: MutableMap<String, Any?>, snapshot: MetricSnapshot) {
        val worlds = snapshot.server?.worlds ?: return
        val projected = linkedMapOf<String, Any?>()
        for (world in worlds) {
            val counts = linkedMapOf<String, Any?>()
            if (world.loadedChunks >= 0) {
                counts[KEY_WORLD_LOADED_CHUNKS] = world.loadedChunks
            }
            if (world.entityCount >= 0) {
                counts[KEY_WORLD_ENTITIES] = world.entityCount
            }
            if (world.tileEntityCount >= 0) {
                counts[KEY_WORLD_TILE_ENTITIES] = world.tileEntityCount
            }
            projected[world.name] = counts
        }
        if (projected.isNotEmpty()) {
            metrics[KEY_WORLDS] = projected
        }
    }

    /**
     * GC 汇总：各收集器累计次数/耗时求和（对应 `/metrics` 的逐收集器 `gc_count_total` /
     * `gc_time_seconds_total` 汇总到实例维度，耗时以毫秒表达）。
     */
    private fun appendGc(metrics: MutableMap<String, Any?>, snapshot: MetricSnapshot) {
        val collectors = snapshot.jvm.gcCollectors ?: return
        var count = 0L
        var timeMillis = 0L
        for (collector in collectors) {
            count += collector.collectionCount
            timeMillis += collector.collectionTimeMs
        }
        metrics[KEY_GC_COUNT_TOTAL] = count
        metrics[KEY_GC_TIME_MILLIS] = timeMillis
    }

    /** 帧内字段名（协议规格 §3.4.4）。 */
    private const val KEY_TPS = "tps"
    private const val KEY_MSPT_AVG = "msptAvg"
    private const val KEY_MSPT_P95 = "msptP95"
    private const val KEY_PLAYERS_ONLINE = "playersOnline"
    private const val KEY_HEAP_USED = "heapUsed"
    private const val KEY_HEAP_MAX = "heapMax"
    private const val KEY_THREADS = "threads"
    private const val KEY_CPU_LOAD = "cpuLoad"
    private const val KEY_UPTIME_SECONDS = "uptimeSeconds"
    private const val KEY_WORLDS = "worlds"
    private const val KEY_GC_COUNT_TOTAL = "gcCountTotal"
    private const val KEY_GC_TIME_MILLIS = "gcTimeMillis"

    // ── 桥中转指标（本通道自身）────────────────────────────────
    /** 可靠事件队列深度（帧）。 */
    private const val KEY_BRIDGE_RELIABLE_QUEUE = "bridgeReliableQueue"
    /** 可丢事件队列深度（帧）。 */
    private const val KEY_BRIDGE_BEST_EFFORT_QUEUE = "bridgeBestEffortQueue"
    /** 可靠事件因队列满被拒累计数（单调递增，CP 侧差分）。 */
    private const val KEY_BRIDGE_RELIABLE_REJECTED_TOTAL = "bridgeReliableRejectedTotal"
    /** 可丢事件被丢累计数（单调递增）。 */
    private const val KEY_BRIDGE_BEST_EFFORT_DROPPED_TOTAL = "bridgeBestEffortDroppedTotal"
    /** 命令回执被拒累计数——回执被拒会让 JM 误判业务失败。 */
    private const val KEY_BRIDGE_RECEIPT_REJECTED_TOTAL = "bridgeReceiptRejectedTotal"
    /** 已受理但被落盘缓冲挤掉的累计数（仅 spill 开启时非零）。 */
    private const val KEY_BRIDGE_SPILL_DROPPED_TOTAL = "bridgeSpillDroppedTotal"
    /** 写出失败累计数。 */
    private const val KEY_BRIDGE_WRITE_FAILURES_TOTAL = "bridgeWriteFailuresTotal"
    /** 写超时累计数（强关 socket 次数）。 */
    private const val KEY_BRIDGE_WRITE_TIMEOUTS_TOTAL = "bridgeWriteTimeoutsTotal"

    /** `worlds.<name>` 下的字段名（与 `/metrics` 的 `serverprobe_world_*` 一一对应）。 */
    private const val KEY_WORLD_LOADED_CHUNKS = "loadedChunks"
    private const val KEY_WORLD_ENTITIES = "entities"
    private const val KEY_WORLD_TILE_ENTITIES = "tileEntities"
}
