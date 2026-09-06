package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.lang.management.ThreadMXBean
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 不依赖 Arthas 和平台 API 的原生诊断工具集合。 */
class NativeMcpToolProvider(
    private val serverStatus: () -> Map<String, Any?>,
    private val threads: NativeThreadDiagnostics = NativeThreadDiagnostics(),
    private val platformControl: PlatformControl = UnavailablePlatformControl,
    private val arthasControl: ArthasControl = ArthasControlRegistry(),
    private val defaultArthasTimeoutMillis: Long = DEFAULT_ARTHAS_TIMEOUT_MILLIS,
    private val artifacts: McpArtifactWorkspace? = null,
) : McpToolProvider {

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> = when (name) {
        SERVER_STATUS -> serverStatus()
        SERVER_COMMAND -> serverCommand(arguments)
        THREAD_TOP -> threads.top()
        THREAD_DUMP -> threads.dump()
        THREAD_DEADLOCKS -> threads.deadlocks()
        ARTHAS_EXECUTE -> arthasExecute(arguments)
        ARTHAS_TASK_STATUS -> arthasStatus(arguments)
        ARTHAS_TASK_OUTPUT -> arthasOutput(arguments)
        ARTHAS_TASK_CANCEL -> arthasCancel(arguments)
        ARTHAS_RETRY_ATTACH -> arthasRetryAttach()
        DIAGNOSTIC_BUNDLE -> diagnosticBundle()
        ARTIFACT_WRITE_CHUNK -> artifactWrite(arguments)
        ARTIFACT_LIST -> artifactList()
        ARTIFACT_READ_CHUNK -> artifactRead(arguments)
        ARTIFACT_DELETE -> artifactDelete(arguments)
        ARTHAS_WATCH, ARTHAS_TRACE, ARTHAS_STACK, ARTHAS_MONITOR -> arthasMethodCommand(name.removePrefix("arthas_"), arguments)
        ARTHAS_TT -> arthasTimeTunnel(arguments)
        ARTHAS_OGNL -> arthasExpressionCommand("ognl", arguments)
        ARTHAS_PROFILER -> arthasProfiler(arguments)
        ARTHAS_RETRANSFORM, ARTHAS_REDEFINE, ARTHAS_REVERT -> arthasClassCommand(name.removePrefix("arthas_"), arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    private fun arthasExecute(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val command = arguments?.getString("command")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("arthas_execute 缺少非空 command 参数")
        val requested = arguments?.getRaw("timeoutMillis")?.toString()?.toLongOrNull() ?: defaultArthasTimeoutMillis
        val timeout = if (defaultArthasTimeoutMillis == 0L) requested.coerceAtLeast(0L)
        else requested.coerceIn(0L, defaultArthasTimeoutMillis)
        return taskSnapshot(arthasControl.submit(ArthasCommandRequest(command, timeout)))
    }

    private fun arthasStatus(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> =
        taskSnapshot(arthasControl.status(taskId(arguments)))

    private fun arthasOutput(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val offset = arguments?.getRaw("offset")?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val output = arthasControl.output(taskId(arguments), offset)
        return linkedMapOf(
            "taskId" to output.taskId,
            "content" to output.content,
            "nextOffset" to output.nextOffset,
            "truncated" to output.truncated,
        )
    }

    private fun arthasCancel(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> =
        taskSnapshot(arthasControl.cancel(taskId(arguments)))

    private fun arthasRetryAttach(): Map<String, Any?> = arthasControl.retryAttach().let { status ->
        linkedMapOf("available" to status.available, "source" to status.source, "message" to status.message)
    }

    private fun arthasMethodCommand(operation: String, arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val className = requiredIdentifier(arguments, "className")
        val methodName = requiredIdentifier(arguments, "methodName")
        val expression = arguments?.getString("expression")?.trim()?.takeIf(String::isNotBlank)
        val maxMatches = arguments?.getRaw("maxMatches")?.let { requiredPositiveInt(arguments, "maxMatches") }
        val command = buildString {
            append(operation).append(' ').append(className).append(' ').append(methodName)
            if (expression != null) append(" '").append(quote(expression)).append('\'')
            if (maxMatches != null) append(" -n ").append(maxMatches)
        }
        return taskSnapshot(arthasControl.submit(ArthasCommandRequest(command, timeout(arguments))))
    }

    private fun arthasTimeTunnel(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val className = requiredIdentifier(arguments, "className")
        val methodName = requiredIdentifier(arguments, "methodName")
        return taskSnapshot(arthasControl.submit(ArthasCommandRequest("tt -t $className $methodName", timeout(arguments))))
    }

    private fun arthasExpressionCommand(operation: String, arguments: JsonObject?): Map<String, Any?> {
        val expression = arguments?.getString("expression")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$operation 缺少 expression 参数")
        return taskSnapshot(arthasControl.submit(ArthasCommandRequest("$operation '${quote(expression)}'", timeout(arguments))))
    }

    private fun arthasProfiler(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val action = arguments?.getString("action")?.trim()?.takeIf(PROFILER_ACTIONS::contains)
            ?: throw IllegalArgumentException("profiler action 仅支持 start、stop、status 或 list")
        val artifactName = arguments?.getString("artifactName")?.trim()?.takeIf(String::isNotBlank)
        val command = when (action) {
            "stop" -> "profiler stop --file '${quote(outputArtifactPath(artifactName))}'"
            else -> {
                require(artifactName == null) { "仅 profiler stop 支持 artifactName 参数" }
                "profiler $action"
            }
        }
        return taskSnapshot(arthasControl.submit(ArthasCommandRequest(command, timeout(arguments))))
    }

    private fun arthasClassCommand(operation: String, arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val command = when (operation) {
            "redefine", "retransform" -> "$operation '${quote(artifactPath(arguments))}'"
            "revert" -> listOf("retransform", "-d", requiredPositiveInt(arguments, "entryId").toString()).joinToString(" ")
            else -> throw IllegalArgumentException("不支持的 Arthas 类操作")
        }
        return taskSnapshot(arthasControl.submit(ArthasCommandRequest(command, timeout(arguments))))
    }

    private fun requiredIdentifier(arguments: JsonObject?, key: String): String =
        arguments?.getString(key)?.trim()?.takeIf { IDENTIFIER.matches(it) }
            ?: throw IllegalArgumentException("$key 必须是合法类名或方法名")
    private fun quote(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    private fun timeout(arguments: JsonObject?): Long {
        val requested = arguments?.getRaw("timeoutMillis")?.toString()?.toLongOrNull() ?: defaultArthasTimeoutMillis
        return if (defaultArthasTimeoutMillis == 0L) requested.coerceAtLeast(0) else requested.coerceIn(0, defaultArthasTimeoutMillis)
    }

    private fun diagnosticBundle(): Map<String, Any?> = linkedMapOf(
        "serverStatus" to serverStatus(),
        "threadTop" to threads.top(),
        "threadDump" to threads.dump(),
        "threadDeadlocks" to threads.deadlocks(),
    )

    private fun artifactWrite(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val artifact = workspace().writeChunk(
            artifactName(arguments),
            arguments?.getString("content") ?: "",
            arguments?.getBoolean("append", false) ?: false,
        )
        return artifactMap(artifact)
    }

    private fun artifactList(): Map<String, Any?> = mapOf("artifacts" to workspace().list().map(::artifactMap))

    private fun artifactRead(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val chunk = workspace().readChunk(
            artifactName(arguments),
            arguments?.getRaw("offset")?.toString()?.toLongOrNull() ?: 0,
            arguments?.getRaw("maxBytes")?.toString()?.toIntOrNull() ?: DEFAULT_ARTIFACT_READ_BYTES,
        )
        return linkedMapOf("content" to chunk.content, "nextOffset" to chunk.nextOffset, "truncated" to chunk.truncated)
    }

    private fun artifactDelete(arguments: JsonObject?): Map<String, Any?> = mapOf("deleted" to workspace().delete(artifactName(arguments)))

    private fun workspace(): McpArtifactWorkspace = artifacts ?: error("当前未启用 MCP 工件工作区")
    private fun artifactName(arguments: JsonObject?): String =
        arguments?.getString("name")?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("工件操作缺少 name 参数")
    private fun artifactPath(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): String = arthasPath(workspace().pathOf(
        arguments?.getString("artifactName")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("类操作缺少 artifactName 参数"),
    ))
    private fun outputArtifactPath(name: String?): String = arthasPath(workspace().outputPath(
        name ?: throw IllegalArgumentException("profiler stop 缺少 artifactName 参数"),
    ))
    /** Arthas 命令解析器将反斜杠视作转义符，Windows 路径统一转为正斜杠。 */
    private fun arthasPath(path: java.nio.file.Path): String = path.toAbsolutePath().toString().replace('\\', '/')
    private fun requiredPositiveInt(arguments: JsonObject?, key: String): Int =
        arguments?.getRaw(key)?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$key 必须为正整数")
    private fun artifactMap(artifact: McpArtifact): Map<String, Any?> = linkedMapOf(
        "name" to artifact.name,
        "size" to artifact.size,
        "modifiedAtMillis" to artifact.modifiedAtMillis,
    )

    private fun taskId(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): String =
        arguments?.getString("taskId")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Arthas 任务缺少 taskId 参数")

    private fun taskSnapshot(snapshot: ArthasTaskSnapshot): Map<String, Any?> =
        linkedMapOf("taskId" to snapshot.taskId, "state" to snapshot.state.name, "message" to snapshot.message)

    private companion object {
        private const val SERVER_STATUS = "server_status"
        private const val SERVER_COMMAND = "server_command"
        private const val THREAD_TOP = "thread_top"
        private const val THREAD_DUMP = "thread_dump"
        private const val THREAD_DEADLOCKS = "thread_deadlocks"
        private const val ARTHAS_EXECUTE = "arthas_execute"
        private const val ARTHAS_TASK_STATUS = "arthas_task_status"
        private const val ARTHAS_TASK_OUTPUT = "arthas_task_output"
        private const val ARTHAS_TASK_CANCEL = "arthas_task_cancel"
        private const val ARTHAS_RETRY_ATTACH = "arthas_retry_attach"
        private const val DIAGNOSTIC_BUNDLE = "diagnostic_bundle"
        private const val ARTIFACT_WRITE_CHUNK = "artifact_write_chunk"
        private const val ARTIFACT_LIST = "artifact_list"
        private const val ARTIFACT_READ_CHUNK = "artifact_read_chunk"
        private const val ARTIFACT_DELETE = "artifact_delete"
        private const val ARTHAS_WATCH = "arthas_watch"
        private const val ARTHAS_TRACE = "arthas_trace"
        private const val ARTHAS_STACK = "arthas_stack"
        private const val ARTHAS_MONITOR = "arthas_monitor"
        private const val ARTHAS_TT = "arthas_tt"
        private const val ARTHAS_OGNL = "arthas_ognl"
        private const val ARTHAS_PROFILER = "arthas_profiler"
        private const val ARTHAS_RETRANSFORM = "arthas_retransform"
        private const val ARTHAS_REDEFINE = "arthas_redefine"
        private const val ARTHAS_REVERT = "arthas_revert"
        private val COMMAND_INPUT_SCHEMA = mapOf(
            "command" to mapOf(
                "type" to "string",
                "description" to "要执行的控制台命令，不要带斜杠",
                "required" to true,
            ),
        )
        private val ARTHAS_COMMAND_SCHEMA = mapOf(
            "command" to mapOf("type" to "string", "required" to true),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_METHOD_SCHEMA = mapOf(
            "className" to mapOf("type" to "string", "required" to true), "methodName" to mapOf("type" to "string", "required" to true),
            "expression" to mapOf("type" to "string"), "maxMatches" to mapOf("type" to "integer"),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_TT_SCHEMA = mapOf(
            "className" to mapOf("type" to "string", "required" to true), "methodName" to mapOf("type" to "string", "required" to true),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_EXPRESSION_SCHEMA = mapOf(
            "expression" to mapOf("type" to "string", "required" to true),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_PROFILER_SCHEMA = mapOf(
            "action" to mapOf("type" to "string", "required" to true),
            "artifactName" to mapOf("type" to "string"),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_ARTIFACT_SCHEMA = mapOf(
            "artifactName" to mapOf("type" to "string", "required" to true),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_REVERT_SCHEMA = mapOf(
            "entryId" to mapOf("type" to "integer", "required" to true),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val TASK_ID_SCHEMA = mapOf("taskId" to mapOf("type" to "string", "required" to true))
        private val TASK_OUTPUT_SCHEMA = TASK_ID_SCHEMA + ("offset" to mapOf("type" to "integer"))
        private val ARTIFACT_WRITE_SCHEMA = mapOf(
            "name" to mapOf("type" to "string", "description" to "工件名（仅字母数字点下划线连字符）", "required" to true),
            "content" to mapOf("type" to "string", "description" to "文本内容", "required" to true),
            "append" to mapOf("type" to "boolean", "description" to "是否追加"),
        )
        private val ARTIFACT_READ_SCHEMA = mapOf(
            "name" to mapOf("type" to "string", "description" to "工件名", "required" to true),
            "offset" to mapOf("type" to "integer", "description" to "字节偏移，默认 0"),
            "maxBytes" to mapOf("type" to "integer", "description" to "本分片最大字节，默认 64 KiB，上限 1 MiB"),
        )
        private val ARTIFACT_NAME_SCHEMA = mapOf("name" to mapOf("type" to "string", "description" to "工件名", "required" to true))
        private val TOOLS = listOf(
            McpTool(SERVER_STATUS, "读取当前服务器与 JVM 状态", usageExample = "{}",
                workflow = "同步调用",
                outputFields = mapOf(
                "jvm" to "JVM 指标快照", "classLoading" to "类加载计数", "latestMetricSnapshot" to "最新指标快照",
            )),
            McpTool(SERVER_COMMAND, "在当前平台执行一条控制台命令", COMMAND_INPUT_SCHEMA, 
                usageExample = "{\"command\":\"list\"}", workflow = "同步调用，超时 5 秒",
                outputFields = mapOf(
                "success" to "是否被服务器接受", "output" to "回显（截断时含标记）", "outputTruncated" to "是否截断", "error" to "失败原因",
            )),
            McpTool(THREAD_TOP, "读取线程 CPU 时间排名", workflow = "同步调用",
                outputFields = mapOf(
                "available" to "JVM 是否启用线程 CPU 时间", "threads" to "线程列表（id/name/state/cpuTimeNanos）",
            )),
            McpTool(THREAD_DUMP, "读取有界线程转储", workflow = "同步调用",
                outputFields = mapOf(
                "threads" to "线程列表（含栈帧）", "truncated" to "是否因上限截断",
            )),
            McpTool(THREAD_DEADLOCKS, "读取 JVM 检测到的死锁线程", workflow = "同步调用",
                outputFields = mapOf(
                "deadlocked" to "死锁线程列表",
            )),
            McpTool(ARTHAS_EXECUTE, "异步执行内嵌 Arthas 原始命令", ARTHAS_COMMAND_SCHEMA, 
                usageExample = "{\"command\":\"sc *com.example*\"}", workflow = ASYNC_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT", "message" to "状态说明",
            )),
            McpTool(ARTHAS_TASK_STATUS, "读取 Arthas 任务状态", TASK_ID_SCHEMA, workflow = "同步调用",
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_TASK_OUTPUT, "读取 Arthas 任务输出分片", TASK_OUTPUT_SCHEMA, 
                usageExample = "{\"taskId\":\"<taskId>\",\"offset\":0}", workflow = "同步调用，按 offset 分页",
                outputFields = mapOf(
                "taskId" to "任务 ID", "content" to "本分片内容", "nextOffset" to "下一页偏移", "truncated" to "是否还有更多",
            )),
            McpTool(ARTHAS_TASK_CANCEL, "取消运行中的 Arthas 任务", TASK_ID_SCHEMA, workflow = "同步调用",
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "取消后状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_RETRY_ATTACH, "显式重试获取 Arthas Instrumentation", workflow = "同步调用",
                outputFields = mapOf(
                "available" to "是否可用", "source" to "获取来源（premain/self-attach/helper）", "message" to "说明",
            )),
            McpTool(DIAGNOSTIC_BUNDLE, "汇总服务器、线程与死锁诊断证据", workflow = "同步调用",
                outputFields = mapOf(
                "serverStatus" to "服务器状态", "threadTop" to "线程 CPU Top", "threadDump" to "线程转储", "threadDeadlocks" to "死锁",
            )),
            McpTool(ARTIFACT_WRITE_CHUNK, "写入工件文本分块", ARTIFACT_WRITE_SCHEMA, 
                usageExample = "{\"name\":\"<工件名>\",\"content\":\"<文本>\",\"append\":false}", workflow = "同步调用",
                outputFields = mapOf(
                "name" to "工件名", "size" to "字节数", "modifiedAtMillis" to "修改时间",
            )),
            McpTool(ARTIFACT_LIST, "列出 MCP 工件", workflow = "同步调用",
                outputFields = mapOf(
                "artifacts" to "工件列表（name/size/modifiedAtMillis）",
            )),
            McpTool(ARTIFACT_READ_CHUNK, "读取 MCP 工件文本分块", ARTIFACT_READ_SCHEMA, 
                usageExample = "{\"name\":\"<工件名>\",\"offset\":0}", workflow = "同步调用，按 offset 分页；文本工件用本工具，二进制用 artifact_read_binary",
                outputFields = mapOf(
                "content" to "本分片文本", "nextOffset" to "下一页偏移", "truncated" to "是否还有更多",
            )),
            McpTool(ARTIFACT_DELETE, "删除 MCP 工件", ARTIFACT_NAME_SCHEMA, workflow = "同步调用",
                outputFields = mapOf(
                "deleted" to "是否删除",
            )),
            McpTool(ARTHAS_WATCH, "异步观察指定方法", ARTHAS_METHOD_SCHEMA, 
                usageExample = "{\"className\":\"<类名>\",\"methodName\":\"<方法名>\"}", workflow = METHOD_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_TRACE, "异步追踪指定方法", ARTHAS_METHOD_SCHEMA, 
                usageExample = "{\"className\":\"<类名>\",\"methodName\":\"<方法名>\",\"maxMatches\":1}", workflow = METHOD_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_STACK, "异步查看指定方法调用栈", ARTHAS_METHOD_SCHEMA, workflow = METHOD_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_MONITOR, "异步统计指定方法", ARTHAS_METHOD_SCHEMA, workflow = METHOD_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_TT, "异步记录指定方法时间隧道", ARTHAS_TT_SCHEMA, workflow = METHOD_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_OGNL, "异步执行 JVM 内 OGNL", ARTHAS_EXPRESSION_SCHEMA, 
                usageExample = "{\"expression\":\"@java.lang.System@getProperty('java.version')\"}",
                workflow = ASYNC_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_PROFILER, "异步执行 Arthas 性能采样器", ARTHAS_PROFILER_SCHEMA, 
                usageExample = "{\"action\":\"start\"}", workflow = "异步：start → 触发负载 → stop（产物写工作区）→ artifact_read_binary 取回；必须先 stop 再读取",
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_RETRANSFORM, "异步重转换工作区内的类字节码", ARTHAS_ARTIFACT_SCHEMA, 
                usageExample = "{\"artifactName\":\"<工件名>\"}", workflow = BACKUP_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_REDEFINE, "异步替换工作区内的类字节码", ARTHAS_ARTIFACT_SCHEMA, 
                usageExample = "{\"artifactName\":\"<工件名>\"}", workflow = BACKUP_WORKFLOW,
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
            McpTool(ARTHAS_REVERT, "按重转换条目编号恢复类字节码", ARTHAS_REVERT_SCHEMA, 
                usageExample = "{\"entryId\":1}", workflow = "异步：task_status 轮询",
                outputFields = mapOf(
                "taskId" to "任务 ID", "state" to "任务状态", "message" to "状态说明",
            )),
        )
        private const val ASYNC_WORKFLOW = "异步：返回 taskId → arthas_task_status 轮询 → arthas_task_output 分片读 → 完成后可选 arthas_task_cancel"
        private const val METHOD_WORKFLOW = "异步：先调用再触发目标方法，然后 task_status 轮询 → task_output 分片读"
        private const val BACKUP_WORKFLOW = "异步：替换前自动备份（见 artifact_backup_list）→ task_status 轮询"
        private const val DEFAULT_ARTIFACT_READ_BYTES = 64 * 1024
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$.]*")
        val PROFILER_ACTIONS = setOf("start", "stop", "status", "list")
        private const val COMMAND_TIMEOUT_SECONDS = 5L
        private const val MAX_COMMAND_OUTPUT_CHARS = 16 * 1_024
        private const val DEFAULT_ARTHAS_TIMEOUT_MILLIS = 30 * 60 * 1_000L
    }

    /** 在 MCP 线程等待有限时间；超时只终止等待，不承诺中断已进入平台的命令。 */
    private fun serverCommand(arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> {
        val command = arguments?.getString("command")?.trim()?.removePrefix("/")
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("server_command 缺少非空 command 参数")
        val future = runCatching {
            platformControl.execute(PlatformCommandRequest(command, BoundedCommandOutput(MAX_COMMAND_OUTPUT_CHARS)))
        }.getOrElse { error ->
            return commandResult(PlatformCommandResult.failure("提交平台控制台命令失败：${error.javaClass.simpleName}"))
        }
        // 平台命令失败必须转成结构化失败回执,不允许向 MCP 调用方抛异常,故有意宽捕。
        @Suppress("TooGenericExceptionCaught")
        val result = try {
            future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            PlatformCommandResult.failure("等待平台控制台命令超时，命令可能仍在执行")
        } catch (error: Exception) {
            PlatformCommandResult.failure("平台控制台命令执行失败：${error.javaClass.simpleName}")
        }
        return commandResult(result)
    }

    /** 统一 MCP 的平台命令结果字段，避免适配器泄露平台异常。 */
    private fun commandResult(result: PlatformCommandResult): Map<String, Any?> = linkedMapOf(
        "success" to result.success,
        "output" to result.output,
        "outputTruncated" to result.outputTruncated,
        "error" to result.error,
    )
}

/** 平台模块缺失时保持 MCP 原生诊断可用。 */
private object UnavailablePlatformControl : PlatformControl {

    override fun execute(request: PlatformCommandRequest): java.util.concurrent.CompletableFuture<PlatformCommandResult> =
        java.util.concurrent.CompletableFuture.completedFuture(PlatformCommandResult.failure("当前平台未注册控制台命令执行器"))
}

/** 基于 JDK MXBean 的线程诊断，所有集合与栈帧均有固定上限。 */
class NativeThreadDiagnostics(private val threadBean: ThreadMXBean = ManagementFactory.getThreadMXBean()) {

    fun top(): Map<String, Any?> {
        if (!threadBean.isThreadCpuTimeSupported || !threadBean.isThreadCpuTimeEnabled) {
            return mapOf("available" to false, "reason" to "当前 JVM 未启用线程 CPU 时间")
        }
        val rows = threadBean.allThreadIds.asSequence().mapNotNull(::cpuRow)
            .sortedByDescending { it["cpuTimeNanos"] as Long }.take(MAX_THREADS).toList()
        return mapOf("available" to true, "threads" to rows)
    }

    fun dump(): Map<String, Any?> = mapOf(
        "threads" to threadBean.dumpAllThreads(false, false).asSequence().take(MAX_THREADS).map(::threadRow).toList(),
        "truncated" to (threadBean.threadCount > MAX_THREADS),
    )

    /** 返回保留原始 [StackTraceElement] 的线程快照（供按类名归属过滤，避免 toString 反解丢 className）。 */
    fun threadSamples(): List<NativeThreadSample> = threadBean.dumpAllThreads(false, false)
        .asSequence()
        .take(MAX_THREADS)
        .map { info ->
            NativeThreadSample(
                id = info.threadId,
                name = info.threadName,
                state = info.threadState.name,
                stack = info.stackTrace.take(MAX_STACK_FRAMES).toList(),
                cpuTimeNanos = runCatching { threadBean.getThreadCpuTime(info.threadId) }.getOrDefault(-1L),
            )
        }
        .toList()

    fun deadlocks(): Map<String, Any?> {
        val ids = threadBean.findDeadlockedThreads() ?: return mapOf("deadlocked" to emptyList<Map<String, Any?>>())
        return mapOf("deadlocked" to threadBean.getThreadInfo(ids, true, true).asSequence()
            .filterNotNull().take(MAX_THREADS).map(::threadRow).toList())
    }

    private fun cpuRow(id: Long): Map<String, Any?>? {
        val cpuTime = threadBean.getThreadCpuTime(id)
        val info = threadBean.getThreadInfo(id) ?: return null
        return cpuTime.takeIf { it >= 0 }?.let {
            linkedMapOf("id" to id, "name" to info.threadName, "state" to info.threadState.name, "cpuTimeNanos" to it)
        }
    }

    private fun threadRow(info: ThreadInfo): Map<String, Any?> = linkedMapOf(
        "id" to info.threadId,
        "name" to info.threadName,
        "state" to info.threadState.name,
        "lockName" to info.lockName,
        "lockOwnerId" to info.lockOwnerId,
        "lockOwnerName" to info.lockOwnerName,
        "stack" to info.stackTrace.asSequence().take(MAX_STACK_FRAMES).map(StackTraceElement::toString).toList(),
    )

    companion object {
        const val MAX_THREADS = 128
        private const val MAX_STACK_FRAMES = 64
    }
}

/** 保留原始栈帧的线程快照（供按类名归属过滤）。 */
data class NativeThreadSample(
    val id: Long,
    val name: String,
    val state: String,
    val stack: List<StackTraceElement>,
    val cpuTimeNanos: Long,
)
