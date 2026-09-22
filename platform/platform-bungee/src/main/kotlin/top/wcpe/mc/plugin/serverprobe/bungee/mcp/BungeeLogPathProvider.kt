package top.wcpe.mc.plugin.serverprobe.bungee.mcp

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.mcp.LogPathProvider
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * BungeeCord 的日志路径适配器（FR-28,补齐 FR-16 代理端欠条）。
 *
 * 日志落在**进程工作目录**（BungeeCord 无 `getWorldContainer()` 类 API,一律工作目录）,
 * 但文件名随 BungeeCord 构建而异——**BungeeCord #2088 + JDK 21 真机实证**：根目录下没有
 * 明文 `proxy.log`,当前日志是带代次后缀的 `proxy.log.0`（同目录另有 `proxy.log.0.lck` 锁文件）,
 * 轮转档为 `proxy.log.N` / `.gz`。故解析规则为：**优先明文 `proxy.log`,否则取按修改时间最新的
 * `proxy.log.<纯数字>`**（代次排序语义随 JDK `FileHandler` 版本而异,按时间取最稳,且天然排除
 * `.lck` 与压缩档）；两处都不存在时回退明文路径,交由 core 的既有降级路径报"日志文件不存在"。
 *
 * 每次调用实时解析（不 lazy 固化）,与 [top.wcpe.mc.plugin.serverprobe.bukkit.mcp.BukkitLogPathProvider] 的回退路径同源。
 * 字符集取 `file.encoding`（低版本 Windows 常为 GBK）,禁止硬编码 UTF-8。
 *
 * 本实现不直接注册进工具注册表——core 的 [top.wcpe.mc.plugin.serverprobe.core.mcp.LogTailToolProvider]
 * 经 IOC 按接口注入本 Bean,再由其注册进 `McpToolProviderRegistry`。
 */
@Service
@PlatformSide(Platform.BUNGEE)
class BungeeLogPathProvider : LogPathProvider {

    /** BungeeCord 根目录即进程工作目录绝对路径（服务器以 chdir 方式启动）。 */
    override fun serverRoot(): Path? = Paths.get("").toAbsolutePath().normalize()

    override fun latestLog(): Path? = serverRoot()?.let(::resolveLatestLog)

    /**
     * 解析"当前正在写的日志文件";抽为 internal 纯函数,便于用临时目录穷举命名变体。
     *
     * @param root 代理端工作目录。
     * @return 当前日志路径;不存在时返回明文 `proxy.log`（供调用方按文件缺失降级）。
     */
    internal fun resolveLatestLog(root: Path): Path {
        val plain = root.resolve(PLAIN_LOG_NAME)
        if (Files.isRegularFile(plain)) {
            return plain
        }
        return newestNumbered(root) ?: plain
    }

    /** 取目录中修改时间最新的 `proxy.log.<纯数字>`;目录不可读时视为不存在。 */
    private fun newestNumbered(root: Path): Path? = runCatching {
        Files.list(root).use { stream ->
            stream.filter(::isNumberedLog)
                .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)
        }
    }.getOrNull()

    /** 代次文件判定：`proxy.log.` 后全为数字（据此排除 `.lck`、`.gz` 与其它同名文件）。 */
    private fun isNumberedLog(path: Path): Boolean {
        val suffix = path.fileName.toString().removePrefix("$PLAIN_LOG_NAME.")
        return suffix.isNotEmpty() && suffix.all(Char::isDigit) && Files.isRegularFile(path)
    }

    override fun charset(): Charset = LOG_CHARSET

    private companion object {

        /** 明文日志名（部分 BungeeCord 构建使用）。 */
        private const val PLAIN_LOG_NAME = "proxy.log"

        /** 日志字符集：取 JVM `file.encoding`；异常（极端环境）时回退平台默认字符集。 */
        private val LOG_CHARSET: Charset by lazy {
            runCatching { Charset.forName(System.getProperty("file.encoding") ?: Charset.defaultCharset().name()) }
                .getOrDefault(Charset.defaultCharset())
        }
    }
}
