package top.wcpe.mc.plugin.serverprobe.core.control

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.channels.FileChannel

/**
 * 敏感文件的"原子 + 持久化 + 权限收紧"落盘工具。
 *
 * 与既有 [top.wcpe.mc.plugin.serverprobe.core.store.AtomicJsonWriter] 的区别:
 * 后者服务指标/画像等可重建的普通数据,只做"临时文件 → 原子重命名";本工具服务身份文件这类
 * **安全敏感且不可重建**的凭据载体,额外做两件事:
 *
 * 1. **fsync**:临时文件写完先 `force(true)` 落盘,再重命名(协议规格 §3.2 的 `tmp → fsync → rename`);
 *    否则断电时可能出现"重命名已生效但内容还在页缓存"的空文件。
 * 2. **权限收紧**:POSIX 平台设 0600(仅属主可读写);Windows 无 POSIX 权限语义,尽力以
 *    `File.setReadable/setWritable` 收紧到当前用户,失败不影响写入。
 *
 * 临时文件固定命名为 `<目标文件名>.tmp`(与协议规格一致),失败路径尽力清理。
 */
internal object AtomicFileWrite {

    /**
     * 把 [content] 以 UTF-8 原子写入 [path]，并尽力收紧文件权限。
     *
     * @param path 目标文件路径（父目录不存在时自动创建）。
     * @param content 待写入文本。
     */
    fun write(path: Path, content: String) {
        val parent = path.toAbsolutePath().parent ?: throw IllegalArgumentException("目标文件缺少父目录:$path")
        Files.createDirectories(parent)
        val tmp = parent.resolve(path.fileName.toString() + TMP_SUFFIX)
        // 上次崩溃可能残留同名临时文件:先清掉,避免 CREATE_NEW 失败
        Files.deleteIfExists(tmp)
        try {
            writeAndSync(tmp, content.toByteArray(StandardCharsets.UTF_8))
            restrictPermissions(tmp)
            moveReplacing(tmp, path)
            syncDirectory(parent)
        } finally {
            // 移动成功后临时文件已不存在;此处只清理失败路径的残留
            Files.deleteIfExists(tmp)
        }
    }

    /** 写入临时文件并 fsync（数据 + 元数据都落盘后才返回）。 */
    private fun writeAndSync(tmp: Path, bytes: ByteArray) {
        FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    /** 临时文件移动覆盖到目标：优先原子移动，文件系统不支持时回退普通覆盖移动。 */
    private fun moveReplacing(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (ignored: AtomicMoveNotSupportedException) {
            // 个别文件系统（跨卷/网络盘）不支持原子移动，退化为普通覆盖移动
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 收紧文件权限：POSIX 设 0600；不支持的平台尽力以 Java File API 收紧到当前用户。
     *
     * 权限收紧失败**不影响**写入结果（Windows 上本就是尽力而为），故整体吞掉异常。
     */
    internal fun restrictPermissions(path: Path) {
        val posix = runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS) }
        if (posix.isSuccess) {
            return
        }
        runCatching {
            val file = path.toFile()
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    /**
     * 以「仅属主可读写（0600）」创建空文件。
     *
     * 用于**写入前**就已确定要保留敏感内容的文件（如迁移备份）：直接 `Files.copy` 会先以默认权限建文件，
     * 多用户机器上那一段窗口内同机其他用户可读。POSIX 不支持时退化为普通创建，再由调用方收紧。
     */
    internal fun createOwnerOnly(path: Path) {
        runCatching { Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS)) }
            .recoverCatching { Files.createFile(path) }
    }

    /** 对父目录做一次 fsync，确保重命名本身持久化（部分平台不支持目录 fsync，尽力而为）。 */
    private fun syncDirectory(directory: Path) {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /** 临时文件后缀（协议规格 §3.2）。 */
    private const val TMP_SUFFIX = ".tmp"

    /** POSIX 0600：仅属主可读写。 */
    private val OWNER_ONLY_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
}
