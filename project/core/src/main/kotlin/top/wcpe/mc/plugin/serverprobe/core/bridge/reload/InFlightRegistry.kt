package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 在途占用的尝试结果（配置重载规格 §3.5）。 */
enum class AcquireResult {

    /** 占用成功，可以执行。 */
    ACQUIRED,

    /** 已有同路径（或全量）在途，且未超 [InFlightRegistry] 的卡死阈值。 */
    IN_FLIGHT,

    /** 上次在途已超时且任务仍未返回，疑似卡死。 */
    STUCK,
}

/**
 * 一条在途记录的快照。
 *
 * @property path 规范化路径；全量重载为 [InFlightRegistry.FULL_RELOAD_KEY]。
 * @property heldMs 已占用时长（毫秒）。
 * @property stuck 是否已超过卡死阈值。
 */
data class InFlightEntry(
    val path: String,
    val heldMs: Long,
    val stuck: Boolean,
)

/** 占用被拒的详情（用于拼回执 message，避免运维看到千篇一律的 "IN_FLIGHT"）。 */
data class InFlightBlocker(
    val path: String,
    val heldMs: Long,
    val stuck: Boolean,
) {
    /** 是否是全量重载占着位。 */
    fun isFullReload(): Boolean = path == InFlightRegistry.FULL_RELOAD_KEY
}

/**
 * 在途表：保证「同一时刻不会有两份重载并发改插件状态」（配置重载规格 §3.5）。
 *
 * ## `release` 只由任务自身在 finally 调用
 * 协调器在超时后**不代它 release**：`Future.cancel(true)` 对不响应中断的任务无效，
 * 代 release 会让同路径的第二次请求与仍在执行的第一份**并发**跑插件配置加载，两份写入交错。
 *
 * ## 代价：靠 `STUCK` 而不是靠撒谎
 * 永不返回的任务会把路径长期占住。因此 [tryAcquire] 在「已占用且超过 `stuckAfterMs`」时返回
 * [AcquireResult.STUCK]（独立错误码，message 写明"疑似卡死"），而不是继续用 `IN_FLIGHT` 掩盖——
 * 那会让运维误判为"只是有点慢，等等就好"。
 *
 * ## 全量哨兵键 `*` 与单路径互斥
 * 全量占用 [FULL_RELOAD_KEY]，任何单路径请求都被它挡住；反过来任何单路径在途时全量请求也被挡住。
 * 牺牲少量吞吐，换取**可断言的不变量**：同一时刻只有一份重载在跑。
 *
 * ## 为什么用锁而不是纯 CAS
 * 全量的互斥判定要同时看"哨兵键"与"是否已有任意单路径"，这不是单键 CAS 能表达的原子条件。
 * 临界区只做几次 map 操作，重载请求本身是低频事件，锁开销可忽略；且**不用裸 `HashMap` 之外
 * 还有一层保证**：`snapshot` 也被同一把锁保护，不会读到中间态。
 *
 * @param stuckAfterMs 在途超过此时长即判定为卡死（毫秒）。
 */
class InFlightRegistry(private val stuckAfterMs: Long) {

    /** path → 占用时刻。 */
    private val held = LinkedHashMap<String, Long>()

    /** 保护 [held] 的互斥与复合判定。 */
    private val lock = ReentrantLock()

    /**
     * 尝试占用一个路径（或全量哨兵键）。
     *
     * @param path 规范化路径；全量重载传 [FULL_RELOAD_KEY]。
     * @param nowMs 当前时刻（毫秒），由调用方注入以便单测控制。
     * @return ACQUIRED / IN_FLIGHT / STUCK。
     */
    fun tryAcquire(path: String, nowMs: Long): AcquireResult = lock.withLock {
        if (path.isEmpty()) return AcquireResult.IN_FLIGHT
        val blocker = blockerOf(path, nowMs)
        if (blocker == null) {
            held[path] = nowMs
            return AcquireResult.ACQUIRED
        }
        if (blocker.stuck) AcquireResult.STUCK else AcquireResult.IN_FLIGHT
    }

    /**
     * 释放占用；**只能在任务自身的 finally 中调用**。
     *
     * 幂等：重复 release 无副作用（任务异常路径可能被多次清理）。
     *
     * @param path 规范化路径；全量重载传 [FULL_RELOAD_KEY]。
     */
    fun release(path: String) {
        lock.withLock { held.remove(path) }
    }

    /**
     * 谁挡住了这次占用（诊断与回执 message 用）。
     *
     * @return 挡住它的那条在途记录；无人阻挡返回 null。
     */
    fun blockerOf(path: String, nowMs: Long): InFlightBlocker? = lock.withLock {
        val candidate = if (path == FULL_RELOAD_KEY) {
            // 全量：哨兵自己，或任意一条单路径都挡。
            entryOf(FULL_RELOAD_KEY, nowMs) ?: held.keys.firstOrNull { it != FULL_RELOAD_KEY }?.let { entryOf(it, nowMs) }
        } else {
            // 单路径：自己的键，或全量哨兵。
            entryOf(path, nowMs) ?: entryOf(FULL_RELOAD_KEY, nowMs)
        }
        candidate
    }

    /**
     * 诊断快照：在途路径 + 已占用时长，按路径字典序。
     *
     * @param nowMs 当前时刻（毫秒）。
     * @return 不可变快照，永不为 null。
     */
    fun snapshot(nowMs: Long): List<InFlightEntry> = lock.withLock {
        held.entries
            .map { (path, since) ->
                val heldMs = (nowMs - since).coerceAtLeast(0L)
                InFlightEntry(path, heldMs, heldMs > stuckAfterMs)
            }
            .sortedBy { it.path }
    }

    /** 按路径取一条快照；不存在返回 null。 */
    private fun entryOf(path: String, nowMs: Long): InFlightBlocker? {
        val since = held[path] ?: return null
        val heldMs = (nowMs - since).coerceAtLeast(0L)
        return InFlightBlocker(path, heldMs, heldMs > stuckAfterMs)
    }

    companion object {

        /** 全量重载的全局哨兵键。 */
        const val FULL_RELOAD_KEY = "*"
    }
}
