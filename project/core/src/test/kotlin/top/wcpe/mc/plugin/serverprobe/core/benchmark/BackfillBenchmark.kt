package top.wcpe.mc.plugin.serverprobe.core.benchmark

import top.wcpe.mc.plugin.serverprobe.core.bridge.FrameSender
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundSink
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.BridgeEventPublisher
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.EventPolicy
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.util.concurrent.atomic.AtomicLong

/**
 * **回灌（backfill）**场景压测：模拟经济插件从本地库把一大批水位线上的账单同步上来。
 *
 * ## 为什么单独做这一项
 * 前面的微基准与吞吐压测用的都是"稳态小流量"，而回灌是**突发大流量**：
 * 一次查询可能捞出 10 万条历史账单，逐条上报时生产速率远高于单条 WebSocket 的写出速率。
 * 这类场景的失败模式与稳态完全不同——稳态下偶发拒绝无所谓，回灌下拒绝就等于**数据缺口**，
 * 而 `emitReliable` 返回 false 时**只有调用方自己能重试**（探针不做重放承诺，
 * 因为事件还没进可靠队列就已经被拒了）。
 *
 * ## 三种典型实现策略的对比
 * 1. **不管返回值**（最常见的错误）：直接丢，回灌结束后数据静默缺失。
 * 2. **失败即重试**（忙等）：把拒绝率转成 CPU 空转，可能饿死发送线程。
 * 3. **按批退避**（推荐的正确做法）：撞背压就短暂让出 CPU，等队列消化后继续。
 *
 * 本基准把三种都跑一遍，用实测数字说明"为什么回灌必须自己处理背压"。
 */
object BackfillBenchmark {

    /** 一条账单事件的真实形状：金额一律字符串（避免 JSON 数字的科学计数与精度问题）。 */
    private fun billFields(seq: Int): Map<String, String> = linkedMapOf(
        "ledgerId" to "L$seq",
        "currencyId" to "gem",
        "playerUuid" to "00000000-0000-0000-0000-00000000abcd",
        "signedAmount" to (if (seq % 2 == 0) "-1250" else "1250"),
        "balanceAfter" to "98765432109876",
        "entryType" to "TRANSFER",
        "occurredAt" to "1730000000000",
    )

    /**
     * 跑一次回灌。
     *
     * @param total 回灌条数（模拟"查询水位线以上的全部账单"）。
     * @param batchSize 数据库游标分页大小（每批之间模拟一次读取停顿）。
     * @param strategy 背压策略。
     */
    fun run(
        total: Int = 100_000,
        batchSize: Int = 1_000,
        strategy: Strategy = Strategy.BACKOFF,
        /** 每批之间的读取停顿；0 = 最坏情况（DB 已在内存、全速灌入，不留给消费者任何喘息）。 */
        dbBatchPauseMs: Long = DB_BATCH_PAUSE_MS,
    ): Report {
        val settings = LineSettings()
        val sender = FrameSender(settings, null, FrameChunker(ChunkSettings()))
        val sink = CountingSink()
        sender.attach(sink)
        val senderThread = Thread({ sender.runLoop() }, "backfill-sender").apply {
            isDaemon = true
            start()
        }
        val publisher = BridgeEventPublisher({ sender }, { "s1" })

        // 四个计数器聚成一处：`publishOne` 若把它们逐个当参数传，会触发参数过多检查，
        // 而它们本来就是同一份"回灌统计"的四个侧面。
        val stats = Stats()
        val startNs = System.nanoTime()

        var seq = 0
        while (seq < total) {
            val batchEnd = minOf(seq + batchSize, total)
            while (seq < batchEnd) {
                publishOne(publisher, seq, strategy, stats)
                seq++
            }
            // 模拟数据库游标翻页的读取停顿（真实回灌里每批都要等一次查询）。
            if (dbBatchPauseMs > 0) Thread.sleep(dbBatchPauseMs)
        }

        // 排空：让发送线程把队列写出去，否则统计的是"入队即成功"。
        val drainDeadline = System.nanoTime() + DRAIN_TIMEOUT_MS * 1_000_000L
        while (sender.status().reliableQueue > 0 && System.nanoTime() < drainDeadline) {
            Thread.sleep(DRAIN_GRACE_MS)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val status = sender.status()
        sender.shutdown()
        senderThread.join(1_000)

        return Report(
            strategy = strategy,
            total = total,
            accepted = stats.accepted.get(),
            firstRoundRejected = stats.firstRoundRejected.get(),
            retries = stats.retries.get(),
            abandoned = stats.abandoned.get(),
            written = sink.count(),
            stillQueued = status.reliableQueue,
            elapsedMs = elapsedMs,
        )
    }

    /**
     * 按策略上报一条账单。
     *
     * 抽成独立方法是为了让 [run] 的复杂度落在阈值内——三种策略各自的重试循环
     * 内联在双层 while 里会成为难读的嵌套块。
     */
    private fun publishOne(
        publisher: BridgeEventPublisher,
        seq: Int,
        strategy: Strategy,
        stats: Stats,
    ) {
        val fields = billFields(seq)
        val dedupKey = "L$seq"
        if (publisher.publish("economy", dedupKey, "economy.bill", fields, EventPolicy.RELIABLE)) {
            stats.accepted.incrementAndGet()
            return
        }
        stats.firstRoundRejected.incrementAndGet()
        when (strategy) {
            Strategy.DROP -> stats.abandoned.incrementAndGet()
            Strategy.SPIN_RETRY -> {
                // 忙等重试：把拒绝率直接转成 CPU 空转。
                while (!republish(publisher, dedupKey, fields)) stats.retries.incrementAndGet()
                stats.accepted.incrementAndGet()
            }
            Strategy.BACKOFF -> {
                // 每 32 次失败让出一次 CPU（真实实现应结合队列深度做自适应）。
                while (!republish(publisher, dedupKey, fields)) {
                    if (stats.retries.incrementAndGet() % BACKOFF_EVERY == 0L) Thread.yield()
                }
                stats.accepted.incrementAndGet()
            }
        }
    }

    /** 单次重投；返回值直接交给策略的循环条件。 */
    private fun republish(
        publisher: BridgeEventPublisher,
        dedupKey: String,
        fields: Map<String, String>,
    ): Boolean = publisher.publish("economy", dedupKey, "economy.bill", fields, EventPolicy.RELIABLE)

    /** 回灌过程中的四个计数器（同一份统计的四个侧面）。 */
    private class Stats {
        val accepted = AtomicLong()
        val firstRoundRejected = AtomicLong()
        val retries = AtomicLong()
        val abandoned = AtomicLong()
    }

    /** 回灌结果。 */
    data class Report(
        val strategy: Strategy,
        val total: Int,
        val accepted: Long,
        val firstRoundRejected: Long,
        val retries: Long,
        val abandoned: Long,
        val written: Long,
        val stillQueued: Int,
        val elapsedMs: Long,
    ) {
        /** 数据完整性：最终真正写出去的条数 / 应回灌条数。 */
        fun deliveryRate(): Double = if (total == 0) 1.0 else written * 100.0 / total

        fun print() {
            println(
                "  %-14s 受理 %,d | 首轮拒绝 %,d | 重试 %,d | 放弃 %,d | 已写出 %,d | 仍在队列 %d | 耗时 %,d ms | 送达率 %.2f%%".format(
                    strategy.label, accepted, firstRoundRejected, retries, abandoned,
                    written, stillQueued, elapsedMs, deliveryRate(),
                ),
            )
        }
    }

    /** 背压处理策略。 */
    enum class Strategy(val label: String) {
        /** 不看返回值直接丢（最常见错误）。 */
        DROP("不管返回值"),

        /** 失败就忙等重试。 */
        SPIN_RETRY("忙等重试"),

        /** 失败就短暂让出 CPU 再试（推荐）。 */
        BACKOFF("让出CPU重试"),
    }

    /** 假连接：只计数。回灌压测关心"能否全部送出"，不掺网络延迟。 */
    private class CountingSink : OutboundSink {
        private val written = AtomicLong()
        override fun sendText(text: String): Boolean {
            written.incrementAndGet()
            return true
        }

        fun count(): Long = written.get()
    }

    private const val DB_BATCH_PAUSE_MS = 2L
    private const val BACKOFF_EVERY = 32L
    private const val DRAIN_TIMEOUT_MS = 30_000L
    private const val DRAIN_GRACE_MS = 10L
}
