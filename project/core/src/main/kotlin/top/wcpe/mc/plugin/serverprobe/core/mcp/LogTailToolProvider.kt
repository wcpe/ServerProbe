package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR-16 MCP 日志流式检索工具（只读）。
 *
 * 提供 `log_tail`（末尾 N 行，可选关键字过滤）与 `log_search`（自字节偏移起的命中行与下一页偏移）。
 * - 日志路径由平台 [LogPathProvider] 提供，core 端做规范化前缀校验，禁止读取根目录外文件。
 * - 字符集由平台适配器提供，禁止硬编码 UTF-8。
 * - 文件不存在 / 不可读 / 平台不支持时返回结构化 `{available:false, reason}`，不抛异常、不打 WARN。
 * - 超长行按 8KiB 截断并跳过至下一个换行符，保证字节游标总能前进；
 *   大文件从头扫描限定单次上限（默认 64 MiB 或 10 万行），超出截断并标记。
 *
 * 无共享可变状态：每次调用独立打开/关闭文件，MCP 请求线程安全。
 */
@Service
class LogTailToolProvider(
    private val logPath: LogPathProvider? = null,
) : McpToolProvider {

    @Inject(required = false)
    var logPathRegistry: LogPathProvider? = null

    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    /** 启动期注册进扩展工具注册表，由控制面聚合进 dispatcher。 */
    @PostConstruct
    fun register() {
        if (logPath == null && logPathRegistry == null) {
            ProbeLogger.warn("未装配日志路径提供者，log_tail / log_search 不可用")
            return
        }
        mcpToolProviderRegistry.register(this)
    }

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = when (name) {
        LOG_TAIL -> logTail(arguments)
        LOG_SEARCH -> logSearch(arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    /** 尾部读取：返回末尾 [lines] 行，可选关键字过滤命中行。 */
    private fun logTail(arguments: JsonObject?): Map<String, Any?> {
        val lines = arguments?.getRaw("lines")?.toString()?.toIntOrNull()?.coerceIn(1, MAX_TAIL_LINES) ?: DEFAULT_TAIL_LINES
        val keyword = arguments?.getString("keyword")?.takeIf(String::isNotBlank)
        return readFile { scanner ->
            val result = scanner.tail(lines, keyword)
            linkedMapOf(
                "file" to scanner.path.toString(),
                "lines" to result.lines.map { linkedMapOf("lineNumber" to it.lineNumber, "text" to it.text) },
                "truncated" to result.truncated,
                // 大日志（>64MiB）无法精确统计起点前换行数时，行号从窗口起点重计，非文件真实行号
                "lineNumbersExact" to result.lineNumbersExact,
            )
        }
    }

    /** 字节游标检索：返回自 [sinceOffset] 起的命中行与下一页偏移。 */
    private fun logSearch(arguments: JsonObject?): Map<String, Any?> {
        val keyword = arguments?.getString("keyword")?.trim()
        require(!keyword.isNullOrEmpty()) { "log_search 缺少非空 keyword 参数" }
        val sinceOffset = arguments?.getRaw("sinceOffset")?.toString()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val maxLines = arguments?.getRaw("maxLines")?.toString()?.toIntOrNull()?.coerceIn(1, MAX_SEARCH_LINES) ?: DEFAULT_SEARCH_LINES
        return readFile { scanner ->
            val result = scanner.search(keyword, sinceOffset, maxLines)
            linkedMapOf(
                "file" to scanner.path.toString(),
                "hits" to result.hits.map { linkedMapOf("offset" to it.offset, "text" to it.text) },
                "nextOffset" to result.nextOffset,
                "truncated" to result.truncated,
            )
        }
    }

    /** 统一降级出口：所有失败一律返回结构化不可用，不向 MCP 调用方抛异常。 */
    private fun readFile(block: (LogScanner) -> Map<String, Any?>): Map<String, Any?> {
        val source = logPathRegistry ?: logPath ?: return unavailable("当前平台未提供日志文件")
        val path = source.latestLog() ?: return unavailable("当前平台未提供日志文件")
        return runCatching {
            val normalized = path.toAbsolutePath().normalize()
            val root = source.serverRoot()?.toAbsolutePath()?.normalize()
            check(LogPathGuard.withinRoot(normalized, root)) { "日志路径超出服务端根目录范围" }
            check(Files.isRegularFile(normalized)) { "日志文件不存在" }
            check(Files.isReadable(normalized)) { "日志文件不可读" }
            LogScanner(normalized, source.charset()).use { scanner -> block(scanner) }
        }.getOrElse { error ->
            unavailable(error.message ?: "读取日志失败")
        }
    }

    private fun unavailable(reason: String): Map<String, Any?> = linkedMapOf(
        "available" to false,
        "reason" to reason,
    )

    private companion object {
        const val LOG_TAIL = "log_tail"
        const val LOG_SEARCH = "log_search"
        const val DEFAULT_TAIL_LINES = 200
        const val MAX_TAIL_LINES = 2000
        const val DEFAULT_SEARCH_LINES = 100
        const val MAX_SEARCH_LINES = 1000
        val TOOLS = listOf(
            McpTool(LOG_TAIL, "读取服务端最新日志的末尾若干行，可按关键字过滤命中行", mapOf(
                "lines" to mapOf("type" to "integer", "description" to "读取行数，默认 200，上限 2000"),
                "keyword" to mapOf("type" to "string", "description" to "可选关键字，大小写不敏感子串过滤"),
            ), usageExample = "{\"lines\":200}", workflow = "同步调用", outputFields = mapOf(
                "file" to "日志文件绝对路径", "lines" to "行列表（lineNumber 从 1 计/text）", "truncated" to "是否截断",
            )),
            McpTool(LOG_SEARCH, "自字节偏移起检索日志命中行，返回下一页偏移用于游标分页", mapOf(
                "keyword" to mapOf("type" to "string", "description" to "检索关键字，大小写不敏感子串匹配"),
                "sinceOffset" to mapOf("type" to "integer", "description" to "起始字节偏移，默认 0"),
                "maxLines" to mapOf("type" to "integer", "description" to "单页最多命中行数，默认 100，上限 1000"),
            ), usageExample = "{\"keyword\":\"ERROR\",\"sinceOffset\":0}", workflow = "同步调用，按 nextOffset 游标分页；文件增长时以读取时快照为准，新增行请发起新查询",
                outputFields = mapOf(
                    "file" to "日志文件绝对路径", "hits" to "命中行列表（offset 字节偏移/text）",
                    "nextOffset" to "下一页起始字节偏移", "truncated" to "是否截断",
                )),
        )
    }
}

/** 日志路径前缀校验：规范化后的绝对路径必须位于服务端根目录内。 */
internal object LogPathGuard {

    fun withinRoot(normalized: Path, root: Path?): Boolean {
        if (root == null) return false
        return normalized == root || normalized.startsWith(root)
    }
}

/** 尾部/检索扫描器：按平台字符集解码，超长行截断并跳到下一换行，保证游标总前进。 */
internal class LogScanner(
    val path: Path,
    private val charset: Charset,
) : AutoCloseable {

    private val raf = RandomAccessFile(path.toFile(), "r")

    /** 读取末尾 [lines] 行；[keyword] 非空时在末尾窗口内过滤命中行（行号仍为相对文件头的真实行号）。 */
    fun tail(lines: Int, keyword: String?): TailResult {
        val start = tailStart(lines)
        val lineStart = startLine(start)
        val snapshot = raf.length()
        raf.seek(start)
        val window = ArrayList<TailLine>()
        // 起点落在行中（非行首）时首行是不完整的截断行，须置截断标记
        var truncated = !lineStart.exact || !atLineStart(start)
        while (raf.filePointer < snapshot) {
            val line = readLine() ?: break
            if (line.truncated) truncated = true
            window.add(TailLine(lineStart.lineNumber + window.size, line.text))
        }
        // 窗口按平均行宽估算可能略超 N 行，只保留末尾 N 行并同步修正行号
        val tail = if (window.size > lines) {
            window.subList(window.size - lines, window.size).mapIndexed { index, item ->
                TailLine(lineStart.lineNumber + window.size - lines + index, item.text)
            }
        } else {
            window
        }
        val filtered = if (keyword == null) tail else tail.filter { it.text.contains(keyword, ignoreCase = true) }
        return TailResult(filtered, truncated, lineStart.exact)
    }

    /** 自 [sinceOffset] 起扫描至当前文件大小，收集命中行；游标按读取时快照推进。 */
    fun search(keyword: String, sinceOffset: Long, maxLines: Int): SearchResult {
        val start = sinceOffset.coerceIn(0, raf.length())
        raf.seek(start)
        val hits = ArrayList<HitLine>()
        var nextOffset = start
        var scannedBytes = 0L
        var scannedLines = 0
        var limitHit = false
        var overLimit = false
        while (raf.filePointer < raf.length() && !limitHit && !overLimit) {
            val offset = raf.filePointer
            val line = readLine() ?: break
            scannedBytes += raf.filePointer - offset
            scannedLines++
            nextOffset = raf.filePointer
            if (line.text.contains(keyword, ignoreCase = true)) {
                hits.add(HitLine(offset, line.text))
                if (hits.size >= maxLines) limitHit = true
            }
            overLimit = scannedBytes >= MAX_SCAN_BYTES || scannedLines >= MAX_SCAN_LINES
        }
        return SearchResult(hits, nextOffset, overLimit)
    }

    /** 从当前位置读一行：8KiB 截断后跳过至下一换行符，保证游标总能前进。 */
    private fun readLine(): Line? {
        val start = raf.filePointer
        if (start >= raf.length()) return null
        val buffer = ByteArray(MAX_LINE_BYTES)
        var length = 0
        var newline = false
        while (length < MAX_LINE_BYTES && !newline) {
            val b = raf.read()
            newline = b == '\n'.code
            if (b < 0 || newline) break
            if (b != '\r'.code) buffer[length++] = b.toByte()
        }
        if (length == 0 && raf.filePointer == start) return null
        if (newline) length = dropCr(buffer, length)
        // 恰好读满 8KiB 时探测下一字节：仍有内容才算截断（文件末行恰好 8192 字节无换行是合法情形）
        val truncated = length >= MAX_LINE_BYTES && raf.filePointer < raf.length()
        if (truncated) skipToEol()
        return Line(String(buffer, 0, length, charset), truncated)
    }

    /** 去掉行尾回车（CRLF 与 LF 统一）。 */
    private fun dropCr(buffer: ByteArray, length: Int): Int =
        if (length > 0 && buffer[length - 1] == '\r'.code.toByte()) length - 1 else length

    /** 当前文件指针是否位于行首（前一字节为换行符或位于文件头）。 */
    private fun atLineStart(position: Long): Boolean {
        if (position <= 0L) return true
        raf.seek(position - 1)
        return raf.read() == '\n'.code
    }

    /** 跳到下一个换行符之后，保证截断行的游标继续前进。 */
    private fun skipToEol() {
        while (true) {
            val b = raf.read()
            if (b < 0 || b == '\n'.code) return
        }
    }

    /** 尾部定位：从文件尾按平均行宽回退，不足则从头；返回扫描起点字节偏移。 */
    private fun tailStart(lines: Int): Long {
        val length = raf.length()
        val window = minOf(length, lines.toLong() * AVERAGE_LINE_BYTES)
        return maxOf(0L, length - window)
    }

    /**
     * 起点行号：相对文件头的真实行号，需统计起点前的换行数。
     * 起点较深（超过单次扫描上限）时放弃精确计数，行号从窗口起点重计并返回非精确标记，
     * 调用方据此置截断标记（起点前的行未计入窗口，行号不代表完整文件的行数）。
     */
    private fun startLine(start: Long): LineStart {
        if (start == 0L) return LineStart(1L, exact = true)
        val count = countNewlines(start) ?: return LineStart(1L, exact = false)
        return LineStart(count + 1, exact = true)
    }

    /** 统计 [0, upTo) 的换行符数量；扫描量超过上限时返回 null（放弃精确计数）。 */
    private fun countNewlines(upTo: Long): Long? {
        var position = 0L
        var count = 0L
        val buffer = ByteArray(MAX_LINE_BYTES)
        raf.seek(0)
        while (position < upTo) {
            val size = minOf(buffer.size.toLong(), upTo - position).toInt()
            val read = raf.read(buffer, 0, size)
            if (read < 0) break
            position += read
            for (i in 0 until read) {
                if (buffer[i] == '\n'.code.toByte()) count++
            }
            if (position > MAX_SCAN_BYTES) return null
        }
        return count
    }

    override fun close() {
        raf.close()
    }

    private companion object {
        const val MAX_LINE_BYTES = 8 * 1024
        const val AVERAGE_LINE_BYTES = 120L
        const val MAX_SCAN_BYTES = 64L * 1024 * 1024
        const val MAX_SCAN_LINES = 100_000L
    }
}

/** 一行日志的读取结果。 */
internal data class Line(val text: String, val truncated: Boolean)

internal data class LineStart(val lineNumber: Long, val exact: Boolean)

internal data class TailResult(val lines: List<TailLine>, val truncated: Boolean, val lineNumbersExact: Boolean)

internal data class TailLine(val lineNumber: Long, val text: String)

internal data class SearchResult(val hits: List<HitLine>, val nextOffset: Long, val truncated: Boolean)

internal data class HitLine(val offset: Long, val text: String)
