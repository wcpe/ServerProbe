package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.bukkit.Bukkit
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.mcp.LogPathProvider
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Bukkit/Paper/Folia 的日志路径适配器（FR-16）。
 *
 * 返回服务端根目录下 `logs/latest.log`（与启动画像解析的 [top.wcpe.mc.plugin.serverprobe.bukkit.startup.StartupLoadListener] 一致）。
 * 服务端根目录取世界容器目录的父目录（`getWorldContainer()` 返回世界目录，其父目录即服务端根目录），
 * 取不到时回退为进程工作目录；字符集取 `file.encoding`，与 JVM 默认一致，
 * 低版本 Windows 服务器日志常为 GBK，禁止硬编码 UTF-8。
 *
 * 本实现不直接注册进工具注册表——core 的 [top.wcpe.mc.plugin.serverprobe.core.mcp.LogTailToolProvider]
 * 经 IOC 按接口注入本 Bean，再由其注册进 `McpToolProviderRegistry`。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitLogPathProvider : LogPathProvider {

    override fun serverRoot(): Path? = SERVER_ROOT

    override fun latestLog(): Path? = serverRoot()?.resolve("logs/latest.log")

    override fun charset(): Charset = LOG_CHARSET

    private companion object {

        /** 服务端根目录：世界容器目录的父目录；Bukkit 未就绪或取不到时回退进程工作目录。 */
        private val SERVER_ROOT: Path? by lazy {
            runCatching { Bukkit.getWorldContainer()?.parentFile?.toPath() }
                .getOrNull()?.takeIf { it.isAbsolute }
                ?: Paths.get("").toAbsolutePath().normalize()
        }

        /** 日志字符集：取 JVM `file.encoding`；异常（极端环境）时回退平台默认字符集。 */
        private val LOG_CHARSET: Charset by lazy {
            runCatching { Charset.forName(System.getProperty("file.encoding") ?: Charset.defaultCharset().name()) }
                .getOrDefault(Charset.defaultCharset())
        }
    }
}
