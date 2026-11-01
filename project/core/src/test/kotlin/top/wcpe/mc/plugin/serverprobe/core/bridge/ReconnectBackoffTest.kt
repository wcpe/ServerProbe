package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.ReconnectSettings

/**
 * 重连退避取值的验收测试。
 *
 * ## 为什么这几条必须有
 * 两个都是真实发生过的退化形态：
 * - `initial-backoff-ms` 配成 0 → `sleep(0)` 紧循环，对控制面形成重连风暴；
 * - 退避值只增不减 → 稳定运行数小时后再掉线，首次重连就吃满最大退避（看起来像“桥很久不恢复”）。
 */
class ReconnectBackoffTest {

    private val settings = ReconnectSettings(
        initialBackoffMs = 1_000,
        maxBackoffMs = 30_000,
        authFailedMaxBackoffMs = 10_000,
        authFailedDegradeThreshold = 3,
        authFailedDegradedBackoffMs = 60_000,
    )

    /** 初始值配成 0 / 负数时必须有下界，否则退化成紧循环。 */
    @Test
    fun `初始退避零或负数被夹到下限`() {
        assertEquals(ReconnectBackoff.MIN_BACKOFF_MS, ReconnectBackoff.initial(settings.copy(initialBackoffMs = 0)))
        assertEquals(ReconnectBackoff.MIN_BACKOFF_MS, ReconnectBackoff.initial(settings.copy(initialBackoffMs = -5_000)))
    }

    /** 初始值不得超过封顶值。 */
    @Test
    fun `初始退避不超过封顶`() {
        assertEquals(30_000, ReconnectBackoff.initial(settings.copy(initialBackoffMs = 999_999)))
    }

    /** 常规失败：指数翻倍并封顶。 */
    @Test
    fun `常规失败指数翻倍并封顶`() {
        val snapshot = BridgeLinkSnapshot(lastDisconnectReason = BridgeLinkState.REASON_UNREACHABLE)
        assertEquals(2_000, ReconnectBackoff.next(1_000, snapshot, settings))
        assertEquals(30_000, ReconnectBackoff.next(30_000, snapshot, settings))
    }

    /** 认证失败：封顶更小（便于 token 轮换后快速恢复）。 */
    @Test
    fun `认证失败使用更小的封顶`() {
        val snapshot = BridgeLinkSnapshot(lastDisconnectReason = BridgeLinkState.REASON_AUTH_FAILED, consecutiveFailures = 1)
        assertEquals(2_000, ReconnectBackoff.next(1_000, snapshot, settings))
        assertEquals(10_000, ReconnectBackoff.next(8_000, snapshot, settings))
    }

    /** 连续认证失败达到阈值：降频，但不彻底放弃。 */
    @Test
    fun `连续认证失败降频`() {
        val snapshot = BridgeLinkSnapshot(
            lastDisconnectReason = BridgeLinkState.REASON_AUTH_FAILED,
            consecutiveFailures = 3,
        )
        assertEquals(60_000, ReconnectBackoff.next(10_000, snapshot, settings))
        assertEquals(
            60_000,
            ReconnectBackoff.waitMs(60_000),
            "降频值不得被 maxBackoffMs 夹回 30s（否则降频形同虚设）",
        )
    }

    /** 实际休眠值只做下界夹取（上界由本次断开原因决定，不在 waitMs 里再夹）。 */
    @Test
    fun `实际休眠值只取下限`() {
        assertEquals(ReconnectBackoff.MIN_BACKOFF_MS, ReconnectBackoff.waitMs(0))
        assertEquals(999_999, ReconnectBackoff.waitMs(999_999), "上界不在 waitMs 里夹")
    }

    /** 复位门槛：至少 10s，且不小于 2 倍初始值（避免“接受 WS 后立刻关闭”变成 1s 热重连）。 */
    @Test
    fun `复位门槛取初始值两倍与十秒的较大者`() {
        assertEquals(10_000, ReconnectBackoff.stableThresholdMs(settings))
        assertEquals(20_000, ReconnectBackoff.stableThresholdMs(settings.copy(initialBackoffMs = 10_000)))
    }
}
