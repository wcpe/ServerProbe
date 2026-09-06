package top.wcpe.mc.plugin.serverprobe.core.mcp

import java.nio.charset.Charset
import java.nio.file.Path

/**
 * 日志文件位置与字符集契约（FR-16）。
 *
 * 由各平台适配器实现，把平台真实的 `logs/latest.log` 位置暴露给 core 的 [LogTailToolProvider]，
 * core 编译期不依赖任何平台 API。字符集由平台提供（如 Bukkit 取 `file.encoding`），
 * 禁止在 core 硬编码 UTF-8——低版本 Windows 服务器日志常为 GBK，硬编码会乱码。
 *
 * 返回的 [Path] 会经 core 做规范化前缀校验（必须位于服务端根目录内），
 * 防止平台实现错误或注入导致读取任意路径。
 */
interface LogPathProvider {

    /** 服务端根目录（路径前缀校验的基准）；为空表示平台不支持日志检索。 */
    fun serverRoot(): Path?

    /** 最新日志文件；为 null 时工具返回结构化不可用，不抛异常。 */
    fun latestLog(): Path?

    /** 日志文件解码字符集；实现必须显式提供，不得回退为平台默认。 */
    fun charset(): Charset
}
