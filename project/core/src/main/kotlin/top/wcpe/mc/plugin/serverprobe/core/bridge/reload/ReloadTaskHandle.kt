package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次监听器派发的执行结果。
 *
 * @property results 该次派发内**每个监听器**的结果（成功或失败）。
 */
data class DispatchOutcome(val results: List<ListenerOutcome>)

/**
 * 单监听器结果。
 *
 * @property listener 监听器类名（日志与归属用）。
 * @property result 监听器返回的结果；返回 null 时已归一为失败。
 * @property threw 是否抛了异常（已兜住 → `PLUGIN_THREW`）。
 */
data class ListenerOutcome(
    val listener: String,
    val result: top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadResult,
    val threw: Boolean = false,
)

/**
 * 一次子任务的落定视图：要么有结果，要么有未捕获异常。
 *
 * @property outcome 任务体返回的结果；异常退出时为 null。
 * @property error 未捕获异常；正常返回时为 null。
 */
data class TaskOutcome(val outcome: DispatchOutcome?, val error: Throwable?)

/**
 * 可等待的子任务句柄。
 *
 * ## 为什么自己包一层而不是直接用 `FutureTask`
 * 回执必须区分三种"没有结果"（规格 §3.8），其中「排队到超时仍未被取走」与「跑过但没跑完」
 * 只能靠"任务是否已开始执行"区分。该标志由本类维护（[started]），
 * 不依赖 `ThreadPoolExecutor` 的内部计数器（那不可移植、也不可测）。
 *
 * ## 迟到结果一律丢弃
 * 协调器只经 [resultIfDone] / [awaitSlice] 取值；任务自身**不写任何共享回执**，
 * 因此被取消任务迟到完成时不会污染下一次请求。
 *
 * @param body 任务体；返回本次派发的结果。
 */
class ReloadTaskHandle(private val body: () -> DispatchOutcome) : Runnable {

    /** 是否已开始执行（由执行线程置位）。 */
    private val startedFlag = AtomicBoolean(false)

    /** 是否已被协调器取消（迟到结果据此丢弃）。 */
    private val cancelledFlag = AtomicBoolean(false)

    /** 结果槽；只由执行线程写入一次。 */
    private val slot = AtomicReference<DispatchOutcome?>(null)

    /** 未捕获异常槽（执行线程写入）。 */
    private val failure = AtomicReference<Throwable?>(null)

    /** 正在执行本任务的线程（供 [cancel] 打中断标记）。 */
    @Volatile
    private var runner: Thread? = null

    /** 是否已开始执行。 */
    fun started(): Boolean = startedFlag.get()

    /** 任务是否已落定（有结果或有异常）。 */
    fun isDone(): Boolean = slot.get() != null || failure.get() != null

    /** 是否已被协调器取消。 */
    fun cancelled(): Boolean = cancelledFlag.get()

    /** 已落定则取结果；未落定返回 null。 */
    fun resultIfDone(): TaskOutcome? {
        slot.get()?.let { return TaskOutcome(it, null) }
        failure.get()?.let { return TaskOutcome(null, it) }
        return null
    }

    /**
     * 有界等待**一个极短分片**，最多 [sliceMs]。
     *
     * 用"分片轮询 + 轮转所有未完成项"而不是按列表顺序长等：第一个卡住的任务会吃光预算，
     * 后面早已完成的项也会被误判 `TIMEOUT`（轮转收割的核心动机）。
     *
     * @return 已落定的结果；分片到期仍未完成返回 null。
     */
    fun awaitSlice(sliceMs: Long): TaskOutcome? {
        resultIfDone()?.let { return it }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sliceMs.coerceAtLeast(MIN_SLICE_MS))
        while (System.nanoTime() < deadline) {
            sleepQuietly()
            resultIfDone()?.let { return it }
            if (cancelledFlag.get()) return resultIfDone()
        }
        return resultIfDone()
    }

    /**
     * 取消：只置**中断标记**，不 join、不等线程退出（规格 §3.9）。
     *
     * 对不响应中断的任务自然无效——这正是"`release` 必须由任务自身 finally 执行"的原因，
     * 也是 `STUCK` 这个独立错误码存在的理由。
     */
    fun cancel() {
        cancelledFlag.set(true)
        if (isDone()) return
        runner?.interrupt()
    }

    // 必须捕 Throwable：外部监听器可能抛 Error，而这里在 worker 池线程上执行——
    // 漏出去会打死池线程并让池永久少一个位（`BusinessHostImpl.runSafely` 同一理由）。
    @Suppress("TooGenericExceptionCaught")
    override fun run() {
        startedFlag.set(true)
        runner = Thread.currentThread()
        try {
            val outcome = try {
                body()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                return
            }
            slot.compareAndSet(null, outcome)
        } finally {
            // 清中断位：`cancel()` 会把 `interrupt()` 打在执行本任务的线程上（超时/卡住时）。
            // 原来的做法是任务正常返回就直接走人，中断标记却留在那个线程上——
            // 于是**同一个 worker 线程上的下一个监听器回调**一进门就是“被中断”态：
            // 它内部任何 sleep/wait/IO 立刻抛 InterruptedException，被广捕记成 PLUGIN_THREW，
            // 把上一次的超时错误归因到一个完全无辜的插件；更坏的是这个错误会被写进
            // 健康/熔断统计，把本当正常的域隔离掉。
            // 不能指望执行器代清：任务体被直接 `run()`（单测、自定义执行器）时无人清，
            // 而且“谁打的中断谁负责收尾”本来就是任务体自己的义务。
            Thread.interrupted()
            // 线程已退出任务体，不再持有它：否则 `cancel()` 会去 interrupt 一个
            // 已经被池回收去跑另一个任务（甚至另一个请求）的线程。
            runner = null
        }
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(POLL_SLICE_MS)
        } catch (_: InterruptedException) {
            // 恢复中断标志：否则停机后池线程无法正常退出。
            Thread.currentThread().interrupt()
        }
    }

    private companion object {

        /** 分片内轮询粒度（毫秒）。 */
        const val POLL_SLICE_MS = 1L

        /** 最小分片（毫秒）：`0` 会让等待退化成"永远立即返回未完成"。 */
        const val MIN_SLICE_MS = 1L
    }
}
