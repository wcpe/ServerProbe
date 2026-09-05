package top.wcpe.mc.plugin.serverprobe.core.mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.stream.Collectors

/** MCP 工件工作区的容量与保留边界。 */
data class ArtifactWorkspaceSettings(val maxBytes: Long, val retentionMillis: Long) {
    init {
        require(maxBytes > 0) { "工件目录上限必须大于零" }
        require(retentionMillis > 0) { "工件保留期必须大于零" }
    }
}

/** 工件的最小元数据。 */
data class McpArtifact(val name: String, val size: Long, val modifiedAtMillis: Long)

/** 有界工件读取分片。 */
data class McpArtifactChunk(val content: String, val nextOffset: Long, val truncated: Boolean)

/** 仅允许固定文件名的本地 MCP 工件目录，拒绝任意路径穿越。 */
class McpArtifactWorkspace(
    private val directory: Path,
    private val settings: ArtifactWorkspaceSettings,
) {

    init {
        Files.createDirectories(directory)
    }

    @Synchronized
    fun writeChunk(name: String, content: String, append: Boolean): McpArtifact {
        val file = resolve(name)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= settings.maxBytes) { "单个工件超过工作区容量上限" }
        cleanup()
        val existing = sizeIfExists(file)
        val targetSize = if (append) existing + bytes.size else bytes.size.toLong()
        makeSpace(file.fileName.toString(), existing, targetSize)
        val options = if (append) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        // Files.write 的 openOptions 为变长参数,按写/追加模式动态展开必须使用 spread。
        @Suppress("SpreadOperator")
        Files.write(file, bytes, *options)
        return metadata(file)
    }

    @Synchronized
    fun readChunk(name: String, offset: Long, maxBytes: Int): McpArtifactChunk {
        val file = resolve(name)
        require(Files.isRegularFile(file)) { "工件不存在" }
        val size = Files.size(file)
        val safeOffset = offset.coerceIn(0, size)
        val limit = maxBytes.coerceIn(1, MAX_READ_BYTES)
        val buffer = ByteArray(limit)
        val count = Files.newInputStream(file).use { input ->
            skipFully(input, safeOffset)
            input.read(buffer)
        }.coerceAtLeast(0)
        val next = safeOffset + count
        return McpArtifactChunk(String(buffer, 0, count, StandardCharsets.UTF_8), next, next < size)
    }

    @Synchronized
    fun list(): List<McpArtifact> = Files.list(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.map(::metadata)
            .sorted(Comparator.comparing(McpArtifact::name)).collect(Collectors.toList())
    }

    @Synchronized
    fun delete(name: String): Boolean = Files.deleteIfExists(resolve(name))

    /** 返回已校验的工件文件路径，供 Arthas 仅在工作区内读取类字节码。 */
    @Synchronized
    fun pathOf(name: String): Path {
        val file = resolve(name)
        require(Files.isRegularFile(file)) { "工件不存在" }
        return file
    }

    /** 返回已校验的输出路径，供 Arthas 在工作区内生成诊断产物。 */
    @Synchronized
    fun outputPath(name: String): Path = resolve(name)

    /** 先按时间清理过期文件，再按最早修改时间清理至容量上限。 */
    @Synchronized
    fun cleanup() {
        val now = System.currentTimeMillis()
        list().filter { now - it.modifiedAtMillis >= settings.retentionMillis }.forEach { delete(it.name) }
        var total = list().sumOf(McpArtifact::size)
        if (total <= settings.maxBytes) return
        list().sortedBy(McpArtifact::modifiedAtMillis).forEach { artifact ->
            if (total <= settings.maxBytes) return
            if (delete(artifact.name)) total -= artifact.size
        }
    }

    private fun resolve(name: String): Path {
        require(FILE_NAME.matches(name)) { "工件名称只能包含字母、数字、点、下划线和连字符" }
        return directory.resolve(name)
    }

    private fun metadata(file: Path): McpArtifact = McpArtifact(
        file.fileName.toString(), Files.size(file), Files.getLastModifiedTime(file).toMillis(),
    )

    /** 为本次写入预留容量；仅删除目标以外最早的工件。 */
    private fun makeSpace(targetName: String, existingSize: Long, targetSize: Long) {
        var total = list().sumOf(McpArtifact::size) - existingSize
        list().filterNot { it.name == targetName }.sortedBy(McpArtifact::modifiedAtMillis).forEach { artifact ->
            if (total + targetSize <= settings.maxBytes) return
            if (delete(artifact.name)) total -= artifact.size
        }
        require(total + targetSize <= settings.maxBytes) { "工件写入后将超过工作区容量上限" }
    }

    private fun sizeIfExists(file: Path): Long = if (Files.isRegularFile(file)) Files.size(file) else 0

    private fun skipFully(input: java.io.InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() < 0) return
            else remaining--
        }
    }

    private companion object {
        val FILE_NAME = Regex("[A-Za-z0-9._-]{1,128}")
        const val MAX_READ_BYTES = 1024 * 1024
    }
}
