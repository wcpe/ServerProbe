package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * FR-21 MCP 运行期 CPU 火焰图工具（薄封装，不推翻 ADR-8）。
 *
 * 本 provider 不实现任何采样器，只做两件事：
 * 1. 把 `flamegraph_start` / `flamegraph_stop` 薄封装为既有 `arthas_profiler`
 *    （async-profiler）的 start/stop 异步任务，产物统一落 `mcp-workspace/artifacts/`；
 * 2. 提供 `flamegraph_view` 按扩展名自动分流读取：HTML 火焰图走文本分块，
 *    JFR 仅返回元信息并提示经 `artifact_read_binary` 取回，其他扩展名结构化拒绝。
 *
 * 降级策略（与既有工具一致）：
 * - Arthas 未 attach / 工作区未装配时，`flamegraph_start` / `flamegraph_stop`
 *   返回结构化 `{available:false, reason}`，不抛异常；
 * - `flamegraph_view` 对非法名、不存在的工件抛 [IllegalArgumentException]
 *   （由 dispatcher 统一转为 JSON-RPC 结构化错误）。
 *
 * 无共享可变状态：每次调用经 [McpArtifactWorkspaceRegistry] 取当前工作区只读快照。
 */
@Service
class FlamegraphToolProvider(
    testWorkspaceRegistry: McpArtifactWorkspaceRegistry? = null,
    testArthasControl: ArthasControl? = null,
) : McpExtensionToolBase() {

    override val testWorkspaceRegistry: McpArtifactWorkspaceRegistry? = testWorkspaceRegistry
    override val testArthasControl: ArthasControl? = testArthasControl

    /** 测试注入用时钟；生产运行期为当前时间戳。 */
    @Volatile
    var clockMillis: Long = 0L

    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    /** 启动期注册进扩展工具注册表，由控制面聚合进 dispatcher。 */
    @PostConstruct
    fun register() {
        mcpToolProviderRegistry.register(this)
    }

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = when (name) {
        FLAMEGRAPH_START -> flamegraphStart(arguments)
        FLAMEGRAPH_STOP -> flamegraphStop(arguments)
        FLAMEGRAPH_VIEW -> flamegraphView(arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    /** start 薄封装：提交 profiler start 异步任务，Arthas 不可用时结构化降级。 */
    private fun flamegraphStart(arguments: JsonObject?): Map<String, Any?> {
        val control = arthas()
        val snapshot = control.submit(ArthasCommandRequest("profiler start", timeoutMillis(arguments)))
        return if (snapshot.state == ArthasTaskState.FAILED) {
            ProbeLogger.warn("flamegraph_start 失败：${snapshot.message}")
            linkedMapOf("available" to false, "reason" to snapshot.message)
        } else {
            taskSnapshot(snapshot)
        }
    }

    /** stop 薄封装：默认自动命名 `flamegraph-<epochMillis>.html` 消歧，路径经工作区白名单校验。 */
    private fun flamegraphStop(arguments: JsonObject?): Map<String, Any?> {
        val workspace = currentWorkspace()
            ?: return linkedMapOf("available" to false, "reason" to "当前未启用 MCP 工件工作区")
        val artifactName = arguments?.getString("artifactName")?.trim()?.takeIf(String::isNotBlank)
            ?: "flamegraph-${nowMillis()}.html"
        val command = "profiler stop --file '${arthasPath(workspace.outputPath(artifactName))}'"
        val snapshot = arthas().submit(ArthasCommandRequest(command, timeoutMillis(arguments)))
        return if (snapshot.state == ArthasTaskState.FAILED) {
            ProbeLogger.warn("flamegraph_stop 失败：${snapshot.message}")
            linkedMapOf("available" to false, "reason" to snapshot.message)
        } else {
            taskSnapshot(snapshot)
        }
    }

    /** view 分流：.html 分块文本 / .jfr 元信息 / 其他扩展名结构化拒绝。 */
    private fun flamegraphView(arguments: JsonObject?): Map<String, Any?> {
        val workspace = currentWorkspace()
            ?: return linkedMapOf("available" to false, "reason" to "当前未启用 MCP 工件工作区")
        val artifactName = arguments?.getString("artifactName")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("flamegraph_view 缺少 artifactName 参数")
        // pathOf 做白名单与存在性双重校验：非法名/不存在统一抛结构化异常
        workspace.pathOf(artifactName)
        val artifact = workspace.list().first { it.name == artifactName }
        return when {
            artifactName.endsWith(".html") -> htmlChunk(workspace, artifactName, arguments)
            artifactName.endsWith(".jfr") -> jfrMetadata(artifactName, artifact)
            else -> throw IllegalArgumentException("不支持的火焰图产物扩展名，仅支持 .html / .jfr：$artifactName")
        }
    }

    /** HTML 火焰图：复用工作区文本分块语义（offset/maxBytes 游标分页）。 */
    private fun htmlChunk(workspace: McpArtifactWorkspace, artifactName: String, arguments: JsonObject?): Map<String, Any?> {
        val offset = arguments?.getRaw("offset")?.toString()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val maxBytes = arguments?.getRaw("maxBytes")?.toString()?.toIntOrNull() ?: DEFAULT_READ_BYTES
        val chunk = workspace.readChunk(artifactName, offset, maxBytes)
        return linkedMapOf(
            "name" to artifactName,
            "kind" to "html",
            "content" to chunk.content,
            "nextOffset" to chunk.nextOffset,
            "truncated" to chunk.truncated,
        )
    }

    /** JFR：返回元信息与取回提示，二进制内容一律走 artifact_read_binary。 */
    private fun jfrMetadata(artifactName: String, artifact: McpArtifact): Map<String, Any?> = linkedMapOf(
        "artifactName" to artifactName,
        "kind" to "jfr",
        "size" to artifact.size,
        "modifiedAtMillis" to artifact.modifiedAtMillis,
        "hint" to "JFR 为二进制产物，请使用 artifact_read_binary 分块取回后本地分析",
    )

    private fun nowMillis(): Long = if (clockMillis > 0L) clockMillis else System.currentTimeMillis()

    private companion object {
        const val FLAMEGRAPH_START = "flamegraph_start"
        const val FLAMEGRAPH_STOP = "flamegraph_stop"
        const val FLAMEGRAPH_VIEW = "flamegraph_view"
        const val DEFAULT_TIMEOUT_MILLIS = 30 * 60 * 1_000L
        const val MAX_TIMEOUT_MILLIS = 30 * 60 * 1_000L
        const val DEFAULT_READ_BYTES = 64 * 1024
        val TOOLS = listOf(
            McpTool(
                FLAMEGRAPH_START, "启动 async-profiler CPU 采样（薄封装 arthas_profiler start，异步任务）",
                mapOf("timeoutMillis" to mapOf("type" to "integer", "description" to "任务超时毫秒，默认 30 分钟，上限 30 分钟")),
                usageExample = "{}",
                workflow = "异步：返回 taskId → arthas_task_status 轮询 → 触发负载 → flamegraph_stop",
                outputFields = mapOf("taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明"),
            ),
            McpTool(
                FLAMEGRAPH_STOP, "停止采样并生成火焰图产物（薄封装 arthas_profiler stop；默认自动命名 flamegraph-<epochMillis>.html）",
                mapOf(
                    "artifactName" to mapOf("type" to "string", "description" to "产物名，默认 flamegraph-<epochMillis>.html（仅字母数字点下划线连字符）"),
                    "timeoutMillis" to mapOf("type" to "integer", "description" to "任务超时毫秒，默认 30 分钟，上限 30 分钟"),
                ),
                usageExample = "{\"artifactName\":\"flamegraph-1700000000123.html\"}",
                workflow = "异步：返回 taskId → arthas_task_status 轮询 → 完成后 flamegraph_view 或 artifact_read_binary 取回",
                outputFields = mapOf("taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明"),
            ),
            McpTool(
                FLAMEGRAPH_VIEW, "按扩展名分流查看火焰图产物：.html 分块文本返回，.jfr 返回元信息（二进制走 artifact_read_binary）",
                mapOf(
                    "artifactName" to mapOf("type" to "string", "description" to "工作区内火焰图/JFR 产物名"),
                    "offset" to mapOf("type" to "integer", "description" to "HTML 分块起始字节偏移，默认 0"),
                    "maxBytes" to mapOf("type" to "integer", "description" to "HTML 分块最大字节，默认 64 KiB，上限 1 MiB"),
                ),
                usageExample = "{\"artifactName\":\"flamegraph-1700000000123.html\"}",
                workflow = "同步调用；HTML 按 nextOffset 游标分页，JFR 用 artifact_read_binary 取回；等 profiler stop 任务完成后再读取，避免读到不完整文件",
                outputFields = mapOf(
                    "name" to "HTML 分块时的产物名", "content" to "HTML 分片文本", "nextOffset" to "下一页偏移", "truncated" to "是否还有更多",
                    "artifactName" to "JFR 产物名", "kind" to "html/jfr", "size" to "JFR 文件字节数", "modifiedAtMillis" to "JFR 修改时间",
                ),
            ),
        )
    }
}
