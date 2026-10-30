package top.wcpe.mc.plugin.serverprobe.core.benchmark

import java.lang.management.ManagementFactory

/**
 * 无依赖的微基准框架（**不挂进 check**）。
 *
 * ## 为什么不用 JMH
 * JMH 需要注解处理器与额外依赖，而本仓的 core 刻意保持"零额外运行期依赖"。
 * 这里只需要"相对量级 + 分配字节数"两个信号，手写足够；数字不参与 CI 判定。
 *
 * ## 为什么把"分配字节"作为一等指标
 * 用户的要求是"**不要随便加什么转换**"。吞吐数字会被 JIT、GC 时机、缓存命中掩盖，
 * 而"每次调用分配了多少字节"是稳定且可解释的信号：一次多余的 `toByteArray`、
 * 一次 `listOf`、一次 `filterValues` 拷贝，都会直接体现在这个数字上。
 * 用 `ThreadMXBean.getThreadAllocatedBytes` 读取**当前线程**的累计分配量。
 *
 * ## 为什么不在 check 里断言时间
 * 本仓既有约定：墙钟阈值在 CI 上必然 flaky（见 FrameSenderTest 的类注释）。
 * 因此本类只作为手动运行的诊断工具，输出数字供人判断。
 */
object Bench {

    /** 单次测量的结果。 */
    data class Result(
        /** 每次操作的耗时（纳秒）。 */
        val nsPerOp: Double,
        /** 每次操作的分配字节数。 */
        val bytesPerOp: Double,
    ) {
        override fun toString(): String = "%.1f ns/op, %.1f B/op".format(nsPerOp, bytesPerOp)
    }

    private val threadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    /**
     * 测一个操作的耗时与分配。
     *
     * @param warmup 预热轮数：让 JIT 编译并让分支预测稳定，否则首次测得的是解释执行。
     * @param rounds 正式测量轮数，取**中位数**以抵消偶发 GC/调度抖动。
     * @param iterations 每轮内的操作次数。
     * @param action 被测操作；返回值会被丢弃（防止 JIT 死代码消除，见下方 sink）。
     */
    fun measure(
        name: String,
        warmup: Int = DEFAULT_WARMUP,
        rounds: Int = DEFAULT_ROUNDS,
        iterations: Int = DEFAULT_ITERATIONS,
        action: () -> Any?,
    ): Result {
        repeat(warmup) { runBatch(iterations, action) }

        val times = DoubleArray(rounds)
        val allocs = DoubleArray(rounds)
        for (round in 0 until rounds) {
            val allocBefore = allocatedBytes()
            val start = System.nanoTime()
            runBatch(iterations, action)
            val elapsed = System.nanoTime() - start
            times[round] = elapsed.toDouble() / iterations
            allocs[round] = (allocatedBytes() - allocBefore).toDouble() / iterations
        }
        val result = Result(times.median(), allocs.median())
        // 汇总表场景（多条子项自己排版）可以静默，避免逐行刷屏。
        if (!silent.get()) {
            println("%-46s %s".format(name, result))
        }
        return result
    }

    /**
     * 跑一批操作并"消费"结果。
     *
     * 必须把结果写进 [sink]：否则 JIT 会把整个调用当死代码删掉，
     * 测出接近 0 的耗时——这是微基准最经典的假绿。
     */
    private fun runBatch(iterations: Int, action: () -> Any?) {
        var local = 0
        // 用 repeat 而非 `for (index in 0 until n)`：循环变量未被使用，
        // detekt 的 UnusedPrivateProperty 会把它误判成未使用属性。
        repeat(iterations) {
            val produced = action()
            if (produced is Boolean) {
                if (produced) local++
            } else {
                local += produced?.hashCode() ?: 0
            }
        }
        sink = local
    }

    /** 防死代码消除的汇点（volatile 保证写入不被优化掉）。 */
    @Volatile
    private var sink: Int = 0

    /** 为 {@code true} 时不再逐行打印（调用方自行汇总输出）。 */
    private val silent = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 在给定代码块内静默逐行输出。 */
    fun <T> silently(block: () -> T): T {
        silent.set(true)
        try {
            return block()
        } finally {
            silent.set(false)
        }
    }

    /** 当前线程累计分配字节；JVM 不支持时返回 0（此时分配列会显示 0，需人工忽略）。 */
    private fun allocatedBytes(): Long =
        if (threadBean != null && threadBean.isThreadAllocatedMemorySupported) {
            threadBean.getThreadAllocatedBytes(Thread.currentThread().id)
        } else {
            0L
        }

    /** 取中位数：比平均值更抗偶发停顿（GC、线程调度）。 */
    private fun DoubleArray.median(): Double {
        val sorted = this.sortedArray()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
    }

    const val DEFAULT_WARMUP = 3

    const val DEFAULT_ROUNDS = 7

    const val DEFAULT_ITERATIONS = 20_000
}
