package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.ChunkResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameGroupQueue
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * 发送器所需的最小连接能力。
 *
 * 抽成接口而非直接依赖 [MinimalWebSocketClient]：后者是具体类且构造需要真实 socket，
 * 直接依赖会让发送器的分级/优先级逻辑无法单测。
 */
interface OutboundSink {

    /**
     * 发一条文本帧。
     *
     * **非阻塞语义**：写锁被占用或写超时时**立即返回 false 而非阻塞调用线程**——
     * 上行事件由 Worker 侧周期补救，丢一条远好过卡死主线程（探针"不成事故源"优先于"确保送达"）。
     *
     * @return true=已写出；false=写锁不可得、写超时或连接已坏，消息被丢弃（调用方无需重试）。
     * @throws java.io.IOException 连接不可用或写失败。
     */
    fun sendText(text: String): Boolean
}

/**
 * 上行帧发送器（线路治理规格 §3.4 + 帧分片规格 §3.3）。
 *
 * 桥的全部上行流量都经这里:三条队列 + 优先级轮询 + 滤镜链 + 分片 + 落盘兜底。
 *
 * ## 为什么需要它
 * 单条 WS 连接承载全部流量,若所有帧挤一个队列,一个大数据帧或一阵事件就会把
 * 5 秒超时的命令回执挤到队尾 —— 回执超时会让 JM 误判业务失败。
 *
 * ## 三条有界队列 + 优先级
 * - 命令回执:`COMMAND_RESULT`,最高队列优先级,**绝不因事件积压而排队**;
 * - 不可丢事件:`RELIABLE_EVENT`,满了先落盘,落盘也失败才拒绝;
 * - 可丢事件:`BEST_EFFORT_EVENT`,满了直接丢并计数。
 *
 * 发送线程按「回执 → 可靠 → 可丢」顺序轮询,并在空闲时用条件变量唤醒(避免轮询延迟)。
 *
 * ## 分片与整组原子入队
 * 提交路径固定为「滤镜 → 分片 → 容量判定 → 整组入队」:
 * 超阈值帧被 [FrameChunker] 切成 1 或 N 片,**按 `total` 一次性预留容量**——
 * 要么整组进队,要么一片都不进。逐片 `offer` 会让队列只剩几个空位时发出
 * `seq 0..2 / total 64`,对端永久等待其余片(重组缓冲泄漏)。
 * 发送线程取出的单位是**组**而不是单帧,同组连续写出、不被其它普通帧穿插;
 * `CONTROL` 帧仍走旁路,因此 ping 可能插入某个组的中间(对端按 `groupId`/`seq` 重组,不依赖连续性)。
 *
 * ## 职责边界
 * 写出与降级处置在 [FrameWriter]，计数在 [SenderCounters]；本类只负责
 * "接帧 → 滤镜 → 分片 → 预留 → 入队"与队列调度。
 *
 * ## 连接生命周期
 * 连接句柄用 [AtomicReference] 持有:重连时由 [attach]/[detach] 换绑。
 * 早期设计直接持有单个连接实例,重连后旧实例的流已关闭 → 之后每一帧都失败,
 * 而这只会在"第一次真实断线"后才暴露。
 *
 * ## 线程安全
 * [submit]/[offerReceipt] 可被任意线程调用;**读线程只允许用 [offerReceipt]**
 * (非阻塞)——[submitReceipt] 会做有界阻塞,只能从池线程调用。
 */
// 三池 + 优先级轮询 + 滤镜链 + 分片 + 落盘兜底的逻辑天然需要一组小方法；
// 折成更少的大函数会让分级与容量判定绞在一起，反而更难验证。
// （与 BridgeClient 同一种取舍，那里也有同样的抑制。）
@Suppress("TooManyFunctions")
class FrameSender(
    private val settings: LineSettings,
    private val spill: EventSpillStore? = null,
    private val chunker: FrameChunker = FrameChunker(ChunkSettings()),
) {

    /** 当前连接;重连期间为 null。 */
    private val connection = AtomicReference<OutboundSink?>(null)

    /** 上行滤镜链(按 order 升序执行),写入后不再变更。 */
    private val filters = ArrayList<OutboundFilter>()

    private val receiptQueue = FrameGroupQueue(settings.receiptCapacity)
    private val reliableQueue = FrameGroupQueue(settings.reliableCapacity)
    private val bestEffortQueue = FrameGroupQueue(settings.bestEffortCapacity)

    private val counters = SenderCounters()
    private val writer = FrameWriter(connection, spill, counters)

    private val wakeup = ReentrantLock()
    private val signal = wakeup.newCondition()

    @Volatile
    private var running = true

    /** 绑定当前连接(重连成功后调用)。 */
    fun attach(ws: OutboundSink) {
        connection.set(ws)
    }

    /** 解绑当前连接(断开时调用);队列中的帧保留,等下次 [attach] 后继续发。 */
    fun detach() {
        val previous = connection.getAndSet(null)
        // 不主动关闭:调用方负责关闭(它可能还要读最后一帧)
        if (previous != null) ProbeLogger.debug("桥发送器已解绑连接,队列内帧将等待重连后发出")
    }

    /** 注册一个上行滤镜;须在发送线程启动前调用。 */
    fun addFilter(filter: OutboundFilter) {
        filters.add(filter)
        filters.sortBy { it.order }
    }

    /**
     * 提交一帧（通用入口，**不阻塞**）。
     *
     * 执行顺序固定为：滤镜链 → 分片 → 容量判定 → 入队。
     * 滤镜在前是因为被过滤掉的帧**不该占容量**——顺序颠倒会让"订阅越多、业务越容易失败"。
     * 分片在容量判定之前：容量要以**片数**（`total`）为单位一次性预留。
     *
     * @return 见 [SubmitOutcome]；`FILTERED_OUT` **不算失败**。
     */
    fun submit(frame: OutboundFrame): SubmitOutcome {
        // 控制帧走旁路:分片组是发送临界区,ping 若排队会被拖住,导致对端误判失联。
        if (frame.priority == OutboundFrame.Priority.CONTROL) return writer.writeControl(frame)
        val accepted = applyFilters(frame) ?: return SubmitOutcome.FILTERED_OUT
        val frames = splitOrReject(accepted) ?: return SubmitOutcome.TOO_LARGE
        if (enqueueGroup(accepted, frames)) return SubmitOutcome.QUEUED
        return overflow(accepted, frames)
    }

    /**
     * 提交一条命令回执；回执队列满时在**调用线程**做有界阻塞（至多 `receiptOfferBudgetMs`）。
     *
     * **只允许池线程调用。** 读线程必须用 [offerReceipt]：本方法最长阻塞 500ms，
     * 读线程连续处理 100 条饱和命令就会被卡住 50 秒，超过读超时。
     */
    fun submitReceipt(frame: OutboundFrame): SubmitOutcome {
        val accepted = applyFilters(frame) ?: return SubmitOutcome.FILTERED_OUT
        val frames = splitOrReject(accepted) ?: return SubmitOutcome.TOO_LARGE
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.receiptOfferBudgetMs)
        while (true) {
            if (enqueueGroup(accepted, frames)) return SubmitOutcome.QUEUED
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return overflow(accepted, frames)
            if (!awaitCapacity(remaining)) return overflow(accepted, frames)
        }
    }

    /**
     * 非阻塞提交回执，**专供桥读线程**。
     *
     * 只做一次容量预留 + 一次 `offer`，绝不阻塞、绝不抛。失败仅计数——读线程的可用性优先于
     * 单条回执送达，该条回执由 JM 侧超时兜底。
     */
    fun offerReceipt(frame: OutboundFrame): SubmitOutcome {
        val accepted = applyFilters(frame) ?: return SubmitOutcome.FILTERED_OUT
        val frames = splitOrReject(accepted) ?: return SubmitOutcome.TOO_LARGE
        if (enqueueGroup(accepted, frames)) return SubmitOutcome.QUEUED
        return overflow(accepted, frames)
    }

    /** 发送线程主循环;由桥装配处在独立 daemon 线程里跑。 */
    fun runLoop() {
        while (running) {
            val group = pollNext()
            if (group == null) {
                awaitSignal()
                continue
            }
            // 容量已腾出:唤醒可能正阻塞等待容量的回执提交方。
            signalWakeup()
            writer.writeGroup(group)
        }
    }

    /**
     * 回放落盘的可靠事件（重连成功后调用）。
     *
     * 只在有容量时滴灌，避免"队列满 → 回放再被拒 → 保留 → 死循环"。
     *
     * @return 本次回放的条数。
     */
    @Suppress("LoopWithTooManyJumpStatements")
    fun replaySpill(): Int {
        val store = spill ?: return 0
        var replayed = 0
        // 两个 break 分别对应"落盘已回放完"与"队列又满"——都是立即停止本轮回放，
        // 用 while 条件表达不了（需要先取再判），故只抑制跳转数检查。
        while (receiptQueue.depth + reliableQueue.depth < settings.reliableCapacity) {
            val frame = store.next() ?: break
            if (!enqueueGroup(frame, listOf(frame))) break
            replayed++
        }
        if (replayed > 0) {
            ProbeLogger.info("桥已回放 $replayed 条落盘事件")
            signalWakeup()
        }
        return replayed
    }

    /** 状态快照(不可变,整体替换,避免多字段撕裂读)。 */
    fun status(): SenderStatus = counters.snapshot(
        queues = QueueDepths(
            receipt = receiptQueue.depth,
            reliable = reliableQueue.depth,
            bestEffort = bestEffortQueue.depth,
        ),
        spillPending = spill?.pending() ?: 0,
        // 落盘内存缓冲溢出时 `EventSpillStore.append` 会丢最旧一条并仍返回 true，
        // 即"调用方以为已受理、实际已丢"——必须把这个计数暴露出去，否则回灌时的
        // 数据缺口在运维侧完全不可见（只能靠对端条数对不上才发现）。
        spillDropped = spill?.droppedBeyondDisk() ?: 0L,
    )

    /**
     * 请求发送线程退出。
     *
     * **不立即丢弃队列**：`runLoop` 的条件是 `while (running)`，置 false 后它会做完
     * 当前这一组再退出——但在途队列里残留的帧仍会丢。需要“把队列跑完再退”时用
     * [shutdownAfterDrain]（停机路径），本方法留给强制停止/异常路径。
     */
    fun shutdown() {
        running = false
        signalWakeup()
    }

    /**
     * 停机路径：先尽力把在途队列跑完，再退出。
     *
     * 不发新帧、但已入队的回执与事件继续写出，避免“服务器正常关闭时 JM 收不到任何回执”。
     * 受 [drainBudgetMs] 约束——对端已死时不能把停机无限拖住。
     *
     * @return 是否在预算内排空。
     */
    fun shutdownAfterDrain(drainBudgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + drainBudgetMs
        var drained = false
        while (System.currentTimeMillis() < deadline) {
            if (queuedFrames() == 0) {
                drained = true
                break
            }
            runCatching { Thread.sleep(DRAIN_POLL_MS) }.onFailure { Thread.currentThread().interrupt() }
        }
        running = false
        signalWakeup()
        if (!drained) {
            ProbeLogger.warn("桥发送队列未在 ${drainBudgetMs}ms 内排空,停机时丢弃剩余 ${queuedFrames()} 帧")
        }
        return drained
    }

    /** 三池当前待发帧总数（供停机 drain 与状态快照使用）。 */
    fun queuedFrames(): Int = receiptQueue.depth + reliableQueue.depth + bestEffortQueue.depth

    /** 应用滤镜链;返回 null 表示被丢弃(已计数,不算失败)。 */
    private fun applyFilters(frame: OutboundFrame): OutboundFrame? {
        var current = frame
        for (filter in filters) {
            current = filter.apply(current) ?: run {
                counters.countFilteredOut()
                return null
            }
        }
        return current
    }

    /**
     * 分片；`TooLarge` 时计数并返回 null（调用方转为 [SubmitOutcome.TOO_LARGE]）。
     *
     * 超限计数**没有独立字段**（`bridge_status` 的权威 schema 不含该计数器，见规格 §6 风险 3），
     * 故并入各优先级已有的拒绝/丢弃计数。
     */
    // 必须广捕 Throwable：分片器的契约是"不抛"，但它内部会调用第三方注入的
    // `envelope` 供应器与 ID 生成，真抛 Error（如 NoClassDefFoundError）时也绝不能
    // 让它冒到发送线程或命令池——那会打死池线程。降级为"不可分片"是唯一安全方向。
    @Suppress("TooGenericExceptionCaught")
    private fun splitOrReject(frame: OutboundFrame): List<OutboundFrame>? {
        // 用 try/catch 而非 runCatching：本方法对**每一帧**都会执行（上行唯一入口），
        // runCatching 会为每帧构造一个 Result 对象，纯属白付。
        val result = try {
            chunker.split(frame)
        } catch (e: Throwable) {
            // 分片器契约上不抛；真抛了也不能让它冒到发送线程或命令池（降级为不可分片）。
            counters.countRejected(frame.priority)
            ProbeLogger.warn("桥分片失败,已按超限处置(${e.javaClass.simpleName}):type=${frame.type}/${frame.event}")
            return null
        }
        return when (result) {
            is ChunkResult.Whole -> result.frames
            is ChunkResult.TooLarge -> {
                counters.countRejected(frame.priority)
                ProbeLogger.warn("桥上行帧过大且无法分片,已拒绝(${result.reason}):type=${frame.type}/${frame.event}")
                null
            }
        }
    }

    /**
     * 一次性预留并整组入队。
     *
     * @return true 表示整组已入队；false 表示容量不足（**队列深度不变**，一片也没进）。
     */
    private fun enqueueGroup(frame: OutboundFrame, frames: List<OutboundFrame>): Boolean {
        val queue = queueOf(frame.priority)
        if (!queue.reserve(frames.size)) return false
        if (!queue.commit(frames)) return false
        signalWakeup()
        return true
    }

    /**
     * 等一次"容量腾出"信号（池线程的有界阻塞等待用）。
     *
     * @return false 表示线程被中断（调用方应放弃等待并降级）。
     */
    private fun awaitCapacity(remainingNanos: Long): Boolean {
        wakeup.lock()
        return try {
            signal.await(remainingNanos, TimeUnit.NANOSECONDS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            wakeup.unlock()
        }
    }

    /**
     * 整组无法入队时的降级处置（**绝不入队前缀片**）。
     *
     * - 单帧 + 可靠事件：先落盘兜底（落盘也接不下才拒绝）；
     * - 可丢事件：丢弃计数；
     * - 分片组不落盘：落盘格式不保存 `groupId`/`seq`/`total`，回放会产出无法重组的孤儿片，
     *   故多片组一律按可丢性拒绝，由对端"新 hello 清空分组"兜底；
     * - 回执不落盘（它有时效性，重放无意义）。
     *
     * @param frames 原本要入队的组（`singleOrNull() != null && !isChunk` 表示未分片的普通单帧）。
     */
    private fun overflow(frame: OutboundFrame, frames: List<OutboundFrame>): SubmitOutcome {
        // 单帧才谈得上落盘：分片帧即使 total==1 也不能落盘（落盘格式不含 groupId/seq/total）。
        val plainSingle = frames.singleOrNull()?.isChunk == false
        if (plainSingle && frame.priority == OutboundFrame.Priority.RELIABLE_EVENT) {
            if (spill?.append(frame) == true) {
                counters.countSpilled()
                return SubmitOutcome.QUEUED
            }
        }
        if (frame.priority.droppable) {
            counters.countRejected(frame.priority)
            return SubmitOutcome.DROPPED
        }
        counters.countRejected(frame.priority)
        return SubmitOutcome.REJECTED
    }

    /** 取该优先级对应的分组队列。 */
    private fun queueOf(priority: OutboundFrame.Priority): FrameGroupQueue = when (priority) {
        OutboundFrame.Priority.COMMAND_RESULT -> receiptQueue
        OutboundFrame.Priority.RELIABLE_EVENT -> reliableQueue
        else -> bestEffortQueue
    }

    /** 按优先级取下一组；全空返回 null。 */
    private fun pollNext(): List<OutboundFrame>? =
        receiptQueue.poll() ?: reliableQueue.poll() ?: bestEffortQueue.poll()

    /** 空闲等待:任一入队都会唤醒;100ms 兜底防止漏信号。 */
    private fun awaitSignal() {
        wakeup.lock()
        try {
            signal.await(AWAIT_SLICE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        } finally {
            wakeup.unlock()
        }
    }

    /** 唤醒发送线程。 */
    private fun signalWakeup() {
        wakeup.lock()
        try {
            signal.signalAll()
        } finally {
            wakeup.unlock()
        }
    }

    private companion object {
        /** 空闲等待单片时长(毫秒):仅作漏信号兜底,正常由入队唤醒。 */
        const val AWAIT_SLICE_MS = 100L

        /** 停机 drain 时轮询队列深度的间隔(毫秒)。 */
        const val DRAIN_POLL_MS = 20L
    }
}

/**
 * 发送器状态快照。
 *
 * @property commandResultQueue 回执队列当前深度(单位:帧,与容量语义一致)。
 * @property commandResultRejected 回执因队列满(或超大无法分片)被拒次数。
 * @property reliableQueue 可靠事件队列当前深度(单位:帧)。
 * @property reliableRejected 可靠事件因队列满且落盘失败被拒次数。
 * @property bestEffortQueue 可丢事件队列当前深度(单位:帧)。
 * @property bestEffortDropped 可丢事件累计丢弃数。
 * @property filteredOut 被滤镜判定为无需上送的累计数(不算失败)。
 * @property spilled 落盘兜底的累计条数。
 * @property spillPending 落盘文件中未提交的条数。
 * @property writeTimeouts 写超时(强关 socket)次数。
 * @property writeFailures 写失败次数。
 */
data class SenderStatus(
    val commandResultQueue: Int,
    val commandResultRejected: Long,
    val reliableQueue: Int,
    val reliableRejected: Long,
    val bestEffortQueue: Int,
    val bestEffortDropped: Long,
    val filteredOut: Long,
    val spilled: Long,
    val spillPending: Int,
    /**
     * 落盘内存缓冲溢出而被丢弃的条数（**真实数据丢失**）。
     *
     * 与 [reliableRejected] 的区别：`reliableRejected` 是"拒绝受理、调用方拿到 false 可自行重试"，
     * 而这里丢失的是**已被受理**的可靠事件——调用方当时收到的是 true。
     * 典型触发场景：大批量回灌（一次查询捞出数万条历史账单）时生产速率远超发送速率，
     * 队列满 → 落盘 → 落盘缓冲也满 → 丢最旧。运维必须监控这一项，
     * 持续 > 0 说明回灌流量已超过单条连接的承载能力，应改为分批 + 回读确认。
     */
    val spillDropped: Long,
    val writeTimeouts: Long,
    val writeFailures: Long,
)
