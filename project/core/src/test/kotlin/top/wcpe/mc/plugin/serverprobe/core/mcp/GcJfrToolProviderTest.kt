package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.nio.file.Path

/** GcJfrToolProvider 单测：gc_events/gc_stats 差分、jfr 命令构造与降级。 */
class GcJfrToolProviderTest {

    @TempDir
    lateinit var directory: Path

    lateinit var arthas: FakeArthasControl

    private fun provider(): GcJfrToolProvider {
        arthas = FakeArthasControl()
        return GcJfrToolProvider(
            arthas,
            McpArtifactWorkspaceRegistry().apply {
                register(McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(16 * 1024 * 1024, 60_000)))
            },
        )
    }

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

    @Test
    fun `gc_events 首采返回零增量`() {
        val result = provider().call("gc_events", null)

        val events = result["events"] as List<*>
        assertTrue(events.isNotEmpty())
        events.forEach { event ->
            val map = event as Map<*, *>
            assertEquals(0L, map["countDelta"])
        }
    }

    @Test
    fun `gc_stats 返回堆与内存池字段`() {
        val result = provider().call("gc_stats", null)

        assertNotNull(result["heapUsedKb"])
        assertNotNull(result["heapCommittedKb"])
        assertNotNull(result["pools"])
        assertNotNull(result["gcTotalCount"])
        assertNotNull(result["gcCountDelta"])
    }

    @Test
    fun `jfr_start 提交异步任务返回 taskId`() {
        val provider = provider()
        val result = provider.call("jfr_start", null)

        assertEquals("task-1", result["taskId"])
        assertEquals("QUEUED", result["state"])
    }

    @Test
    fun `jfr_stop 默认命名并写工作区路径`() {
        val provider = provider()
        val result = provider.call("jfr_stop", arguments("artifactName" to "trace.jfr"))

        assertNotNull(result["taskId"])
        assertTrue(arthas.commands.single().contains("jfr stop"))
        assertTrue(arthas.commands.single().contains("trace.jfr"))
    }

    @Test
    fun `工具目录包含 gc 与 jfr 工具`() {
        val names = provider().tools().map { it.name }.toSet()

        assertEquals(setOf("gc_events", "gc_stats", "jfr_start", "jfr_stop"), names)
    }

    /** 测试用 Arthas 控制器：记录提交命令。 */
    class FakeArthasControl : ArthasControl {
        val commands = mutableListOf<String>()

        override fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot {
            commands += request.command
            return ArthasTaskSnapshot("task-1", ArthasTaskState.QUEUED, "已入队")
        }

        override fun status(taskId: String): ArthasTaskSnapshot =
            ArthasTaskSnapshot(taskId, ArthasTaskState.QUEUED, "已入队")

        override fun output(taskId: String, offset: Int): ArthasTaskOutput =
            ArthasTaskOutput(taskId, "", offset, false)

        override fun cancel(taskId: String): ArthasTaskSnapshot =
            ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "已取消")
    }
}
