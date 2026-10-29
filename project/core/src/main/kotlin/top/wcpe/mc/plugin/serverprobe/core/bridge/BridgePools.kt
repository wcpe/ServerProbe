package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 桥的三池（线路治理规格 §3.1）。
 *
 * ## 为什么三个池
 * 桥是单连接承载全部流量,但三类工作的事故域完全不同:
 * - **命令**:治理指令与业务派发,必须低延迟;
 * - **事件**:量大且可分级丢弃,不能挤占命令;
 * - **重载**:最容易卡住(插件配置加载可能阻塞主线程),必须独立,
 *   否则一次卡死会占满命令池、连带治理指令全部排队。
 *
 * ## 全部有界 + AbortPolicy
 * - 有界:无界队列会把"没轮到执行"伪装成"执行超时",且积压能拖垮内存。
 * - `AbortPolicy`:**拒绝必须被看见**。不接受 `CallerRunsPolicy`——那会让提交方
 *   (桥读线程)亲自跑业务逻辑,等于把"单 Provider 卡死"升级成"读线程卡死"。
 * - 拒绝由 [BridgeKernel] 就地消化(转失败回执/计数丢弃),**绝不向上抛给读循环**
 *   (否则异常冒到 `runLoop` 的 catch 会主动断连,形成"池饱和 → 掉线 → 重连风暴")。
 *
 * ## daemon
 * 全部守护线程:卡死的任务不阻止 JVM 退出,核心线程超时回收避免常驻。
 *
 * 生命周期由创建方(桥装配处)统一 [shutdownNow]。
 */
class BridgePools(settings: LineSettings) {

    /** 命令执行池。 */
    val command: ThreadPoolExecutor = newPool(
        name = THREAD_CMD,
        size = settings.cmdPoolSize,
        capacity = settings.cmdQueueCapacity,
    )

    /** 事件发送池。 */
    val event: ThreadPoolExecutor = newPool(
        name = THREAD_EVENT,
        size = settings.eventPoolSize,
        capacity = settings.eventQueueCapacity,
    )

    /** 重载协调池(监听器子任务另走 worker 池,见重载规格)。 */
    val reload: ThreadPoolExecutor = newPool(
        name = THREAD_RELOAD,
        size = settings.reloadPoolSize,
        capacity = settings.reloadQueueCapacity,
    )

    /** 按目标池取执行器。 */
    fun of(kind: ExecutorKind): ThreadPoolExecutor = when (kind) {
        ExecutorKind.COMMAND -> command
        ExecutorKind.EVENT -> event
        ExecutorKind.RELOAD -> reload
    }

    /**
     * 投递一个任务到指定池。
     *
     * **不抛异常**:拒绝时返回 false,由调用方就地降级(生成失败回执或计数丢弃)。
     * 这样读线程无需 try/catch,也不会因池饱和断连。
     *
     * @param kind 目标池。
     * @param task 待执行任务。
     * @return true=已提交;false=被拒(池满或已关闭)。
     */
    fun submit(kind: ExecutorKind, task: () -> Unit): Boolean =
        try {
            of(kind).execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    /** 三池合计的排队深度(诊断用)。 */
    fun queuedOf(kind: ExecutorKind): Int = of(kind).queue.size

    /** 三池合计的活跃线程数(诊断用)。 */
    fun activeOf(kind: ExecutorKind): Int = of(kind).activeCount

    /** 停止全部池:不打断在途业务的办法见停机协议(命令池用 shutdown,重载池用 shutdownNow)。 */
    fun shutdown() {
        reload.shutdownNow()
        command.shutdown()
        event.shutdown()
    }

    /** 强制停止全部池。 */
    fun shutdownNow() {
        listOf(command, event, reload).forEach { runCatching { it.shutdownNow() } }
    }

    private fun newPool(name: String, size: Int, capacity: Int): ThreadPoolExecutor {
        val threads = AtomicInteger(0)
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "$name-${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        return ThreadPoolExecutor(
            size.coerceAtLeast(1),
            size.coerceAtLeast(1),
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(capacity.coerceAtLeast(1)),
            factory,
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    companion object {

        /** 线程名前缀:命令池。 */
        const val THREAD_CMD = "serverprobe-cmd"

        /** 线程名前缀:事件池。 */
        const val THREAD_EVENT = "serverprobe-event"

        /** 线程名前缀:重载协调池。 */
        const val THREAD_RELOAD = "serverprobe-reload"

        /** 空闲线程回收时长(秒):不给探针留常驻线程。 */
        private const val IDLE_SECONDS = 60L
    }
}
