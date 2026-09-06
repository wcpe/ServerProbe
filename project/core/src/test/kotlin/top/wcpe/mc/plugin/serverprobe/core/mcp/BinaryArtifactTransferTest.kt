package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.nio.file.Path
import java.util.Base64

/**
 * FR-18 二进制分块读取：覆盖字节往返一致、offset 定位、nextOffset 连续、
 * truncated 边界、offset 越界（coerceIn）、空文件、非法名拒绝。
 */
class McpArtifactWorkspaceBinaryTest {

    @TempDir
    lateinit var directory: Path

    private fun workspace(): McpArtifactWorkspace = McpArtifactWorkspace(
        directory,
        ArtifactWorkspaceSettings(maxBytes = 16 * 1024 * 1024, retentionMillis = 60_000),
    )

    private fun write(name: String, bytes: ByteArray) {
        java.nio.file.Files.write(directory.resolve(name), bytes)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }

    /** 二进制往返一致：0 字节、小块、恰好 1 MiB 分块上限与分块读取。 */
    @Test
    fun `二进制分块往返与原始字节一致`() {
        val ws = workspace()
        val payload = randomBytes(70000)
        write("dump.bin", payload)

        val first = ws.readBinaryChunk("dump.bin", 0, 64 * 1024)
        assertArrayEquals(payload.copyOfRange(0, 64 * 1024), first.content)
        assertEquals(64 * 1024L, first.nextOffset)
        assertTrue(first.truncated)

        val second = ws.readBinaryChunk("dump.bin", first.nextOffset, 64 * 1024)
        assertArrayEquals(payload.copyOfRange(64 * 1024, 70000), second.content)
        assertEquals(70000L, second.nextOffset)
        assertFalse(second.truncated)
    }

    /** 空文件：contentBase64 为空串、nextOffset=0、truncated=false。 */
    @Test
    fun `空文件返回空内容与零游标`() {
        val ws = workspace()
        write("empty.bin", ByteArray(0))

        val chunk = ws.readBinaryChunk("empty.bin", 0, 64 * 1024)

        assertTrue(chunk.content.isEmpty())
        assertEquals(0L, chunk.nextOffset)
        assertFalse(chunk.truncated)
    }

    /** offset 超出文件末尾：coerceIn(0,size) 后读到空、nextOffset=size、truncated=false。 */
    @Test
    fun `offset 超出文件末尾按末尾裁剪`() {
        val ws = workspace()
        write("tail.bin", randomBytes(100))

        val chunk = ws.readBinaryChunk("tail.bin", 500, 64 * 1024)

        assertTrue(chunk.content.isEmpty())
        assertEquals(100L, chunk.nextOffset)
        assertFalse(chunk.truncated)
    }

    /** 恰好等于分块大小：读满一整块但 nextOffset==size，truncated=false。 */
    @Test
    fun `恰好等于分块大小时不截断`() {
        val ws = workspace()
        val payload = randomBytes(64 * 1024)
        write("exact.bin", payload)

        val chunk = ws.readBinaryChunk("exact.bin", 0, 64 * 1024)

        assertArrayEquals(payload, chunk.content)
        assertEquals(64 * 1024L, chunk.nextOffset)
        assertFalse(chunk.truncated)
    }

    /** maxBytes 超上限被裁剪到 1 MiB，超 1 MiB 文件首块读满 1 MiB 并截断。 */
    @Test
    fun `超 1 MiB 文件首块按上限读取并截断`() {
        val ws = workspace()
        val payload = randomBytes(1024 * 1024 + 17)
        write("big.bin", payload)

        val chunk = ws.readBinaryChunk("big.bin", 0, Int.MAX_VALUE)

        assertEquals(1024 * 1024, chunk.content.size)
        assertArrayEquals(payload.copyOfRange(0, 1024 * 1024), chunk.content)
        assertEquals(1024 * 1024L, chunk.nextOffset)
        assertTrue(chunk.truncated)
    }

    /** 名称白名单：非法名被 resolve 拒绝，抛 IllegalArgumentException。 */
    @Test
    fun `非法工件名被拒绝`() {
        val ws = workspace()

        assertThrows(IllegalArgumentException::class.java) {
            ws.readBinaryChunk("../etc/passwd", 0, 64 * 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ws.readBinaryChunk("a/b.bin", 0, 64 * 1024)
        }
    }
}

/**
 * FR-18 artifact_read_binary：Base64 往返、游标、空文件、非法名与缺失文件结构化错误。
 */
class BinaryArtifactToolProviderTest {

    @TempDir
    lateinit var directory: Path

    private fun workspace(): McpArtifactWorkspace =
        McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(maxBytes = 16 * 1024 * 1024, retentionMillis = 60_000))

    private fun write(name: String, bytes: ByteArray) {
        java.nio.file.Files.write(directory.resolve(name), bytes)
    }

    private fun provider(ws: McpArtifactWorkspace): BinaryArtifactToolProvider {
        val registry = McpArtifactWorkspaceRegistry().apply { register(ws) }
        return BinaryArtifactToolProvider(registry)
    }

    private fun arguments(vararg values: Pair<String, Any?>): JsonObject = object : top.wcpe.mc.plugin.serverprobe.core.json.JsonObject {
        private val map = values.toMap()
        override fun getRaw(key: String): Any? = map[key]
        override fun getString(key: String, default: String): String = map[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = map[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = emptyList()
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun getObject(key: String): top.wcpe.mc.plugin.serverprobe.core.json.JsonObject? = null
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }

    /** 默认参数：name 必填，offset 默认 0，maxBytes 默认 64 KiB。 */
    @Test
    fun `二进制工具 Base64 往返一致`() {
        val payload = randomBytes(70_000)
        write("heap.hprof", payload)

        val result = provider(workspace()).call("artifact_read_binary", arguments("name" to "heap.hprof"))

        assertEquals("heap.hprof", result["name"])
        assertEquals(70_000L, result["size"])
        assertArrayEquals(payload.copyOfRange(0, 64 * 1024), Base64.getDecoder().decode(result["contentBase64"] as String))
        assertEquals(64 * 1024L, result["nextOffset"])
        assertEquals(true, result["truncated"])
    }

    /** 分页：从 nextOffset 循环续读，还原整文件与原始字节一致。 */
    @Test
    fun `游标翻页还原整文件`() {
        val payload = randomBytes(200_000)
        write("jfr.bin", payload)
        val provider = provider(workspace())

        val output = java.io.ByteArrayOutputStream()
        var offset = 0L
        var truncated = true
        var lastNextOffset = 0L
        while (truncated) {
            val page = provider.call("artifact_read_binary", arguments("name" to "jfr.bin", "offset" to offset))
            output.write(Base64.getDecoder().decode(page["contentBase64"] as String))
            offset = page["nextOffset"] as Long
            truncated = page["truncated"] as Boolean
            lastNextOffset = offset
        }
        val assembled = output.toByteArray()

        assertArrayEquals(payload, assembled)
        assertEquals(200_000L, lastNextOffset)
    }

    /** 空文件：contentBase64 空串、nextOffset=0、truncated=false、size=0。 */
    @Test
    fun `空文件返回空 Base64 与零游标`() {
        write("empty.bin", ByteArray(0))

        val result = provider(workspace()).call("artifact_read_binary", arguments("name" to "empty.bin"))

        assertEquals("", result["contentBase64"])
        assertEquals(0L, result["nextOffset"])
        assertEquals(false, result["truncated"])
        assertEquals(0L, result["size"])
    }

    /** 缺省 maxBytes=64KiB、上限 1MiB：超限请求被裁剪，1MiB Base64 无 MAX_ITEMS 截断问题。 */
    @Test
    fun `maxBytes 默认值与上限裁剪`() {
        write("limit.bin", randomBytes(1024 * 1024 + 5))

        val result = provider(workspace()).call(
            "artifact_read_binary",
            arguments("name" to "limit.bin", "offset" to 0, "maxBytes" to Int.MAX_VALUE),
        )

        assertEquals(1024 * 1024, Base64.getDecoder().decode(result["contentBase64"] as String).size)
        assertEquals(true, result["truncated"])
    }

    /** 非法名与缺失文件均为结构化错误（IllegalArgumentException 消息透出）。 */
    @Test
    fun `非法名与缺失文件结构化错误`() {
        val provider = provider(workspace())

        val invalid = assertThrows(IllegalArgumentException::class.java) {
            provider.call("artifact_read_binary", arguments("name" to "../x.bin"))
        }
        assertTrue(invalid.message!!.contains("工件名称"))

        val missing = assertThrows(IllegalArgumentException::class.java) {
            provider.call("artifact_read_binary", arguments("name" to "missing.bin"))
        }
        assertTrue(missing.message!!.contains("不存在"))
    }

    /** 工具目录：只暴露 artifact_read_binary 且入参契约符合 spec。 */
    @Test
    fun `工具目录包含 artifact_read_binary`() {
        val tool = provider(workspace()).tools().single()

        assertEquals("artifact_read_binary", tool.name)
        assertEquals(setOf("name", "offset", "maxBytes"), tool.inputSchema.keys)
    }
}
