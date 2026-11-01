package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.config.ReconnectSettings

/**
 * 重连退避的取值规则（纯函数，便于单测）。
 *
 * ## 为什么单独收口
 * 1. **成功后必须回到初始值**：稳定连接数小时后再掉线，不该直接吃满最大退避；
 * 2. **初始值要有下界**：`bridge.reconnect.initial-backoff-ms` 配成 0（或负数）会退化成
 *    `sleep(0)` 的紧循环重连风暴，对控制面形成冲击。
 *
 * 退避语义仍由协议规格约定（常规指数翻倍封顶 / 认证失败更激进），本对象只负责把
 * “当前值 → 本次休眠 → 下次退避”这三步算准，不引入新的策略。
 */
internal object ReconnectBackoff {

    /**
     * 退避下限（毫秒）。
     *
     * 取值只为一个目的：杜绝 `sleep(0)` 紧循环。200ms 远小于默认初始值（1000ms），
     * 对正常退避序列没有任何影响。
     */
    const val MIN_BACKOFF_MS = 200L

    /**
     * 首次退避值（带下界与封顶夹取）。
     *
     * @param settings 当前生效的重连参数。
     * @return 夹在 `[MIN_BACKOFF_MS, maxBackoffMs]` 之间的初始退避。
     */
    fun initial(settings: ReconnectSettings): Long = clamp(settings.initialBackoffMs, settings)

    /**
     * 本次实际休眠时长。
     *
     * @param current 上一轮算出的退避值。
     * @param settings 当前生效的重连参数。
     * @return 实际用于 `sleep` 的毫秒数（已夹取）。
     */
    fun waitMs(current: Long, settings: ReconnectSettings): Long = clamp(current, settings)

    /**
     * 下一轮退避值。
     *
     * 三条策略与原实现一致：常规指数翻倍封顶；认证失败用更小的上限（token 轮换后要尽快恢复）；
     * 连续认证失败达到阈值后降频（但保留下限，不彻底放弃）。
     *
     * @param current 上一轮退避值。
     * @param snapshot 当前连接状态快照（取最近断开原因与连续失败次数）。
     * @param settings 当前生效的重连参数。
     * @return 下一轮退避值。
     */
    fun next(current: Long, snapshot: BridgeLinkSnapshot, settings: ReconnectSettings): Long {
        val wait = clamp(current, settings)
        val authFailed = snapshot.lastDisconnectReason == BridgeLinkState.REASON_AUTH_FAILED
        if (!authFailed) {
            return (wait * 2).coerceAtMost(settings.maxBackoffMs.coerceAtLeast(MIN_BACKOFF_MS))
        }
        if (snapshot.consecutiveFailures >= settings.authFailedDegradeThreshold) {
            return settings.authFailedDegradedBackoffMs.coerceAtLeast(MIN_BACKOFF_MS)
        }
        return (wait * 2).coerceAtMost(settings.authFailedMaxBackoffMs.coerceAtLeast(MIN_BACKOFF_MS))
    }

    /**
     * 把退避值夹到合法区间。
     *
     * @param value 原始退避值（可能来自配置，可能为 0 或负数）。
     * @param settings 当前生效的重连参数（取封顶值）。
     * @return 合法退避值。
     */
    private fun clamp(value: Long, settings: ReconnectSettings): Long =
        value.coerceIn(MIN_BACKOFF_MS, settings.maxBackoffMs.coerceAtLeast(MIN_BACKOFF_MS))
}
