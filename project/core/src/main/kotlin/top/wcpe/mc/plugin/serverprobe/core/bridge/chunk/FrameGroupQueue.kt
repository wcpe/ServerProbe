package top.wcpe.mc.plugin.serverprobe.core.bridge.chunk

import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按**帧数**计容量的分组队列（规格 `docs/specs/frame-chunking.md` §3.3）。
 *
 * ## 为什么不能逐片 `offer`
 * 队列只剩几个空位时，逐片入队会发出 `seq 0..2 / total 64`，对端于是永久等待其余 61 片：
 * 重组缓冲泄漏，且没有任何超时机制能可靠回收它。因此改为**按 `total` 一次性预留**：
 * 要么整组进队，要么一片都不进（[reserve] 失败即整组放弃）。
 *
 * ## 容量单位是帧而不是组
 * 与 `bridge.line.*-capacity` 语义一致：一个 64 片组需要 64 个连续空位。
 * 这是刻意的——宁可整组明确失败，也不要半组成功（半组是对端无法回收的状态）。
 * 因此 [depth] 报的是**已占用帧数**（含已入队但尚未被发送线程取走的片）。
 *
 * ## 状态迁移
 * `reserve(n)` 成功即占用 n 帧容量；`commit(group)` 把预留转为真实入队；
 * `commit` 失败或调用方主动放弃时用 `abortReservation(n)` 归还。
 * `poll()` 取出整组并立即归还该组的帧占用（此后它已不在队列里）。
 *
 * ## 线程安全
 * [reserve]/[abortReservation] 用 CAS 保证"一次占满 n 个空位或一个都不占"；
 * [commit]/[poll] 走 `ArrayBlockingQueue` 自带的锁。均可从任意线程调用，且**永不阻塞**
 * （桥读线程要走 [ArrayBlockingQueue.offer] 那侧的路径，不能有界阻塞）。
 */
class FrameGroupQueue(framesCapacity: Int) {

    /** 以"帧数"计的容量；至少 1（配置成 0 会让队列彻底不可用）。 */
    private val capacity: Int = framesCapacity.coerceAtLeast(1)

    /**
     * 组队列本身。
     *
     * 上界按帧数给（组数必然不超过帧数），真正起门控作用的是 [occupied]：
     * 这里只是存放，不做容量判定。
     */
    private val queue = ArrayBlockingQueue<List<OutboundFrame>>(capacity)

    /** 已占用帧数（已入队的片 + 已预留但尚未提交的片）。 */
    private val occupied = AtomicInteger(0)

    /** 当前占用帧数（供状态快照展示）。 */
    val depth: Int
        get() = occupied.get()

    /**
     * 一次性预留 n 个帧槽位；失败则一个都不占。
     *
     * @param frames 需要预留的片数（必须 ≥ 1）。
     * @return true 表示预留成功，此时调用方**必须**要么 [commit]，要么 [abortReservation]。
     */
    fun reserve(frames: Int): Boolean {
        val need = frames.coerceAtLeast(1)
        while (true) {
            val current = occupied.get()
            if (current + need > capacity) return false
            if (occupied.compareAndSet(current, current + need)) return true
        }
    }

    /**
     * 把已预留的整组提交进队。
     *
     * @return true 表示整组已入队；false 表示无法入队（预留已在本方法内归还，调用方不必再归还）。
     */
    fun commit(group: List<OutboundFrame>): Boolean {
        if (queue.offer(group)) return true
        abortReservation(group.size)
        return false
    }

    /** 归还 n 个帧槽位（预留被放弃时调用）。 */
    fun abortReservation(frames: Int) {
        if (frames > 0) occupied.addAndGet(-frames)
    }

    /** 取出下一组；队列为空返回 null，同时归还该组的帧占用。 */
    fun poll(): List<OutboundFrame>? {
        val group = queue.poll() ?: return null
        occupied.addAndGet(-group.size)
        return group
    }
}
