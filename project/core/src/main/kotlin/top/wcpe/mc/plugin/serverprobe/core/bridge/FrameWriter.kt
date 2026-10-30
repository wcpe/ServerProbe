package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 发送器的全部计数（一处定义，写路径与提交路径共用）。
 *
 * ## 为什么单独一个类
 * "哪些情况算失败、算到哪个计数里"是本功能的**语义核心**，却被提交路径（容量不足、无法分片）
 * 与写路径（未连接、写失败）两处都用到。分开持有副本必然出现"一边加了、另一边忘了加"，
 * 而 `bridge_status` 的计数是运维判断链路的唯一入口。
 *
 * ## 不新增 `bridge_status` 字段
 * 分片超限（`TOO_LARGE`）没有独立计数器：权威 schema 未含该字段，故并入各优先级**已有**的
 * 拒绝/丢弃计数（回执侧即 `commandResultRejected`），见帧分片规格 §6 风险 3。
 */
class SenderCounters {

    private val commandResultRejected = AtomicLong(0)
    private val reliableRejected = AtomicLong(0)
    private val bestEffortDropped = AtomicLong(0)
    private val filteredOut = AtomicLong(0)
    private val spilled = AtomicLong(0)
    private val writeTimeouts = AtomicLong(0)
    private val writeFailures = AtomicLong(0)

    /** 回执被拒次数（含"超大无法分片"）。 */
    fun commandResultRejected(): Long = commandResultRejected.get()

    /** 可靠事件被拒次数（含"超大无法分片"）。 */
    fun reliableRejected(): Long = reliableRejected.get()

    /** 可丢事件丢弃次数（含"超大无法分片"）。 */
    fun bestEffortDropped(): Long = bestEffortDropped.get()

    /** 被滤镜丢弃的次数（不算失败）。 */
    fun filteredOut(): Long = filteredOut.get()

    /** 落盘兜底条数。 */
    fun spilled(): Long = spilled.get()

    /** 写超时次数。 */
    fun writeTimeouts(): Long = writeTimeouts.get()

    /** 写失败次数。 */
    fun writeFailures(): Long = writeFailures.get()

    /** 记一次滤镜丢弃。 */
    fun countFilteredOut() {
        filteredOut.incrementAndGet()
    }

    /** 记一次落盘兜底。 */
    fun countSpilled() {
        spilled.incrementAndGet()
    }

    /** 记一次写失败。 */
    fun countWriteFailure() {
        writeFailures.incrementAndGet()
    }

    /**
     * 按可丢性把一次"不可丢失败"记入**已有**计数。
     *
     * 可丢帧不走这里（它们有自己的丢弃计数），因为"丢弃"对可丢帧不是失败。
     *
     * @param priority 失败帧的优先级。
     */
    fun countRejected(priority: OutboundFrame.Priority) {
        when {
            priority == OutboundFrame.Priority.COMMAND_RESULT -> commandResultRejected.incrementAndGet()
            priority.droppable -> bestEffortDropped.incrementAndGet()
            else -> reliableRejected.incrementAndGet()
        }
    }

    /**
     * 组装状态快照。
     *
     * @param queues 三条队列的当前深度（单位：帧）。
     * @param spillPending 落盘文件中未提交的条数。
     * @param spillDropped 落盘内存缓冲溢出被丢弃的条数（**这是真实的数据丢失**，必须可观测）。
     */
    fun snapshot(queues: QueueDepths, spillPending: Int, spillDropped: Long = 0L): SenderStatus = SenderStatus(
        commandResultQueue = queues.receipt,
        commandResultRejected = commandResultRejected.get(),
        reliableQueue = queues.reliable,
        reliableRejected = reliableRejected.get(),
        bestEffortQueue = queues.bestEffort,
        bestEffortDropped = bestEffortDropped.get(),
        filteredOut = filteredOut.get(),
        spilled = spilled.get(),
        spillPending = spillPending,
        spillDropped = spillDropped,
        writeTimeouts = writeTimeouts.get(),
        writeFailures = writeFailures.get(),
    )
}

/**
 * 三条队列的深度（单位：帧，与 `bridge.line.*-capacity` 语义一致）。
 *
 * @property receipt 回执队列深度。
 * @property reliable 可靠事件队列深度。
 * @property bestEffort 可丢事件队列深度。
 */
data class QueueDepths(val receipt: Int, val reliable: Int, val bestEffort: Int)

/**
 * 发送线程的写出路径（从 [FrameSender] 拆出）。
 *
 * ## 为什么单独一个类
 * 写出与"降级处置"是一个内聚的关注点，且与提交流程（滤镜/分片/预留）没有共享状态；
 * 混在一起会让 [FrameSender] 变成什么都做的巨类。拆开也让"未连接/写失败时到底落到哪个计数"
 * 只有一处可读。
 *
 * ## 分片组不落盘
 * 多片组一律**不落盘**：落盘格式（`type \t event \t domain \t topic \t requestId \t payload`）
 * 不保存 `groupId`/`seq`/`total`，回放会得到一个既非原帧、也无法被对端重组的"孤儿 chunk"。
 * 因此分片组写不出去时按可丢性计入拒绝/丢弃，由对端"新 hello 清空分组"兜底（规格 §3.5）。
 *
 * ## 线程安全
 * [writeGroup]/[writeControl] 只由发送线程与提交线程分别调用；连接句柄本身是原子的。
 */
class FrameWriter(
    private val connection: AtomicReference<OutboundSink?>,
    private val spill: EventSpillStore?,
    private val counters: SenderCounters,
) {

    /**
     * 整组连续写出（同组不可穿插）。
     *
     * 未分片的单帧沿用单帧路径（含落盘提交语义）；分片组走 [writeChunkGroup]。
     */
    fun writeGroup(group: List<OutboundFrame>) {
        val single = singleFrameOf(group)
        if (single != null) {
            write(single)
            return
        }
        writeChunkGroup(group)
    }

    /** 控制帧旁路:不入队、直接写,避免被分片组或事件积压拖住心跳。 */
    fun writeControl(frame: OutboundFrame): SubmitOutcome {
        val ws = connection.get() ?: return SubmitOutcome.REJECTED
        return try {
            ws.sendText(frame.payload)
            SubmitOutcome.QUEUED
        } catch (_: IOException) {
            // 这里的 IOException 本身就是"没写出去"的结论，属预期降级路径（写失败计数已反映），
            // 不需要把原始异常继续上抛或记录，避免刷屏。
            counters.countWriteFailure()
            SubmitOutcome.REJECTED
        }
    }

    /**
     * 未分片的单帧；分片帧即使 `total == 1` 也不算——落盘格式不保存 `groupId`/`seq`/`total`，
     * 回放会得到一个既非原帧、也对端无法重组的"孤儿 chunk"。
     *
     * @return 组的唯一元素（且不是分片帧）时返回它，否则 null。
     */
    private fun singleFrameOf(frames: List<OutboundFrame>): OutboundFrame? =
        frames.singleOrNull()?.takeIf { !it.isChunk }

    /**
     * 分片组整组写出。
     *
     * 写失败即放弃本组剩余片——对端会收到半组，由"新 hello 清空分组状态"兜底（规格 §3.5），
     * 探针不重传（§2 范围外）。
     */
    private fun writeChunkGroup(group: List<OutboundFrame>) {
        val ws = connection.get()
        if (ws == null) {
            lostChunkGroup(group, "未连接")
            return
        }
        for (frame in group) {
            try {
                ws.sendText(frame.payload)
            } catch (e: IOException) {
                counters.countWriteFailure()
                lostChunkGroup(group, e.javaClass.simpleName)
                return
            }
        }
    }

    /** 分片组无法写出时按可丢性计数（不落盘、不重试）。 */
    private fun lostChunkGroup(group: List<OutboundFrame>, cause: String) {
        counters.countRejected(group.first().priority)
        ProbeLogger.debug("桥分片组未能写出(${group.size} 片,priority=${group.first().priority},原因=$cause),已按可丢性计数")
    }

    /** 写出一帧;失败只计数不中断循环(断连由读侧统一处置)。 */
    private fun write(frame: OutboundFrame) {
        val ws = connection.get()
        if (ws == null) {
            // 未连接:可靠事件先落盘等回放,落盘也接不下才算被拒。
            // 可靠事件落盘失败必须计入 reliableRejected（而不是可丢丢弃）：
            // `SenderStatus.reliableRejected` 的语义就是"可靠事件因队列满且落盘失败被拒"，
            // 记到 bestEffortDropped 会让可靠事件丢失在运维看板上彻底看不见。
            if (frame.priority == OutboundFrame.Priority.RELIABLE_EVENT && spill?.append(frame) == true) {
                counters.countSpilled()
            } else if (frame.priority == OutboundFrame.Priority.BEST_EFFORT_EVENT) {
                counters.countRejected(OutboundFrame.Priority.BEST_EFFORT_EVENT)
            } else {
                counters.countRejected(frame.priority)
            }
            return
        }
        try {
            ws.sendText(frame.payload)
            // 发送成功才算"已提交":推进落盘位点,未提交的会在下次回放重发(至少一次)。
            spill?.onSent(frame)
        } catch (e: IOException) {
            counters.countWriteFailure()
            if (frame.priority == OutboundFrame.Priority.RELIABLE_EVENT) {
                // 必须看 append 的返回值：落盘失败时该事件实际已丢失，
                // 无条件记 spilled 会让一次真实丢失在监控上表现为"已落盘、等回放"。
                if (spill?.append(frame) == true) {
                    counters.countSpilled()
                } else {
                    counters.countRejected(OutboundFrame.Priority.RELIABLE_EVENT)
                }
            }
            ProbeLogger.debug("桥上发送帧失败(${frame.type}/${frame.event}),已按可靠级别降级:${e.message}")
        }
    }
}
