package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * helper 子进程外置注入（方案③）。
 *
 * self-attach 被 JDK 9+ 默认禁止（`Can not attach to current VM`），而**跨进程** attach 是允许的
 * （arthas-boot 同款原理）：本类以同一 `java.home` 的 java 可执行文件 spawn 一个一次性 helper 进程，
 * 由 helper 进程对目标 PID 执行 `attach + loadAgent` 后立即退出。
 *
 * helper 类与本类同在发行 Jar 内，故 classpath 与被加载的 agent 路径是同一个 Jar。
 * 超时或非零退出码均视为失败（错误输出截断回传，供 MCP 状态消息展示）。
 */
class SubprocessAttacher(
    private val javaExecutable: () -> Path = ::defaultJavaExecutable,
    private val waitForMillis: Long = 30_000L,
) : DynamicAttacher {

    override fun attach(agentJar: Path): DynamicAttachResult = runCatching {
        require(Files.isRegularFile(agentJar)) { "ServerProbe Agent 文件不存在" }
        val agentPath = agentJar.toAbsolutePath().toString()
        val process = ProcessBuilder(
            javaExecutable().toString(),
            "-cp", agentPath,
            HELPER_CLASS,
            currentProcessIdValue(),
            agentPath,
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exited = process.waitFor(waitForMillis, TimeUnit.MILLISECONDS)
        when {
            !exited -> {
                process.destroyForcibly()
                DynamicAttachResult(false, "helper 进程 ${waitForMillis}ms 内未退出")
            }
            process.exitValue() == 0 -> DynamicAttachResult(true, "helper 子进程注入成功")
            else -> DynamicAttachResult(false, "helper 注入失败:${output.take(200)}")
        }
    }.getOrElse { failure ->
        DynamicAttachResult(false, "helper 进程启动失败:${failure.message}")
    }

    private companion object {
        /** 发行 Jar 内的一次性注入入口（同 Jar 既作 helper classpath 又作被加载 agent）。 */
        const val HELPER_CLASS = "top.wcpe.mc.plugin.serverprobe.diagnostics.arthas.SubprocessAttachMain"
    }
}

/** 优先取当前 JVM 同款 java 可执行文件,保证 helper 与服务器运行时一致。 */
internal fun defaultJavaExecutable(): Path {
    val name = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    return Paths.get(System.getProperty("java.home"), "bin", name)
}
