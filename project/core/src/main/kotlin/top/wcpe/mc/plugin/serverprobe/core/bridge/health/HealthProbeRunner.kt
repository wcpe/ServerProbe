package top.wcpe.mc.plugin.serverprobe.core.bridge.health

import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.core.config.HealthSettings
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 一次 probe 的结果归类（规格 §3.2 的三条归类 + 两种控制流出口）。
 *
 * 用密封类而非 `ProviderHealth` 直传：调用方必须区分"Provider 说了什么"与"Provider 没说话，
 * 是探针替它下的结论"，否则无法决定要不要隔离该域。
 */
internal sealed class ProbeOutcome {

    /** Provider 正常返回了自报值。 */
    class Reported(val health: ProviderHealth) : ProbeOutcome()

    /**
     * 探针替 Provider 下的结论（超时 / 抛异常 / 返回 null）。
     *
     * @property reason 已带明确前缀的可读原因；存储时再做脱敏与限长。
     * @property quarantine 是否进入隔离窗口（超时与抛异常进入；返回 null 不进入——
     *   那次调用并没有占住 probe 线程）。
     */
    class Failed(val reason: String, val quarantine: Boolean) : ProbeOutcome()

    /** probe 线程被占住、提交被 `AbortPolicy` 拒绝。 */
    object Rejected : ProbeOutcome()

    /** 调度线程被中断（停机）。 */
    object Interrupted : ProbeOutcome()
}

/**
 * 唯一调用 `provider.health()` 的执行器（规格 §3.3 的 probe 池）。
 *
 * ## 为什么必须是独立单线程 + 有界队列
 * 一个线程无法给自己发起的调用设上限：超时只能由**另一个**线程（调度者）用
 * `future.get(budget)` 施加。故本类只负责"真正进第三方代码"，超时判定留给调用方。
 *
 * - 单线程 = 同时只有一个 `health()` 在跑，线程数恒定，Provider 卡死也不会泄漏线程
 *   （Java 无法强杀线程）。
 * - 队列容量 1 + `AbortPolicy`：卡死时后续提交**立刻**被拒，调用方能据此置 `stalled`
 *   并跳过本轮剩余域，而不是排队等一个已经不会回来的线程。
 *
 * 本类不自行判超时、不自行写缓存：它只把结果归成 [ProbeOutcome]，让调度逻辑集中在一处。
 */
internal class HealthProbeRunner(private val settings: () -> HealthSettings) {

    /** probe 池；`start()` 创建、`stop()` 置空（故停机后提交一律 [ProbeOutcome.Rejected]）。 */
    @Volatile
    private var pool: ThreadPoolExecutor? = null

    /** probe 线程句柄（停机时有界 join，验收要求无残留线程）。 */
    @Volatile
    private var workerThread: Thread? = null

    /** 启动 probe 池。幂等。 */
    fun start() {
        if (pool != null) return
        val created = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(PROBE_QUEUE_CAPACITY),
            ThreadFactory { runnable -> newWorker(runnable) },
            ThreadPoolExecutor.AbortPolicy(),
        )
        pool = created
        // 预启动核心线程：让"刷新线程已存在"在 start() 后立刻可观测，
        // 验收要求 threadNames() 恒为 2、不随轮次增长。
        created.prestartCoreThread()
    }

    /** `shutdownNow` probe 池并有界 join。幂等。 */
    fun stop() {
        val current = pool
        pool = null
        if (current != null) runCatching { current.shutdownNow() }
        val thread = workerThread
        workerThread = null
        if (thread != null) runCatching { thread.join(JOIN_TIMEOUT_MS) }
    }

    /**
     * 在 probe 线程上调用一次 `provider.health()`，并在当前线程（调度者）上有界等待。
     *
     * **绝不抛**：所有失败都归一为 [ProbeOutcome]。
     *
     * @return 归类结果，永不为 null。
     */
    fun probe(provider: BusinessProvider): ProbeOutcome {
        val executor = pool ?: return ProbeOutcome.Rejected
        val future = try {
            executor.submit(Callable { provider.health() })
        } catch (_: RejectedExecutionException) {
            // probe 线程被占住（或池已关闭）：这是"停滞"，不是该域的失败。
            return ProbeOutcome.Rejected
        }
        return await(future)
    }

    /** probe 线程名（诊断与验收用）。 */
    fun threadName(): String = THREAD_PROBE

    /**
     * 有界等待并归类。
     *
     * 三种归类严格按规格 §3.2：超时 → 隔离；抛异常 → 隔离（它也说明该域不可靠）；
     * 返回 null → 只降级不隔离（调用立刻返回，没有占住 probe 线程）。
     */
    // 必须广捕：`future.get` 除了声明内的两种受检异常，还可能抛 CancellationException，
    // 而 Provider 抛出的 Error 会经 ExecutionException 冒出来；契约要求"任何分支都不抛"。
    @Suppress("TooGenericExceptionCaught")
    private fun await(future: Future<ProviderHealth>): ProbeOutcome =
        try {
            val health = future.get(timeoutMs(), TimeUnit.MILLISECONDS)
            if (health == null) ProbeOutcome.Failed(REASON_NULL, quarantine = false) else ProbeOutcome.Reported(health)
        } catch (_: TimeoutException) {
            // 超时后必须 cancel：不 cancel 的话卡住的调用会继续占用 probe 线程。
            future.cancel(true)
            ProbeOutcome.Failed(REASON_TIMEOUT, quarantine = true)
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            ProbeOutcome.Interrupted
        } catch (e: ExecutionException) {
            ProbeOutcome.Failed(describe(e.cause), quarantine = true)
        } catch (e: Throwable) {
            ProbeOutcome.Failed(REASON_THROW_PREFIX + e.javaClass.simpleName, quarantine = true)
        }

    /** 把异常转成"类名 + 经脱敏的 message"（脱敏在存储时统一做）。 */
    private fun describe(cause: Throwable?): String {
        val type = cause?.javaClass?.simpleName ?: UNKNOWN_THROWABLE
        val message = cause?.message
        return if (message.isNullOrEmpty()) {
            REASON_THROW_PREFIX + type
        } else {
            REASON_THROW_PREFIX + type + "：" + message
        }
    }

    /** 单次 `health()` 的有界等待上限；读不到配置时用兜底值（调度线程绝不因此挂掉）。 */
    private fun timeoutMs(): Long =
        runCatching { settings().healthTimeoutMs }.getOrDefault(FALLBACK_TIMEOUT_MS).coerceAtLeast(MIN_TIMEOUT_MS)

    /** 创建 probe 线程（daemon：卡死的 Provider 不阻止 JVM 退出）。 */
    private fun newWorker(runnable: Runnable): Thread =
        Thread(runnable, THREAD_PROBE).apply { isDaemon = true }.also { workerThread = it }

    companion object {

        /** probe 线程名（**唯一**调用 `provider.health()` 的线程）。 */
        const val THREAD_PROBE = "serverprobe-health-probe"

        /** 超时归类的原因（与规格逐字一致）。 */
        const val REASON_TIMEOUT = "health() 超时"

        /** 返回 null 归类的原因。 */
        const val REASON_NULL = "health() 返回 null"

        /** 抛异常归类的原因前缀。 */
        const val REASON_THROW_PREFIX = "health() 抛出异常："

        /** 异常对象没有 simpleName 时的兜底名。 */
        private const val UNKNOWN_THROWABLE = "未知异常"

        /** probe 队列容量：1 表示"最多再排队一个"，多于一个就说明 probe 已经停了。 */
        private const val PROBE_QUEUE_CAPACITY = 1

        /** 停机 join 上限（毫秒）。 */
        private const val JOIN_TIMEOUT_MS = 3_000L

        /** 兜底超时（毫秒）：读不到配置时用它，避免 0 变成无界等待。 */
        private const val FALLBACK_TIMEOUT_MS = 1_000L

        /** 超时下限（毫秒）：配置成 0 会让 `future.get` 变成纯轮询。 */
        private const val MIN_TIMEOUT_MS = 1L
    }
}
