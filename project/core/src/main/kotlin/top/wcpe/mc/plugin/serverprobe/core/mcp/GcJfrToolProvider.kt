package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP GC/JFR 详诊工具（只读）。
 *
 * 提供 `gc_events`（自上次调用以来的 GC 增量）、`gc_stats`（堆/内存池/GC 累计 + delta）、
 * `jfr_start`/`jfr_stop`（经内嵌 Arthas 采集 JFR，产物写工作区，配合二进制回传）。
 * - `gc_*`经 JMX，始终可用；`jfr_*`依赖 Arthas attach，失败结构化降级。
 * - 差分窗口为请求驱动：core 保存上次样本，自上次调用起算。
 */
@Service
class GcJfrToolProvider(
    testArthasControl: ArthasControl? = null,
    testWorkspaceRegistry: McpArtifactWorkspaceRegistry? = null,
) : McpExtensionToolBase() {

    override val testArthasControl: ArthasControl? = testArthasControl
    override val testWorkspaceRegistry: McpArtifactWorkspaceRegistry? = testWorkspaceRegistry

    /** 生产 Arthas 控制器（IOC 注入；基类抽象属性 getter 委托）。 */
    @Inject
    lateinit var injectedArthasControl: ArthasControl

    override val arthasControlRegistry: ArthasControl get() = injectedArthasControl

    /** 生产工作区注册表（IOC 注入；基类抽象属性 getter 委托）。 */
    @Inject
    lateinit var injectedWorkspaceRegistry: McpArtifactWorkspaceRegistry

    override val workspaceRegistry: McpArtifactWorkspaceRegistry get() = injectedWorkspaceRegistry

    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    private val lastSample = AtomicReference<List<GcSample>?>(null)

    /** 当前活跃 recording 的 id，供 `jfr_stop` 使用。
     *
     * JFR 的 recording 是**进程级共享**的（不是本探针私有），id 由 JVM 分配且只增不减。
     * 早先用「本工具 start 过几次」来推断 id，但别人（人、其他工具）也可能 start，
     * 一错位就会停错 recording。现在改成以 JVM 实际状态为准：
     * start 前先读 `jfr status` 拿当前最大 id，start 后预期 id = 它 + 1。
     * 读不到状态时退回自增计数（不阻断采集），仍可由调用方显式指定覆盖。
     */
    private val lastRecordingId = AtomicLong(1L)

    /** status 读取失败时的退化计数；仅在 executeSync 不可用时使用。 */
    private val startedRecordings = AtomicInteger(0)

    @PostConstruct
    fun register() {
        mcpToolProviderRegistry.register(this)
    }

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = when (name) {
        GC_EVENTS -> gcEvents()
        GC_STATS -> gcStats()
        JFR_START -> jfrCommand("start", arguments)
        JFR_STOP -> jfrCommand("stop", arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    /** 自上次调用以来的 GC 增量；heapAfterGcKb 仅当 lastGcInfo 可用时填充。 */
    private fun gcEvents(): Map<String, Any?> {
        val current = GcDiff.sample(ManagementFactory.getGarbageCollectorMXBeans())
        val previous = lastSample.getAndSet(current)
        val events = GcDiff.diff(current, previous).map { event ->
            linkedMapOf(
                "collector" to event.collector,
                "countDelta" to event.countDelta,
                "timeDeltaMs" to event.timeDeltaMs,
                "heapAfterGcKb" to event.heapAfterGcKb,
            )
        }
        return mapOf("events" to events)
    }

    /** 当前堆/非堆/内存池/GC 累计与增量。 */
    private fun gcStats(): Map<String, Any?> {
        val memory = ManagementFactory.getMemoryMXBean()
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val pools = ManagementFactory.getMemoryPoolMXBeans().map { pool ->
            val usage = pool.usage
            linkedMapOf(
                "name" to pool.name,
                "type" to pool.type.name,
                "usedKb" to usage.used / 1024,
                "committedKb" to usage.committed / 1024,
                "maxKb" to if (usage.max < 0) -1L else usage.max / 1024,
            )
        }
        val beans = ManagementFactory.getGarbageCollectorMXBeans()
        val current = GcDiff.sample(beans)
        val previous = lastSample.getAndSet(current)
        val diff = GcDiff.diff(current, previous)
        return linkedMapOf(
            "heapUsedKb" to heap.used / 1024,
            "heapCommittedKb" to heap.committed / 1024,
            "heapMaxKb" to if (heap.max < 0) -1L else heap.max / 1024,
            "nonHeapUsedKb" to nonHeap.used / 1024,
            "pools" to pools,
            "gcTotalCount" to current.sumOf { it.count },
            "gcTotalTimeMs" to current.sumOf { it.timeMs },
            "gcCountDelta" to diff.sumOf { it.countDelta },
            "gcTimeDeltaMs" to diff.sumOf { it.timeDeltaMs },
        )
    }

    /** 封装 Arthas jfr 命令；产物路径经工作区白名单校验；jfr_stop 支持默认命名。
     *
     * ## `jfr stop` 必须带 `--recording`
     *
     * Arthas 的 JFRCommand 有**两个独立参数**：`recording`（Long，目标 recording 的 id）
     * 与 `filename`（String，落盘路径）。缺 `--recording` 时它无从判断该停哪一个，
     * 直接报 `Failed to stop. please input recording id`——真机实测踩过：
     * start 成功、status 能看到 recording 在跑，唯独 stop 恒失败。
     *
     * 另两条实测约束：
     *   - 路径**不能加引号**。命令串不是 shell，引号会被当作文件名的一部分传下去。
     *   - jfr start 的输出形如 `Started recording 1`，id 从 1 起递增；本工具每次 start
     *     只有一个活动 recording，取当前最大 id 即目标。
     */
    private fun jfrCommand(operation: String, arguments: JsonObject?): Map<String, Any?> {
        val workspace = currentWorkspace()
            ?: return linkedMapOf("available" to false, "reason" to "当前未启用 MCP 工件工作区")
        val command = if (operation == "start") {
            // 先从 JVM 实际状态取当前最大 id，本次 start 后应为其 +1。
            // 读不到（控制台未提供 executeSync / status 失败）才退回自增计数。
            lastRecordingId.set(nextRecordingIdAfterStart())
            "jfr start"
        } else {
            val name = arguments?.getString("artifactName")?.trim()?.takeIf(String::isNotBlank)
                ?: "jfr-${System.currentTimeMillis()}.jfr"
            // 调用方可显式覆盖 id（多 recording 并存时）；否则用 start 时记下的那个。
            val recordingId = arguments?.getLong("recordingId", 0L)?.takeIf { it > 0L } ?: lastRecordingId.get()
            "jfr stop --recording $recordingId --filename ${arthasPath(workspace.outputPath(name))}"
        }
        return taskSnapshot(arthas().submit(ArthasCommandRequest(command, timeoutMillis(arguments))))
    }

    /**
     * 估算本条 `jfr start` 将分配的 recording id。
     *
     * JFR recording 是**进程级共享**的，id 由 JVM 单调递增分配、停掉也不回收。
     * 早先按「本工具 start 过几次」推断 id，一旦有人/其他工具也 start 过就会错位，
     * stop 时停到别的 recording。现在以 JVM 实际状态为准：读 `jfr status`
     * 拿当前最大 id，本次 start 后应为它 +1。
     *
     * 读不到（executeSync 不可用、命令超时、输出格式变化）时退回自增计数——
     * 宁可 id 可能不准也别阻断采集，反正调用方仍可用 recordingId 显式覆盖。
     *
     * `jfr status` 输出形如：`Recording: recording=1 name=Recording-1 (running)`。
     * 无活动 recording 时输出 `No available recordings.`。
     *
     * @return 预期的新 recording id（至少 1）。
     */
    private fun nextRecordingIdAfterStart(): Long {
        val status = runCatching { arthas().executeSync("jfr status", STATUS_TIMEOUT_MILLIS) }.getOrNull()
        val maxExisting = status
            ?.let { Regex("recording=(\\d+)").findAll(it).mapNotNull { m -> m.groupValues.getOrNull(1)?.toLongOrNull() }.maxOrNull() }
        return if (maxExisting != null) maxExisting + 1 else (startedRecordings.incrementAndGet()).toLong()
    }

    private companion object {
        /** 读 jfr status 的同步超时；只是一条查询命令，取小值。 */
        const val STATUS_TIMEOUT_MILLIS = 5_000L

        const val GC_EVENTS = "gc_events"
        const val GC_STATS = "gc_stats"
        const val JFR_START = "jfr_start"
        const val JFR_STOP = "jfr_stop"

        val TOOLS = listOf(
            McpTool(GC_EVENTS, "返回自上次调用以来的 GC 事件增量（请求驱动差分窗口）",
                usageExample = "{}", workflow = "同步调用", outputFields = mapOf(
                    "events" to "GC 事件列表（collector/countDelta/timeDeltaMs/heapAfterGcKb）",
                )),
            McpTool(GC_STATS, "返回当前堆/非堆/内存池详情与 GC 累计 + 增量",
                usageExample = "{}", workflow = "同步调用", outputFields = mapOf(
                    "heapUsedKb" to "堆已用", "pools" to "内存池列表", "gcTotalCount" to "GC 累计次数",
                    "gcCountDelta" to "自上次增量", "gcTimeDeltaMs" to "自上次耗时增量",
                )),
            McpTool(JFR_START, "经内嵌 Arthas 启动 JFR 采集（异步任务）",
                usageExample = "{}", workflow = "异步：返回 taskId → arthas_task_status 轮询 → jfr_stop",
                outputFields = mapOf("taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明")),
            McpTool(JFR_STOP, "停止 JFR 并写产物到工作区（异步任务，默认命名 jfr-<epoch>.jfr）",
                mapOf(
                    "artifactName" to mapOf("type" to "string", "description" to "产物名，默认 jfr-<epochMillis>.jfr"),
                    "recordingId" to mapOf("type" to "integer", "description" to "要停止的 recording id；默认取 jfr_start 启动的那个"),
                ),
                usageExample = "{}", workflow = "异步：返回 taskId → 完成后用 artifact_read_binary 取回 JFR",
                outputFields = mapOf("taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明")),
        )
    }
}
