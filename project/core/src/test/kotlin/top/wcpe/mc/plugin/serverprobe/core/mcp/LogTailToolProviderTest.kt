package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * FR-16 日志流式检索：覆盖尾部定位、超长行截断与游标推进、
 * 关键字命中、游标分页（含文件增长快照语义）、文件缺失降级、路径前缀校验拒绝。
 */
class LogTailToolProviderTest {

    @TempDir
    lateinit var tempDir: Path

    private val charset = Charset.forName("GBK")

    private fun provider(root: Path? = tempDir, log: Path? = tempDir.resolve("logs/latest.log")) = LogTailToolProvider(
        logPath = object : LogPathProvider {
            override fun serverRoot(): Path? = root
            override fun latestLog(): Path? = log
            override fun charset(): Charset = charset
        },
    )

    private fun write(vararg lines: String): Path {
        val log = tempDir.resolve("logs/latest.log")
        Files.createDirectories(log.parent)
        Files.write(log, buildString {
            lines.forEach { append(it).append('\n') }
        }.toByteArray(charset))
        return log
    }

    private fun append(line: String) {
        val log = tempDir.resolve("logs/latest.log")
        Files.write(log, (line + "\n").toByteArray(charset), StandardOpenOption.APPEND)
    }

    /** 尾部定位返回末尾指定行数与相对文件头的行号（从 1 开始）。 */
    @Test
    fun `尾部定位返回末尾指定行数与行号`() {
        val log = write("第一行", "第二行", "第三行")
        val result = provider().call("log_tail", arguments(mapOf("lines" to 2)))

        assertEquals(log.toAbsolutePath().toString(), result["file"])
        assertFalse(result["truncated"] as Boolean)
        val lines = result["lines"] as List<*>
        assertEquals(2, lines.size)
        assertEquals(mapOf("lineNumber" to 2L, "text" to "第二行"), lines[0])
        assertEquals(mapOf("lineNumber" to 3L, "text" to "第三行"), lines[1])
    }

    /** 文件只有 1 行且无换行结尾时，尾部定位也必须把该行纳入。 */
    @Test
    fun `尾部定位覆盖无换行结尾的末行`() {
        Files.createDirectories(tempDir.resolve("logs"))
        Files.write(tempDir.resolve("logs/latest.log"), "无换行末行".toByteArray(charset))

        val lines = provider().call("log_tail", null)["lines"] as List<*>

        assertEquals(1, lines.size)
        assertEquals(mapOf("lineNumber" to 1L, "text" to "无换行末行"), lines[0])
    }

    /** 超长行按 8KiB 截断，且截断后必须跳到下一个换行符，保证游标总能前进。 */
    @Test
    fun `超长行截断后跳过至下一换行`() {
        val huge = "长".repeat(20_000)
        write("短行", huge, "短行")

        val tail = provider().call("log_tail", arguments(mapOf("lines" to 10)))
        val search = provider().call("log_search", arguments(mapOf("keyword" to "短行")))

        val tailTexts = (tail["lines"] as List<*>).map { (it as Map<*, *>)["text"] }
        assertTrue(tailTexts.all { it.toString().length <= 8 * 1024 })
        assertTrue(tail["truncated"] as Boolean)
        // 截断行之后的行仍能被检索到，证明游标越过了超长行
        assertTrue((search["hits"] as List<*>).size >= 2)
        assertFalse(search["truncated"] as Boolean)
    }

    /** 关键字命中行以字节偏移返回，大小写不敏感。 */
    @Test
    fun `搜索命中包含关键字的行`() {
        write("正常启动", "ERROR DATABASE connection failed", "继续运行")

        val hits = provider().call("log_search", arguments(mapOf("keyword" to "database")))["hits"] as List<*>

        assertEquals(1, hits.size)
        val hit = hits[0] as Map<*, *>
        assertEquals(9L, hit["offset"])
        assertTrue((hit["text"] as String).contains("ERROR"))
    }

    /** 字节偏移游标分页：首页返回 nextOffset，续页从偏移继续，命中行不重复。 */
    @Test
    fun `游标分页连续且无重复`() {
        write("a-1", "b-2", "a-3", "b-4", "a-5")

        val first = provider().call("log_search", arguments(mapOf("keyword" to "b", "maxLines" to 1)))
        val nextOffset = first["nextOffset"] as Long
        val second = provider().call(
            "log_search",
            arguments(mapOf("keyword" to "b", "sinceOffset" to nextOffset, "maxLines" to 10)),
        )

        val firstText = ((first["hits"] as List<*>).single() as Map<*, *>)["text"] as String
        val secondTexts = (second["hits"] as List<*>).map { (it as Map<*, *>)["text"] as String }
        assertEquals("b-2", firstText)
        assertEquals(listOf("b-4"), secondTexts)
        assertFalse(second["truncated"] as Boolean)
    }

    /** 文件增长快照语义：nextOffset 是读取时刻的文件大小，跨页续页不重复也不漏掉新增命中行。 */
    @Test
    fun `文件增长时游标按快照推进且续页不重复`() {
        val log = write("命中-1", "干扰-1")
        val snapshotSize = Files.size(log)

        val first = provider().call("log_search", arguments(mapOf("keyword" to "命中")))
        assertEquals(snapshotSize, first["nextOffset"])
        val firstTexts = (first["hits"] as List<*>).map { (it as Map<*, *>)["text"] as String }
        assertEquals(listOf("命中-1"), firstTexts)

        // 文件增长：追加一条命中行，其字节偏移落在旧快照之后
        append("命中-2")

        val second = provider().call(
            "log_search",
            arguments(mapOf("keyword" to "命中", "sinceOffset" to snapshotSize)),
        )
        val secondTexts = (second["hits"] as List<*>).map { (it as Map<*, *>)["text"] as String }

        assertEquals(listOf("命中-2"), secondTexts)
        assertEquals(snapshotSize, first["nextOffset"])
    }

    /** 文件缺失时返回结构化不可用，不抛异常。 */
    @Test
    fun `文件缺失时结构化降级`() {
        val result = provider(log = tempDir.resolve("logs/不存在.log")).call("log_tail", null)

        assertEquals(false, result["available"])
        assertTrue((result["reason"] as String).contains("不存在"))
    }

    /** 平台不支持时返回结构化不可用。 */
    @Test
    fun `平台不支持时结构化降级`() {
        val result = provider(root = null, log = null).call("log_search", arguments(mapOf("keyword" to "x")))

        assertEquals(false, result["available"])
    }

    /** 路径穿越：日志路径不在服务端根目录内时必须被前缀校验拒绝。 */
    @Test
    fun `路径前缀校验拒绝根目录外的日志路径`() {
        val outside = tempDir.parent.resolve("outside.log")
        Files.write(outside, "外部文件".toByteArray(charset))

        val result = provider(log = outside).call("log_tail", null)

        assertEquals(false, result["available"])
        assertTrue((result["reason"] as String).contains("根目录"))
    }

    /** 相对路径经规范化后仍须通过前缀校验。 */
    @Test
    fun `相对路径经规范化后仍须通过前缀校验`() {
        val inside = tempDir.resolve("logs/latest.log")
        Files.createDirectories(inside.parent)
        Files.write(inside, "内部文件".toByteArray(charset))
        // 故意使用含 .. 的相对写法，规范化后应落在根目录内
        val tricky = tempDir.resolve("logs").resolve("../logs/latest.log")

        val result = provider(log = tricky).call("log_tail", null)

        assertEquals(1, (result["lines"] as List<*>).size)
    }

    /** 关键字为空时按普通 tail 处理，返回末尾行。 */
    @Test
    fun `空关键字退化为普通尾部读取`() {
        write("第一行", "第二行")

        val result = provider().call("log_tail", arguments(mapOf("keyword" to "")))

        assertEquals(2, (result["lines"] as List<*>).size)
    }

    private fun arguments(values: Map<String, Any?>): JsonObject =
        object : JsonObject {
            override fun getRaw(key: String): Any? = values[key]
            override fun getString(key: String, default: String): String =
                values[key]?.toString() ?: default
            override fun getInt(key: String, default: Int): Int =
                (values[key] as? Number)?.toInt() ?: default
            override fun getLong(key: String, default: Long): Long =
                (values[key] as? Number)?.toLong() ?: default
            override fun getDouble(key: String, default: Double): Double =
                (values[key] as? Number)?.toDouble() ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean =
                (values[key] as? Boolean) ?: default
            override fun getStringList(key: String): List<String> = emptyList()
            override fun contains(key: String): Boolean = values.containsKey(key)
            override fun getObject(key: String): JsonObject? = null
        }
}
