package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainStatus
import top.wcpe.mc.plugin.serverprobe.api.bridge.FailureInfo
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.core.bridge.health.HealthProbeRunner
import top.wcpe.mc.plugin.serverprobe.core.bridge.health.HealthSnapshot
import top.wcpe.mc.plugin.serverprobe.core.bridge.health.HealthTextSanitizer
import top.wcpe.mc.plugin.serverprobe.core.bridge.health.ProbeOutcome
import top.wcpe.mc.plugin.serverprobe.core.config.HealthSettings
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfigBridgeConfigSource
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * 业务域健康状态缓存（Provider 健康规格 §3.3）。
 *
 * ## 为什么是"缓存 + 独立刷新"，而不是查询时直调
 * `core.business_status` 跑在命令池上，而 `health()` 是第三方代码。在查询路径上同步调用它，
 * 一个卡 30s 的 `health()` 就会占住命令池的一个槽——而查询自身已占一个槽，
 * 两个慢域足以让整条命令通道饱和。因此：
 *
 * - **查询只读本类的内存快照**（[viewOf] / [snapshot]），O(域数)、无 IO、不派发；
 * - **Provider 只在刷新线程上被调用**：[sweepLoop]（`serverprobe-health`）决定查谁、
 *   施加超时、归类与清理；[HealthProbeRunner]（`serverprobe-health-probe`）真正进第三方代码。
 *   两者的线程名都不是 `serverprobe-cmd`，这本身就是"没在命令线程跑 Provider"的可断言证据。
 *
 * ## 为什么两个线程
 * 一个线程无法给自己发起的调用设上限。超时由调度线程的 `future.get(budget)` 施加，
 * 执行线程只负责跑 `health()`。
 *
 * ## 有界性与已知上限
 * probe 池单线程 + 队列容量 1 + `AbortPolicy`：某 Provider 的 `health()` 不可中断地卡住后，
 * 唯一 probe 线程被永久占住，后续提交**立刻被拒** → [HealthSnapshot.stalled] 置位、WARN 一次、
 * 本轮剩余域跳过刷新（保留上次缓存，查询仍成功）。代价是"其它域的健康值不再新鲜"，
 * 收益是**线程数恒定、不会泄漏、命令通道完全不受影响**——这正是本设计要解决的问题本身。
 * Java 无法强杀线程，这是唯一诚实的处理方式。
 *
 * ## 阈值注入
 * 所有阈值来自 [HealthSettings]，经 [settingsProvider] 现读（默认读 `bridge.health.*`，
 * 配置重载后无需重建）。单测直接替换 [settingsProvider] 与 [clock] 为极端小值/假时钟，
 * **不读 `ProbeConfig`**（core 单测 classpath 无 `basic-configuration`，`conf` 是 `lateinit`）。
 */
@Service
class ProviderHealthRegistry {

    /** 阈值来源；单测可替换为固定值（生产默认读 `bridge.health.*`）。 */
    @Volatile
    internal var settingsProvider: () -> HealthSettings = { ProbeConfigBridgeConfigSource.health() }

    /** 时钟（毫秒）；单测可替换为假时钟以精确控制保留窗口与隔离窗口。 */
    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    /** WARN 出口；单测可替换以断言"只告警一次"。 */
    @Volatile
    internal var warn: (String) -> Unit = ProbeLogger::warn

    /** 域 → 条目。 */
    private val entries = ConcurrentHashMap<String, Entry>()

    /** probe 执行器（唯一调用 `health()` 的线程）。 */
    private val runner = HealthProbeRunner { current() }

    /** 保护 [pending] 与唤醒信号。 */
    private val lock = ReentrantLock()

    /** 被 [requestRefresh] 置位、等待下一轮 sweep 的域（合并去重）。 */
    private val pending = LinkedHashSet<String>()

    /** 等待-唤醒条件。 */
    private val wakeSignal = lock.newCondition()

    /** 调度线程是否在运行。 */
    @Volatile
    private var running = false

    /** 调度线程句柄。 */
    @Volatile
    private var schedulerThread: Thread? = null

    /** 是否正有一轮刷新在进行（回执字段）。 */
    @Volatile
    private var refreshInFlight = false

    /** probe 池是否已停滞（回执字段）。 */
    @Volatile
    private var stalled = false

    /** 最近一轮 sweep 的开始时刻。 */
    @Volatile
    private var lastSweepAtMs = 0L

    /** 最近一轮被跳过的域。 */
    @Volatile
    private var skippedThisSweep: List<String> = emptyList()

    /** 停滞告警去重标记（只 WARN 一次，避免每轮刷屏）。 */
    private val stalledWarned = AtomicBoolean(false)

    /**
     * 启动 `serverprobe-health` 与 `serverprobe-health-probe`。幂等。
     *
     * 启动后若已有待刷新域（例如 Provider 先于桥注册），第一轮 sweep 立即执行，
     * 不必等满一个周期——否则 Provider 的健康值会白白空白 `refresh-interval-ms`。
     */
    fun start() {
        if (running) return
        running = true
        runner.start()
        schedulerThread = Thread({ sweepLoop() }, THREAD_SCHEDULER).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * `shutdownNow` 两个线程（停机协议 §3.7，与 reload 池同批）。幂等。
     *
     * 打断是安全的：`health()` 是只读上报，无副作用，且停用时本就不该再进第三方代码。
     * 若某 Provider 的 `health()` 不可中断地卡住，probe 线程会残留——这是已登记的取舍
     * （daemon 线程不阻止 JVM 退出）。
     */
    fun stop() {
        running = false
        // 先唤醒调度线程，让它从 awaitNanos 或 future.get 里出来。
        lock.lock()
        try {
            wakeSignal.signalAll()
        } finally {
            lock.unlock()
        }
        val thread = schedulerThread
        thread?.interrupt()
        if (thread != null) runCatching { thread.join(JOIN_TIMEOUT_MS) }
        schedulerThread = null
        runner.stop()
    }

    /**
     * Provider 注册时调用：初始化条目、缓存 manifest 指纹、请求一次刷新（规格 §3.6 / §3.1）。
     *
     * 指纹由调用方算好再传进来，而不是在这里调 `provider.manifest()`：
     * 注册路径（[BusinessHostImpl.register]）已经为了注册期校验取过一次清单，
     * 再取一次等于让 Provider 的 `manifest()` 被调两遍——而那是第三方代码，可能是几毫秒的现算。
     * 指纹计算本身是纯内存操作（几微秒），由持有清单的那一方顺手算完，语义与开销都更清楚。
     *
     * @param domain 业务域
     * @param provider 刚注册的实例
     * @param digest 该域 manifest 的内容指纹；null 表示无可比对指纹（旧契约 / 清单不可用）
     */
    fun attach(domain: String, provider: BusinessProvider, digest: String? = null) {
        if (domain.isEmpty()) return
        // 必须在 compute 内一次性完成「创建 + 初始化」：computeIfAbsent 会把尚未 reset 的
        // Entry 先发布进 map，此刻它的 registered=false、unregisteredAtMs=0，而 sweep 的
        // 过期判定 now - unregisteredAtMs() > retainAfterUnregisterMs 对 0 恒成立——
        // 刚注册的域会被当成"早已注销"直接删掉，reset 之后改的只是一个已脱离 map 的孤儿。
        // 下面的 requestRefresh 会立刻唤醒 sweep 线程，所以这个窗口被命中的概率很高。
        entries.compute(domain) { _, existing -> (existing ?: Entry()).also { it.reset(provider, digest) } }
        requestRefresh(domain)
    }

    /**
     * Provider 注销时调用：**按实例比对**，仅当当前域仍指向该实例才标记为「已注销」。
     *
     * 与 [BusinessHost.unregister] 同语义：插件热重载后旧实例的卸载回调绝不能抹掉新注册。
     *
     * @param domain 业务域
     * @param provider 发起注销的实例
     */
    fun detach(domain: String, provider: BusinessProvider) {
        val entry = entries[domain] ?: return
        // 实例比对：不是当前注册者则忽略（陈旧回调保护）。
        if (entry.providerOrNull() !== provider) return
        entry.markUnregistered(clock())
    }

    /**
     * dispatch 完成后记录一次结果。
     *
     * **纯内存、O(1)、不阻塞、不抛**：它跑在业务池线程上，一个卡顿就会拖慢业务回执本身。
     * 失败原因在**记录时**即脱敏并限长（规格 §6 风险 3：否则注册表会长期持有 Provider 返回的原串）。
     *
     * 本方法刻意只认 [BridgeCommandResult] 这一种入参：健康缓存关心的是「成功与否 + 失败原因」，
     * 而的 [ActionResult] 到这两个字段的折算由调用方（`BusinessHostImpl`）完成——
     * 在这里再加一个重载只会让本类多一个函数，却不多一点语义。
     *
     * @param domain 业务域
     * @param result 该次派发结果
     * @param costMs 该次派发耗时（毫秒）
     */
    // 必须广捕 Throwable：契约要求"纯内存、不抛"，而 `current()` 在配置未注入时会抛
    // UninitializedPropertyAccessException（运行期 RuntimeException），
    // 漏出去会让业务回执被健康统计连带打死。
    @Suppress("TooGenericExceptionCaught")
    fun recordDispatch(domain: String, result: BridgeCommandResult, costMs: Long) {
        try {
            if (domain.isEmpty()) return
            val entry = entries[domain] ?: return
            val settings = current()
            entry.record(
                success = result.success,
                rawMessage = result.error,
                messageOf = { reason -> HealthTextSanitizer.sanitize(reason, settings.reasonMaxChars) },
                costMs = costMs,
                nowMs = clock(),
            )
        } catch (_: Throwable) {
            // 契约要求永不抛：健康统计坏掉绝不能影响业务回执。
        }
    }

    /**
     * 请求尽快刷新某域；合并去重。
     *
     * **纯内存、O(1)、不阻塞、不抛**：只置位 + 唤醒，绝不等待刷新完成。
     *
     * @param domain 业务域
     */
    fun requestRefresh(domain: String) {
        if (domain.isEmpty()) return
        lock.lock()
        try {
            pending.add(domain)
            // 唤醒条件用 signal 而非 signalAll：只有调度线程在等。
            wakeSignal.signal()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 单域视图；**只读缓存，不做 IO、不调 Provider**。
     *
     * @param domain 业务域；未注册的域返回 `registered=false` 的快照，不报错
     * @return 不可变快照，永不为 null
     */
    fun viewOf(domain: String): DomainStatus {
        val entry = entries[domain]
            ?: return unregistered(domain, REASON_NOT_REGISTERED)
        return entry.status(domain, clock())
    }

    /**
     * 全量视图；**只读缓存**。
     *
     * @return 不可变快照，永不为 null
     */
    fun snapshot(): HealthSnapshot {
        val now = clock()
        val all = entries.entries.toList()
        val ordered = orderedDomains(all)
        val quarantined = all.filter { it.value.isQuarantined(now) }.map { it.key }.sorted()
        return HealthSnapshot(
            domains = ordered.map { (domain, entry) -> entry.status(domain, now) },
            refreshInFlight = refreshInFlight,
            stalled = stalled,
            quarantined = quarantined,
            lastSweepAtMs = lastSweepAtMs,
            skippedThisSweep = skippedThisSweep,
        )
    }

    /**
     * 本进程内已创建的刷新线程名。
     *
     * 两个线程在 [start] 时**即**创建（probe 池预启动核心线程），因此长度恒为 2、
     * 且**不随 sweep 轮次增长**——这正是"Provider 卡死不泄漏线程"的可断言证据。
     */
    fun threadNames(): List<String> = listOf(THREAD_SCHEDULER, runner.threadName())

    /** 调度循环：有界等待 → 一轮 sweep，直到 [stop]。 */
    private fun sweepLoop() {
        while (running) {
            val periodic = awaitNext()
            if (!running) return
            runSweep(periodic)
        }
    }

    /**
     * 等待下一次 sweep。
     *
     * @return true 表示周期到期（应刷新全部已注册域）；false 表示被 [requestRefresh] 提前唤醒
     *   （只刷新被置位的域），或线程被中断。
     */
    private fun awaitNext(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(current().refreshIntervalMs.coerceAtLeast(1L))
        lock.lock()
        try {
            // 已有待刷新域则不必等：启动期注册的 Provider 应立刻拿到第一份健康值。
            while (running && pending.isEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return true
                wakeSignal.awaitNanos(remaining)
            }
            return false
        } catch (_: InterruptedException) {
            // 恢复中断标志：否则停机后线程无法正常退出。
            Thread.currentThread().interrupt()
            return false
        } finally {
            lock.unlock()
        }
    }

    /**
     * 执行一轮 sweep：逐域刷新 → 清理。
     *
     * **任何分支都不抛**；某域失败（超时/异常/null）绝不影响本轮其它域。
     *
     * @param periodic true=周期到期（刷新全部已注册域）；false=只刷新被请求的域。
     */
    private fun runSweep(periodic: Boolean) {
        val skipped = ArrayList<String>()
        refreshInFlight = true
        // 每轮重置停滞标记：probe 恢复后应自动回到正常，否则一次卡死会永久显示 stalled。
        stalled = false
        try {
            val targets = candidatesOf(periodic)
            lastSweepAtMs = clock()
            var blocked = false
            for (domain in targets) {
                if (!running) break
                if (blocked) {
                    // probe 已停滞：本轮剩余域直接跳过，保留上次缓存（查询仍成功）。
                    skipped.add(domain)
                } else if (refreshOne(domain) == RefreshState.REJECTED) {
                    // 被拒的这一域本身也没刷新成功，必须一并计入 skipped——
                    // 否则回执只列"后面的域"，运维会以为被拒的那个域刚刚刷新过。
                    skipped.add(domain)
                    blocked = true
                }
            }
            sweepExpired()
        } finally {
            skippedThisSweep = skipped
            refreshInFlight = false
        }
    }

    /** 本轮要刷新的域（已排好序、已剔除隔离窗口内的域）。 */
    private fun candidatesOf(periodic: Boolean): List<String> {
        val requested = LinkedHashSet<String>()
        lock.lock()
        try {
            requested.addAll(pending)
            pending.clear()
        } finally {
            lock.unlock()
        }
        val due = if (periodic) requested + entries.keys else requested
        val now = clock()
        return due.filter { domain ->
            // 已注销的域不再刷新；隔离窗口内的域不重复打扰 Provider（直接沿用上次结论）。
            entries[domain]?.let { it.isRegistered() && !it.isQuarantined(now) } == true
        }.sorted()
    }

    /** 刷新单域；返回 [RefreshState.REJECTED] 表示 probe 已被占住。 */
    private fun refreshOne(domain: String): RefreshState {
        val entry = entries[domain] ?: return RefreshState.DONE
        val provider = entry.providerOrNull() ?: return RefreshState.DONE
        if (!entry.isRegistered()) return RefreshState.DONE
        return when (val outcome = runner.probe(provider)) {
            is ProbeOutcome.Reported -> {
                entry.applyReported(outcome.health, clock(), current())
                RefreshState.DONE
            }
            is ProbeOutcome.Failed -> {
                entry.applyDegraded(outcome.reason, clock(), current(), outcome.quarantine)
                RefreshState.DONE
            }
            ProbeOutcome.Rejected -> {
                stalled = true
                if (stalledWarned.compareAndSet(false, true)) {
                    warn(
                        "Provider 健康刷新停滞:probe 线程被占住,后续刷新已跳过(其余域沿用上次缓存;" +
                            "已知上限,见规格 §6 风险 2)",
                    )
                }
                RefreshState.REJECTED
            }
            // 停机中断：本轮直接结束，不当作 Provider 失败。
            ProbeOutcome.Interrupted -> RefreshState.REJECTED
        }
    }

    /**
     * 清理：注销超保留窗口的条目整条删除；超上限时按"已注销且 `refreshedAtMs` 最旧"淘汰。
     *
     * **已注册条目永不淘汰**，即使因此短暂超上限——淘汰在册域会让 JM 误以为域消失了。
     */
    private fun sweepExpired() {
        val now = clock()
        val retainMs = current().retainAfterUnregisterMs
        entries.entries.toList().forEach { (domain, entry) ->
            if (!entry.isRegistered() && now - entry.unregisteredAtMs() > retainMs) {
                entries.remove(domain, entry)
            }
        }
        evictOverflow()
    }

    /** 超上限裁剪（只淘汰已注销条目）。 */
    private fun evictOverflow() {
        val maxEntries = current().maxEntries
        var overflow = entries.size - maxEntries
        if (overflow <= 0) return
        val victims = entries.entries
            .filter { !it.value.isRegistered() }
            .sortedBy { it.value.refreshedAtMs() }
        for (victim in victims) {
            if (overflow <= 0) return
            if (entries.remove(victim.key, victim.value)) overflow--
        }
    }

    /** 稳定顺序：已注册在前，其次各按 domain 字典序。 */
    private fun orderedDomains(all: List<Map.Entry<String, Entry>>): List<Pair<String, Entry>> {
        val registered = all.filter { it.value.isRegistered() }.sortedBy { it.key }
        val unregistered = all.filterNot { it.value.isRegistered() }.sortedBy { it.key }
        return (registered + unregistered).map { it.key to it.value }
    }

    /** 现读阈值；读不到（配置未就绪）时退回默认值，调度线程绝不因此挂掉。 */
    private fun current(): HealthSettings = runCatching { settingsProvider() }.getOrDefault(FALLBACK_SETTINGS)

    /** 未注册域的降级快照。 */
    private fun unregistered(domain: String, reason: String): DomainStatus = DomainStatus(
        domain,
        false,
        ProviderHealth.unknown(),
        reason,
        0L,
        null,
        0L,
        0L,
        0L,
        false,
        null,
        0L,
        //：未注册本身就是降级原因，`degradedReason` 与 `reasonOverride` 同值，
        // 否则面板读到「不可用但没说为什么」。
        reason,
    )

    /** 一轮中单域的刷新出口。 */
    private enum class RefreshState { DONE, REJECTED }

    /**
     * 单域条目（可变的内部状态；对外一律经 [status] 投影成不可变快照）。
     *
     * 用 `@Volatile` 字段而非整块对象替换：`attach`/`detach`/`recordDispatch` 与刷新线程
     * 会并发写同一域的不同字段，整块替换需要无锁 CAS 且会产生大量短命对象；
     * 这里的字段彼此独立（计数、时间戳、健康值），`@Volatile` 已足以保证可见性。
     */
    private class Entry {

        /** 当前注册的 Provider 实例；已注销时为最后一次注册者。 */
        @Volatile
        private var provider: BusinessProvider? = null

        @Volatile
        private var registered = false

        @Volatile
        private var unregisteredAtMs = 0L

        @Volatile
        private var health: ProviderHealth = ProviderHealth.unknown()

        @Volatile
        private var refreshedAtMs = 0L

        @Volatile
        private var lastFailure: FailureInfo? = null

        @Volatile
        private var consecutiveFailures = 0

        @Volatile
        private var dispatchTotal = 0L

        @Volatile
        private var dispatchFailed = 0L

        @Volatile
        private var lastCostMs = 0L

        /** manifest 内容指纹：**注册时**算一次并缓存，查询路径永不重算。 */
        @Volatile
        private var manifestDigest: String? = null

        /** 最近一次派发的**完成**时刻；0=本进程内从未派发过。 */
        @Volatile
        private var lastDispatchAtMs = 0L

        /** 隔离截止时刻；`<= now` 表示不在隔离窗口内。 */
        @Volatile
        private var quarantinedUntilMs = 0L

        /** 重新注册：初始化计数、清掉旧 Provider 的结论（健康值、失败、隔离），并缓存新的指纹。 */
        fun reset(newProvider: BusinessProvider, digest: String?) {
            provider = newProvider
            registered = true
            unregisteredAtMs = 0L
            health = ProviderHealth.unknown()
            refreshedAtMs = 0L
            lastFailure = null
            consecutiveFailures = 0
            dispatchTotal = 0L
            dispatchFailed = 0L
            lastCostMs = 0L
            manifestDigest = digest
            lastDispatchAtMs = 0L
            quarantinedUntilMs = 0L
        }

        /** 标记已注销并记录时刻（条目保留 `retainAfterUnregisterMs` 供查询）。 */
        fun markUnregistered(nowMs: Long) {
            registered = false
            unregisteredAtMs = nowMs
        }

        fun providerOrNull(): BusinessProvider? = provider

        fun isRegistered(): Boolean = registered

        fun isQuarantined(nowMs: Long): Boolean = nowMs < quarantinedUntilMs

        fun unregisteredAtMs(): Long = unregisteredAtMs

        fun refreshedAtMs(): Long = refreshedAtMs

        /**
         * 记录一次 dispatch 结果（成功即清零连续失败）。
         *
         * @param rawMessage 失败原因原文（来自 Provider/桥，可能含凭据）
         * @param messageOf 原文 → 入库文本的加工器（脱敏 + 限长），**只在失败时求值**
         */
        fun record(success: Boolean, rawMessage: String, messageOf: (String) -> String, costMs: Long, nowMs: Long) {
            dispatchTotal += 1
            lastCostMs = costMs
            //：`lastDispatchAt` 是**完成**时刻，因此在收尾处写，不在进入时写。
            lastDispatchAtMs = nowMs
            if (success) {
                consecutiveFailures = 0
                lastFailure = null
                return
            }
            dispatchFailed += 1
            consecutiveFailures += 1
            val message = if (rawMessage.isEmpty()) DEFAULT_FAILURE_MESSAGE else rawMessage
            lastFailure = FailureInfo(messageOf(message), nowMs, consecutiveFailures)
        }

        /** 存入 Provider 自报值（**存储时即脱敏与限长**，规格 §6 风险 3）。 */
        fun applyReported(reported: ProviderHealth, nowMs: Long, settings: HealthSettings) {
            health = ProviderHealth.of(
                reported.status,
                HealthTextSanitizer.sanitize(reported.reason, settings.reasonMaxChars),
                HealthTextSanitizer.truncateOnly(reported.detailJson, settings.detailMaxChars),
                reported.sinceMs,
            )
            refreshedAtMs = nowMs
            // 成功取到值即解除隔离：Provider 已能正常回答，没有继续隔离的理由。
            quarantinedUntilMs = 0L
        }

        /** 存入探针替 Provider 下的结论（超时 / 抛异常 / 返回 null）。 */
        fun applyDegraded(reason: String, nowMs: Long, settings: HealthSettings, quarantine: Boolean) {
            health = ProviderHealth.of(
                ProviderHealth.Status.DEGRADED,
                HealthTextSanitizer.sanitize(reason, settings.reasonMaxChars),
                "",
                0L,
            )
            refreshedAtMs = nowMs
            if (quarantine) quarantinedUntilMs = nowMs + settings.quarantineMs
        }

        /** 投影成不可变快照。 */
        fun status(domain: String, nowMs: Long): DomainStatus {
            val override = when {
                registered -> ""
                unregisteredAtMs > 0L -> REASON_UNREGISTERED
                else -> REASON_NOT_REGISTERED
            }
            //：降级原因的**呈现口径**只有一处——探针补充优先，退而用 Provider 自报，
            // 两者都为空时为 null（"健康"）。让面板只看一个字段就能决定画不画红点。
            val degraded = override.ifEmpty { health.reason }.ifEmpty { null }
            return DomainStatus(
                domain,
                registered,
                health,
                override,
                refreshedAtMs,
                lastFailure,
                dispatchTotal,
                dispatchFailed,
                lastCostMs,
                isQuarantined(nowMs),
                manifestDigest,
                lastDispatchAtMs,
                degraded,
            )
        }
    }

    companion object {

        /** 调度线程名（周期 sweep + 清理）。 */
        const val THREAD_SCHEDULER = "serverprobe-health"

        /** 调度线程停机 join 上限（毫秒）。 */
        private const val JOIN_TIMEOUT_MS = 3_000L

        /** 未注册域的原因。 */
        const val REASON_NOT_REGISTERED = "Provider 未注册"

        /** 已注销（仍在保留窗口内）的原因。 */
        const val REASON_UNREGISTERED = "Provider 已注销"

        /** 兜底失败文本（Provider 确实没给出原因时用，避免回执里出现空 message）。 */
        private const val DEFAULT_FAILURE_MESSAGE = "业务执行失败(Provider 未给出原因)"

        /** 读不到配置时的兜底阈值。 */
        private val FALLBACK_SETTINGS = HealthSettings()
    }
}
