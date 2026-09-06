package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import taboolib.common.platform.function.getDataFolder
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasControlRegistration
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasControl
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasInstrumentationSnapshot
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 在插件生命周期内注册真实 Arthas 控制器。 */
@Service
class ArthasDiagnosticsLifecycle {
    @Inject lateinit var registry: ArthasControlRegistration
    private var tasks: ArthasTaskManager? = null
    @Inject lateinit var control: ArthasControl
    private var instrumentationAccess: ArthasInstrumentationAccess? = null
    private var agentJar: Path? = null
    @Volatile private var instrumentation = ArthasInstrumentationStatus(
        InstrumentationSource.UNAVAILABLE,
        null,
        "尚未初始化 Arthas Instrumentation",
    )

    @PostEnable fun start() {
        if (!ProbeConfig.mcpEnabled()) return
        val runtime = getDataFolder().toPath().resolve("mcp-workspace").resolve("arthas")
        val extraction = ArthasRuntimeExtractor { path -> javaClass.classLoader.getResourceAsStream(path) }.extract(runtime)
        if (extraction !is ArthasRuntimeExtraction.Ready) return
        instrumentationAccess = ArthasInstrumentationAccess(ProbeAgentInstrumentationReader::read)
        agentJar = ServerProbeAgentJarLocator.locate(javaClass)
        instrumentation = agentJar?.let(instrumentationAccess!!::acquire) ?: unavailableAgentJar()
        val runner = ArthasMemoryShellRunner(extraction.directory) { instrumentation.instrumentation }
        register(runner)
    }

    @PreDestroy fun stop() {
        if (this::control.isInitialized) registry.unregister(control)
        tasks?.close()
        tasks = null
    }

    private fun register(runner: ArthasMemoryShellRunner) {
        val manager = ArthasTaskManager(
            runner,
            taskSettings(),
            bytecodeReader = runner::dumpClassBytes,
        )
        tasks = manager
        RetryingArthasControl(manager, ::retryAttach).also { current -> control = current; registry.register(current) }
    }

    private fun retryAttach(): ArthasInstrumentationSnapshot {
        instrumentation = agentJar?.let { instrumentationAccess?.retry(it) } ?: unavailableAgentJar()
        return instrumentation.toSnapshot()
    }

    private fun unavailableAgentJar() = ArthasInstrumentationStatus(
        InstrumentationSource.UNAVAILABLE,
        null,
        "无法定位 ServerProbe Agent 文件，不能动态附加",
    )

    private fun taskSettings(): ArthasTaskSettings = ArthasTaskSettings(
        maxConcurrent = ProbeConfig.mcpTaskMaxConcurrent(),
        maxOutputChars = outputChars(),
        completionRetentionMillis = TimeUnit.MINUTES.toMillis(ProbeConfig.mcpTaskRetentionMinutes()),
    )

    private fun outputChars(): Int = (ProbeConfig.mcpTaskOutputMebibytes().toLong() * BYTES_PER_MEBIBYTE)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val BYTES_PER_MEBIBYTE = 1024L * 1024L
    }
}

/** diagnostics 负责 attach 细节，core 仅看到稳定的控制器契约。 */
private class RetryingArthasControl(
    private val delegate: ArthasControl,
    private val retry: () -> ArthasInstrumentationSnapshot,
) : ArthasControl by delegate {
    override fun retryAttach(): ArthasInstrumentationSnapshot = retry()
}

private fun ArthasInstrumentationStatus.toSnapshot(): ArthasInstrumentationSnapshot =
    ArthasInstrumentationSnapshot(instrumentation != null, source.name, message)
