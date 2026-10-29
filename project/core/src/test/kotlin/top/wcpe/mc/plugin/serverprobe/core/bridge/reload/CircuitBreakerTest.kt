package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [CircuitBreaker] 单元测试（配置重载规格 §3.6 + §5「熔断」「熔断退避」「熔断容量」「熔断并发」四行）。
 *
 * 时间全部由用例注入，故"窗口边界""打开时长边界""退避翻倍"都是**确定性断言**。
 */
class CircuitBreakerTest {

    /** 首次超时即熔断（默认阈值 1），打开时长 = base。 */
    @Test
    fun `首次超时即熔断且打开时长等于基数`() {
        val breaker = newBreaker(base = 1_000L)

        breaker.recordTimeout(PATH, 10_000L)

        assertTrue(breaker.isOpen(PATH, 10_000L))
        assertEquals(1_000L, breaker.snapshot(10_000L).single().openDurationMs)
    }

    /**
     * 边界比较符：`nowMs - openedAtMs < openDurationMs` → 仍开；`>=` → 恢复。
     *
     * 钉住比较符的理由：写成 `<=` 会多挡 1ms，高频重试时表现为"偶尔通过"，
     * 那种抖动比明确失败更难排查。
     */
    @Test
    fun `打开时长边界按小于判定`() {
        val breaker = newBreaker(base = 1_000L)
        breaker.recordTimeout(PATH, 10_000L)

        assertTrue(breaker.isOpen(PATH, 10_999L), "差 1ms 到期时仍应熔断")
        assertFalse(breaker.isOpen(PATH, 11_000L), "恰好到期即恢复")
        assertFalse(breaker.isOpen(PATH, 11_001L))
    }

    /** 未登记过的路径永不熔断。 */
    @Test
    fun `未登记路径不熔断`() {
        assertFalse(newBreaker().isOpen("plugins/Unknown/config.yml", 1L))
    }

    /** 达到阈值才熔断（阈值 > 1 时前 N-1 次只累计不开）。 */
    @Test
    fun `达到阈值才熔断`() {
        val breaker = newBreaker(base = 1_000L, threshold = 3)

        breaker.recordTimeout(PATH, 10_000L)
        assertFalse(breaker.isOpen(PATH, 10_000L), "第 1 次未达阈值")
        breaker.recordTimeout(PATH, 10_100L)
        assertFalse(breaker.isOpen(PATH, 10_100L), "第 2 次未达阈值")
        breaker.recordTimeout(PATH, 10_200L)
        assertTrue(breaker.isOpen(PATH, 10_200L), "第 3 次应熔断")
    }

    /** 统计窗口外的历史计数作废：一个窗口内未达阈值则从头累计。 */
    @Test
    fun `窗口过期后计数重新累计`() {
        val breaker = newBreaker(base = 1_000L, threshold = 2, window = 1_000L)

        breaker.recordTimeout(PATH, 10_000L)
        // 距窗口起点已 1000ms（>= 窗口）→ 本次应重新计数为 1，不达阈值 2。
        breaker.recordTimeout(PATH, 11_000L)

        assertFalse(breaker.isOpen(PATH, 11_000L), "跨窗口不应凑够阈值")
        assertEquals(1, breaker.snapshot(11_000L).single().failures)
    }

    // ---------------------------------------------------------------- 指数退避

    /** 验收「熔断退避」：连续 3 次超时 → openDuration 依次翻倍（base / 2×base / 4×base）。 */
    @Test
    fun `连续超时按指数退避翻倍`() {
        val breaker = newBreaker(base = 1_000L, threshold = 1, window = 60_000L)

        val durations = (1..3).map { attempt ->
            val now = 10_000L + attempt * 100L
            breaker.recordTimeout(PATH, now)
            breaker.snapshot(now).single().openDurationMs
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L), durations, "openDuration 应逐次翻倍")
    }

    /** 退避封顶到 maxOpenDurationMs。 */
    @Test
    fun `退避不超过上限`() {
        val breaker = newBreaker(base = 1_000L, threshold = 1, window = 60_000L, maxOpen = 3_000L)

        repeat(5) { attempt -> breaker.recordTimeout(PATH, 10_000L + attempt * 100L) }

        assertEquals(3_000L, breaker.snapshot(10_500L).single().openDurationMs, "应封顶到 max-open-duration-ms")
    }

    /** 一次成功即清零，退避回到 base。 */
    @Test
    fun `成功后计数清零并恢复基数`() {
        val breaker = newBreaker(base = 1_000L, threshold = 1, window = 60_000L)
        breaker.recordTimeout(PATH, 10_000L)
        breaker.recordTimeout(PATH, 10_100L)

        breaker.recordSuccess(PATH)

        assertFalse(breaker.isOpen(PATH, 10_200L), "成功后应立即恢复")
        assertTrue(breaker.snapshot(10_200L).isEmpty(), "成功后条目应被清除（计数清零的最简表达）")
        breaker.recordTimeout(PATH, 10_300L)
        assertEquals(1_000L, breaker.snapshot(10_300L).single().openDurationMs, "退避应回到 base")
    }

    // ---------------------------------------------------------------- 容量：满则拒绝新条目

    /** 验收「熔断容量」：灌满 maxEntries 后新 path → rejectedEntries +1，已有条目不被淘汰。 */
    @Test
    fun `容量满时拒绝新条目且不淘汰已有`() {
        val breaker = newBreaker(base = 1_000L, maxEntries = 2)
        breaker.recordTimeout("plugins/A/config.yml", 10_000L)
        breaker.recordTimeout("plugins/B/config.yml", 10_000L)
        assertEquals(2, breaker.size())

        breaker.recordTimeout("plugins/C/config.yml", 10_000L)

        assertEquals(1, breaker.rejectedEntries(), "被拒绝的次数应 +1")
        assertEquals(2, breaker.size(), "不应因拒绝而改变条目数")
        val paths = breaker.snapshot(10_000L).map { it.path }
        assertEquals(listOf("plugins/A/config.yml", "plugins/B/config.yml"), paths, "已有条目绝不能被淘汰")
        assertFalse(breaker.isOpen("plugins/C/config.yml", 10_000L), "被拒绝登记的新路径自然不熔断")
    }

    /** 容量满时**已登记**路径仍可更新（不是"满了就什么都不做"）。 */
    @Test
    fun `容量满时已登记路径仍可累计`() {
        val breaker = newBreaker(base = 1_000L, threshold = 5, maxEntries = 1, window = 60_000L)
        breaker.recordTimeout(PATH, 10_000L)

        repeat(4) { breaker.recordTimeout(PATH, 10_000L + it) }

        assertEquals(5, breaker.snapshot(10_000L).single().failures, "已登记条目应继续累计")
        assertEquals(0, breaker.rejectedEntries())
    }

    // ---------------------------------------------------------------- 并发压测

    /**
     * 验收「熔断并发」：8 线程并发 `recordTimeout`/`isOpen` 共 1000 次 → 无异常、计数准确。
     *
     * 计数窗口设得足够大（1 小时），因此 8 × 1000 次全部落在同一窗口，`failures` 必须恰为 8000；
     * 若实现用了裸 `HashMap` 或非原子的"读出-改-写"，这里会丢更新或抛 CME。
     */
    @Test
    fun `八线程并发记录无丢失且计数准确`() {
        val breaker = newBreaker(base = 1_000L, threshold = Int.MAX_VALUE, window = 3_600_000L)
        val threads = 8
        val perThread = 1_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val error = AtomicReference<Throwable?>()
        val now = 10_000L

        repeat(threads) { index ->
            Thread({
                start.await()
                runCatching {
                    repeat(perThread) { round ->
                        breaker.recordTimeout(PATH, now)
                        // 每 10 次读一次，制造并发读/写交错。
                        if (round % 10 == 0) breaker.isOpen(PATH, now)
                        assertTrue(breaker.snapshot(now).size <= 1)
                    }
                }.onFailure { error.compareAndSet(null, it) }
                done.countDown()
            }, "breaker-stress-$index").apply { isDaemon = true }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "压测线程应在 30s 内完成")

        error.get()?.let { throw AssertionError("并发记录不应抛异常:${it.javaClass.name}", it) }
        assertEquals(threads * perThread, breaker.snapshot(now).single().failures, "计数必须精确")
    }

    /** 阈值设为 MAX_VALUE 时永不熔断（用于压测里排除打开状态干扰）。 */
    @Test
    fun `阈值极大时永不熔断`() {
        val breaker = newBreaker(base = 1_000L, threshold = Int.MAX_VALUE)
        repeat(100) { breaker.recordTimeout(PATH, 10_000L) }

        assertFalse(breaker.isOpen(PATH, 10_000L))
    }

    /** 快照字段完整：包含窗口起点、累计次数、打开时刻、剩余时长。 */
    @Test
    fun `快照字段完整`() {
        val breaker = newBreaker(base = 5_000L, threshold = 1, window = 60_000L)
        breaker.recordTimeout(PATH, 10_000L)

        val entry = breaker.snapshot(12_000L).single()

        assertEquals(PATH, entry.path)
        assertEquals(10_000L, entry.windowStartMs)
        assertEquals(1, entry.failures)
        assertEquals(10_000L, entry.openedAtMs)
        assertEquals(5_000L, entry.openDurationMs)
        assertEquals(3_000L, entry.remainingOpenMs)
        assertTrue(entry.open)
    }

    /** 构造一个熔断器（默认阈值 1、窗口 60s、容量 256、上限 600s）。 */
    private fun newBreaker(
        base: Long = 1_000L,
        threshold: Int = 1,
        window: Long = 60_000L,
        maxEntries: Int = 256,
        maxOpen: Long = 600_000L,
    ): CircuitBreaker = CircuitBreaker(
        failureWindowMs = window,
        openDurationMs = base,
        failureThreshold = threshold,
        maxEntries = maxEntries,
        maxOpenDurationMs = maxOpen,
    )

    private companion object {
        const val PATH = "plugins/Foo/config.yml"
    }
}
