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
 * 在插件生命周期内注册真实 Arthas 控制器，并提供运行期加载/卸载。
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

    /** 是否已随插件卸载；置位后拒绝一切加载（异步加载可能晚于卸载被调度）。 */
    @Volatile private var destroyed = false
    @Volatile private var instrumentation = ArthasInstrumentationStatus(
        InstrumentationSource.UNAVAILABLE,
        null,
        "尚未初始化 Arthas Instrumentation",
    )

    /**
     * 注册进 [ArthasRuntimeRegistry] 的启停入口（构造一次，长期有效）。
     *
     * `runtimeReady` 以"控制器已注册 **且** Instrumentation 可用"为准：attach 失败时控制器仍会被注册
     * （命令执行时会报"无 Instrumentation"），若只按"已注册"呈现会与"已加载 = 可用"的预期不符。
     */
    private val runtime = LifecycleArthasRuntime(::startRuntime, ::stopRuntime) { tasks != null && instrumentation.instrumentation != null }

    /**
     * 启动期自动加载的调度入口。
     *
     * 收口成可替换字段，是为了让“启动不得阻塞启用线程”这条红线能被**行为测试**验证：
     * 测试注入捕获型调度器后可断言 `start()` 只交出任务、并没有在这里执行加载。
     *
     * ## 为何用裸线程而不是 `submitAsync`
     *
     * `@PostEnable` 跑在 TabooLib 的 ENABLE 阶段（优先级 -80），早于平台执行器可用。
     * 那里调 `submitAsync` **不会报错**（拿得到一个 task handle），但任务体永远不会被执行：
     * 真机（game-1）实测“已提交异步自动加载任务”之后再也没有任何后续日志，
     * 解包目标目录也从未创建——排查了一整天，现象与“从来没调过加载”完全一样。
     *
     * 裸守护线程不依赖任何平台服务，与 [BridgeClient][top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient]
     * 在同一个 `@PostEnable` 阶段启动网络线程的做法一致（那边已经被验证可用）。
     * 用 daemon 是为了不阻止 JVM 退出；[stop] 通过 `destroyed` 与任务自身的幂等保证安全。
     */
    internal var autoLoadScheduler: (() -> Unit) -> Unit = { task ->
        Thread(task, "ServerProbe-ArthasAutoLoad").apply {
            isDaemon = true
            start()
        }
    }

    /** 启动期是否需要自动加载；默认读 `mcp.enabled`（托管/独立判定不变），测试可替换。 */
    internal var autoLoadGate: () -> Boolean = { ProbeConfig.mcpEnabled() }

    /** 实际加载动作；默认 [startRuntime]，测试可替换以免在无平台环境下触碰宿主目录。 */
    internal var runtimeLoader: () -> ArthasInstrumentationSnapshot = ::startRuntime

    /**
     * 启动期：始终注册启停能力（使 `mcp.enabled=false` 的实例也能被控制台按需加载），
     * `mcp.enabled=true` 时**异步**自动加载。
     *
     * 加载含运行包解包与 Instrumentation 附加（必要时 spawn helper 子进程注入，等待上限 30s），
     * 不得占用启用线程（= 服务器主线程）；[startRuntime] 幂等且 `@Synchronized`，异步与命令路径并发安全。
     * 异步任务可能被调度到 [stop]（卸载）之后执行，故由 [loadAfterStartup] 拒绝“卸载后加载”，
     * 避免残留线程阻止 JVM 退出。
     */
    @PostEnable fun start() {
        runtimeRegistration.register(runtime)
        // 注册与自动加载各留一行日志：排查时能分清「Bean 根本没被实例化/PostEnable 没触发」
        // 与「PostEnable 跑了但注册/加载失败」——这两种故障的现象（retry_attach 报未注册）完全一样。
        println("[ServerProbe][Arthas] @PostEnable 已执行:控制器已注册")
        // 注意：**不要在这里读配置**。
        //
        // `mcp.enabled` 依赖 `ProbeConfig.conf`（TabooLib `@Config` 注入）。真机实测：
        // 当 IoC 走构建期索引路径时，本 Bean 的 `@PostEnable` 会排在插件启用主流程之前，
        // 彼时 `conf` 尚未注入，`mcpEnabled()` 会静默取到默认值 false ——
        // 现象是插件不报任何错、但 Arthas 永远不自动加载（日志里 “mcp.enabled=false”）。
        //
        // 因此把判定推迟到异步任务真正执行时（那时启用流程早已走完、配置已就绪）。
        // 这同时消除了对“框架偶然执行顺序”的依赖。
        runCatching { autoLoadScheduler { autoLoadAfterStartup() } }
            .onFailure { println("[ServerProbe][Arthas] 自动加载调度失败: ${it.javaClass.simpleName}:${it.message}") }
            .onSuccess { println("[ServerProbe][Arthas] 已提交异步自动加载任务") }
    }

    /**
     * 异步任务入口：等配置真正就绪后再读 `mcp.enabled`，决定是否加载。
     *
     * 放在异步线程里做，是为了避开 `@PostEnable` 早于 `@Config` 注入的时序窗口。
     *
     * 但“已到异步线程”不等于“配置已就绪”（调度可能快过启用流程），所以这里再
     * 按退避重试几次：配置一旦可用就立即结束等待。总共最多约 5 秒，不影响启动。
     */
    private fun autoLoadAfterStartup() {
        val enabled = awaitConfigReady()
        println("[ServerProbe][Arthas] 自动加载判定: mcp.enabled=$enabled")
        if (enabled) loadAfterStartup()
    }

    /**
     * 轮询读 `mcp.enabled` 直到配置可用（或超时）。
     *
     * 不能用“读一次失败就放弃”：`@PostEnable` 与插件启用主流程的相对顺序会随
     * IoC 扫描实现变化（反射路径与索引路径就不同），单次读取必然会在某个版本上碰壁。
     *
     * 用退避递增而不是固定间隔：正常情况（配置已就绪）第一次就能拿到，零延迟；
     * 只有在真的碰上了时序窗口时才依次退让，最多 5 秒。
     */
    private fun awaitConfigReady(): Boolean {
        // 用项目级的统一入口，而不是自己轮询：
        // `ProbeConfig.conf` 由 TabooLib 在加载期反射注入，而 IoC 组件的装配可能跑在注入之前。
        // `McpControlPlane` / `McpBridgeService` / `WebPanelServer` / `PrometheusExporter` 全都先过
        // `awaitLoaded()`，之前本类漏了这一步，才出现“config.yml 里 mcp.enabled: true，
        // 这里却读到默认 false、Arthas 永不自动加载”的真机故障。
        if (!ProbeConfig.awaitLoaded()) {
            println(
                "[ServerProbe][Arthas] 等待配置就绪超时，按未启用处理；" +
                    "可稍后用 retry_attach 或控制台手动加载",
            )
            return false
        }
        return autoLoadGate()
    }

    /**
     * 启动期异步加载的唯一入口：已卸载时直接拒绝，否则交给 [runtimeLoader]。
     *
     * 拒绝判定必须在真正加载之前完成——异步任务可能晚于 [stop]（`@PreDestroy`）被调度执行，
     * 此时再拉起运行时只会留下阻止 JVM 退出的非 daemon 线程。
     */
    internal fun loadAfterStartup(): ArthasInstrumentationSnapshot =
        if (destroyed) unavailableDestroyed() else runCatching { runtimeLoader() }.getOrElse { error ->
            // 这里是异步任务的最外层：异常逃出会只留一条无堆栈的线程警告，
            // 完全看不出是 Arthas 加载失败，所以就地捕获并打完整堆栈。
            println("[ServerProbe][Arthas] 异步加载抛出异常: ${error.javaClass.name}:${error.message}")
            error.printStackTrace()
            ArthasInstrumentationSnapshot(false, "UNAVAILABLE", "异步加载异常:${error.javaClass.simpleName}:${error.message}")
        }

    @PreDestroy fun stop() {
        destroyed = true
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
     * "已加载"取"控制器已注册 **且** Instrumentation 可用"，与 [runtime] 呈现给运维的口径一致：
     * attach 失败时控制器虽已注册但无 Instrumentation，此时必须允许再次尝试（先卸载残留再重来），
     * 否则运维会陷入"状态显示未加载、却又被幂等拦截"的矛盾，永远无法重试。
     *
     * 每次加载都新建 [ArthasMemoryShellRunner] 与 [ArthasTaskManager]：后者被关闭后其线程池永久终止，
     * 且关闭会连带关闭 runner，故实例不可复用。Instrumentation 访问器同样重建，以复位此前失败的 attach 记录。
     */
    @Synchronized
    private fun startRuntime(): ArthasInstrumentationSnapshot {
        if (destroyed) return unavailableDestroyed()
        if (tasks != null && instrumentation.instrumentation != null) return alreadyLoaded()
        // 上一次 attach 失败留下的控制器必须回收：其任务管理器持有非 daemon 线程，不释放会阻止 JVM 退出。
        if (tasks != null) stopRuntime()
        val runtime = getDataFolder().toPath().resolve("mcp-workspace").resolve("arthas")
        val extraction = ArthasRuntimeExtractor { path -> javaClass.classLoader.getResourceAsStream(path) }.extract(runtime)
        println("[ServerProbe][Arthas] 运行包提取结果: ${extraction.javaClass.simpleName}")
        if (extraction !is ArthasRuntimeExtraction.Ready) {
            val snapshot = unavailableRuntime(extraction)
            println("[ServerProbe][Arthas] 加载中止: ${snapshot.message}")
            return snapshot
        }
        instrumentationAccess = ArthasInstrumentationAccess(ProbeAgentInstrumentationReader::read)
        agentJar = ServerProbeAgentJarLocator.locate(javaClass)
        println("[ServerProbe][Arthas] agent 文件定位: ${agentJar?.toString() ?: "未找到"}")
        instrumentation = agentJar?.let { jar -> instrumentationAccess?.acquire(jar) } ?: unavailableAgentJar()
        register(ArthasMemoryShellRunner(extraction.directory) { instrumentation.instrumentation })
        println("[ServerProbe][Arthas] Instrumentation 状态: ${instrumentation.toSnapshot()}")
        if (instrumentation.instrumentation == null) {
            // attach 失败同样不留残骸：控制器无法工作，留着只会让状态与能力不一致。
            stopRuntime()
            return instrumentation.toSnapshot()
        }
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

    /** 插件已卸载（异步加载被调度到卸载之后）时拒绝加载。 */
    private fun unavailableDestroyed() = ArthasInstrumentationSnapshot(
        false,
        InstrumentationSource.UNAVAILABLE.name,
        "插件已卸载，拒绝加载 Arthas 运行时",
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
 * 把 lifecycle 的私有启停逻辑接到 core 的 [ArthasRuntime] 契约上。
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
