package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** 原生诊断工具必须只返回有界、可序列化的诊断数据。 */
class NativeMcpToolProviderTest {

    private val provider = NativeMcpToolProvider(serverStatus = {
        linkedMapOf("状态" to "正常", "onlinePlayers" to 2)
    })

    @Test
    fun `状态工具返回调用方提供的服务器证据`() {
        val result = provider.call("server_status", null)

        assertEquals("正常", result["状态"])
    }

    @Test
    fun `线程工具返回有界的结构化数据`() {
        val top = provider.call("thread_top", null)
        val dump = provider.call("thread_dump", null)
        val deadlocks = provider.call("thread_deadlocks", null)

        assertNotNull(top["available"])
        assertTrue((dump["threads"] as List<*>).isNotEmpty())
        assertTrue((dump["threads"] as List<*>).size <= NativeThreadDiagnostics.MAX_THREADS)
        assertFalse((deadlocks["deadlocked"] as List<*>).isNotEmpty())
    }

    @Test
    fun `平台命令工具传递命令并返回有界输出`() {
        val provider = NativeMcpToolProvider(
            serverStatus = { emptyMap() },
            platformControl = object : PlatformControl {
                override fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult> {
                    assertEquals("list", request.command)
                    request.output.append("在线 2 名")
                    return CompletableFuture.completedFuture(PlatformCommandResult.success(request.output.snapshot()))
                }
            },
        )

        val result = provider.call("server_command", object : JsonObject {
            override fun getString(key: String, default: String): String = if (key == "command") "list" else default
            override fun getInt(key: String, default: Int): Int = default
            override fun getLong(key: String, default: Long): Long = default
            override fun getDouble(key: String, default: Double): Double = default
            override fun getBoolean(key: String, default: Boolean): Boolean = default
            override fun getStringList(key: String): List<String> = emptyList()
            override fun contains(key: String): Boolean = key == "command"
            override fun getObject(key: String): JsonObject? = null
        })

        assertTrue(result["success"] as Boolean)
        assertEquals("在线 2 名", result["output"])
    }

    @Test
    fun `显式重试 attach 返回诊断控制器的降级或恢复状态`() {
        val provider = NativeMcpToolProvider(
            serverStatus = { emptyMap() },
            arthasControl = object : ArthasControl {
                override fun submit(request: ArthasCommandRequest) = ArthasTaskSnapshot("", ArthasTaskState.FAILED, "未使用")
                override fun status(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.FAILED, "未使用")
                override fun output(taskId: String, offset: Int) = ArthasTaskOutput(taskId, "", offset, false)
                override fun cancel(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.FAILED, "未使用")
                override fun retryAttach() = ArthasInstrumentationSnapshot(true, "DYNAMIC_ATTACH", "动态附加成功")
            },
        )

        val result = provider.call("arthas_retry_attach", null)

        assertTrue(result["available"] as Boolean)
        assertEquals("DYNAMIC_ATTACH", result["source"])
    }

    @Test
    fun `结构化 Arthas 工具校验参数并提交异步任务`() {
        val control = RecordingArthasControl()
        val provider = NativeMcpToolProvider(serverStatus = { emptyMap() }, arthasControl = control, defaultArthasTimeoutMillis = 5_000)

        val args = arguments(
            "className" to "example.Target",
            "methodName" to "run",
            "expression" to "{params,returnObj}",
            "maxMatches" to 1,
            "timeoutMillis" to 99_999,
        )
        val result = provider.call("arthas_watch", args)

        assertEquals("watch example.Target run '{params,returnObj}' -n 1", control.command)
        assertEquals(5_000L, control.timeoutMillis)
        assertEquals("task-1", result["taskId"])
    }

    @Test
    fun `结构化 Arthas 方法工具拒绝非正匹配上限`() {
        val provider = NativeMcpToolProvider(serverStatus = { emptyMap() }, arthasControl = RecordingArthasControl())

        assertThrows(IllegalArgumentException::class.java) {
            provider.call("arthas_trace", arguments("className" to "example.Target", "methodName" to "run", "maxMatches" to 0))
        }
    }

    @Test
    fun `诊断包包含有界线程转储`() {
        val bundle = provider.call("diagnostic_bundle", null)

        assertNotNull(bundle["serverStatus"])
        assertNotNull(bundle["threadTop"])
        assertNotNull(bundle["threadDump"])
        assertNotNull(bundle["threadDeadlocks"])
    }

    @Test
    fun `采样器工具以异步任务提交合法动作`() {
        val control = RecordingArthasControl()
        val provider = NativeMcpToolProvider(serverStatus = { emptyMap() }, arthasControl = control)

        val result = provider.call("arthas_profiler", arguments("action" to "status"))

        assertEquals("profiler status", control.command)
        assertEquals("task-1", result["taskId"])
    }

    @Test
    fun `结构化 Arthas 方法工具拒绝非法标识符`() {
        val provider = NativeMcpToolProvider(serverStatus = { emptyMap() }, arthasControl = RecordingArthasControl())

        assertThrows(IllegalArgumentException::class.java) {
            provider.call("arthas_trace", arguments("className" to "example.Target;", "methodName" to "run"))
        }
    }

    @Test
    fun `类替换只读取 MCP 工件工作区`(@TempDir directory: Path) {
        val workspace = McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(128, 60_000))
        workspace.writeChunk("replacement.class", "字节码", append = false)
        val control = RecordingArthasControl()
        val provider = NativeMcpToolProvider(serverStatus = { emptyMap() }, arthasControl = control, artifacts = workspace)

        provider.call("arthas_redefine", arguments("artifactName" to "replacement.class"))

        assertEquals("redefine '${directory.resolve("replacement.class").toAbsolutePath().toString().replace('\\', '/')}'", control.command)
    }

    private fun arguments(vararg values: Pair<String, Any?>): JsonObject = arguments(values.toMap())

    private fun arguments(values: Map<String, Any?>): JsonObject = object : JsonObject {
        override fun getRaw(key: String): Any? = values[key]
        override fun getString(key: String, default: String): String = values[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = values[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = emptyList()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getObject(key: String): JsonObject? = null
    }

    private class RecordingArthasControl : ArthasControl {
        var command = ""
        var timeoutMillis = -1L
        override fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot {
            command = request.command
            timeoutMillis = request.timeoutMillis
            return ArthasTaskSnapshot("task-1", ArthasTaskState.QUEUED, "已提交")
        }
        override fun status(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.QUEUED, "")
        override fun output(taskId: String, offset: Int) = ArthasTaskOutput(taskId, "", offset, false)
        override fun cancel(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "")
    }
}
