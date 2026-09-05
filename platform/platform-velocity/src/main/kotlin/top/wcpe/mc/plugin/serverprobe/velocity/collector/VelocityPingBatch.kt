package top.wcpe.mc.plugin.serverprobe.velocity.collector

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 把同一轮全部后端探测限制在一个总等待时限内。 */
internal class VelocityPingBatch(
    private val timeoutNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    init {
        require(timeoutNanos > 0) { "后端探测总时限必须大于零" }
    }

    /** 等待已并发发起的探测；总时限耗尽后，未完成项直接记为不可达。 */
    fun await(pending: Map<String, Future<*>>): Map<String, Boolean> {
        val deadlineNanos = nanoTime() + timeoutNanos
        return pending.mapValues { (_, future) -> awaitOne(future, deadlineNanos) }
    }

    /** 仅以批次剩余时间等待一个后端，避免逐后端叠加超时。 */
    private fun awaitOne(future: Future<*>, deadlineNanos: Long): Boolean {
        val remainingNanos = deadlineNanos - nanoTime()
        if (remainingNanos <= 0L) return false
        return runCatching { future.get(remainingNanos, TimeUnit.NANOSECONDS) }.isSuccess
    }
}
