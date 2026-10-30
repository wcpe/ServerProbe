package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [ReloadWorkerPool] 单元测试（配置重载规格 §3.1 + §3.8）。
 *
 * ## 为什么值得单独钉
 * 这个池子只有一百行，但它同时掺了两个**架构决策**，两者都没有编译期保证：
 * 1. **有界队列 + `AbortPolicy`**：让"根本没进队列"与"排队没轮到"可区分（回执分别是
 *    `INTERNAL_ERROR` 与 `TIMEOUT`）。若有人换成无界队列，满永远不会发生，
 *    `INTERNAL_ERROR` 分支就成了死代码，运维看到的全是超时，摸不到真正原因。
 * 2. **拒绝时 `submit` 返回 null 而不抛**：`submit` 从协调线程调用，一旦抛 `RejectedExecutionException`
 *    就会穿透协调器、把"池满"变成一个没人预期的运行期异常；改为返回 null 后，
 *    降级动作（回写 `INTERNAL_ERROR`）是显式写在协调器里的。
 *
 * ## 如何做到不 sleep
 * 队列深度不能靠"提交后等一会儿"来观察（那是最典型的 flaky 来源）。这里用
 * **受控阻塞任务**把并发位占死：`ThreadPoolExecutor.execute` 在创建核心线程时就已递增
 * worker 计数，因此第一个任务必进线程、后续任务必进队列——队列满与拒绝都是**确定**的，
 * 用 latch 只等"任务真的开始执行"这一件事（而不用猜时长）。
 */
class ReloadWorkerPoolTest {

    private val pools = ArrayList<ReloadWorkerPool>()

    @AfterEach
    fun tearDown() {
        pools.forEach { runCatching { it.shutdownNow() } }
        pools.clear()
    }

    /** 建池并纳入统一停机（避免卡死的任务把测试 JVM 拖住）。 */
    private fun pool(workerPoolSize: Int, queueCapacity: Int? = null): ReloadWorkerPool =
        (if (queueCapacity == null) {
            ReloadWorkerPool(workerPoolSize)
        } else {
            ReloadWorkerPool(workerPoolSize, queueCapacity)
        }).also { pools.add(it) }

    /**
     * **核心不变量**：队列满时 `submit` 返回 null 且**不抛**。
     *
     * 这是"拒绝必须被看见、但绝不把异常抛给读/协调线程"的落点。
     * 用例用并发位 1 + 队列容量 2：占住唯一线程后恰好堆满 2 个排队位，
     * 第 4 个任务必然被 `AbortPolicy` 拒。
     */
    @Test
    fun `队列满时submit返回null且不抛`() {
        val worker = pool(workerPoolSize = 1, queueCapacity = 2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val blocker = worker.submit { blockingTask(started, release) }
        assertNotNull(blocker, "第一个任务应进核心线程（此时队列为空）")
        assertTrue(started.await(5, TimeUnit.SECONDS), "占位任务应开始执行")

        val queued1 = worker.submit { SUCCESS }
        val queued2 = worker.submit { SUCCESS }
        assertNotNull(queued1, "队列容量 2，第一个排队任务应受理")
        assertNotNull(queued2, "队列容量 2，第二个排队任务应受理")

        // 到这里：线程被占死 + 队列已满 → 下一个必须被拒。
        val rejected = worker.submit { SUCCESS }

        assertNull(rejected, "队列满时 submit 必须返回 null（而不是抛 RejectedExecutionException）")

        release.countDown()
        assertNotNull(blocker!!.awaitSlice(5_000), "占位任务应能正常落定")
        assertNotNull(queued1!!.awaitSlice(5_000), "排队任务应在释放后执行完")
        assertNotNull(queued2!!.awaitSlice(5_000), "排队任务应在释放后执行完")
    }

    /**
     * 队列满了之后重新空出容量，`submit` 又能拿到句柄（拒绝是瞬态的，不是永久置位）。
     *
     * 反面实现（如拒绝后用标志位永久拒新）在这里会露馅。
     */
    @Test
    fun `拒绝是瞬态的容量空出后可重新提交`() {
        val worker = pool(workerPoolSize = 1, queueCapacity = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val blocker = worker.submit { blockingTask(started, release) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "占位任务应开始执行")
        val queued = worker.submit { SUCCESS }
        assertNotNull(queued)
        assertNull(worker.submit { SUCCESS }, "前提:队列已满应被拒")

        release.countDown()
        assertNotNull(blocker!!.awaitSlice(5_000))
        assertNotNull(queued!!.awaitSlice(5_000))

        val reaccepted = worker.submit { SUCCESS }
        assertNotNull(reaccepted, "容量空出后必须能重新受理")
        assertNotNull(reaccepted!!.awaitSlice(5_000), "重新受理的任务应真的跑完")
        assertEquals(0, worker.queuedCount(), "任务落定后不应再有人排队")
    }

    /**
     * `queuedCount()` / `activeCount()` 如实反映池内状态：占位任务在跑、两个任务在等。
     *
     * 这两个计数是"回执说排队没轮到"时的唯一诊断入口，算错就等于诊断失真。
     */
    @Test
    fun `排队与活跃计数反映池内状态`() {
        val worker = pool(workerPoolSize = 1, queueCapacity = 2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        worker.submit { blockingTask(started, release) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "占位任务应开始执行")
        worker.submit { SUCCESS }
        worker.submit { SUCCESS }

        assertEquals(2, worker.queuedCount(), "两个任务在排队")
        assertEquals(1, worker.activeCount(), "唯一并发位正在执行占位任务")

        release.countDown()
    }

    /**
     * 池关闭后 `submit` 同样返回 null（而不是抛）。
     *
     * 停机协议里 worker 池与 reload 池同批 `shutdownNow`，此时如果还有请求到达，
     * 协调器需要把"未执行"写进回执——那条路径靠的就是返回 null。
     */
    @Test
    fun `关闭后submit返回null`() {
        val worker = pool(workerPoolSize = 2)

        worker.shutdownNow()

        assertNull(worker.submit { SUCCESS }, "池已关闭时 submit 必须返回 null（不得抛）")
    }

    /**
     * 任务体抛 `Throwable` 不打死池线程：句柄记下异常，池仍能接新任务。
     *
     * 监听器是插件代码，抛 `Error` 也不奇怪。若让异常逃出 `run()`，池线程会死掉、
     * 并发位永久减一——而且只在「某插件崩过一次」之后才发作，极难定位。
     */
    @Test
    fun `任务抛异常不打死池线程`() {
        val worker = pool(workerPoolSize = 1)

        val broken = worker.submit { error("监听器崩了") }
        assertNotNull(broken, "任务应被受理")
        val brokenOutcome = broken!!.awaitSlice(5_000)
        assertNotNull(brokenOutcome, "抛异常的任务也必须落定（不能一直未完成）")
        assertNotNull(brokenOutcome!!.error, "异常应被记录到句柄上")
        assertNull(brokenOutcome.outcome, "异常退出时没有结果")

        // 池仍可用:能再跑一个正常任务（如果池线程被打死，这里会卡到超时）。
        val next = worker.submit { SUCCESS }
        assertNotNull(next, "池线程不得因上一个任务抛异常而减员")
        val nextOutcome = next!!.awaitSlice(5_000)
        assertNotNull(nextOutcome?.outcome, "后续任务应正常执行并返回结果")
    }

    /**
     * worker 线程为 **daemon** 且名字带 `-worker` 前缀。
     *
     * - daemon：卡死的监听器回调不得阻止 JVM 退出；
     * - 前缀：与协调池 `serverprobe-reload` 区分开，是"两池确实分离"的可见证据。
     */
    @Test
    fun `worker线程为守护线程且带前缀`() {
        val worker = pool(workerPoolSize = 1)
        val captured = AtomicReference<Thread?>(null)

        val handle = worker.submit {
            captured.set(Thread.currentThread())
            SUCCESS
        }
        assertNotNull(handle!!.awaitSlice(5_000), "任务应执行完")

        val thread = captured.get()
        assertNotNull(thread, "任务体应被执行过")
        assertTrue(thread!!.isDaemon, "worker 线程必须是 daemon，否则卡死的回调会阻止 JVM 退出")
        assertTrue(
            thread.name.startsWith(ReloadWorkerPool.THREAD_WORKER),
            "线程名应以 ${ReloadWorkerPool.THREAD_WORKER} 开头（与协调池区分），实际=${thread.name}",
        )
        assertEquals(ReloadWorkerPool.THREAD_WORKER, worker.threadPrefix(), "诊断用前缀需与线程名一致")
    }

    /**
     * `queueCapacity = 0` 时按并发位兜底，而不是建池直接失败。
     *
     * 配置项写错（或未配）不应该让重载功能在启动时就炸——有界容量退一步变成
     * "每个并发位至少 1 个排队位"，依旧有界、依旧可区分拒绝与超时。
     */
    @Test
    fun `队列容量零时按并发位兜底`() {
        val worker = pool(workerPoolSize = 1, queueCapacity = 0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        worker.submit { blockingTask(started, release) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "占位任务应开始执行")

        assertNotNull(worker.submit { SUCCESS }, "兜底容量至少 1，应能再排一个")
        assertNull(worker.submit { SUCCESS }, "但依旧有界:第三个必被拒")

        release.countDown()
    }

    /**
     * 一个会卡住的占位任务：先 `started.countDown()` 报告"我已经开始"，再等 `release` 放行。
     *
     * 这是不用 `Thread.sleep` 就能确定性地"占死并发位并堆满队列"的手段。
     */
    private fun blockingTask(started: CountDownLatch, release: CountDownLatch): DispatchOutcome {
        started.countDown()
        runCatching { release.await(5, TimeUnit.SECONDS) }
        return SUCCESS
    }

    private companion object {
        /** 无侧效应的成功结果。 */
        val SUCCESS = DispatchOutcome(emptyList())
    }
}
