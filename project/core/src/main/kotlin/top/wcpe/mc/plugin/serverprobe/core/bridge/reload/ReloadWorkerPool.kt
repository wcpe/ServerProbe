package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 重载子任务的 worker 池：**与协调池分离**（配置重载规格 §3.1）。
 *
 * ## 为什么必须独立于 `serverprobe-reload`
 * 协调器本身跑在 `serverprobe-reload` 池上，监听器子任务若提交到**同一个**有界池：
 * 4 个并发 `config_reload` 各占 1 个协调槽后，把 16~32 个子任务推进同一队列；
 * 队列一满即无空闲线程（所有线程都在等自己提交的子任务），协调器全部阻塞在
 * `future.get`，子任务永不执行 → 全部误判 `TIMEOUT`。
 * "轮转收割"是收口算法，**解决不了池内自锁**——两池分离是硬要求，不是优化。
 *
 * ## 有界 + AbortPolicy
 * 有界让"没进队列"与"排队没轮到"可区分（回执记 `INTERNAL_ERROR` vs `TIMEOUT`）；
 * `AbortPolicy` 让拒绝被看见，**不用 `CallerRunsPolicy`**——那会让协调线程亲自跑监听器回调，
 * 等于把一个卡死的插件升级成"占住协调槽还占住它自己"。
 *
 * ## daemon
 * 全部守护线程：卡死的监听器回调不阻止 JVM 退出。停机由桥统一 [shutdownNow]。
 *
 * @param workerPoolSize 并发位（`bridge.reload.worker-pool-size`）。
 * @param queueCapacity 队列容量；默认"单请求最大路径数 × 并发位"，避免新增配置项。
 */
class ReloadWorkerPool(
    workerPoolSize: Int,
    queueCapacity: Int = workerPoolSize.coerceAtLeast(1) * PERMITS_PER_WORKER,
) {

    /** 实际并发位（下限 1：配置成 0 时建池会直接失败）。 */
    private val size: Int = workerPoolSize.coerceAtLeast(1)

    /** 底层池。 */
    private val pool: ThreadPoolExecutor = newPool(queueCapacity)

    /**
     * 提交一个监听器子任务。
     *
     * **不抛异常**：拒绝时返回 null，由协调器就地降级为 `INTERNAL_ERROR`
     * （"根本没进队列"与"排队没轮到"运维要做的事完全不同）。
     *
     * @param task 任务体；返回该次派发的结果。
     * @return 可等待的句柄；被拒（池满或已关闭）返回 null。
     */
    fun submit(task: () -> DispatchOutcome): ReloadTaskHandle? {
        val handle = ReloadTaskHandle(task)
        return try {
            pool.execute(handle)
            handle
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    /** 当前排队深度（诊断用）。 */
    fun queuedCount(): Int = pool.queue.size

    /** 当前活跃线程数（诊断用）。 */
    fun activeCount(): Int = pool.activeCount

    /** 线程名前缀（诊断与"池分离"断言用）。 */
    fun threadPrefix(): String = THREAD_WORKER

    /** 停止：打断在途子任务（停机协议里与 reload 池同批）。 */
    fun shutdownNow() {
        runCatching { pool.shutdownNow() }
    }

    private fun newPool(capacity: Int): ThreadPoolExecutor {
        val threads = AtomicInteger(0)
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "$THREAD_WORKER-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        return ThreadPoolExecutor(
            size,
            size,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(capacity.coerceAtLeast(size)),
            factory,
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    companion object {

        /** worker 线程名前缀（协调池是 `serverprobe-reload`，**不带** `-worker`）。 */
        const val THREAD_WORKER = "serverprobe-reload-worker"

        /** 每个并发位预留的排队许可数。 */
        private const val PERMITS_PER_WORKER = 32

        /** 空闲线程回收时长（秒）：不给探针留常驻线程。 */
        private const val IDLE_SECONDS = 60L
    }
}
