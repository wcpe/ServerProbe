package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 指标经反向 WS 推送的服务（FR-34，协议规格 §3.4.4 / §3.4.5）。
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

    /** 运行标志：start 置 true，stop 置 false。 */
    private val running = AtomicBoolean(false)

    /** 推送后台线程；未启动时为 null。 */
    @Volatile
    private var thread: Thread? = null

    /**
     * 启动周期推送线程，并注册 `metrics_now` 内置动作。
     *
     * 幂等：重复回调不会起第二个线程、也不会重复注册动作。
     */
    @PostEnable
    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        val pusher = MetricPusher(
            snapshots = { readApi.latestSnapshot() },
            instance = { bridge.instanceId },
            // 编码收口到统一 JSON 适配器（ADR-14），推送器只负责帧树
            send = { frame -> bridge.sendUplink(Json.encode(frame)) },
        )
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
        val worker = Thread({ pushLoop(pusher) }, THREAD_NAME)
        worker.isDaemon = true
        thread = worker
        worker.start()
        ProbeLogger.debug("指标 WS 推送已启动(间隔 ${runCatching { ProbeConfig.metricsPushIntervalMs() }.getOrDefault(0L)}ms)")
    }

    /**
     * 停止周期推送。置停标志并打断休眠中的线程，让循环尽快退出。
     */
    @PreDestroy
    fun stop() {
        if (!running.getAndSet(false)) {
            return
        }
        thread?.let { worker -> runCatching { worker.interrupt() } }
        thread = null
        ProbeLogger.info("指标 WS 推送已停止")
    }

    /**
     * 周期推送循环（跑在后台线程）。
     *
     * 每轮：读一次间隔 → 休眠 → 推一帧。间隔每轮重读以支持热生效；休眠被打断（stop）即退出。
     *
     * @param pusher 推送执行体。
     */
    private fun pushLoop(pusher: MetricPusher) {
        while (running.get()) {
            val interval = runCatching { ProbeConfig.metricsPushIntervalMs() }.getOrDefault(DEFAULT_INTERVAL_MS)
            try {
                Thread.sleep(interval)
            } catch (e: InterruptedException) {
                // stop 主动打断：恢复中断标志后立即退出
                Thread.currentThread().interrupt()
                return
            }
            if (!running.get()) {
                return
            }
            pusher.pushOnce()
        }
    }

    private companion object {

        /** 推送线程名（便于线上排查）。 */
        private const val THREAD_NAME = "ServerProbe-MetricPush"

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
