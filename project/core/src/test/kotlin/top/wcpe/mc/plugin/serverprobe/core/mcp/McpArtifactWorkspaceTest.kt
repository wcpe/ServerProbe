package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class McpArtifactWorkspaceTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `工件支持有界分块写入读取列出和删除`() {
        val workspace = McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(maxBytes = 128, retentionMillis = 60_000))

        workspace.writeChunk("thread.txt", "第一段", append = false)
        workspace.writeChunk("thread.txt", "第二段", append = true)

        assertEquals("第一段第二段", workspace.readChunk("thread.txt", 0, 64).content)
        assertTrue(workspace.list().single().name == "thread.txt")
        assertTrue(workspace.delete("thread.txt"))
        assertFalse(workspace.list().isNotEmpty())
    }

    @Test
    fun `清理优先删除最早且已过期的工件`() {
        val workspace = McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(maxBytes = 16, retentionMillis = 1))
        workspace.writeChunk("old.txt", "旧内容", append = false)
        Thread.sleep(2)
        workspace.cleanup()

        assertTrue(workspace.list().isEmpty())
    }

    @Test
    fun `写入新工件后仍删除最早记录以满足总容量`() {
        val workspace = McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(maxBytes = 10, retentionMillis = 60_000))
        workspace.writeChunk("old.txt", "123456", append = false)
        Thread.sleep(2)

        workspace.writeChunk("new.txt", "abcdef", append = false)

        assertTrue(workspace.list().sumOf(McpArtifact::size) <= 10)
        assertFalse(workspace.list().any { it.name == "old.txt" })
        assertTrue(workspace.list().any { it.name == "new.txt" })
    }
}
