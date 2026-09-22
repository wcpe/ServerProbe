package top.wcpe.mc.plugin.serverprobe.velocity.mcp

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.mcp.LogPathProvider
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Velocity 的日志路径适配器（FR-28,补齐 FR-16 代理端欠条）。
 *
 * Velocity 把日志写在**工作目录根下的 `logs/latest.log`**（轮转 `logs/latest.log.N.gz`,
 * 由 log4j2 配置决定）,共享源码一次覆盖 3.1.1–4.x（该路径无版本差异）。
 * Velocity 无 Bukkit 式世界容器概念,服务端根目录即进程工作目录（服务器以 chdir 方式启动,
 * 与 e2e 实测运行目录一致）。每次调用实时解析（不 lazy 固化）。
 * 字符集取 `file.encoding`（低版本 Windows 常为 GBK）,禁止硬编码 UTF-8。
 *
 * 本实现不直接注册进工具注册表——core 的 [top.wcpe.mc.plugin.serverprobe.core.mcp.LogTailToolProvider]
 * 经 IOC 按接口注入本 Bean,再由其注册进 `McpToolProviderRegistry`。
 */
@Service
@PlatformSide(Platform.VELOCITY)
class VelocityLogPathProvider : LogPathProvider {

    /** Velocity 根目录即进程工作目录绝对路径（服务器以 chdir 方式启动）。 */
    override fun serverRoot(): Path? = Paths.get("").toAbsolutePath().normalize()

    override fun latestLog(): Path? = serverRoot()?.resolve("logs/latest.log")

    override fun charset(): Charset = LOG_CHARSET

    private companion object {

        /** 日志字符集：取 JVM `file.encoding`；异常（极端环境）时回退平台默认字符集。 */
        private val LOG_CHARSET: Charset by lazy {
            runCatching { Charset.forName(System.getProperty("file.encoding") ?: Charset.defaultCharset().name()) }
                .getOrDefault(Charset.defaultCharset())
        }
    }
}
