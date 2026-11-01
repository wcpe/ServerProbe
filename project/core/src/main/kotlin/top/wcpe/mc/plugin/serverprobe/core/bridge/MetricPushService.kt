package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneModeService
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 指标经反向 WS 推送的服务（协议规格 §3.4.4 / §3.4.5）。
 *
 * ## 职责
 * 1. **周期推送**：按 `metrics-push-interval-ms`（默认 5000，下限 1000）推 `metric_snapshot`；
 * 2. **按需推送**：把 `metrics_now` 注册为 core 内置动作，经 [BridgeCommandRegistry] 分发，
 *    收到即推一帧，并由 [BridgeClient] 用**同一个 `requestId`** 回 `command_result`。
 *
 * ## 数据来源：复用既有采集管线，不新写采集
 * 快照取自 [ProbeReadApi.latestSnapshot]——与 `/metrics` 处理器（`MetricsHttpHandler`）**同一个入口**，
 * 帧内字段由 [MetricSnapshotFrame] 按协议规格 §7.2 的对照表逐项投影。这样推与拉永远同源，
 * 不会出现"推送一套、抓取一套"的漂移（协议规格 §3.4.4 的关键约束）。
 *
 * ## 线程模型：独占守护线程，绝不触碰 MC 主线程
 * 周期推送跑在本类自起的 daemon 线程（`ServerProbe-MetricPush`），只做"读内存快照 + 写 socket"，
 * 不调任何平台 API。`metrics_now` 的执行发生在桥的读线程上（下发帧本就由它接收），同样不在主线程。
 * 两种路径都整体 try/catch 降级：推送失败绝不影响采集、桥连接与宿主运行。
 *
 * ## 间隔热生效
 * 每轮循环**重新读取**间隔配置，故托管面下发新间隔后下一轮即生效，无需重启（协议规格 §3.5 的要求）。
 *
 * ## 桥断开即停
 * 桥未连接时不推帧、不告警、不补发旧值——这是协议规格 §3.6 R1 已确认接受的取舍。
 *
 * ## 只在托管模式启用
 * 指标上行是控制面专属能力：独立模式的桥只承载业务转发，拿到 `metric_snapshot` 既无用也无处安放。
 * 故先等模式判定，确认是托管模式才注册 `metrics_now` 并起周期推送——否则手工配了 `bridge` 的独立模式
 * 用户会持续收到自己的 WS 服务根本用不上的指标帧。
 */
@Service
class MetricPushService {

    /** 只读数据出口，提供最新指标快照；经 IOC 注入，与 `/metrics` 消费同一实例。 */
    @Inject
    lateinit var readApi: ProbeReadApi

    /** 反向 WS 客户端：推送复用它的连接与写锁，不另开 socket。 */
    @Inject
    lateinit var bridge: BridgeClient

    /** 指令分发中心：`metrics_now` 与平台治理动作走同一套分发机制。 */
    @Inject
    lateinit var commandRegistry: BridgeCommandRegistry

    /**
     * 托管模式判定服务：推送必须先等它给出结论。
     *
     * 指标上行只在托管模式下启用，而模式判定本身是异步的（要知道身份文件能不能用、控制面连不连得上），
     * 故推送的启动收在装配线程里、判定完成之后。
     */
    @Inject
    lateinit var modeService: ControlPlaneModeService

    /** 装配幂等标志：`@PostEnable` 只应装配一次。 */
    private val started = AtomicBoolean(false)

    /** 运行标志：装配成功置 true，stop 置 false。 */
    private val running = AtomicBoolean(false)

    /** 是否已进入停止流程：防止“stop 先到、装配后到”时白白起一个没人打断的循环。 */
    @Volatile
    private var stopping = false

    /** 装配与停止的串行化锁。 */
    private val startLock = Any()

    /** 等模式判定；单测可替换，以便确定性地验证"stop 先到"的时序（生产永远用真实现）。 */
    internal var modeReadiness: (Long) -> Unit = { timeoutMs -> modeService.awaitReady(timeoutMs) }

    /** 推送后台线程；未启动时为 null。 */
    @Volatile
    private var thread: Thread? = null

    /**
     * 启动推送服务：起装配线程，等模式判定后再决定是否真的推。
     *
     * 幂等：重复回调不会起第二个装配线程。
     */
    @PostEnable
    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        Thread({ bootstrap() }, BOOT_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 装配（跑在后台线程）：等模式判定 → 只在托管模式注册 `metrics_now` 并起周期推送。
     *
     * 独立模式下直接返回：指标上行是控制面专属能力，独立模式的桥只做业务转发。
     */
    private fun bootstrap() {
        modeReadiness(BOOTSTRAP_WAIT_MS)
        if (modeService.mode() != ProbeMode.MANAGED) {
            ProbeLogger.info("独立模式：指标不向插件桥上行（上行端点在托管模式下才启用）")
            return
        }
        val pusher = MetricPusher(
            snapshots = { readApi.latestSnapshot() },
            instance = { bridge.instanceId },
            // 编码收口到统一 JSON 适配器（ADR-14），推送器只负责帧树
            send = { frame -> bridge.sendUplink(Json.encode(frame)) },
        )
        synchronized(startLock) {
            // stop 已先到：不再注册动作、不再起推送线程。两件事必须在同一临界区里判，
            // 否则 stop 可能恰好落在 register 与 set(true) 之间，留下无人注销的动作。
            if (stopping) {
                return
            }
            // 与平台治理动作共用同一个分发中心；未注册时该类指令自然回落到平台执行器
            commandRegistry.registerCoreAction(ACTION_METRICS_NOW) {
                val sequence = pusher.pushOnce()
                if (sequence == null) {
                    // 推送失败或暂无快照：回执带原因，由 BridgeClient 用同一个 requestId 回给控制面
                    BridgeCommandResult.fail("指标推送失败:${pusher.lastFailureReason ?: "当前无可推送的指标快照"}")
                } else {
                    BridgeCommandResult.ok("已推送一帧 $EVENT(sequence=$sequence)")
                }
            }
            running.set(true)
            val worker = Thread({ pushLoop(pushOnce = { pusher.pushOnce() }) }, THREAD_NAME)
            worker.isDaemon = true
            thread = worker
            worker.start()
        }
        ProbeLogger.debug("指标 WS 推送已启动(间隔 ${runCatching { ProbeConfig.metricsPushIntervalMs() }.getOrDefault(0L)}ms)")
    }

    /**
     * 停止推送。置停标志并打断休眠中的线程，让循环尽快退出；未真的起过推送时直接返回。
     *
     * 与 [bootstrap] 共用 [startLock]：这是“stop 先到、装配后到”能被真正堵住的前提——
     * 旧写法里 stop 不拿锁，于是 stop 置位后 bootstrap 仍会起一个无人打断的推送线程。
     */
    @PreDestroy
    fun stop() {
        val stopped: Thread?
        synchronized(startLock) {
            stopping = true
            if (!running.getAndSet(false)) {
                return
            }
            stopped = thread
            thread = null
        }
        // 注销动作 + 打断线程都放在锁外：前者是并发容器操作，后者要等锁可能引入无谓的阻塞
        commandRegistry.unregisterCoreAction(ACTION_METRICS_NOW)
        stopped?.let { worker ->
            runCatching { worker.interrupt() }
            runCatching { worker.join(STOP_JOIN_MS) }
            if (worker.isAlive) {
                ProbeLogger.warn("指标推送线程未在 ${STOP_JOIN_MS}ms 内退出(${worker.name},daemon 会自行结束)")
            }
        }
        ProbeLogger.info("指标 WS 推送已停止")
    }

    /**
     * 周期推送循环（跑在后台线程）。
     *
     * 每轮：读一次间隔 → 休眠 → 推一帧。间隔每轮重读以支持热生效；休眠被打断（stop）即退出。
     *
     * 三个参数带默认值是**仅为可测**：单测传入空 sleep 与有限次数的 `shouldContinue`，
     * 就能确定性地验证"单次推送抛异常不会弄死循环"，而这正是过去缺的保证。
     *
     * @param pushOnce 推一帧的执行体（生产为 `pusher.pushOnce()`）。
     * @param intervalProvider 本轮间隔（毫秒），默认读配置。
     * @param shouldContinue 是否继续下一轮，默认看 [running]。
     * @param sleeper 休眠实现，默认 `Thread.sleep`（单测传空实现）。
     */
    // 必须广捕：单帧推送的失败点可能在任何一行（编码、入队、背压），而这一层的目的就是“不让任何
    // 异常杀死推送线程”；中断单独处理（恢复中断位并退出），所以 Exception 分支只兜真正的异常。
    @Suppress("TooGenericExceptionCaught")
    internal fun pushLoop(
        pushOnce: () -> Long?,
        intervalProvider: () -> Long = { ProbeConfig.metricsPushIntervalMs() },
        shouldContinue: () -> Boolean = { running.get() },
        sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    ) {
        while (shouldContinue()) {
            val interval = runCatching(intervalProvider).getOrDefault(DEFAULT_INTERVAL_MS)
            try {
                sleeper(interval)
            } catch (e: InterruptedException) {
                // stop 主动打断：恢复中断标志后立即退出
                Thread.currentThread().interrupt()
                ProbeLogger.info("指标 WS 推送循环收到中断，已退出")
                return
            }
            if (!shouldContinue()) {
                return
            }
            // 单次失败只跳过这一轮：异常逃出循环会让推送线程静默死亡（控制面只看到指标停止更新）。
            // 中断例外：它只在停机时出现，必须恢复中断位并退出，而不是被当成普通失败继续睡。
            try {
                pushOnce()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                ProbeLogger.info("指标 WS 推送循环收到中断，已退出")
                return
            } catch (e: Exception) {
                ProbeLogger.warn("指标推送失败(本轮跳过,下一轮继续):${e.message}")
            }
        }
    }

    private companion object {

        /** 推送线程名（便于线上排查）。 */
        private const val THREAD_NAME = "ServerProbe-MetricPush"

        /** 装配线程名（等模式判定，与推送线程分开以区分线上堆栈）。 */
        private const val BOOT_THREAD_NAME = "ServerProbe-MetricPushBoot"

        /** 等托管模式判定的上限（毫秒）：与其它托管组件保持同一口径。 */
        private const val BOOTSTRAP_WAIT_MS = 5_000L

        /** stop 时等推送线程退出的上限（毫秒）：daemon 线程即使未退出也不会阻碍 JVM 退出。 */
        private const val STOP_JOIN_MS = 1_000L

        /** `metrics_now` 动作名（协议规格 §3.4.5）。 */
        private const val ACTION_METRICS_NOW = "metrics_now"

        /** 事件名（复用编码器常量，避免两处漂移）。 */
        private const val EVENT = MetricSnapshotFrame.EVENT

        /**
         * 读不到配置时的兜底间隔（毫秒）：与代码默认值一致。
         *
         * 配置读取本身已带默认值，这里的兜底只为"读取抛异常"这种极端情况。
         */
        private const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
