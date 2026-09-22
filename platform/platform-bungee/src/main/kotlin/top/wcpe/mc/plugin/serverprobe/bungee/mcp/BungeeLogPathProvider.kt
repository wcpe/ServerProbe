package top.wcpe.mc.plugin.serverprobe.bungee.mcp

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.mcp.LogPathProvider
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths

/**
 * BungeeCord 的日志路径适配器（FR-28,补齐 FR-16 代理端欠条）。
 *
 * BungeeCord 把日志写在**工作目录根下的 `proxy.log`**（轮转为 `proxy.log.N` / `proxy.log.N.gz`）,
 * 与服务端根目录即进程工作目录的事实一致（BungeeCord 无 `getWorldContainer()` 类 API,一律工作目录）。
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

    override fun latestLog(): Path? = serverRoot()?.resolve("proxy.log")

    override fun charset(): Charset = LOG_CHARSET

    private companion object {

        /** 日志字符集：取 JVM `file.encoding`；异常（极端环境）时回退平台默认字符集。 */
        private val LOG_CHARSET: Charset by lazy {
            runCatching { Charset.forName(System.getProperty("file.encoding") ?: Charset.defaultCharset().name()) }
                .getOrDefault(Charset.defaultCharset())
        }
    }
}
