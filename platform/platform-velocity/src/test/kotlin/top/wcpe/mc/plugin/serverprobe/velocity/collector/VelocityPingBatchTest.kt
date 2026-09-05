package top.wcpe.mc.plugin.serverprobe.velocity.collector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Velocity 后端并发探测批次的总时限测试。 */
class VelocityPingBatchTest {

    /** 首个后端耗尽批次时限后，后续未完成探测不得继续阻塞。 */
    @Test
    fun `批次总时限耗尽后不再等待后续后端`() {
        var nowNanos = 0L
        val slow = TimeoutFuture { waitedNanos -> nowNanos += waitedNanos }
        val later = UnexpectedWaitFuture()

        val reachable = VelocityPingBatch(BATCH_TIMEOUT_NANOS) { nowNanos }
            .await(mapOf("slow" to slow, "later" to later))

        assertFalse(reachable.getValue("slow"))
        assertFalse(reachable.getValue("later"))
        assertEquals(BATCH_TIMEOUT_NANOS, slow.waitedNanos)
        assertFalse(later.awaited)
    }

    /** 已完成的后端应在批次时限内被正确标记为可达。 */
    @Test
    fun `已完成后端在批次时限内标记可达`() {
        val reachable = VelocityPingBatch(BATCH_TIMEOUT_NANOS) { 0L }
            .await(mapOf("lobby" to CompletedFuture()))

        assertEquals(mapOf("lobby" to true), reachable)
    }

    private class TimeoutFuture(
        private val onWait: (Long) -> Unit,
    ) : Future<Unit> {

        var waitedNanos = 0L
            private set

        override fun get(timeout: Long, unit: TimeUnit): Unit {
            waitedNanos = unit.toNanos(timeout)
            onWait(waitedNanos)
            throw TimeoutException("测试用超时")
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Unit = throw UnsupportedOperationException("测试不允许无时限等待")
    }

    private class UnexpectedWaitFuture : Future<Unit> {

        var awaited = false
            private set

        override fun get(timeout: Long, unit: TimeUnit): Unit {
            awaited = true
            throw AssertionError("批次时限耗尽后不应继续等待")
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Unit = throw UnsupportedOperationException("测试不允许无时限等待")
    }

    private class CompletedFuture : Future<Unit> {

        override fun get(timeout: Long, unit: TimeUnit): Unit = Unit

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = true

        override fun get(): Unit = Unit
    }

    private companion object {
        private const val BATCH_TIMEOUT_NANOS = 3_000_000_000L
    }
}
