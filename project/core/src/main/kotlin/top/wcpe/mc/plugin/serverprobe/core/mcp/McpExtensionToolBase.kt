package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.taboolib.ioc.annotation.Inject

/**
 * MCP 扩展工具提供者的公共基类（技术债收敛，消除 4 个 provider 的复制粘贴）。
 *
 * 统一封装：工作区获取（构造参数测试注入优先、字段注入兜底）、Arthas 提交与快照、
 * 超时钳制、Windows 路径转正斜杠。
 */
abstract class McpExtensionToolBase : McpToolProvider {

    @Inject
    lateinit var workspaceRegistry: McpArtifactWorkspaceRegistry

    @Inject
    lateinit var arthasControlRegistry: ArthasControl

    /** 测试注入用工作区（构造参数）；生产为 null 走字段注入。 */
    protected open val testWorkspaceRegistry: McpArtifactWorkspaceRegistry? = null

    /** 测试注入用 Arthas 控制器（构造参数）；生产为 null 走字段注入。 */
    protected open val testArthasControl: ArthasControl? = null

    /** 当前工件工作区；未装配返回 null（工具降级）。 */
    protected fun currentWorkspace(): McpArtifactWorkspace? =
        (testWorkspaceRegistry ?: workspaceRegistry).current()

    /** 当前 Arthas 控制器（测试注入优先）。 */
    protected fun arthas(): ArthasControl = testArthasControl ?: arthasControlRegistry

    /** 提交 Arthas 命令并返回任务快照。 */
    protected fun submit(command: String, timeoutMillis: Long): Map<String, Any?> =
        taskSnapshot(arthas().submit(ArthasCommandRequest(command, timeoutMillis)))

    /** 任务快照 → 对外 Map。 */
    protected fun taskSnapshot(snapshot: ArthasTaskSnapshot): Map<String, Any?> =
        linkedMapOf("taskId" to snapshot.taskId, "state" to snapshot.state.name, "message" to snapshot.message)

    /** 超时毫秒钳制：默认 [DEFAULT_TIMEOUT_MILLIS]，上限 [MAX_TIMEOUT_MILLIS]；子类可覆盖收紧上限。 */
    protected open fun timeoutMillis(arguments: JsonObject?): Long {
        val requested = arguments?.getRaw("timeoutMillis")?.toString()?.toLongOrNull() ?: DEFAULT_TIMEOUT_MILLIS
        return requested.coerceIn(0L, MAX_TIMEOUT_MILLIS)
    }

    /** Arthas 命令解析器将反斜杠视作转义符，Windows 路径统一转为正斜杠。 */
    protected fun arthasPath(path: java.nio.file.Path): String = path.toAbsolutePath().toString().replace('\\', '/')

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val MAX_TIMEOUT_MILLIS = 30 * 60 * 1_000L
    }
}
