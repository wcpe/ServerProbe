package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.bukkit.Bukkit
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.mcp.LogPathProvider
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * Bukkit/Paper/Folia 的日志路径适配器（FR-16）。
 *
 * 返回服务端根目录下 `logs/latest.log`（与启动画像解析的 [top.wcpe.mc.plugin.serverprobe.bukkit.startup.StartupLoadListener] 一致）。
 * 服务端根目录取世界容器目录的父目录（`getWorldContainer()` 返回世界容器目录，其父目录即服务端根/工作目录）；
 * **取不到时返回 null**（工具降级"平台未提供日志文件"）——不得回退进程工作目录，
 * 否则 core 的路径前缀校验（root 与目标同源）会形同虚设，破坏防穿越纵深。
 * 字符集取 `file.encoding`，与 JVM 默认一致，低版本 Windows 服务器日志常为 GBK，禁止硬编码 UTF-8。
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

        /** 服务端根目录：世界容器目录的父目录；Bukkit 未就绪或取不到时返回 null（不回退工作目录，防校验架空）。 */
        private val SERVER_ROOT: Path? by lazy {
            runCatching { Bukkit.getWorldContainer()?.parentFile?.toPath() }
                .getOrNull()?.takeIf { it.isAbsolute }?.normalize()
        }

        /** 日志字符集：取 JVM `file.encoding`；异常（极端环境）时回退平台默认字符集。 */
        private val LOG_CHARSET: Charset by lazy {
            runCatching { Charset.forName(System.getProperty("file.encoding") ?: Charset.defaultCharset().name()) }
                .getOrDefault(Charset.defaultCharset())
        }
    }
}
