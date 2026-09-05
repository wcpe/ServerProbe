package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** MCP 调用审计输入；参数原文仅用于计算哈希，绝不落盘。 */
data class McpAuditRecord(
    val sourceIp: String,
    val tool: String,
    val taskId: String?,
    val durationMillis: Long,
    val result: String,
    val parameters: String,
)

/**
 * 单线程异步写入 JSONL 审计，避免 HTTP 处理线程直接执行磁盘 IO。
 *
 * 轮转与保留:活跃文件超过 [maxFileBytes] 时 gzip 轮转为 `mcp-audit-<epochMs>.jsonl.gz`;
 * gzip 归档超过 [retentionDays] 天(按文件修改时间)自动删除。默认 20MB/30 天。
 */
class McpAuditTrail(
    private val directory: Path,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) : AutoCloseable {
    private val file = directory.resolve("mcp-audit.jsonl")
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ServerProbe-MCP-Audit").apply { isDaemon = true }
    }

    init {
        Files.createDirectories(directory)
    }

    fun record(record: McpAuditRecord) {
        executor.execute {
            runCatching { append(record) }.onFailure { error ->
                ProbeLogger.warn("MCP 审计写入失败：${error.javaClass.simpleName}")
            }
        }
    }

    fun flush() {
        executor.submit {}.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    internal fun fileContent(): String = if (Files.isRegularFile(file)) String(Files.readAllBytes(file), StandardCharsets.UTF_8) else ""

    /** 活跃审计文件超限时 gzip 轮转(mcp-audit-<epochMs>.jsonl.gz),原文件清空复用。 */
    private fun rotateIfNeeded() {
        runCatching {
            if (!Files.isRegularFile(file) || Files.size(file) < maxFileBytes) {
                return
            }
            val archive = directory.resolve("mcp-audit-${System.currentTimeMillis()}.jsonl.gz")
            java.util.zip.GZIPOutputStream(Files.newOutputStream(archive), 8192).use { out ->
                Files.copy(file, out)
            }
            Files.deleteIfExists(file)
        }.onFailure { error ->
            ProbeLogger.warn("MCP 审计轮转失败：${error.javaClass.simpleName}")
        }
    }

    /** 删除超过保留期的 gzip 审计归档(retentionDays<=0 时不清理)。 */
    private fun expireOldArchives() {
        if (retentionDays <= 0) {
            return
        }
        runCatching {
            val cutoff = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY
            Files.newDirectoryStream(directory, "mcp-audit-*.jsonl.gz").use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { archive ->
                    if (Files.getLastModifiedTime(archive).toMillis() < cutoff) {
                        Files.deleteIfExists(archive)
                    }
                }
            }
        }.onFailure { error ->
            ProbeLogger.warn("MCP 审计归档清理失败：${error.javaClass.simpleName}")
        }
    }

    override fun close() {
        executor.shutdown()
        runCatching { executor.awaitTermination(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        executor.shutdownNow()
    }

    private fun append(record: McpAuditRecord) {
        rotateIfNeeded()
        expireOldArchives()
        val line = McpJsonWriter.encode(linkedMapOf(
            "timestamp" to System.currentTimeMillis(),
            "sourceIp" to record.sourceIp,
            "tool" to record.tool,
            "taskId" to record.taskId,
            "durationMillis" to record.durationMillis,
            "result" to record.result,
            "parametersSha256" to sha256(record.parameters),
        )) + "\n"
        Files.write(file, line.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FLUSH_TIMEOUT_SECONDS = 5L

        /** 活跃审计文件默认轮转阈值(字节):20MB。 */
        const val DEFAULT_MAX_FILE_BYTES: Long = 20L * 1024 * 1024

        /** gzip 归档默认保留天数。 */
        const val DEFAULT_RETENTION_DAYS = 30

        /** 一天的毫秒数。 */
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
