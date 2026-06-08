package top.wcpe.mc.plugin.serverprobe.core.store

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 文本文件原子写入工具。
 *
 * 落盘采用"写临时文件 → 原子重命名"策略([writeAtomic]):先把内容写到同目录下的临时文件,再以
 * [StandardCopyOption.ATOMIC_MOVE] 重命名为目标文件,从而避免崩溃/并发读取到半截内容(FR1.5 原子写入)。
 * 部分文件系统(如跨卷、个别网络盘)不支持原子移动,此时回退为普通覆盖移动([StandardCopyOption.REPLACE_EXISTING])。
 *
 * 无状态纯工具,以 `object` 实现,不纳入 IOC 容器。统一使用 UTF-8 编码。
 */
object AtomicJsonWriter {

    /**
     * 将 [content] 原子写入 [path]。
     *
     * 自动创建目标文件的父目录;先写临时文件再原子重命名,失败回退普通移动。临时文件在异常路径下尽力清理。
     *
     * @param path 目标文件路径。
     * @param content 待写入的文本内容(UTF-8)。
     */
    fun writeAtomic(path: Path, content: String) {
        val parent = path.toAbsolutePath().parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        // 临时文件与目标同目录,确保后续 move 在同一文件系统内,原子移动方可生效
        val tmp = Files.createTempFile(parent, path.fileName.toString(), TMP_SUFFIX)
        try {
            Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
            moveReplacing(tmp, path)
        } finally {
            // move 成功后临时文件已不存在,deleteIfExists 仅清理 move 失败时遗留的临时文件
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * 读取 [path] 的文本内容(UTF-8)。
     *
     * @param path 目标文件路径。
     * @return 文件内容;文件不存在时为 null。
     */
    fun readText(path: Path): String? {
        if (!Files.exists(path)) {
            return null
        }
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    /**
     * 将临时文件移动覆盖到目标:优先原子移动,文件系统不支持时回退普通覆盖移动。
     *
     * @param tmp 源临时文件。
     * @param target 目标文件。
     */
    private fun moveReplacing(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (ignored: AtomicMoveNotSupportedException) {
            // 文件系统不支持原子移动(如跨卷),回退为普通覆盖移动:虽非原子,但仍优于直接写目标文件
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 临时文件后缀。 */
    private const val TMP_SUFFIX = ".tmp"
}
