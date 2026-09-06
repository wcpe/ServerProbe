package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR-19 补丁前自动备份：
 * - 命名转义（`.`→`_`）与同时间戳冲突消歧（`_2` 后缀）；
 * - `dumpClassBytes` 默认 null 兼容路径（WARN+跳过+继续替换）与空字节/异常拒绝替换；
 * - `artifact_backup_list` 按 `backup_` 前缀过滤；
 * - `artifact_backup_restore` 写回 `restored_` 前缀并校验非法名/前缀；
 * - 多 provider 组合下替换调用被本 provider 拦截（dispatcher 级接线验证）。
 */
class PrePatchBackupToolProviderTest {

    @TempDir
    lateinit var directory: Path

    private fun workspace(): McpArtifactWorkspace =
        McpArtifactWorkspace(directory, ArtifactWorkspaceSettings(maxBytes = 16 * 1024 * 1024, retentionMillis = 60_000))

    private fun provider(
        ws: McpArtifactWorkspace,
        arthasControl: ArthasControl = FakeArthasControl(),
        clock: Long = 1000L,
    ): PrePatchBackupToolProvider {
        val registry = McpArtifactWorkspaceRegistry().apply { register(ws) }
        return PrePatchBackupToolProvider(registry, arthasControl).apply { clockMillis = clock }
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

    private fun readBytes(ws: McpArtifactWorkspace, name: String): ByteArray = Files.readAllBytes(ws.outputPath(name))

    private fun writeBytes(ws: McpArtifactWorkspace, name: String, bytes: ByteArray) {
        Files.write(ws.outputPath(name), bytes)
    }

    /** 替换前备份：类名 `.`→`_` 转义、备份写入成功才提交替换、命令与工件内容正确。 */
    @Test
    fun `替换前自动备份命名转义并提交替换`() {
        listOf("arthas_redefine" to "redefine", "arthas_retransform" to "retransform").forEachIndexed { index, (tool, operation) ->
            val ws = McpArtifactWorkspace(
                directory.resolve("case-$index"),
                ArtifactWorkspaceSettings(maxBytes = 16 * 1024 * 1024, retentionMillis = 60_000),
            )
            writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
            val arthas = FakeArthasControl { byteArrayOf(1, 2, 3) }
            val p = provider(ws, arthas)

            val result = p.call(tool, arguments("artifactName" to "patch.class", "className" to "com.example.Foo"))

            val backup = ws.list().single { it.name.startsWith("backup_") }
            assertEquals("backup_com_example_Foo_1000.class", backup.name)
            assertArrayEquals(byteArrayOf(1, 2, 3), readBytes(ws, backup.name))
            assertEquals(
                "$operation '${ws.outputPath("patch.class").toAbsolutePath().toString().replace('\\', '/')}'",
                arthas.commands.single(),
            )
            assertEquals("task-1", result["taskId"])
        }
    }

    /** 兼容路径：dumpClassBytes 默认 null（能力缺失）→ WARN+跳过备份+继续替换。 */
    @Test
    fun `dumpClassBytes 默认 null 时跳过备份继续替换`() {
        val ws = workspace()
        writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
        val arthas = FakeArthasControl() // 不覆写 dumpClassBytes → 默认 null
        val p = provider(ws, arthas)

        val result = p.call("arthas_redefine", arguments("artifactName" to "patch.class", "className" to "com.example.Foo"))

        assertTrue(ws.list().none { it.name.startsWith("backup_") }, "能力缺失不应产生备份工件")
        assertEquals(1, arthas.commands.size)
        assertTrue(result.containsKey("taskId"))
    }

    /** 兼容路径：未提供 className 无法定位已加载类 → 跳过备份继续替换。 */
    @Test
    fun `未提供 className 时跳过备份继续替换`() {
        val ws = workspace()
        writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
        val arthas = FakeArthasControl { byteArrayOf(1) }
        val p = provider(ws, arthas)

        p.call("arthas_redefine", arguments("artifactName" to "patch.class"))

        assertTrue(ws.list().none { it.name.startsWith("backup_") })
        assertEquals(1, arthas.commands.size)
    }

    /** 拒绝路径：实现已提供但读到空字节（读取失败）→ 拒绝替换且不提交。 */
    @Test
    fun `dumpClassBytes 返回空字节时拒绝替换`() {
        val ws = workspace()
        writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
        val arthas = FakeArthasControl { ByteArray(0) }
        val p = provider(ws, arthas)

        val error = assertThrows(IllegalArgumentException::class.java) {
            p.call("arthas_redefine", arguments("artifactName" to "patch.class", "className" to "com.example.Foo"))
        }
        assertTrue(error.message!!.contains("备份"))
        assertTrue(ws.list().none { it.name.startsWith("backup_") })
        assertTrue(arthas.commands.isEmpty())
    }

    /** 拒绝路径：dumpClassBytes 抛异常 → 拒绝替换且不提交。 */
    @Test
    fun `dumpClassBytes 抛异常时拒绝替换`() {
        val ws = workspace()
        writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
        val arthas = FakeArthasControl { error("读取失败") }
        val p = provider(ws, arthas)

        val error = assertThrows(IllegalArgumentException::class.java) {
            p.call("arthas_retransform", arguments("artifactName" to "patch.class", "className" to "com.example.Foo"))
        }
        assertTrue(error.message!!.contains("备份"))
        assertTrue(arthas.commands.isEmpty())
    }

    /** 非法 className 显式传值 → 拒绝替换（防备份名注入）。 */
    @Test
    fun `非法 className 拒绝替换`() {
        val ws = workspace()
        writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
        val p = provider(ws)

        assertThrows(IllegalArgumentException::class.java) {
            p.call("arthas_redefine", arguments("artifactName" to "patch.class", "className" to "../evil"))
        }
        assertTrue(ws.list().none { it.name.startsWith("backup_") })
    }

    /** 命名冲突消歧：同一类同一毫秒两次替换 → 第二个备份名追加 `_2` 后缀。 */
    @Test
    fun `同类同时间戳备份命名冲突消歧`() {
        val ws = workspace()
        writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
        val arthas = FakeArthasControl { byteArrayOf(1) }
        val p = provider(ws, arthas, clock = 1000L)
        val args = arguments("artifactName" to "patch.class", "className" to "com.example.Foo")

        p.call("arthas_redefine", args)
        p.call("arthas_redefine", args)

        val names = ws.list().filter { it.name.startsWith("backup_") }.map { it.name }.sorted()
        assertEquals(listOf("backup_com_example_Foo_1000.class", "backup_com_example_Foo_1000_2.class"), names)
    }

    /** artifact_backup_list：只返回 backup_ 前缀工件，元数据齐全。 */
    @Test
    fun `备份列表仅过滤 backup_ 前缀工件`() {
        val ws = workspace()
        writeBytes(ws, "backup_com_example_Foo_1000.class", byteArrayOf(1))
        writeBytes(ws, "normal.txt", byteArrayOf(2))
        val p = provider(ws)

        val result = p.call("artifact_backup_list", null)

        val backups = result["backups"] as List<*>
        assertEquals(1, backups.size)
        val item = backups.single() as Map<*, *>
        assertEquals("backup_com_example_Foo_1000.class", item["name"])
        assertEquals(1L, item["size"])
        assertTrue(item.containsKey("modifiedAtMillis"))
    }

    /** artifact_backup_restore：字节码原样写回 restored_ 前缀工件并返回新工件名。 */
    @Test
    fun `备份恢复写回 restored_ 前缀工件内容一致`() {
        val ws = workspace()
        val original = byteArrayOf(10, 20, 30)
        writeBytes(ws, "backup_com_example_Foo_1000.class", original)
        val p = provider(ws)

        val result = p.call("artifact_backup_restore", arguments("backupName" to "backup_com_example_Foo_1000.class"))

        assertEquals("restored_backup_com_example_Foo_1000.class", result["name"])
        assertArrayEquals(original, readBytes(ws, "restored_backup_com_example_Foo_1000.class"))
        assertEquals(3L, result["size"])
    }

    /** artifact_backup_restore：非 backup_ 前缀、非法名、缺失工件均结构化拒绝。 */
    @Test
    fun `备份恢复拒绝非法名与前缀`() {
        val ws = workspace()
        val p = provider(ws)

        val noPrefix = assertThrows(IllegalArgumentException::class.java) {
            p.call("artifact_backup_restore", arguments("backupName" to "normal.txt"))
        }
        assertTrue(noPrefix.message!!.contains("backup_"))

        val traversal = assertThrows(IllegalArgumentException::class.java) {
            p.call("artifact_backup_restore", arguments("backupName" to "../x.class"))
        }
        assertTrue(traversal.message!!.contains("工件名称"))

        val missing = assertThrows(IllegalArgumentException::class.java) {
            p.call("artifact_backup_restore", arguments("backupName" to "backup_missing.class"))
        }
        assertTrue(missing.message!!.contains("不存在"))
    }

    /** 工作区未装配：备份工具结构化降级，替换拦截跳过备份并透传工作区缺失错误。 */
    @Test
    fun `工作区未装配时备份工具降级且替换报缺失`() {
        val emptyRegistry = McpArtifactWorkspaceRegistry()
        val p = PrePatchBackupToolProvider(emptyRegistry, FakeArthasControl())

        val listResult = p.call("artifact_backup_list", null)
        assertEquals(false, listResult["available"])

        val restoreResult = p.call("artifact_backup_restore", arguments("backupName" to "backup_x.class"))
        assertEquals(false, restoreResult["available"])

        val error = assertThrows(IllegalArgumentException::class.java) {
            p.call("arthas_redefine", arguments("artifactName" to "patch.class", "className" to "com.example.Foo"))
        }
        assertTrue(error.message!!.contains("工作区"))
    }

    /** 工具目录：三个备份/拦截工具入参契约符合 spec。 */
    @Test
    fun `工具目录包含备份工具与替换拦截工具`() {
        val p = provider(workspace())
        val names = p.tools().map { it.name }.toSet()

        assertEquals(setOf("artifact_backup_list", "artifact_backup_restore", "arthas_redefine", "arthas_retransform"), names)
        val restore = p.tools().first { it.name == "artifact_backup_restore" }
        assertEquals(setOf("backupName"), restore.inputSchema.keys)
        val redefine = p.tools().first { it.name == "arthas_redefine" }
        assertEquals(setOf("artifactName", "className", "timeoutMillis"), redefine.inputSchema.keys)
    }

    /** 接线验证：Native 在前、本 provider 在后时，替换调用被拦截并产生备份（dispatcher 级）。 */
    @Test
    fun `多 provider 组合时替换调用路由到备份拦截`() {
        val ws = workspace()
        writeBytes(ws, "patch.class", byteArrayOf(9, 8, 7))
        val arthas = FakeArthasControl { byteArrayOf(7, 8) }
        val native = NativeMcpToolProvider(serverStatus = { mapOf("ok" to true) }, arthasControl = arthas, artifacts = ws)
        val backupProvider = provider(ws, arthas)
        val dispatcher = McpJsonRpcDispatcher(listOf(native, backupProvider)) { value -> value.toString() }

        val response = dispatcher.dispatch(
            MapJsonObject(mapOf(
                "jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to mapOf(
                    "name" to "arthas_redefine",
                    "arguments" to mapOf("artifactName" to "patch.class", "className" to "com.example.Foo"),
                ),
            )),
        )!!

        assertTrue(response.containsKey("result"), "应路由到备份拦截并成功提交替换")
        assertTrue(ws.list().any { it.name == "backup_com_example_Foo_1000.class" })
        assertEquals(1, arthas.commands.size)
    }

    /** 测试用 Arthas 控制器：记录提交命令，可按类名提供字节码或抛异常。 */
    private class FakeArthasControl(
        private val dumper: (String) -> ByteArray? = { null },
    ) : ArthasControl {
        val commands = mutableListOf<String>()

        override fun dumpClassBytes(className: String): ByteArray? = dumper(className)

        override fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot {
            commands += request.command
            return ArthasTaskSnapshot("task-1", ArthasTaskState.QUEUED, "已入队")
        }

        override fun status(taskId: String): ArthasTaskSnapshot = ArthasTaskSnapshot(taskId, ArthasTaskState.QUEUED, "")
        override fun output(taskId: String, offset: Int): ArthasTaskOutput = ArthasTaskOutput(taskId, "", offset, false)
        override fun cancel(taskId: String): ArthasTaskSnapshot = ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "")
        override fun retryAttach(): ArthasInstrumentationSnapshot = ArthasInstrumentationSnapshot(false, "TEST", "")
    }

    private class MapJsonObject(private val values: Map<String, Any?>) : JsonObject {
        override fun getString(key: String, default: String): String = values[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = values[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = values[key] as? List<String> ?: emptyList()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getObject(key: String): JsonObject? = (values[key] as? Map<String, Any?>)?.let(::MapJsonObject)
        override fun getRaw(key: String): Any? = values[key]
    }
}
