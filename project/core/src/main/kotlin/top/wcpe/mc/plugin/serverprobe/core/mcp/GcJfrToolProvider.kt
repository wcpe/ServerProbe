package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * FR-22 MCP GC/JFR 详诊工具（只读）。
 *
 * 提供 `gc_events`（自上次调用以来的 GC 增量）、`gc_stats`（堆/内存池/GC 累计 + delta）、
 * `jfr_start`/`jfr_stop`（经内嵌 Arthas 采集 JFR，产物写工作区，配合 FR-18 二进制回传）。
 * - `gc_*` 经 JMX，始终可用；`jfr_*` 依赖 Arthas attach，失败结构化降级。
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

    /** 封装 Arthas jfr 命令；产物路径经工作区白名单校验；jfr_stop 支持默认命名。 */
    private fun jfrCommand(operation: String, arguments: JsonObject?): Map<String, Any?> {
        val workspace = currentWorkspace()
            ?: return linkedMapOf("available" to false, "reason" to "当前未启用 MCP 工件工作区")
        val command = if (operation == "start") {
            "jfr start"
        } else {
            val name = arguments?.getString("artifactName")?.trim()?.takeIf(String::isNotBlank)
                ?: "jfr-${System.currentTimeMillis()}.jfr"
            "jfr stop --filename '${arthasPath(workspace.outputPath(name))}'"
        }
        return taskSnapshot(arthas().submit(ArthasCommandRequest(command, timeoutMillis(arguments))))
    }

    private companion object {
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
                mapOf("artifactName" to mapOf("type" to "string", "description" to "产物名，默认 jfr-<epochMillis>.jfr")),
                usageExample = "{}", workflow = "异步：返回 taskId → 完成后用 artifact_read_binary 取回 JFR",
                outputFields = mapOf("taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明")),
        )
    }
}
