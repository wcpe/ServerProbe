package top.wcpe.mc.plugin.serverprobe.core.config

import taboolib.common.platform.function.getDataFolder
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandRegistry
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeFrames
import top.wcpe.mc.plugin.serverprobe.core.bridge.ConfigStateReporter
import top.wcpe.mc.plugin.serverprobe.core.bridge.UplinkOutcome
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneModeService
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.orchestrator.MetricOrchestrator
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 托管配置的服务端装配点（FR-32 `config_push` + FR-33 `config_state`）。
 *
 * ## 独立模式零回归
 * 只有判定为托管模式才
 * 1. 把 `config_push` 注册为 core 内置动作（独立探针收到该指令会自然回落到"未实现"的失败回执）；
 * 2. 起 `config_state` 上报线程（独立模式一个字节都不上行）。
 *
 * 判定本身复用 [ControlPlaneModeService] 的启动三态结论，本类不重新判一遍。
 *
 * ## 线程模型
 * - 启动装配跑在本类自起的 daemon 线程（`ServerProbe-ManagedConfig`）：等模式判定 → 注册动作 → 起上报线程。
 *   主线程只做"取数据目录 + 起线程"。
 * - `config_push` 的处理发生在**桥的读线程**上（下行帧本就由它接收），全程不触碰 MC 主线程。
 * - `config_state` 上报跑在自己的 daemon 线程，每轮重读间隔配置，故下发新间隔后下一轮即生效。
 *
 * ## 缓存路径
 * `plugins/ServerProbe/managed-cache.json`（[ManagedCacheStore.FILE_NAME]），由
 * [ControlPlaneModeService] 在切到托管模式**之前**装载（见 [ManagedCacheStore.loadInto]），
 * 本类只负责后续应用成功时的原子落盘。
 */
@Service
class ManagedConfigService {

    /** 反向 WS 客户端：`config_applied` / `config_state` 都复用它的连接与写锁。 */
    @Inject
    lateinit var bridge: BridgeClient

    /** 指令分发中心：`config_push` 与 `metrics_now` 走同一套内置动作分发。 */
    @Inject
    lateinit var commandRegistry: BridgeCommandRegistry

    /** 托管模式判定服务：本类等它的结论决定是否启用整条链路。 */
    @Inject
    lateinit var modeService: ControlPlaneModeService

    /** 只读数据出口：自检的 `metricProducible` 从它取最新快照。 */
    @Inject
    lateinit var readApi: ProbeReadApi

    /** 采集编排：自检的 `collectorAlive` 看它的调度是否在跑。 */
    @Inject
    lateinit var orchestrator: MetricOrchestrator

    /** 启动幂等标志（`@PostEnable` 重复回调保护）。 */
    private val started = AtomicBoolean(false)

    /** 上报线程运行标志。 */
    private val reporting = AtomicBoolean(false)

    /** 上报线程句柄。 */
    @Volatile
    private var reporterThread: Thread? = null

    /** 上报失败状态：只在原因变化时告警，避免桥不可达时每周期刷屏。 */
    private val lastUplinkFailure = AtomicReference<String?>(null)

    /** 缓存文件路径；无法解析数据目录时为 null（此时不落盘，并已 WARN）。 */
    @Volatile
    private var cachePath: Path? = null

    /**
     * 启动托管配置链路（`@PostEnable`）。
     *
     * 主线程只做数据目录解析与线程启动，IO/等待全部在后台线程。
     */
    @PostEnable
    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        // 平台 API 只在主线程触碰，后台线程只用已解析好的路径
        cachePath = runCatching { getDataFolder().toPath().resolve(ManagedCacheStore.FILE_NAME) }
            .onFailure { ProbeLogger.warn("无法解析插件数据目录，托管配置缓存将不会落盘:${it.message}") }
            .getOrNull()
        val worker = Thread({ bootstrap() }, THREAD_NAME)
        worker.isDaemon = true
        worker.start()
    }

    /** 停止上报线程（`@PreDestroy`）。 */
    @PreDestroy
    fun stop() {
        started.set(false)
        // 只有真的起过上报线程才打这条日志：独立模式下什么都没启用，不该出现"已停止"的噪音
        if (reporting.getAndSet(false)) {
            reporterThread?.let { worker -> runCatching { worker.interrupt() } }
            ProbeLogger.info("托管配置状态上报已停止")
        }
        reporterThread = null
    }

    /** 等模式判定 → 按模式装配 → 起上报线程（后台线程）。 */
    private fun bootstrap() {
        modeService.awaitReady(BOOTSTRAP_WAIT_MS)
        val mode = modeService.mode()
        if (!install(mode)) {
            ProbeLogger.info("当前为独立模式，未启用 config_push 与 config_state（与改造前行为一致）")
            return
        }
        startReporting()
    }

    /**
     * 按运行模式装配链路（独立模式**什么都不做**，这是零回归的开关点）。
     *
     * 抽成独立方法并返回布尔值，是为了让"独立模式不注册、不上报"这条硬约束可被单测直接断言，
     * 不必起线程或连网。
     *
     * @param mode 当前运行模式。
     * @return true 表示已启用托管链路。
     */
    internal fun install(mode: ProbeMode): Boolean {
        if (mode != ProbeMode.MANAGED) {
            return false
        }
        val applier = ConfigPushApplier(
            selfCheck = ::selfCheck,
            // 数据目录解析失败时跳过落盘（启动时已 WARN 过一次），不影响内存生效
            writeCache = { snapshot -> cachePath?.let { ManagedCacheStore.write(it, snapshot) } },
        )
        commandRegistry.registerCoreAction(ACTION_CONFIG_PUSH) { command -> handlePush(applier, command) }
        return true
    }

    /**
     * 处理一条 `config_push`：应用 → 上报 `config_applied` → 回 `command_result`。
     *
     * 两条上行并不重复：`config_applied` 是协议规定的事件（供控制面对账/审计），
     * `command_result` 是对本次 `requestId` 的同步回执（供调用方等待）。二者用同一份结果，不会互相矛盾。
     *
     * @param applier 应用链路。
     * @param command 下行指令（`payload` 为载荷 JSON）。
     * @return 回执结果（`BridgeClient` 负责带回 requestId）。
     */
    private fun handlePush(applier: ConfigPushApplier, command: BridgeCommand): BridgeCommandResult {
        val payload = runCatching { ConfigPushPayload.parse(command.payload) }.getOrElse { failure ->
            val reason = "config_push 载荷不可解析:${failure.message}"
            ProbeLogger.warn(reason)
            return BridgeCommandResult.fail(reason)
        }
        val outcome = applier.apply(payload)
        sendApplied(outcome)
        return if (outcome.applied) {
            BridgeCommandResult.ok("配置已生效:configVersion=${outcome.configVersion}")
        } else {
            BridgeCommandResult.fail(outcome.error ?: "配置未生效(configVersion=${outcome.configVersion})")
        }
    }

    /**
     * 上行一帧 `config_applied`（失败只告警，绝不影响应用结果）。
     *
     * 本方法跑在桥的读线程上，故**整体** try/catch：回执发不出去只能降级成一行 WARN，
     * 绝不能让"配置已经生效了、却因为上报失败而抛异常打断读循环"。
     */
    // 实例标识解析、JSON 编码、写 socket 任一环都可能抛，此处广捕是有意为之
    @Suppress("TooGenericExceptionCaught")
    private fun sendApplied(outcome: ConfigPushOutcome) {
        try {
            val frame = BridgeFrames.configApplied(bridge.instanceId, System.currentTimeMillis(), outcome)
            val sent = bridge.sendUplink(Json.encode(frame))
            if (sent is UplinkOutcome.Failed) {
                ProbeLogger.warn("上报 config_applied 失败:${sent.reason}(控制面可据 config_state 对账)")
            }
        } catch (e: Exception) {
            ProbeLogger.warn("上报 config_applied 异常(配置本身已处理完成):${e.message}")
        }
    }

    /** 起 `config_state` 上报线程（仅托管模式调用）。 */
    private fun startReporting() {
        if (!reporting.compareAndSet(false, true)) {
            return
        }
        val reporter = ConfigStateReporter(
            instance = { bridge.instanceId },
            connection = { bridge.connectionState() },
            send = { frame -> bridge.sendUplink(Json.encode(frame)) },
        )
        val worker = Thread({ reportLoop(reporter) }, REPORTER_THREAD_NAME)
        worker.isDaemon = true
        reporterThread = worker
        worker.start()
        ProbeLogger.debug(
            "托管状态上报已启动(间隔 ${runCatching { ProbeConfig.configStateIntervalMs() }.getOrDefault(0L)}ms)",
        )
    }

    /**
     * 上报循环：每轮重读间隔 → 休眠 → 上报一帧。
     *
     * 间隔每轮重读以支持热生效（SAFE 档键下发后下一轮即生效）；休眠被打断（stop）即退出。
     */
    private fun reportLoop(reporter: ConfigStateReporter) {
        while (reporting.get()) {
            val interval = runCatching { ProbeConfig.configStateIntervalMs() }.getOrDefault(DEFAULT_INTERVAL_MS)
            try {
                Thread.sleep(interval)
            } catch (e: InterruptedException) {
                // stop 主动打断：恢复中断标志后立即退出
                Thread.currentThread().interrupt()
                return
            }
            if (!reporting.get()) {
                return
            }
            // 状态上报是观测手段：任何异常都只降级，绝不让上报线程挂掉
            runCatching { reporter.reportOnce() }
                .onSuccess { outcome -> recordUplink(outcome) }
                .onFailure { ProbeLogger.warn("上报 config_state 异常:${it.message}(同一原因不再重复告警)") }
        }
    }

    /** 记录上行结果：只在失败原因变化时告警，恢复时提示一次。 */
    private fun recordUplink(outcome: UplinkOutcome) {
        when (outcome) {
            // 桥断开属已接受的取舍（规格 §3.6 R1）：状态帧自然停止，不告警、不补发旧值
            UplinkOutcome.NotConnected -> Unit
            is UplinkOutcome.Failed -> {
                if (lastUplinkFailure.getAndSet(outcome.reason) != outcome.reason) {
                    ProbeLogger.warn("上报 config_state 失败:${outcome.reason}(同一原因不再重复告警)")
                }
            }
            UplinkOutcome.Sent -> {
                if (lastUplinkFailure.getAndSet(null) != null) {
                    ProbeLogger.info("config_state 上报已恢复")
                }
            }
        }
    }

    /**
     * 执行自检三项（协议规格 §3.4.2）。
     *
     * 三项都是**真实可判定**的探针状态，不是恒真空壳：
     * - `collectorAlive`：采集调度是否在跑（[MetricOrchestrator.isRunning]）；
     * - `metricProducible`：能否取到一份非空指标快照（JVM 段非空）；
     * - `bridgeUsable`：当前桥上是否可发帧（[BridgeClient.isUplinkReady]，与 `sendUplink` 同口径）。
     *
     * @return 自检结果。
     */
    private fun selfCheck(): SelfCheckResult = SelfCheckResult(
        collectorAlive = runCatching { orchestrator.isRunning() }.getOrDefault(false),
        metricProducible = runCatching { readApi.latestSnapshot()?.jvm != null }.getOrDefault(false),
        bridgeUsable = runCatching { bridge.isUplinkReady() }.getOrDefault(false),
    )

    private companion object {

        /** 装配线程名（便于线上排查）。 */
        private const val THREAD_NAME = "ServerProbe-ManagedConfig"

        /** 上报线程名。 */
        private const val REPORTER_THREAD_NAME = "ServerProbe-ConfigState"

        /** `config_push` 动作名（协议规格 §3.4.1）。 */
        private const val ACTION_CONFIG_PUSH = "config_push"

        /**
         * 等待托管模式判定的上限（毫秒）。
         *
         * 与桥的等待取值一致：判定只做小文件读取，正常毫秒级完成；设上限只为极端情况下
         * 不把装配线程永久卡住——超时即按"非托管"处理（等于不启用配置链路，保守且无副作用）。
         */
        private const val BOOTSTRAP_WAIT_MS = 5_000L

        /** 读不到配置时的兜底上报间隔（毫秒）：与代码默认值一致。 */
        private const val DEFAULT_INTERVAL_MS = 30_000L
    }
}
