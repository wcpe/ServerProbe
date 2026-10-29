package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 熔断表里的一条快照。
 *
 * @property path 规范化路径。
 * @property windowStartMs 当前统计窗口的起点。
 * @property failures 当前窗口内累计超时次数（一次成功即清零）。
 * @property openedAtMs 最近一次打开熔断的时刻；从未打开为 0。
 * @property openDurationMs 本次打开的时长（含指数退避后的值）。
 * @property remainingOpenMs 距恢复还剩多久；`<= 0` 表示已恢复。
 * @property open 当前是否仍处于熔断打开状态。
 */
data class BreakerEntry(
    val path: String,
    val windowStartMs: Long,
    val failures: Int,
    val openedAtMs: Long,
    val openDurationMs: Long,
    val remainingOpenMs: Long,
    val open: Boolean,
)

/**
 * 单个路径的熔断状态：**不可变**，任何更新都整体替换（配置重载规格 §3.6）。
 *
 * @property windowStartMs 当前统计窗口起点。
 * @property failures 窗口内超时次数。
 * @property openedAtMs 最近打开时刻；0 表示从未打开。
 * @property openDurationMs 本次打开时长（指数退避结果）。
 */
private data class BreakerState(
    val windowStartMs: Long,
    val failures: Int,
    val openedAtMs: Long,
    val openDurationMs: Long,
)

/**
 * 重载熔断器（配置重载规格 §3.6）。
 *
 * ## 只挡 `TIMEOUT`
 * `FILE_NOT_FOUND` / `PARSE_ERROR` / `PLUGIN_REJECTED` / `PATH_ESCAPE` / `IN_FLIGHT` / `STUCK`
 * 都是**确定性**结果，重试有意义；把它们也熔断等于"插件说一句不行就再也不问了"。
 * 因此本类只有一个记录入口 [recordTimeout]，由协调器在超时判定时调用。
 *
 * ## 窗口与打开时长是两个参数
 * 原设计用同一个 `windowMs` 承担"统计窗口"和"打开时长"两种语义。`failureThreshold=1` 时
 * 二者恰好重合，看不出问题；一旦把阈值调大（例如"5 分钟内超时 3 次才熔断"），
 * 语义立刻分裂：统计窗口管"多久内累计"，打开时长管"熔断多久"。故拆成两个参数。
 *
 * ## 指数退避
 * `openDuration = min(base * 2^(N-1), max)`，`N` 为当前累计连续超时次数。
 * 固定窗口到期即恢复会让病态插件**周期性**重复"把主线程卡满一个预算"；
 * 退避让越来越不常被碰。一次成功重载（[recordSuccess]）清零计数、恢复 base。
 *
 * ## 容量：满则拒绝新条目，不淘汰已有
 * 淘汰最旧会把真正处于熔断的路径挤出去，等于"用大量不同 path 就能悄悄解除熔断"。
 * 拒绝新条目是保守方向：漏记一个熔断（该路径仍会被执行、可能再次卡住，但会被单次超时兜住）
 * 比误熔断（放行了本该被挡的路径）安全。被拒绝的次数由 [rejectedEntries] 暴露。
 *
 * ## 并发
 * `ConcurrentHashMap` + 不可变 state，任何更新走 [ConcurrentHashMap.compute] **整体替换**。
 * 禁止裸 `HashMap`：并发修改异常会从回执序列化里冒出来，把"熔断统计"升级成"重载命令报错"。
 *
 * @param failureWindowMs 统计窗口（毫秒）。
 * @param openDurationMs 首次熔断打开时长（指数退避基数，毫秒）。
 * @param failureThreshold 窗口内超时次数达到即熔断（`<= 1` 表示首次超时即熔断）。
 * @param maxEntries 条目上限；满则拒绝新条目。
 * @param maxOpenDurationMs 打开时长上限（指数退避封顶）。
 */
class CircuitBreaker(
    private val failureWindowMs: Long,
    private val openDurationMs: Long,
    private val failureThreshold: Int,
    private val maxEntries: Int,
    private val maxOpenDurationMs: Long = DEFAULT_MAX_OPEN_DURATION_MS,
) {

    /** path → 不可变状态。 */
    private val states = ConcurrentHashMap<String, BreakerState>()

    /** 因容量满被拒绝登记的次数。 */
    private val rejected = AtomicLong(0)

    /**
     * 记录一次超时。
     *
     * 窗口外的历史计数先归零（[failureWindowMs] 的语义），再累加；达到阈值即"打开"，
     * 打开时长按累计次数做指数退避。
     *
     * @param path 规范化路径。
     * @param nowMs 当前时刻（毫秒），由调用方注入以便单测控制。
     */
    fun recordTimeout(path: String, nowMs: Long) {
        if (path.isEmpty()) return
        if (states[path] == null && states.size >= maxEntries) {
            // 满则拒绝登记：不淘汰已有条目（见类注释的取舍）。
            rejected.incrementAndGet()
            return
        }
        states.compute(path) { _, current -> advanced(current, nowMs) }
    }

    /**
     * 记录一次成功：计数清零、恢复 base 退避。
     *
     * 实现为"删除条目"——它同时满足两点语义（窗口计数归零 + 打开时长回到 base），
     * 并顺带把容量让出来。删除是幂等的。
     *
     * @param path 规范化路径。
     */
    fun recordSuccess(path: String) {
        if (path.isEmpty()) return
        states.remove(path)
    }

    /**
     * 纯函数式判定：**只读，不改状态**。
     *
     * 边界写法固定为 `nowMs - openedAtMs < openDurationMs` → 仍开；`>=` → 已恢复。
     * 单测直接钉住这个比较符（写成 `<=` 会多挡 1ms，高频重试时表现为"偶尔通过"，
     * 那种抖动比明确失败更难排查）。
     *
     * @param path 规范化路径。
     * @param nowMs 当前时刻（毫秒）。
     * @return true 表示仍在熔断窗口内，本次不应执行。
     */
    fun isOpen(path: String, nowMs: Long): Boolean {
        val state = states[path] ?: return false
        if (state.openedAtMs <= 0L) return false
        return nowMs - state.openedAtMs < state.openDurationMs
    }

    /**
     * 诊断快照（不可变、按路径字典序）。
     *
     * @param nowMs 当前时刻（毫秒）。
     * @return 不可变快照，永不为 null。
     */
    fun snapshot(nowMs: Long): List<BreakerEntry> =
        states.entries
            .map { (path, state) -> toEntry(path, state, nowMs) }
            .sortedBy { it.path }

    /** 因容量满被拒绝登记的次数。 */
    fun rejectedEntries(): Long = rejected.get()

    /** 当前登记条目数（诊断与容量类用例用）。 */
    fun size(): Int = states.size

    /**
     * 计算新状态。
     *
     * @param current 现有状态；null 表示首次登记。
     */
    private fun advanced(current: BreakerState?, nowMs: Long): BreakerState {
        // 窗口已过期 → 历史计数作废，从本次重新累计（与"5 分钟内 N 次"的直觉一致）。
        val fresh = current == null || nowMs - current.windowStartMs >= failureWindowMs
        val windowStart = if (fresh) nowMs else current.windowStartMs
        val failures = (if (fresh) 0 else current.failures) + 1
        val threshold = failureThreshold.coerceAtLeast(1)
        if (failures < threshold) {
            // 未达阈值：只记计数，不打开（已打开过的保持原打开时长，避免"弱化"既有熔断）。
            return BreakerState(windowStart, failures, current?.openedAtMs ?: 0L, current?.openDurationMs ?: 0L)
        }
        return BreakerState(windowStart, failures, nowMs, backoffOf(failures))
    }

    /**
     * 指数退避：`min(base * 2^(N-1), max)`。
     *
     * 移位量做了溢出保护：`N` 很大时直接取上限，而不是让 `1L shl N` 回绕成 0 或负数
     * （回绕会让熔断**瞬间恢复**，正好是最坏结果）。
     */
    private fun backoffOf(failures: Int): Long {
        val exponent = (failures - 1).coerceAtLeast(0)
        if (exponent >= MAX_BACKOFF_EXPONENT) return maxOpenDurationMs
        val base = openDurationMs.coerceAtLeast(1L)
        val shifted = base.toDouble() * (1L shl exponent)
        return shifted.toLong().coerceAtMost(maxOpenDurationMs)
    }

    /** 状态 → 快照。 */
    private fun toEntry(path: String, state: BreakerState, nowMs: Long): BreakerEntry {
        val open = state.openedAtMs > 0L && nowMs - state.openedAtMs < state.openDurationMs
        val remaining = if (state.openedAtMs <= 0L) 0L else (state.openDurationMs - (nowMs - state.openedAtMs))
        return BreakerEntry(
            path = path,
            windowStartMs = state.windowStartMs,
            failures = state.failures,
            openedAtMs = state.openedAtMs,
            openDurationMs = state.openDurationMs,
            remainingOpenMs = remaining,
            open = open,
        )
    }

    private companion object {

        /** 退避封顶的默认值（毫秒）：10 分钟。 */
        const val DEFAULT_MAX_OPEN_DURATION_MS = 600_000L

        /** 超过该移位量即视为溢出（`1L shl 62` 已是天文数字）。 */
        const val MAX_BACKOFF_EXPONENT = 62
    }
}
