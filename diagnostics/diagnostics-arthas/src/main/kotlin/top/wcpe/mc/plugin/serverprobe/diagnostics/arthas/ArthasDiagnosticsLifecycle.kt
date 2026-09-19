package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import taboolib.common.platform.function.getDataFolder
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasControlRegistration
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasControl
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasInstrumentationSnapshot
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasRuntime
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasRuntimeRegistration
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 在插件生命周期内注册真实 Arthas 控制器，并提供运行期加载/卸载（FR-24）。
 *
 * 启动期：**始终**把启停能力注册进 [ArthasRuntimeRegistration]（使 `mcp.enabled=false` 的实例也能被
 * 控制台按需加载），随后仅在 `mcp.enabled=true` 时自动加载，保持既有自动化语义与向后兼容。
 *
 * 本类**不直接实现** [ArthasRuntime]：与既有的 `RetryingArthasControl` 同理，注册一个转发到本类
 * 私有启停逻辑的内部实现，使 [ArthasRuntimeRegistry] 成为该接口下唯一的 IOC Bean，避免按类型注入歧义。
 */
@Service
class ArthasDiagnosticsLifecycle {
    @Inject lateinit var registry: ArthasControlRegistration
    @Inject lateinit var runtimeRegistration: ArthasRuntimeRegistration
    private var tasks: ArthasTaskManager? = null
    @Inject lateinit var control: ArthasControl
    private var instrumentationAccess: ArthasInstrumentationAccess? = null
    private var agentJar: Path? = null
    @Volatile private var instrumentation = ArthasInstrumentationStatus(
        InstrumentationSource.UNAVAILABLE,
        null,
        "尚未初始化 Arthas Instrumentation",
    )

    /** 注册进 [ArthasRuntimeRegistry] 的启停入口（构造一次，长期有效）。 */
    private val runtime = LifecycleArthasRuntime(::startRuntime, ::stopRuntime) { tasks != null }

    @PostEnable fun start() {
        runtimeRegistration.register(runtime)
        if (ProbeConfig.mcpEnabled()) startRuntime()
    }

    @PreDestroy fun stop() {
        stopRuntime()
        runtimeRegistration.unregister(runtime)
    }

    /**
     * 运行期加载 Arthas 运行时；已加载时为幂等空操作。
     *
     * 幂等判定必须在本方法内完成（而非由调用方先查 [LifecycleArthasRuntime.runtimeReady] 再调用）：
     * 两条并发命令若都在检查时看到"未加载"，后到的一条会落到已加载分支却回执上一次的 attach 结果，
     * 造成"重复开启"的误报。此处作为唯一决策点，已加载时返回"无需重复开启"的说明。
     *
     * 每次加载都新建 [ArthasMemoryShellRunner] 与 [ArthasTaskManager]：后者被关闭后其线程池永久终止，
     * 且关闭会连带关闭 runner，故实例不可复用。Instrumentation 访问器同样重建，以复位此前失败的 attach 记录。
     */
    @Synchronized
    private fun startRuntime(): ArthasInstrumentationSnapshot {
        if (tasks != null) return alreadyLoaded()
        val runtime = getDataFolder().toPath().resolve("mcp-workspace").resolve("arthas")
        val extraction = ArthasRuntimeExtractor { path -> javaClass.classLoader.getResourceAsStream(path) }.extract(runtime)
        if (extraction !is ArthasRuntimeExtraction.Ready) return unavailableRuntime(extraction)
        instrumentationAccess = ArthasInstrumentationAccess(ProbeAgentInstrumentationReader::read)
        agentJar = ServerProbeAgentJarLocator.locate(javaClass)
        instrumentation = agentJar?.let { jar -> instrumentationAccess?.acquire(jar) } ?: unavailableAgentJar()
        register(ArthasMemoryShellRunner(extraction.directory) { instrumentation.instrumentation })
        return instrumentation.toSnapshot()
    }

    /**
     * 运行期卸载 Arthas 运行时；未加载时为幂等空操作。
     *
     * 幂等判定在此完成并回报是否确实卸载（理由同 [startRuntime] 的幂等说明）。
     *
     * 顺序不可调换：先按引用注销当前已注册的控制器（避免控制器指向已关闭的任务管理器），再关闭任务管理器
     * 释放其非 daemon 线程与隔离加载器，否则会阻止 JVM 退出。
     */
    @Synchronized
    private fun stopRuntime(): Boolean {
        val manager = tasks ?: return false
        if (this::control.isInitialized) registry.unregister(control)
        manager.close()
        tasks = null
        return true
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

    /** 运行包提取失败时回报原因（[ArthasRuntimeExtraction.Failed] 已含中文说明）。 */
    private fun unavailableRuntime(extraction: ArthasRuntimeExtraction) = ArthasInstrumentationSnapshot(
        false,
        InstrumentationSource.UNAVAILABLE.name,
        (extraction as? ArthasRuntimeExtraction.Failed)?.reason ?: "Arthas 运行包不可用",
    )

    /** 已加载时的幂等回执；`available=true` 使命令层按成功路径呈现"无需重复开启"。 */
    private fun alreadyLoaded() = ArthasInstrumentationSnapshot(true, instrumentation.source.name, "Arthas 运行时已加载，无需重复开启。")

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

/**
 * 把 lifecycle 的私有启停逻辑接到 core 的 [ArthasRuntime] 契约上（FR-24）。
 *
 * 以函数引用而非对象引用持有宿主，避免注册表长期持有 lifecycle 之外的多余能力；
 * 每次调用都读取当前状态，故加载/卸载后的 `runtimeReady` 变化立即可见。
 */
private class LifecycleArthasRuntime(
    private val startRuntime: () -> ArthasInstrumentationSnapshot,
    private val stopRuntime: () -> Boolean,
    private val ready: () -> Boolean,
) : ArthasRuntime {

    override fun startRuntime(): ArthasInstrumentationSnapshot = this.startRuntime.invoke()

    override fun stopRuntime(): Boolean = this.stopRuntime.invoke()

    override val runtimeReady: Boolean
        get() = ready()
}

private fun ArthasInstrumentationStatus.toSnapshot(): ArthasInstrumentationSnapshot =
    ArthasInstrumentationSnapshot(instrumentation != null, source.name, message)
