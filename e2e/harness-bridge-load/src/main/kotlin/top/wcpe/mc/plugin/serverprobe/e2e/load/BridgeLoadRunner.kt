package top.wcpe.mc.plugin.serverprobe.e2e.load

import java.util.concurrent.atomic.AtomicLong

/**
 * 一次压测的结果。
 *
 * 区分「受理」与「拒绝」两类计数，是因为它们的运维含义完全不同：
 *
 * - **[accepted]**：事件进了桥（含“被滤镜判定无需上送”）。
 * - **[rejected]**：队列满 / 落盘失败 / 探针未就绪，**调用方拿到 false**。
 *   在「不可靠、幂等」语义下这是**合法的降级**——丢就丢了，但不是静默的：
 *   它必须体现为一个可观测的计数，否则运维无从得知链路已在丢数据。
 *
 * 两者之和才是提交总数。`accepted + rejected` 与 `count` 不符就是度量 bug。
 */
internal class LoadReport(
    val label: String,
    val elapsedNanos: Long,
    val accepted: Long,
    val rejected: Long,
    val submitLatency: LatencyHistogram.Snapshot,
) {

    /** 接受率（口径：accepted / 总提交数）。 */
    fun acceptRate(): Double {
        val total = accepted + rejected
        return if (total == 0L) 0.0 else accepted.toDouble() / total * 100.0
    }

    /** 吞吐（事件/秒）。 */
    fun throughputPerSecond(): Double {
        if (elapsedNanos <= 0) return 0.0
        return (accepted + rejected).toDouble() / (elapsedNanos / 1_000_000_000.0)
    }

    /** 一行摘要，便于在日志里直接读。 */
    fun summary(): String = buildString {
        append(label)
        append(" | 受理=").append(accepted)
        append(" 拒绝=").append(rejected)
        append(" 接受率=").append("%.2f".format(acceptRate())).append('%')
        append(" 吞吐=").append("%.0f".format(throughputPerSecond())).append("/s")
        append(" | 延迟(ms) p50=").append("%.3f".format(submitLatency.p50Millis()))
        append(" p95=").append("%.3f".format(submitLatency.p95Millis()))
        append(" p99=").append("%.3f".format(submitLatency.p99Millis()))
        append(" p999=").append("%.3f".format(submitLatency.p999Millis()))
        append(" max=").append("%.3f".format(submitLatency.maxMillis()))
    }

    /** 写入 ScenarioResultWriter 的 details 键值对（带前缀，避免多轮压测互相覆盖）。 */
    fun details(prefix: String = ""): Map<String, String> = mapOf(
        "${prefix}accepted" to accepted.toString(),
        "${prefix}rejected" to rejected.toString(),
        "${prefix}acceptRatePct" to "%.2f".format(acceptRate()),
        "${prefix}throughputPerSec" to "%.0f".format(throughputPerSecond()),
        "${prefix}latencyP50Ms" to "%.3f".format(submitLatency.p50Millis()),
        "${prefix}latencyP95Ms" to "%.3f".format(submitLatency.p95Millis()),
        "${prefix}latencyP99Ms" to "%.3f".format(submitLatency.p99Millis()),
        "${prefix}latencyP999Ms" to "%.3f".format(submitLatency.p999Millis()),
        "${prefix}latencyMaxMs" to "%.3f".format(submitLatency.maxMillis()),
        "${prefix}elapsedMs" to (elapsedNanos / 1_000_000).toString(),
    )
}

/**
 * 多线程向桥注流量的压测器。
 *
 * ## 为什么用固定线程池 + 每线程独立计数
 *
 * `submit()` 本身是无锁、非阻塞的（见 `FrameSender.submit`）。若压测器用一个共享
 * `AtomicLong` 累计计数，**压测器自己的 CAS 竞争**会成为瓶颈，测出的延迟会变成
 * “压测器的争用延迟”而不是“桥的处理延迟”。每线程先写局部变量、最后合并，
 * 把测量开销降到只剩一个直方图 `incrementAndGet`。
 *
 * ## 为什么要记录提交延迟
 *
 * 用户关心的本质是“会不会堵塞”，而堵塞的直接体现就是**提交变慢**：
 * `submit()` 是非阻塞的，正常应在微秒级；一旦队列满、滤镜链变长或
 * 发生锁竞争，它就会变慢。p99/p999 比均值更能早早暴露这个问题。
 */
internal class BridgeLoadRunner(
    private val emit: (domain: String, dedupKey: String, topic: String) -> Boolean,
) {

    /**
     * 跑一轮压测。
     *
     * @param label 报告标签
     * @param totalEvents 总事件数
     * @param threads 并发线程数
     * @param maxInFlight 同时未完成的上限（背压阀门，0 = 不限制）
     */
    fun run(
        label: String,
        totalEvents: Int,
        threads: Int,
        maxInFlight: Int = 0,
    ): LoadReport {
        val histogram = LatencyHistogram()
        val accepted = AtomicLong(0)
        val rejected = AtomicLong(0)
        val remaining = AtomicLong(totalEvents.toLong())
        val inFlight = java.util.concurrent.Semaphore(if (maxInFlight > 0) maxInFlight else Int.MAX_VALUE)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val start = System.nanoTime()
        try {
            val tasks = (1..threads).map { threadIndex ->
                java.util.concurrent.Callable {
                    var localAccepted = 0L
                    var localRejected = 0L
                    while (true) {
                        val seq = remaining.decrementAndGet()
                        if (seq < 0) break
                        inFlight.acquire()
                        try {
                            val begin = System.nanoTime()
                            val ok = emit(DOMAIN, "load-$threadIndex-$seq", TOPIC)
                            histogram.record(System.nanoTime() - begin)
                            if (ok) localAccepted++ else localRejected++
                        } finally {
                            inFlight.release()
                        }
                    }
                    accepted.addAndGet(localAccepted)
                    rejected.addAndGet(localRejected)
                }
            }
            pool.invokeAll(tasks)
        } finally {
            pool.shutdown()
        }
        val elapsed = System.nanoTime() - start

        return LoadReport(
            label = label,
            elapsedNanos = elapsed,
            accepted = accepted.get(),
            rejected = rejected.get(),
            submitLatency = histogram.snapshot(),
        )
    }

    companion object {
        /** 压测用的业务域；与真实业务隔离，便于在 JM 侧过滤。 */
        const val DOMAIN = "probe.loadtest"

        /** 压测主题。 */
        const val TOPIC = "probe.loadtest.event"
    }
}
