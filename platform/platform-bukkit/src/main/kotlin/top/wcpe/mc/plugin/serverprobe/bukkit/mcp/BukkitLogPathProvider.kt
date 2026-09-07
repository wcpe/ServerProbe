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
 * 服务端根目录优先取世界容器目录的父目录（`getWorldContainer()` 返回世界容器目录，其父目录即服务端根）；
 * **取不到时回退进程工作目录的绝对路径**——Minecraft 服务器事实以 chdir 到服务端根的方式启动
 * （`StartupLoadListener` 同样用相对 `logs/latest.log` 解析并工作正常），故工作目录即服务端根，
 * 回退它不构成"校验架空"（core 的路径前缀校验仍以该绝对路径为根，目标同源且正确）。
 * 每次调用实时解析（不 lazy 固化）：Bukkit 未就绪时回退工作目录，就绪后优先世界容器父目录。
 * 字符集取 `file.encoding`，与 JVM 默认一致，低版本 Windows 服务器日志常为 GBK，禁止硬编码 UTF-8。
 *
 * 本实现不直接注册进工具注册表——core 的 [top.wcpe.mc.plugin.serverprobe.core.mcp.LogTailToolProvider]
 * 经 IOC 按接口注入本 Bean，再由其注册进 `McpToolProviderRegistry`。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitLogPathProvider : LogPathProvider {

    /** 每次实时解析（不 lazy 固化）：世界容器父目录优先，取不到回退进程工作目录绝对路径。 */
    override fun serverRoot(): Path? = resolveServerRoot()

    override fun latestLog(): Path? = serverRoot()?.resolve("logs/latest.log")

    override fun charset(): Charset = LOG_CHARSET

    private companion object {

        /** 服务端根目录：世界容器父目录优先；回退进程工作目录绝对路径（服务器 chdir 到服务端根启动）。 */
        private fun resolveServerRoot(): Path? {
            val containerParent = runCatching { Bukkit.getWorldContainer()?.parentFile?.toPath() }
                .getOrNull()?.takeIf { it.isAbsolute }?.normalize()
            if (containerParent != null) return containerParent
            return Paths.get("").toAbsolutePath().normalize()
        }

        /** 日志字符集：取 JVM `file.encoding`；异常（极端环境）时回退平台默认字符集。 */
        private val LOG_CHARSET: Charset by lazy {
            runCatching { Charset.forName(System.getProperty("file.encoding") ?: Charset.defaultCharset().name()) }
                .getOrDefault(Charset.defaultCharset())
        }
    }
}
