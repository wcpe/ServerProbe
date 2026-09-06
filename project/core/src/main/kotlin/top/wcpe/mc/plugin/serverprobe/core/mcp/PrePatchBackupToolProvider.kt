package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * FR-19 补丁前自动备份原始字节码。
 *
 * 提供：
 * - `artifact_backup_list`：按 `backup_` 前缀过滤当前工作区工件；
 * - `artifact_backup_restore`：把备份字节码原样写回工作区为 `restored_<原名>` 工件；
 * - 替换前自动备份：在 `tools/call` 层拦截 `arthas_redefine` / `arthas_retransform`，
 *   先经 [ArthasControl.dumpClassBytes] 读取当前已加载字节码并写入 `backup_<类名转义>_<时间戳>.class`，
 *   再按原调用委托执行替换。
 *
 * 接线方式（不触碰 NativeMcpToolProvider）：本 provider 在 [McpToolProviderRegistry] 中的注册
 * 顺序晚于 Native provider，[McpJsonRpcDispatcher] 按工具名合并时后者覆盖前者，故同名替换工具的
 * 调用会路由到本 provider 的拦截实现；拦截成功后重新构造替换参数调用 [McpArtifactWorkspace.pathOf] +
 * [ArthasControl.submit]，与 Native 的 `arthasClassCommand` 语义一致（扁平命名、Windows 路径转正斜杠）。
 *
 * 兼容语义（对照 spec §3）：
 * - [ArthasControl.dumpClassBytes] 默认返回 null（能力未提供）→ 跳过备份 + WARN + 继续替换；
 * - 实现已提供但返回空字节或抛异常（读取失败）→ 结构化拒绝替换（backupFailed）；
 * - 备份名按工作区白名单扁平命名，`.`→`_`；同一毫秒命名冲突追加 `_2` 后缀消歧。
 */
@Service
class PrePatchBackupToolProvider(
    private val testWorkspaceRegistry: McpArtifactWorkspaceRegistry? = null,
    private val testArthasControl: ArthasControl? = null,
) : McpToolProvider {

    @Inject
    lateinit var workspaceRegistry: McpArtifactWorkspaceRegistry

    @Inject
    lateinit var arthasControlRegistry: ArthasControl

    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    /** 测试注入用时钟；生产运行期不使用（保持单例内默认静态时间源）。 */
    @Volatile
    var clockMillis: Long = 0L

    /** 启动期注册进扩展工具注册表，由控制面聚合进 dispatcher（顺序晚于 Native，实现替换调用拦截）。 */
    @PostConstruct
    fun register() {
        mcpToolProviderRegistry.register(this)
    }

    private fun currentWorkspace(): McpArtifactWorkspace? =
        (testWorkspaceRegistry ?: workspaceRegistry).current()

    private fun arthas(): ArthasControl = testArthasControl ?: arthasControlRegistry

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = when (name) {
        ARTIFACT_BACKUP_LIST -> backupList()
        ARTIFACT_BACKUP_RESTORE -> backupRestore(arguments)
        ARTHAS_REDEFINE, ARTHAS_RETRANSFORM -> backupThenReplace(name.removePrefix("arthas_"), arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    /** 替换前自动备份：备份成功才提交替换任务；能力缺失跳过备份，读取失败拒绝替换。 */
    private fun backupThenReplace(operation: String, arguments: JsonObject?): Map<String, Any?> {
        val workspace = workspaceOrNull()
        val className = arguments?.getString("className")?.trim()?.takeIf(String::isNotBlank)
        val backupName = if (workspace != null && className != null) backupOrReject(className) else null
        if (workspace != null && className != null && backupName != null) {
            ProbeLogger.info("替换前已自动备份 $className 到工件 $backupName")
        }
        return submitReplace(operation, arguments)
    }

    /** 返回备份工件名；读取失败（空字节/异常）抛出 backupFailed 结构化错误。 */
    private fun backupOrReject(className: String): String? {
        require(CLASS_NAME.matches(className)) { "className 必须是合法类名" }
        // dumpClassBytes 为外部接口调用，宽捕任何异常统一转为结构化失败并拒绝替换。
        @Suppress("TooGenericExceptionCaught")
        val bytes = try {
            arthas().dumpClassBytes(className)
        } catch (error: Exception) {
            throw IllegalArgumentException("类替换前备份失败（读取已加载字节码异常）：${className}，已拒绝替换", error)
        }
        if (bytes == null) {
            ProbeLogger.warn("当前 Arthas 控制器未提供字节码备份能力（dumpClassBytes 返回 null），跳过自动备份并继续替换：$className")
            return null
        }
        require(bytes.isNotEmpty()) { "类替换前备份失败（已加载字节码为空）：$className，已拒绝替换" }
        return writeBackup(className, bytes)
    }

    /** 写入 `backup_<转义类名>_<时间戳>.class`；同一毫秒命名冲突追加 `_2` 后缀消歧。 */
    private fun writeBackup(className: String, bytes: ByteArray): String {
        val workspace = requireWorkspace()
        val base = "backup_" + className.replace('.', '_') + "_" + nowMillis()
        var name = "$base.class"
        var suffix = 2
        while (workspace.list().any { it.name == name }) {
            name = "${base}_$suffix.class"
            suffix++
        }
        writeBinary(workspace, name, bytes)
        return name
    }

    /** 委托替换：按 Native `arthasClassCommand` 语义构造 Arthas 命令并提交。 */
    private fun submitReplace(operation: String, arguments: JsonObject?): Map<String, Any?> {
        val workspace = requireWorkspace()
        val artifactName = arguments?.getString("artifactName")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("类操作缺少 artifactName 参数")
        val command = "$operation '${arthasPath(workspace.pathOf(artifactName))}'"
        return taskSnapshot(arthas().submit(ArthasCommandRequest(command, timeoutMillis(arguments))))
    }

    /** 备份列表：复用工作区 list 并按 `backup_` 前缀过滤。 */
    private fun backupList(): Map<String, Any?> {
        val workspace = workspaceOrNull()
            ?: return linkedMapOf("available" to false, "reason" to "当前未启用 MCP 工件工作区")
        return mapOf("backups" to workspace.list().filter { it.name.startsWith(BACKUP_PREFIX) }.map(::artifactMap))
    }

    /** 备份恢复：入参必须为 `backup_` 前缀且存在；字节码写回 `restored_<原名>` 并返回新工件名。 */
    private fun backupRestore(arguments: JsonObject?): Map<String, Any?> {
        val workspace = workspaceOrNull()
            ?: return linkedMapOf("available" to false, "reason" to "当前未启用 MCP 工件工作区")
        val backupName = arguments?.getString("backupName")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("备份恢复缺少 backupName 参数")
        // 先经工作区白名单校验名称（防路径注入），再校验前缀，最后校验存在性。
        val source = workspace.outputPath(backupName)
        require(backupName.startsWith(BACKUP_PREFIX)) { "backupName 必须以 backup_ 前缀开头" }
        require(Files.isRegularFile(source)) { "备份工件不存在：$backupName" }
        val targetName = "restored_" + backupName
        writeBinary(workspace, targetName, Files.readAllBytes(source))
        return workspace.list().first { it.name == targetName }.let(::artifactMap)
    }

    /** 经工作区白名单路径写二进制；输出路径语义与 profiler 产物一致（仅工作区内）。 */
    private fun writeBinary(workspace: McpArtifactWorkspace, name: String, bytes: ByteArray) {
        Files.write(workspace.outputPath(name), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun workspaceOrNull(): McpArtifactWorkspace? = currentWorkspace()

    private fun requireWorkspace(): McpArtifactWorkspace =
        workspaceOrNull() ?: throw IllegalArgumentException("当前未启用 MCP 工件工作区")

    private fun nowMillis(): Long = if (clockMillis > 0) clockMillis else System.currentTimeMillis()

    /** Arthas 命令解析器将反斜杠视作转义符，Windows 路径统一转为正斜杠。 */
    private fun arthasPath(path: java.nio.file.Path): String = path.toAbsolutePath().toString().replace('\\', '/')

    private fun timeoutMillis(arguments: JsonObject?): Long {
        val requested = arguments?.getRaw("timeoutMillis")?.toString()?.toLongOrNull() ?: DEFAULT_TIMEOUT_MILLIS
        return requested.coerceIn(0, DEFAULT_TIMEOUT_MILLIS)
    }

    private fun artifactMap(artifact: McpArtifact): Map<String, Any?> = linkedMapOf(
        "name" to artifact.name,
        "size" to artifact.size,
        "modifiedAtMillis" to artifact.modifiedAtMillis,
    )

    private fun taskSnapshot(snapshot: ArthasTaskSnapshot): Map<String, Any?> =
        linkedMapOf("taskId" to snapshot.taskId, "state" to snapshot.state.name, "message" to snapshot.message)

    private companion object {
        const val ARTIFACT_BACKUP_LIST = "artifact_backup_list"
        const val ARTIFACT_BACKUP_RESTORE = "artifact_backup_restore"
        const val ARTHAS_REDEFINE = "arthas_redefine"
        const val ARTHAS_RETRANSFORM = "arthas_retransform"
        const val BACKUP_PREFIX = "backup_"
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$.]*")
        const val DEFAULT_TIMEOUT_MILLIS = 30 * 60 * 1_000L
        val TOOLS = listOf(
            McpTool(
                ARTIFACT_BACKUP_LIST,
                "列出全部补丁备份工件（backup_ 前缀）",
                usageExample = "{}",
                workflow = "同步调用；备份由 arthas_redefine/arthas_retransform 替换前自动产生",
                outputFields = mapOf(
                    "backups" to "备份工件列表（name/size/modifiedAtMillis）",
                ),
            ),
            McpTool(
                ARTIFACT_BACKUP_RESTORE,
                "把备份字节码原样写回工作区为 restored_ 前缀工件，供再次替换恢复原始行为",
                mapOf("backupName" to mapOf("type" to "string", "description" to "备份工件名（必须 backup_ 前缀）")),
                usageExample = "{\"backupName\":\"backup_com_example_Foo_1700000000000.class\"}",
                workflow = "同步调用；写回后需 agent 主动调用 arthas_redefine 引用新工件名，本工具不自动触发替换",
                outputFields = mapOf(
                    "name" to "写回后的新工件名（restored_ 前缀）", "size" to "字节数", "modifiedAtMillis" to "修改时间",
                ),
            ),
            McpTool(
                ARTHAS_REDEFINE,
                "异步替换工作区内的类字节码；替换前自动备份原始字节码",
                mapOf(
                    "artifactName" to mapOf("type" to "string", "description" to "待替换的类字节码工件名"),
                    "className" to mapOf("type" to "string", "description" to "目标类全名（可选；提供则替换前自动备份）"),
                    "timeoutMillis" to mapOf("type" to "integer", "description" to "任务超时毫秒，默认 30 分钟"),
                ),
                usageExample = "{\"artifactName\":\"<工件名>\",\"className\":\"com.example.Foo\"}",
                workflow = "替换前自动备份（见 artifact_backup_list）→ task_status 轮询",
                outputFields = mapOf(
                    "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
                ),
            ),
            McpTool(
                ARTHAS_RETRANSFORM,
                "异步重转换工作区内的类字节码；替换前自动备份原始字节码",
                mapOf(
                    "artifactName" to mapOf("type" to "string", "description" to "待重转换的类字节码工件名"),
                    "className" to mapOf("type" to "string", "description" to "目标类全名（可选；提供则替换前自动备份）"),
                    "timeoutMillis" to mapOf("type" to "integer", "description" to "任务超时毫秒，默认 30 分钟"),
                ),
                usageExample = "{\"artifactName\":\"<工件名>\",\"className\":\"com.example.Foo\"}",
                workflow = "替换前自动备份（见 artifact_backup_list）→ task_status 轮询",
                outputFields = mapOf(
                    "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
                ),
            ),
        )
    }
}
