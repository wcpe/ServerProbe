package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR-21 flamegraph_start / flamegraph_stop / flamegraph_view 薄封装单测。
 *
 * 覆盖：start/stop 命令构造（默认命名、毫秒时间戳、artifactName 覆盖、timeout 传递）、
 * 任务提交返回 taskId、view 的 html/jfr/其他扩展名分流、缺失/非法名结构化错误、
 * Arthas 未 attach / 工作区未装配的结构化降级。
 */
class FlamegraphToolProviderTest {

    @TempDir
    lateinit var directory: Path

    private fun workspace(): McpArtifactWorkspace =
        McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(maxBytes = 16 * 1024 * 1024, retentionMillis = 60_000))

    private fun registry(ws: McpArtifactWorkspace): McpArtifactWorkspaceRegistry = McpArtifactWorkspaceRegistry().apply { register(ws) }

    /** 固定毫秒时间戳的 provider：默认命名可精确断言。 */
    private fun provider(
        ws: McpArtifactWorkspace = workspace(),
        control: ArthasControl = RecordingArthasControl(),
        clockMillis: Long = FIXED_EPOCH_MILLIS,
    ): FlamegraphToolProvider = FlamegraphToolProvider(
        testWorkspaceRegistry = registry(ws),
        arthasControl = control,
    ).apply { this.clockMillis = clockMillis }

    private fun arguments(vararg values: Pair<String, Any?>): JsonObject = object : JsonObject {
        private val map = values.toMap()
        override fun getRaw(key: String): Any? = map[key]
        override fun getString(key: String, default: String): String = map[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = map[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = emptyList()
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun getObject(key: String): JsonObject? = null
    }

    private fun write(name: String, content: String) {
        Files.write(directory.resolve(name), content.toByteArray(Charsets.UTF_8))
    }

    /** arthasPath 等价：Windows 路径统一正斜杠，与既有 NativeMcpToolProvider 一致。 */
    private fun expectedPath(name: String): String =
        directory.resolve(name).toAbsolutePath().toString().replace('\\', '/')

    @Test
    fun `flamegraph_start 提交 profiler start 异步任务并返回 taskId`() {
        val control = RecordingArthasControl()
        val provider = provider(control = control)

        val result = provider.call("flamegraph_start", arguments("timeoutMillis" to 5_000))

        assertEquals("profiler start", control.command)
        assertEquals(5_000L, control.timeoutMillis)
        assertEquals("task-1", result["taskId"])
    }

    @Test
    fun `flamegraph_start 未注册 Arthas 时结构化降级`() {
        // 空 ArthasControlRegistry 等价于"Arthas 未 attach"：submit 返回 FAILED + 未注册提示
        val provider = FlamegraphToolProvider(
            testWorkspaceRegistry = registry(workspace()),
            arthasControl = ArthasControlRegistry(),
        )

        val result = provider.call("flamegraph_start", arguments())

        assertEquals(false, result["available"])
        assertTrue((result["reason"] as String).contains("Arthas"))
    }

    @Test
    fun `flamegraph_stop 默认命名使用毫秒时间戳`() {
        val control = RecordingArthasControl()
        val provider = provider(control = control, clockMillis = FIXED_EPOCH_MILLIS)

        val result = provider.call("flamegraph_stop", arguments())

        assertEquals("profiler stop --file '${expectedPath("flamegraph-$FIXED_EPOCH_MILLIS.html")}'", control.command)
        assertEquals("task-1", result["taskId"])
    }

    @Test
    fun `flamegraph_stop 支持 artifactName 覆盖默认名`() {
        val control = RecordingArthasControl()
        val provider = provider(control = control, clockMillis = FIXED_EPOCH_MILLIS)

        provider.call("flamegraph_stop", arguments("artifactName" to "custom.html"))

        assertEquals("profiler stop --file '${expectedPath("custom.html")}'", control.command)
    }

    @Test
    fun `flamegraph_stop 未装配工作区时结构化降级`() {
        val provider = FlamegraphToolProvider(
            testWorkspaceRegistry = McpArtifactWorkspaceRegistry(),
            arthasControl = ArthasControlRegistry(),
        )

        val result = provider.call("flamegraph_stop", arguments())

        assertEquals(false, result["available"])
        assertTrue((result["reason"] as String).contains("工作区"))
    }

    @Test
    fun `flamegraph_view html 按文本分块语义返回`() {
        val content = "<html>flamegraph</html>"
        write("fg.html", content)
        val provider = provider()

        val result = provider.call("flamegraph_view", arguments("artifactName" to "fg.html"))

        assertEquals("fg.html", result["name"])
        assertEquals("html", result["kind"])
        assertEquals(content, result["content"])
        assertEquals(content.toByteArray(Charsets.UTF_8).size.toLong(), result["nextOffset"])
        assertEquals(false, result["truncated"])
    }

    @Test
    fun `flamegraph_view html 支持 offset 分页续读`() {
        val payload = "abcdefghijklmnopqrstuvwxyz"
        write("fg2.html", payload)
        val provider = provider()

        val first = provider.call("flamegraph_view", arguments("artifactName" to "fg2.html", "maxBytes" to 10))
        assertEquals("abcdefghij", first["content"])
        assertEquals(10L, first["nextOffset"])
        assertEquals(true, first["truncated"])

        val second = provider.call("flamegraph_view", arguments("artifactName" to "fg2.html", "offset" to 10, "maxBytes" to 100))
        assertEquals("klmnopqrstuvwxyz", second["content"])
        assertEquals(payload.length.toLong(), second["nextOffset"])
        assertEquals(false, second["truncated"])
    }

    @Test
    fun `flamegraph_view jfr 返回元信息与取回提示`() {
        val bytes = ByteArray(2048) { index -> (index % 251).toByte() }
        Files.write(directory.resolve("dump.jfr"), bytes)
        val provider = provider()
        val expectedMillis = Files.getLastModifiedTime(directory.resolve("dump.jfr")).toMillis()

        val result = provider.call("flamegraph_view", arguments("artifactName" to "dump.jfr"))

        assertEquals("dump.jfr", result["artifactName"])
        assertEquals("jfr", result["kind"])
        assertEquals(2048L, result["size"])
        assertEquals(expectedMillis, result["modifiedAtMillis"])
        assertTrue((result["hint"] as String).contains("artifact_read_binary"))
    }

    @Test
    fun `flamegraph_view 其他扩展名结构化错误`() {
        write("dump.bin", "binary")
        val provider = provider()

        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.call("flamegraph_view", arguments("artifactName" to "dump.bin"))
        }

        assertTrue(error.message!!.contains("bin"))
    }

    @Test
    fun `flamegraph_view 非法工件名被拒绝`() {
        val provider = provider()

        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.call("flamegraph_view", arguments("artifactName" to "../x.html"))
        }

        assertTrue(error.message!!.contains("工件名称"))
    }

    @Test
    fun `flamegraph_view 不存在的工件结构化错误`() {
        val provider = provider()

        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.call("flamegraph_view", arguments("artifactName" to "missing.html"))
        }

        assertTrue(error.message!!.contains("不存在"))
    }

    @Test
    fun `flamegraph_view 未装配工作区时结构化降级`() {
        val provider = FlamegraphToolProvider(
            testWorkspaceRegistry = McpArtifactWorkspaceRegistry(),
            arthasControl = ArthasControlRegistry(),
        )

        val result = provider.call("flamegraph_view", arguments("artifactName" to "fg.html"))

        assertEquals(false, result["available"])
        assertTrue((result["reason"] as String).contains("工作区"))
    }

    @Test
    fun `工具目录暴露三个 flamegraph 工具`() {
        val tools = provider().tools()

        assertEquals(setOf("flamegraph_start", "flamegraph_stop", "flamegraph_view"), tools.map { it.name }.toSet())
        val view = tools.first { it.name == "flamegraph_view" }
        assertEquals(setOf("artifactName", "offset", "maxBytes"), view.inputSchema.keys)
        assertFalse(tools.any { it.name == "arthas_profiler" })
    }

    private class RecordingArthasControl : ArthasControl {
        var command = ""
        var timeoutMillis = -1L
        var submits = 0
        override fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot {
            submits++
            command = request.command
            timeoutMillis = request.timeoutMillis
            return ArthasTaskSnapshot("task-1", ArthasTaskState.QUEUED, "已提交")
        }
        override fun status(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.QUEUED, "")
        override fun output(taskId: String, offset: Int) = ArthasTaskOutput(taskId, "", offset, false)
        override fun cancel(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "")
    }

    private companion object {
        /** 固定毫秒时间戳（2023-11-14 22:13:20 UTC），保证默认命名断言可重复。 */
        const val FIXED_EPOCH_MILLIS = 1_700_000_000_123L
    }
}
