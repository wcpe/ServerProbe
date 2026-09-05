package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class McpAuditTrailTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `审计持久化来源和哈希但不保留敏感参数原文`() {
        val trail = McpAuditTrail(directory)
        try {
            trail.record(McpAuditRecord("127.0.0.1", "arthas_execute", "task-1", 12, "SUCCEEDED", "ognl 私密正文"))
            trail.flush()

            val content = trail.fileContent()
            assertTrue(content.contains("127.0.0.1"))
            assertTrue(content.contains("arthas_execute"))
            assertFalse(content.contains("私密正文"))
        } finally {
            trail.close()
        }
    }

    @Test
    fun `活跃文件超限时 gzip 轮转并清空原文件`() {
        val directory = tempDirectory()
        val trail = McpAuditTrail(directory, maxFileBytes = 200, retentionDays = 30)
        repeat(30) { index ->
            trail.record(McpAuditRecord("127.0.0.1", "tool$index", null, 1L, "SUCCEEDED", "{}"))
        }
        trail.flush()
        trail.close()

        val archives = directory.toFile().listFiles { f -> f.name.endsWith(".jsonl.gz") } ?: emptyArray()
        assertTrue(archives.isNotEmpty(), "超限后应有 gzip 归档产生")
        assertTrue(archives.all { it.length() > 0 })
    }

    @Test
    fun `过期审计归档自动删除`() {
        val directory = tempDirectory()
        val trail = McpAuditTrail(directory, maxFileBytes = 200, retentionDays = 30)
        repeat(30) { index ->
            trail.record(McpAuditRecord("127.0.0.1", "tool$index", null, 1L, "SUCCEEDED", "{}"))
        }
        trail.flush()
        // 把归档的修改时间拨回 60 天前,模拟过期
        val archives = directory.toFile().listFiles { f -> f.name.endsWith(".jsonl.gz") } ?: emptyArray()
        assertTrue(archives.isNotEmpty())
        val oldTime = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60L * 86_400_000)
        archives.forEach { java.nio.file.Files.setLastModifiedTime(it.toPath(), oldTime) }

        val expired = archives.map { it.toPath() }
        trail.record(McpAuditRecord("127.0.0.1", "trigger", null, 1L, "SUCCEEDED", "{}"))
        trail.flush()
        trail.close()

        // 触发记录可能又轮转出新归档,只断言"被回拨的过期归档"已删除
        assertTrue(expired.all { !java.nio.file.Files.exists(it) }, "过期的审计归档应被删除")
    }

    private fun tempDirectory(): java.nio.file.Path =
        java.nio.file.Files.createTempDirectory("mcp-audit-test")}
