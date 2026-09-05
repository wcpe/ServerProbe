package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasCommandRequest
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasControl
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasTaskOutput
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasTaskSnapshot
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasTaskState
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

/** 执行单条已校验 Arthas 命令的进程内实现。 */
fun interface ArthasCommandRunner {
    fun run(command: String, output: ArthasTaskOutputBuffer)
}

/** 审计只保留命令哈希，不保留 OGNL、源码或其他原文参数。 */
data class ArthasAuditRecord(val taskId: String, val commandHash: String, val durationMillis: Long, val state: ArthasTaskState)

/** Arthas 长任务的资源边界，所有值由 MCP 配置在生命周期创建时提供。 */
data class ArthasTaskSettings(
    val maxConcurrent: Int = DEFAULT_CONCURRENCY,
    val maxOutputChars: Int = DEFAULT_OUTPUT_CHARS,
    val completionRetentionMillis: Long = DEFAULT_COMPLETION_RETENTION_MILLIS,
) {
    init {
        require(maxConcurrent > 0) { "Arthas 并发任务上限必须大于零" }
        require(maxOutputChars > 0) { "Arthas 输出上限必须大于零" }
        require(completionRetentionMillis >= 0) { "Arthas 完成任务保留期不能为负数" }
    }

    private companion object {
        const val DEFAULT_CONCURRENCY = 4
        const val DEFAULT_OUTPUT_CHARS = 64 * 1024 * 1024
        const val DEFAULT_COMPLETION_RETENTION_MILLIS = 60 * 60 * 1_000L
    }
}

/** 有界任务输出缓冲。 */
class ArthasTaskOutputBuffer(private val limit: Int) {
    private val text = StringBuilder()
    private var truncated = false

    @Synchronized fun append(value: String) {
        val remaining = limit - text.length
        if (remaining <= 0) { truncated = true; return }
        text.append(value.take(remaining))
        truncated = truncated || value.length > remaining
    }

    @Synchronized fun slice(offset: Int): Pair<String, Boolean> = text.substring(offset.coerceIn(0, text.length)) to truncated
    @Synchronized fun size(): Int = text.length
}

/** 专用有界执行器管理长 Arthas 任务、超时、取消和脱敏审计。 */
class ArthasTaskManager(
    private val runner: ArthasCommandRunner,
    private val settings: ArthasTaskSettings = ArthasTaskSettings(),
    private val maxOutputChars: Int = settings.maxOutputChars,
    private val audit: (ArthasAuditRecord) -> Unit = {},
    private val executor: ExecutorService = Executors.newFixedThreadPool(settings.maxConcurrent),
) : ArthasControl, AutoCloseable {
    private val tasks = ConcurrentHashMap<String, Task>()
    private val timeouts: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val cleanup = settings.completionRetentionMillis.takeIf { it > 0 }?.let { retention ->
        timeouts.scheduleWithFixedDelay(::cleanupCompleted, retention, retention, TimeUnit.MILLISECONDS)
    }

    override fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot {
        val id = UUID.randomUUID().toString()
        val task = Task(id, request, ArthasTaskOutputBuffer(maxOutputChars))
        tasks[id] = task
        task.future = executor.submit { execute(task) }
        if (request.timeoutMillis > 0) task.timeout = timeouts.schedule({ timeout(task) }, request.timeoutMillis, TimeUnit.MILLISECONDS)
        return task.snapshot("已提交")
    }

    override fun status(taskId: String): ArthasTaskSnapshot = tasks[taskId]?.snapshot() ?: missing(taskId)

    override fun output(taskId: String, offset: Int): ArthasTaskOutput {
        val task = tasks[taskId] ?: return ArthasTaskOutput(taskId, "任务不存在", offset, false)
        val (content, truncated) = task.output.slice(offset)
        return ArthasTaskOutput(taskId, content, task.output.size(), truncated)
    }

    override fun cancel(taskId: String): ArthasTaskSnapshot {
        val task = tasks[taskId] ?: return missing(taskId)
        task.state = ArthasTaskState.CANCELLED
        task.future?.cancel(true)
        task.timeout?.cancel(false)
        return task.snapshot("已请求取消")
    }

    private fun execute(task: Task) {
        if (task.state == ArthasTaskState.CANCELLED) return
        task.state = ArthasTaskState.RUNNING
        val started = System.nanoTime()
        // 反射执行的 LinkageError 等也必须归档为任务失败并回传,任务线程绝不能带异常退出,故 catch(Throwable) 有意为之。
        @Suppress("TooGenericExceptionCaught")
        try {
            runner.run(task.request.command, task.output)
            if (task.state != ArthasTaskState.CANCELLED) task.state = ArthasTaskState.SUCCEEDED
        } catch (_: InterruptedException) {
            if (task.state != ArthasTaskState.TIMED_OUT) task.state = ArthasTaskState.CANCELLED
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (task.state != ArthasTaskState.TIMED_OUT) {
                task.state = ArthasTaskState.FAILED
                task.message = "Arthas 命令执行失败：${failureType(error)}"
            }
        } finally {
            task.timeout?.cancel(false)
            task.completedAtMillis = System.currentTimeMillis()
            audit(ArthasAuditRecord(task.id, sha256(task.request.command), elapsedMillis(started), task.state))
        }
    }

    private fun timeout(task: Task) {
        if (task.state == ArthasTaskState.RUNNING || task.state == ArthasTaskState.QUEUED) {
            task.state = ArthasTaskState.TIMED_OUT
            task.future?.cancel(true)
        }
    }

    override fun close() {
        cleanup?.cancel(false)
        timeouts.shutdownNow()
        executor.shutdownNow()
        tasks.clear()
        (runner as? AutoCloseable)?.let { closeable -> runCatching { closeable.close() } }
    }

    /** 清理已完成且超过保留期的任务，运行中任务绝不移除。 */
    internal fun cleanupCompleted() {
        if (settings.completionRetentionMillis == 0L) return
        val deadline = System.currentTimeMillis() - settings.completionRetentionMillis
        tasks.entries.removeIf { (_, task) -> task.completedAtMillis in 1..deadline }
    }

    private fun missing(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.FAILED, "Arthas 任务不存在")
    private fun elapsedMillis(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun failureType(error: Throwable): String {
        val phase = (error as? ArthasExecutionFailure)?.phase?.plus(":") ?: ""
        val source = (error as? ArthasExecutionFailure)?.cause ?: error
        val target = (source as? InvocationTargetException)?.targetException ?: source.cause ?: source
        return phase + target.javaClass.simpleName
    }

    private class Task(val id: String, val request: ArthasCommandRequest, val output: ArthasTaskOutputBuffer) {
        @Volatile var state = ArthasTaskState.QUEUED
        @Volatile var message = ""
        @Volatile var future: Future<*>? = null
        @Volatile var timeout: Future<*>? = null
        @Volatile var completedAtMillis: Long = 0
        fun snapshot(defaultMessage: String = message) = ArthasTaskSnapshot(id, state, if (message.isBlank()) defaultMessage else message)
    }
}
