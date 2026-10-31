package top.wcpe.mc.plugin.serverprobe.core.control

import java.nio.file.Files
import java.nio.file.Path

/**
 * 身份文件（`agent-identity.json`）读写门面。
 *
 * 只负责"文件 ↔ [ControlPlaneIdentity]"的搬运与防御，不做任何模式判定（判定见
 * [ControlPlaneModeService]）与迁移（见 [IdentityMigration]）。
 *
 * ## 半写防御（协议规格 §3.2）
 * 读取侧**永不抛异常**：文件不存在 → [IdentityLoadResult.Absent]；存在但截断/结构非法/版本未知/字段缺失
 * → [IdentityLoadResult.Unusable]（调用方降级独立模式 + WARN）。绝不因身份文件问题让探针启动失败。
 *
 * ## 写入侧
 * 经 [AtomicFileWrite] 做 `tmp → fsync → rename` 原子替换并收紧权限；写入前若磁盘内容已与目标一致则
 * 直接跳过（迁移幂等要求"内容相同不重写"）。
 *
 * ## 安全
 * 本类任何日志/异常文案都**不得**带出 [ControlPlaneIdentity.credential]；[IdentityLoadResult.Unusable]
 * 的 detail 只描述结构性原因（字段名、版本号、长度），不打印原文。
 */
internal object IdentityFileStore {

    /**
     * 读取身份文件。
     *
     * @param path 身份文件路径。
     * @return 读取结果；任何异常都映射为 [IdentityLoadResult.Unusable]，绝不抛出。
     */
    fun read(path: Path): IdentityLoadResult {
        val text = runCatching {
            if (!Files.isRegularFile(path)) {
                return IdentityLoadResult.Absent
            }
            val size = Files.size(path)
            if (size > MAX_FILE_BYTES) {
                // 身份文件只有几百字节;超出上限只可能是被塞了垃圾,按不可用处理,避免读进大文件
                return IdentityLoadResult.Unusable(
                    IdentityUnusableReason.MALFORMED,
                    "文件体积 $size 字节超出上限 $MAX_FILE_BYTES 字节",
                )
            }
            String(Files.readAllBytes(path), Charsets.UTF_8)
        }.getOrElse { failure ->
            return IdentityLoadResult.Unusable(
                IdentityUnusableReason.MALFORMED,
                "读取失败:${failure.javaClass.simpleName}",
            )
        }
        return IdentityJson.decode(text)
    }

    /**
     * 原子写入身份文件（内容与磁盘一致时跳过写入）。
     *
     * @param path 身份文件路径。
     * @param identity 待写入身份。
     * @return true 表示实际发生了写入；false 表示内容已相同、跳过。
     */
    fun write(path: Path, identity: ControlPlaneIdentity): Boolean {
        val content = IdentityJson.encode(identity)
        if (isSameContent(path, content)) {
            return false
        }
        AtomicFileWrite.write(path, content)
        return true
    }

    /** 磁盘内容是否已与待写入文本完全一致（一致则无需重写，保证迁移幂等）。 */
    private fun isSameContent(path: Path, content: String): Boolean = runCatching {
        Files.isRegularFile(path) && String(Files.readAllBytes(path), Charsets.UTF_8) == content
    }.getOrDefault(false)

    /** 身份文件体积上限：身份文件应只有数百字节，超过即视为损坏（防御被写入垃圾/超大文件）。 */
    private const val MAX_FILE_BYTES = 64L * 1024L
}
