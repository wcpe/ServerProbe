package top.wcpe.mc.plugin.serverprobe.core.control

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地监听面（`metrics` / `mcp` / `web`）的 bind 闸门（协议规格 §3.6「零监听端口」）。
 *
 * ## 判断依据是运行模式，而不是配置值
 * `metrics.enabled` / `mcp.enabled` / `web.enabled` 三组键已被 [ConfigTier] 定为 [ConfigTier.FORBIDDEN]
 * （禁止远程开启），但**本地 `config.yml` 里遗留的 `true` 仍然存在**——存量自动迁移只移除 `bridge` 段，
 * 不会改写这三组开关。所以托管模式下不能信任配置，必须由模式判定强制关断，否则一台被托管的探针
 * 会因为历史配置继续对外开端口，直接违背本 FR 的核心承诺。
 *
 * ## 独立模式零回归
 * 闸门只对 [ProbeMode.MANAGED] 关断；独立模式下恒放行，三个监听面的启停完全照旧由配置决定。
 *
 * ## 判定是异步的
 * 运行模式由 [ControlPlaneModeService] 在自己的后台线程判定，`@PostEnable` 时往往还没有结论。
 * 因此监听面统一经 [LocalListenerLauncher] 启动：先在后台线程等判定完成，再回来问闸门——
 * 这样既保证不会在"托管但未判定"的窗口里抢先 bind，也不阻塞 MC 主线程。
 */
internal object LocalListenerGate {

    /** 三个本地监听面（协议规格 §3.6 的表格）。 */
    internal enum class Surface(val label: String) {

        /** Prometheus `/metrics` 抓取端点，载体是 `PrometheusExporter`。 */
        METRICS("metrics"),

        /** MCP 控制面 HTTP 端点，载体是 `McpHttpServer`。 */
        MCP("mcp"),

        /** Web 面板，载体是 `WebPanelServer`。 */
        WEB("web");

        /** 是否已提示过"托管模式下跳过"：只提示一次，避免运行期命令反复刷屏。 */
        internal val announced = AtomicBoolean(false)
    }

    /**
     * 已 bind 的监听面 → 回撤动作。
     *
     * 只在“独立模式已经起服、随后模式才落定为托管”这种窗口里用得上（正常路径下托管模式根本不会 bind），
     * 但零监听承诺不能只靠启动时那一次判断：一旦必须回撤，得有地方把它关掉。
     */
    private val boundStops = ConcurrentHashMap<Surface, () -> Unit>()

    /**
     * 托管模式是否已经回撤过一轮（粘性）。
     *
     * 用于堵住「回撤已发生、监听面才刚登记」这个 check-then-act 窗口：判定超时路径里
     * launcher 可能正拿着 `allows()` 的放行结果在 bind，而判定线程此时刚好 `publish(MANAGED)`。
     * 若只在回撤时看一次登记表，那一轮会看到空表直接返回，随后登记的这个面就**永远没人关**。
     */
    @Volatile
    private var managedReconciled = false

    /**
     * 登记一个已真正 bind 的监听面，供托管模式落定时回撤。
     *
     * 登记后会复查 [managedReconciled]：若回撤已经发生过（本线程入表晚于回撤），**立即执行关闭**
     * 并返回 false，调用方不得再对外宣告“已启动”。
     *
     * @param surface 监听面。
     * @param stop 幂等的关闭动作（失败只能降级，不得抛）。
     * @return true 表示该面可以继续对外提供服务；false 表示已被回撤（调用方应停止后续动作）。
     */
    fun registerBound(surface: Surface, stop: () -> Unit): Boolean {
        boundStops[surface] = stop
        if (!managedReconciled) {
            return true
        }
        val pending = boundStops.remove(surface) ?: return false
        ProbeLogger.warn(
            "托管模式判定已完成，回撤刚登记的本地 ${surface.label} 监听端口（零监听端口承诺）",
        )
        runCatching(pending).onFailure { error ->
            ProbeLogger.warn("回撤 ${surface.label} 监听面失败（已降级继续）:${error.message}")
        }
        return false
    }

    /** 注销登记（监听面自行停止时调用，避免回撤阶段重复动作）。 */
    fun unregisterBound(surface: Surface) {
        boundStops.remove(surface)
    }

    /**
     * 托管模式落定：回撤所有已开监听面（零监听承诺的兵败傅）。
     *
     * 幂等、可在任意线程调用（实际由判定线程在 [ControlPlaneRuntime.publish] 时触发）。
     * 单个面的关闭失败只 WARN，不影响其它面。
     */
    internal fun reconcileManaged() {
        // 先置位再回撤：之后任何 registerBound 都会自检并当场关掉，不会漏掉“晚登记”的那一个
        managedReconciled = true
        if (boundStops.isEmpty()) {
            return
        }
        boundStops.keys.toList().forEach { surface ->
            val stop = boundStops.remove(surface) ?: return@forEach
            ProbeLogger.warn(
                "托管模式判定完成，回撤已开启的本地 ${surface.label} 监听端口（零监听端口承诺）",
            )
            runCatching(stop).onFailure { error ->
                ProbeLogger.warn("回撤 ${surface.label} 监听面失败（已降级继续）:${error.message}")
            }
        }
    }

    /** 仅测试用：清空已登记的回撤动作。 */
    internal fun clearBoundForTest() {
        boundStops.clear()
        managedReconciled = false
    }

    /**
     * 按当前运行态判定某监听面是否可以 bind。
     *
     * @param surface 监听面。
     * @return true 表示允许 bind（独立模式）；false 表示托管模式，必须跳过。
     */
    fun allows(surface: Surface): Boolean = allows(surface, ControlPlaneRuntime.mode())

    /**
     * 纯判定（不读全局运行态，便于单测逐档断言）。
     *
     * @param surface 监听面。
     * @param mode 当前运行模式。
     * @return true 表示允许 bind；仅托管模式返回 false。
     */
    fun allows(surface: Surface, mode: ProbeMode): Boolean {
        if (mode != ProbeMode.MANAGED) {
            return true
        }
        // 每个监听面只提示一次：托管模式下这是**预期**行为，不是故障
        if (surface.announced.compareAndSet(false, true)) {
            ProbeLogger.info("托管模式下不启动本地 ${surface.label} 监听端口（零监听端口承诺），已跳过")
        }
        return false
    }
}

/**
 * 监听面启动器：把「等托管模式判定 → 再 bind」放到守护后台线程，主线程零阻塞。
 *
 * ## 为什么必须异步
 * 模式判定要读身份文件（磁盘 IO），按红线不能在 MC 主线程做；而判定结论又必须先于 bind，
 * 否则托管探针会在"身份文件存在但判定尚未完成"的窗口里抢先把端口开起来。放到后台线程等即可
 * 同时满足两条约束。
 *
 * ## 失败一律降级，但降级方向看“有没有托管意图”
 * 等待超时（磁盘极慢等极端情况）与 bind 异常都不得影响插件其余功能；但降级方向不同：
 * - **确认没有身份文件** → 按独立模式继续 bind（与引入托管能力前完全一致）；
 * - **身份文件存在（托管意图）但判定未完成** → **不 bind**（fail-close）：宁可不提供服务，
 *   也不能在一台托管机上把端口开起来——那正是本 FR 的核心承诺。
 *
 * @property surface 监听面（用于线程名与日志）。
 * @property awaitDecision 等待判定完成的回调（生产为 [ControlPlaneModeService.awaitReady]）。
 * @property waitMs 等待上限（毫秒）。
 */
internal class LocalListenerLauncher(
    private val surface: LocalListenerGate.Surface,
    private val awaitDecision: (Long) -> Boolean,
    private val waitMs: Long = DEFAULT_WAIT_MS,
) {

    /**
     * 起守护线程：先等判定，再执行 [bind]。
     *
     * @param bind bind 动作；其内部仍会再过一次 [LocalListenerGate]（防止判定为空档）。
     * @return 已启动的后台线程（供单测 join）。
     */
    fun launch(bind: () -> Unit): Thread {
        val worker = Thread({
            // 等待判定：任一异常都只降级（按当前运行态继续），绝不让后台线程挂掉
            val decided = runCatching { awaitDecision(waitMs) }.getOrElse { error ->
                ProbeLogger.warn("等待托管模式判定失败(${surface.label}),按当前运行态继续:${error.message}")
                false
            }
            if (!decided && ControlPlaneRuntime.identityPresent()) {
                // fail-close：有身份文件但判定没完成，宁可这次不开端口
                ProbeLogger.warn(
                    "等待托管模式判定未完成(${surface.label})且存在身份文件：为守住零监听承诺，本次不启动监听",
                )
                return@Thread
            }
            if (!decided) {
                // 确认无托管意图：按独立模式继续，行为与引入托管能力前一致
                ProbeLogger.warn("等待托管模式判定超时(${surface.label},上限 ${waitMs}ms),按独立模式继续")
            }
            // bind 本身已各自 try/catch；此处再兜一层，确保线程不会因意外异常静默死掉
            runCatching(bind)
                .onFailure { ProbeLogger.warn("${surface.label} 监听面启动异常,已降级跳过:${it.message}") }
        }, "ServerProbe-Listener-${surface.label}")
        worker.isDaemon = true
        worker.start()
        return worker
    }

    private companion object {

        /**
         * 等待模式判定的上限（毫秒）。
         *
         * 与 `BridgeClient.BOOTSTRAP_WAIT_MS` 取同一量级：判定只做一次小文件读取（必要时加一次迁移写盘），
         * 正常在毫秒级完成；设上限只为极端情况（磁盘极慢）下不把监听面永久卡住。
         */
        private const val DEFAULT_WAIT_MS = 5_000L
    }
}
