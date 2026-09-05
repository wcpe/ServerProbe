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
            ),
        )
        private val ARTHAS_COMMAND_SCHEMA = mapOf("command" to mapOf("type" to "string"), "timeoutMillis" to mapOf("type" to "integer"))
        private val ARTHAS_METHOD_SCHEMA = mapOf(
            "className" to mapOf("type" to "string"), "methodName" to mapOf("type" to "string"),
            "expression" to mapOf("type" to "string"), "maxMatches" to mapOf("type" to "integer"),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_TT_SCHEMA = mapOf(
            "className" to mapOf("type" to "string"), "methodName" to mapOf("type" to "string"),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_EXPRESSION_SCHEMA = mapOf(
            "expression" to mapOf("type" to "string"),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_PROFILER_SCHEMA = mapOf(
            "action" to mapOf("type" to "string"),
            "artifactName" to mapOf("type" to "string"),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_ARTIFACT_SCHEMA = mapOf(
            "artifactName" to mapOf("type" to "string"),
            "timeoutMillis" to mapOf("type" to "integer"),
        )
        private val ARTHAS_REVERT_SCHEMA = mapOf("entryId" to mapOf("type" to "integer"), "timeoutMillis" to mapOf("type" to "integer"))
        private val TASK_ID_SCHEMA = mapOf("taskId" to mapOf("type" to "string"))
        private val TASK_OUTPUT_SCHEMA = TASK_ID_SCHEMA + ("offset" to mapOf("type" to "integer"))
        private val TOOLS = listOf(
            McpTool(SERVER_STATUS, "读取当前服务器与 JVM 状态"),
            McpTool(SERVER_COMMAND, "在当前平台执行一条控制台命令", COMMAND_INPUT_SCHEMA),
            McpTool(THREAD_TOP, "读取线程 CPU 时间排名"),
            McpTool(THREAD_DUMP, "读取有界线程转储"),
            McpTool(THREAD_DEADLOCKS, "读取 JVM 检测到的死锁线程"),
            McpTool(ARTHAS_EXECUTE, "异步执行内嵌 Arthas 原始命令", ARTHAS_COMMAND_SCHEMA),
            McpTool(ARTHAS_TASK_STATUS, "读取 Arthas 任务状态", TASK_ID_SCHEMA),
            McpTool(ARTHAS_TASK_OUTPUT, "读取 Arthas 任务输出分片", TASK_OUTPUT_SCHEMA),
            McpTool(ARTHAS_TASK_CANCEL, "取消运行中的 Arthas 任务", TASK_ID_SCHEMA),
            McpTool(ARTHAS_RETRY_ATTACH, "显式重试获取 Arthas Instrumentation"),
            McpTool(DIAGNOSTIC_BUNDLE, "汇总服务器、线程与死锁诊断证据"),
            McpTool(ARTIFACT_WRITE_CHUNK, "写入工件文本分块"),
            McpTool(ARTIFACT_LIST, "列出 MCP 工件"),
            McpTool(ARTIFACT_READ_CHUNK, "读取 MCP 工件分块"),
            McpTool(ARTIFACT_DELETE, "删除 MCP 工件"),
            McpTool(ARTHAS_WATCH, "异步观察指定方法", ARTHAS_METHOD_SCHEMA), McpTool(ARTHAS_TRACE, "异步追踪指定方法", ARTHAS_METHOD_SCHEMA),
            McpTool(ARTHAS_STACK, "异步查看指定方法调用栈", ARTHAS_METHOD_SCHEMA), McpTool(ARTHAS_MONITOR, "异步统计指定方法", ARTHAS_METHOD_SCHEMA),
            McpTool(ARTHAS_TT, "异步记录指定方法时间隧道", ARTHAS_TT_SCHEMA), McpTool(ARTHAS_OGNL, "异步执行 JVM 内 OGNL", ARTHAS_EXPRESSION_SCHEMA),
            McpTool(ARTHAS_PROFILER, "异步执行 Arthas 性能采样器", ARTHAS_PROFILER_SCHEMA),
            McpTool(ARTHAS_RETRANSFORM, "异步重转换工作区内的类字节码", ARTHAS_ARTIFACT_SCHEMA),
            McpTool(ARTHAS_REDEFINE, "异步替换工作区内的类字节码", ARTHAS_ARTIFACT_SCHEMA),
            McpTool(ARTHAS_REVERT, "按重转换条目编号恢复类字节码", ARTHAS_REVERT_SCHEMA),
        )
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
