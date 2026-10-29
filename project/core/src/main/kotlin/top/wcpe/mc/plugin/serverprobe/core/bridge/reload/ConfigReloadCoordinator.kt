package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadErrorCode
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadEvent
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.health.HealthTextSanitizer
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfigBridgeConfigSource
import top.wcpe.mc.plugin.serverprobe.core.config.ReloadSettings
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 配置重载编排器（配置重载规格 §3.1、§3.7~§3.9）。
 *
 * ## 一条命令的完整路径
 * ```
 * core.config_reload → reload 协调池 → reload(paths) → 组装回执 → FrameSender
 *                                        ├─ 1. 规范化 + 越界校验（PATH_ESCAPE）
 *                                        ├─ 2. 去重排序 + 查熔断（仅 TIMEOUT 记录）
 *                                        ├─ 3. 查在途（IN_FLIGHT / STUCK）
 *                                        ├─ 4. 匹配监听器（每次派发取声明快照）
 *                                        ├─ 5. 无人认领 → bestEffort（不调用任何人）
 *                                        ├─ 6. 有监听器 → 提交 reload-worker 池
 *                                        └─ 7. 轮转收割 → 组装回执
 * ```
 *
 * ## 三条不可妥协的设计
 * 1. **两池分离**：本类由 `serverprobe-reload` 池执行，子任务进 `serverprobe-reload-worker` 池
 *    （见 [ReloadWorkerPool] 的类注释：共用会池内自锁，轮转收割救不了）。
 * 2. **先全部提交、再统一收**：逐个"提交→等→再提交"会让总耗时等于各插件之和；
 *    批量提交后总耗时才是"最慢的那个"。
 * 3. **轮转收割**：先非阻塞收所有已落定项，再对未完成项按剩余预算分片（≤10ms）轮询；
 *    绝不按列表顺序长等第一个——那会让第一个卡住的项吃光预算，把后面早已完成的项
 *    也误判成 `TIMEOUT`。
 *
 * ## 绝不抛、绝不阻塞桥读线程
 * 本类每个分支都不抛异常：单路径失败只影响该路径。`release` 只由子任务自身 finally 触发
 * （见 [PathLeases]），协调器超时后**不代它释放**——否则第二份重载会与仍在跑的第一份
 * 并发写插件状态。
 *
 * @param runtime 三个表/池（worker 池、在途表、熔断表），构造口径见 [ReloadRuntime]。
 * @param listeners 监听器来源（注册表实现）。
 * @param settings 阈值来源（生产读 `bridge.reload.*`；单测注入极端值）。
 * @param clock 时钟（毫秒），单测可注入假时钟。
 */
// 本类刻意把编排写成线性流程（校验 → 熔断 → 在途 → 匹配 → 提交 → 收割 → 收口），
// 每一步已单独成方法；再拆成多个协作类会把"本次请求的中间状态"切成多份共享，
// 反而更难读。函数数因此略超阈值，只抑制该项。
@Suppress("TooManyFunctions")
class ConfigReloadCoordinator(
    private val runtime: ReloadRuntime,
    private val listeners: ReloadListenerSource,
    private val settings: () -> ReloadSettings = { ProbeConfigBridgeConfigSource.reload() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** WARN 出口；单测可替换以断言告警（沿用 [top.wcpe.mc.plugin.serverprobe.core.bridge.ProviderHealthRegistry] 的范式）。 */
    @Volatile
    internal var warn: (String) -> Unit = ProbeLogger::warn

    /**
     * 执行一次配置重载（**跑在 reload 协调池上**，不得在读线程调用）。
     *
     * @param rawPaths 请求中的原始路径；**空表示全量重载**（通知所有已注册监听器）。
     * @return 完整回执；任何分支都返回回执，绝不抛。
     */
    // 必须捕 Throwable：编排里会进第三方代码（监听器回调、插件自报），且 `Error` 也不该
    // 让一条桥命令把协调池线程打死。失败一律降级为"整请求失败"回执。
    @Suppress("TooGenericExceptionCaught")
    fun reload(rawPaths: List<String>): ReloadReceipt {
        val startedAtMs = clock()
        return try {
            val current = currentSettings()
            if (rawPaths.size > current.maxPathsPerRequest) {
                requestLevelError(startedAtMs, overLimitMessage(rawPaths.size, current.maxPathsPerRequest))
            } else {
                orchestrate(rawPaths, current, startedAtMs)
            }
        } catch (e: Throwable) {
            // 编排自身出错（理论上不可达）：明确失败，绝不把半成品当成功。
            warn("配置重载编排异常:${e.javaClass.simpleName}")
            requestLevelError(startedAtMs, "探针内部错误:${e.javaClass.simpleName}(未执行任何重载)")
        }
    }

    /** 路径数超限的可读原因（整请求拒绝，不静默截断）。 */
    private fun overLimitMessage(actual: Int, limit: Int): String =
        "单次请求路径数超限:$actual > max-paths-per-request=$limit(整请求拒绝,未执行任何重载)"

    /** 一次编排的完整流程（异常已由 [reload] 兜住）。 */
    private fun orchestrate(rawPaths: List<String>, current: ReloadSettings, startedAtMs: Long): ReloadReceipt {
        val state = RequestState(rawPaths.isEmpty(), rawPaths)
        normalizeAll(state)
        if (admit(state, current, startedAtMs)) {
            submitAll(state)
            harvest(state, startedAtMs, current.timeoutMs)
        }
        return finish(state, startedAtMs)
    }

    /** 1：规范化全部路径；非法项直接落 `PATH_ESCAPE`（保留原始输入便于 JM 对账）。 */
    private fun normalizeAll(state: RequestState) {
        val valid = LinkedHashSet<String>()
        for (raw in state.rawPaths) {
            val normalized = ReloadPathGuard.normalize(raw)
            if (normalized == null) {
                state.addFailure(raw, ConfigReloadErrorCode.PATH_ESCAPE, MESSAGE_PATH_ESCAPE)
            } else {
                valid.add(normalized)
            }
        }
        state.requested = valid.sorted()
    }

    /**
     * 2~3：熔断 + 在途准入。
     *
     * @return true 表示还有可派发的键（或全量哨兵），false 表示全部被挡、无需提交。
     */
    private fun admit(state: RequestState, current: ReloadSettings, nowMs: Long): Boolean {
        if (state.fullReload) return admitFull(state, current, nowMs)
        val admitted = state.requested.filter { admitOne(state, it, current, nowMs) }
        state.admitted = admitted
        return admitted.isNotEmpty()
    }

    /** 全量重载的准入：只看哨兵键（全量与单路径互斥，靠 [InFlightRegistry] 的复合判定）。 */
    private fun admitFull(state: RequestState, current: ReloadSettings, nowMs: Long): Boolean {
        val key = InFlightRegistry.FULL_RELOAD_KEY
        if (!admitOne(state, key, current, nowMs)) return false
        state.admitted = listOf(key)
        return true
    }

    /** 单键准入：熔断 → 在途；失败条目随之落账。 */
    private fun admitOne(state: RequestState, path: String, current: ReloadSettings, nowMs: Long): Boolean {
        if (current.breakerEnabled && runtime.breaker.isOpen(path, nowMs)) {
            state.addFailure(path, ConfigReloadErrorCode.TIMEOUT, MESSAGE_BREAKER_OPEN)
            return false
        }
        when (runtime.inFlight.tryAcquire(path, nowMs)) {
            AcquireResult.ACQUIRED -> {
                state.acquired.add(path)
                return true
            }
            AcquireResult.STUCK -> state.addFailure(path, ConfigReloadErrorCode.STUCK, MESSAGE_STUCK)
            AcquireResult.IN_FLIGHT ->
                state.addFailure(path, ConfigReloadErrorCode.IN_FLIGHT, messageInFlight(path, nowMs))
        }
        return false
    }

    /**
     * `IN_FLIGHT` 的可读原因：三种方向对运维要做的事完全不同，因此各自给话。
     *
     * - 被全量挡：等那条 `config_reload`（不带 paths）跑完；
     * - 全量被单路径挡：等那条单路径跑完，或把它写进一次全量；
     * - 被同路径挡：同名的重载正在跑，重复下发没有意义。
     */
    private fun messageInFlight(path: String, nowMs: Long): String {
        val blocker = runtime.inFlight.blockerOf(path, nowMs) ?: return MESSAGE_SAME_PATH_IN_FLIGHT
        return when {
            blocker.isFullReload() -> MESSAGE_FULL_IN_FLIGHT
            path == InFlightRegistry.FULL_RELOAD_KEY -> "已有单路径重载在途(${blocker.path}),全量重载本次未执行"
            else -> MESSAGE_SAME_PATH_IN_FLIGHT
        }
    }

    /**
     * 4~6：匹配监听器 + **先全部提交**。
     *
     * 一个（监听器，命中路径集合）是一个子任务：一次回调覆盖它命中的全部路径
     * （与 `ConfigReloadEvent.matchedPaths` 语义一致），回执再按路径展开记账。
     */
    private fun submitAll(state: RequestState) {
        val dispatches = collectDispatches(state)
        settleUnclaimed(state, dispatches)
        if (dispatches.isEmpty()) return
        val leases = PathLeases(runtime.inFlight)
        dispatches.forEach { leases.register(it.leasePaths()) }
        for (dispatch in dispatches) {
            val handle = runtime.workerPool.submit { runDispatch(dispatch, leases) }
            if (handle == null) {
                // 提交被拒：根本**没进队列** → INTERNAL_ERROR，并就地归还占用（任务不会跑）。
                dispatch.paths.forEach {
                    state.addFailure(it, ConfigReloadErrorCode.INTERNAL_ERROR, MESSAGE_SUBMIT_REJECTED)
                }
                leases.complete(dispatch.leasePaths())
            } else {
                state.submitted[handle] = dispatch
            }
        }
    }

    /** 无人认领的路径进 `bestEffort`（算成功），并立即归还其占用。 */
    private fun settleUnclaimed(state: RequestState, dispatches: List<Dispatch>) {
        val claimed = dispatches.flatMapTo(HashSet()) { it.paths }
        val noDispatch = dispatches.isEmpty()
        state.acquired.forEach { key -> settleUnclaimedKey(state, key, claimed, noDispatch) }
    }

    /** 单个已占用键的"无任务接管"处理。 */
    private fun settleUnclaimedKey(state: RequestState, key: String, claimed: Set<String>, noDispatch: Boolean) {
        if (key == InFlightRegistry.FULL_RELOAD_KEY) {
            // 全量：没有任何监听器 → 没任务会归还哨兵键，立即归还。
            if (noDispatch) runtime.inFlight.release(key)
            return
        }
        if (key in claimed) return
        state.bestEffort.add(key)
        runtime.inFlight.release(key)
    }

    /** 按"每次派发取声明快照"的规则收集本次要发的通知。 */
    private fun collectDispatches(state: RequestState): List<Dispatch> {
        val dispatches = ArrayList<Dispatch>()
        for (listener in listeners.snapshot()) {
            val declared = runCatching { listeners.watchedPathsOf(listener) }.getOrDefault(emptyList())
            val matched = matchedPaths(state, declared)
            if (matched.isEmpty()) continue
            val event = if (state.fullReload) {
                ConfigReloadEvent.full(matched)
            } else {
                ConfigReloadEvent.of(matched, state.requested)
            }
            dispatches.add(Dispatch(listener, matched, event, state.fullReload))
        }
        return dispatches
    }

    /**
     * 本次命中该监听器的路径。
     *
     * 全量重载：命中的就是它的声明集合（规格 §3.1）。
     * 按路径重载：声明与准入路径求交。
     */
    private fun matchedPaths(state: RequestState, declared: List<String>): List<String> {
        if (declared.isEmpty()) return emptyList()
        if (state.fullReload) return declared.sorted()
        return state.admitted.filter { path -> declared.any { ListenerMatcher.matches(it, path) } }
    }

    /**
     * 子任务体：调用监听器 → 结果归一。
     *
     * 释放占用放在**自身 finally**：即使协调器已超时返回，本任务结束时仍会正确归还，
     * 从而让 `STUCK` 能随时间自愈。
     */
    private fun runDispatch(dispatch: Dispatch, leases: PathLeases): DispatchOutcome = try {
        DispatchOutcome(listOf(invoke(dispatch)))
    } finally {
        leases.complete(dispatch.leasePaths())
    }

    /** 调用一次监听器并归一结果（异常 / 返回 null 都不得当作成功）。 */
    // 必须捕 Throwable：外部监听器可能抛 Error（如 NoClassDefFoundError），
    // 而这里在 worker 池线程上执行——漏出去会打死池线程并让池永久少一个位。
    @Suppress("TooGenericExceptionCaught")
    private fun invoke(dispatch: Dispatch): ListenerOutcome {
        val name = dispatch.listener.javaClass.name
        return try {
            val result = dispatch.listener.onConfigReload(dispatch.event)
            if (result == null) {
                ListenerOutcome(name, ConfigReloadResult.failed(ConfigReloadErrorCode.PLUGIN_REJECTED, MESSAGE_NO_RESULT))
            } else {
                ListenerOutcome(name, result)
            }
        } catch (t: Throwable) {
            // 必须捕 Throwable：外部插件可能抛 Error，绝不能让 worker 线程死掉。
            ListenerOutcome(name, ConfigReloadResult.failed(ConfigReloadErrorCode.PLUGIN_THREW, messageThrew(t)), true)
        }
    }

    /** 异常文本只取类名：异常 message 常含路径/凭据，进回执前不该原样透出。 */
    private fun messageThrew(t: Throwable): String = "监听器抛出异常:${t.javaClass.simpleName}"

    /**
     * 7：轮转收割（规格 §3.8）。
     *
     * 先非阻塞收所有已落定项，再对未完成项按剩余预算**分片轮询**，单片 ≤
     * [MAX_HARVEST_SLICE_MS]。绝不"按顺序死等第一个"。
     */
    private fun harvest(state: RequestState, startedAtMs: Long, budgetMs: Long) {
        drainDone(state)
        while (state.pending() > 0) {
            val remaining = budgetMs - (clock() - startedAtMs)
            if (remaining <= 0L) break
            rotateOnce(state, remaining)
            drainDone(state)
        }
        settleUnfinished(state, budgetMs)
    }

    /**
     * 轮转**一轮**：每个未落定项各等一个极短分片，预算见底即提前收手。
     *
     * 顺序遍历而非"挑一个长等"是轮转收割的实质：慢项只吃掉自己那个分片，
     * 快项在下一轮 `drainDone` 里立刻被收走。
     */
    private fun rotateOnce(state: RequestState, remainingMs: Long) {
        val slice = remainingMs.coerceAtMost(MAX_HARVEST_SLICE_MS)
        val deadlineNanos = System.nanoTime() + remainingMs * NANOS_PER_MS
        for (handle in state.submitted.keys) {
            if (state.settled.contains(handle) || handle.isDone()) continue
            handle.awaitSlice(slice)
            if (System.nanoTime() >= deadlineNanos) return
        }
    }

    /** 非阻塞收所有已落定项。 */
    private fun drainDone(state: RequestState) {
        state.submitted.keys.toList().forEach { handle ->
            if (state.settled.contains(handle) || !handle.isDone()) return@forEach
            settle(state, handle)
        }
    }

    /** 把一条已落定任务的结果并入回执账本（幂等：已并入的句柄被记录）。 */
    private fun settle(state: RequestState, handle: ReloadTaskHandle) {
        val dispatch = state.submitted[handle] ?: return
        state.settled.add(handle)
        val outcome = handle.resultIfDone() ?: return
        val results = outcome.outcome?.results.orEmpty()
        if (results.isEmpty()) {
            // 任务异常退出（未被自身兜住的 Error）：按内部错误记，绝不当作成功。
            dispatch.paths.forEach {
                state.addFailure(it, ConfigReloadErrorCode.INTERNAL_ERROR, MESSAGE_INTERNAL_CRASH)
            }
            return
        }
        applyResults(state, dispatch, results)
    }

    /** 单次派发的监听器结果 → 按路径记账（严格口径：有失败即失败）。 */
    private fun applyResults(state: RequestState, dispatch: Dispatch, results: List<ListenerOutcome>) {
        val failedOutcomes = results.filter { !it.result.ok }
        if (failedOutcomes.isEmpty()) {
            for (path in dispatch.paths) {
                if (path in state.failedPaths) continue
                state.succeeded.add(path)
                state.recordApplied(path, results)
            }
            return
        }
        for (path in dispatch.paths) {
            failedOutcomes.forEach { state.addFailure(path, codeOf(it), messageOf(it)) }
            state.succeeded.remove(path)
        }
    }

    /** 失败结果 → 错误码（插件返回 null 码视为拒绝，而不是成功）。 */
    private fun codeOf(outcome: ListenerOutcome): ConfigReloadErrorCode =
        outcome.result.code ?: ConfigReloadErrorCode.PLUGIN_REJECTED

    /** 失败结果 → 回执消息（脱敏 + 限长）。 */
    private fun messageOf(outcome: ListenerOutcome): String {
        val raw = outcome.result.message.ifEmpty { MESSAGE_EMPTY_PLUGIN_MESSAGE }
        return HealthTextSanitizer.sanitize(raw, MAX_MESSAGE_CHARS)
    }

    /**
     * 预算耗尽后仍未落定的任务：区分"没轮到"与"没跑完"，并置中断标记（不强杀、不 join）。
     */
    private fun settleUnfinished(state: RequestState, budgetMs: Long) {
        state.submitted.keys.toList().forEach { handle ->
            if (!state.settled.contains(handle)) settleOneUnfinished(state, handle)
        }
        if (state.timeouts.isNotEmpty()) {
            warn("配置重载超时:${state.timeouts.size} 条路径未在 ${budgetMs}ms 内完成(探针不强杀,该次重载自生自灭)")
        }
    }

    /** 单个未落定任务 → `TIMEOUT`（按"是否已开始执行"给不同 message）。 */
    private fun settleOneUnfinished(state: RequestState, handle: ReloadTaskHandle) {
        val dispatch = state.submitted[handle] ?: return
        val message = if (handle.started()) MESSAGE_TIMEOUT_RUNNING else MESSAGE_TIMEOUT_QUEUED
        handle.cancel()
        dispatch.paths.forEach {
            state.addFailure(it, ConfigReloadErrorCode.TIMEOUT, message)
            state.timeouts.add(it)
        }
        state.settled.add(handle)
    }

    /** 收口：汇总回执 + 熔断记账。 */
    private fun finish(state: RequestState, startedAtMs: Long): ReloadReceipt {
        applyBreakerAccounting(state)
        val reloaded = state.succeeded.filter { it !in state.failedPaths }.sorted()
        val bestEffort = state.bestEffort.filter { it !in state.failedPaths && it !in reloaded }.sorted()
        return ReloadReceipt(
            reloaded = reloaded,
            bestEffort = bestEffort,
            failed = state.sortedFailures(),
            applied = state.applied.filterKeys { it in reloaded },
            costMs = (clock() - startedAtMs).coerceAtLeast(0L),
            error = "",
        )
    }

    /**
     * 熔断记账：**只有 `TIMEOUT` 触发熔断**，成功一次即清零退避。
     *
     * 其余错误码都是确定性结果，重试有意义（见 [ConfigReloadErrorCode] 的注释）。
     * 本次请求只要出现过超时，就不做"成功清零"——否则同一次请求里另一个路径的成功
     * 会把刚记下的熔断立刻抹掉。
     */
    private fun applyBreakerAccounting(state: RequestState) {
        state.timeouts.forEach { runtime.breaker.recordTimeout(it, clock()) }
        if (state.timeouts.isNotEmpty()) return
        state.succeeded.forEach { runtime.breaker.recordSuccess(it) }
    }

    /** 请求级错误回执（不产生 `failed` 条目，只写 `error`）。 */
    private fun requestLevelError(startedAtMs: Long, message: String): ReloadReceipt = ReloadReceipt(
        reloaded = emptyList(),
        bestEffort = emptyList(),
        failed = emptyList(),
        applied = emptyMap(),
        costMs = (clock() - startedAtMs).coerceAtLeast(0L),
        error = message,
    )

    /** 现读阈值；读不到（配置未就绪）时退回默认值，绝不因此抛。 */
    private fun currentSettings(): ReloadSettings = runCatching { settings() }.getOrDefault(FALLBACK_SETTINGS)

    /**
     * 一次请求的中间状态。
     *
     * 集中存放，避免在 7 个阶段之间来回传 7 个参数。**只被协调线程访问**：
     * 子任务不写这些结构（结果只经句柄回收），因此无需加锁。
     */
    private class RequestState(val fullReload: Boolean, val rawPaths: List<String>) {

        /** 规范化后的请求路径（已去重排序）；全量时为空。 */
        var requested: List<String> = emptyList()

        /** 通过熔断与在途准入的键（全量时为哨兵键）。 */
        var admitted: List<String> = emptyList()

        /** 本次已占用的键（归还责任在子任务或 [settleUnclaimed]）。 */
        val acquired = LinkedHashSet<String>()

        /** 句柄 → 派发定义（插入顺序即提交顺序）。 */
        val submitted = LinkedHashMap<ReloadTaskHandle, Dispatch>()

        /** 已并入账本的句柄。 */
        val settled = HashSet<ReloadTaskHandle>()

        /** 有监听器返回 ok 的路径。 */
        val succeeded = LinkedHashSet<String>()

        /** 无人认领的路径。 */
        val bestEffort = LinkedHashSet<String>()

        /** 超时路径（熔断记账用）。 */
        val timeouts = LinkedHashSet<String>()

        /** 失败路径集合（含越界 / 在途 / 卡死 / 插件失败 / 超时）。 */
        val failedPaths = HashSet<String>()

        /** 失败明细。 */
        val failures = ArrayList<ReloadFailure>()

        /** 自报事实。 */
        val applied = LinkedHashMap<String, ReloadApplied>()

        /** 记一条失败（同时维护 [failedPaths]）。 */
        fun addFailure(path: String, code: ConfigReloadErrorCode, message: String) {
            failures.add(ReloadFailure(path, code, message))
            failedPaths.add(path)
        }

        /** 失败明细按路径 + 错误码排序（回执顺序稳定，便于逐字段比对）。 */
        fun sortedFailures(): List<ReloadFailure> = failures.sortedWith(compareBy({ it.path }, { it.code.name }))

        /** 仍未落定的任务数。 */
        fun pending(): Int = submitted.keys.count { !settled.contains(it) }

        /** 记录一条自报事实（同路径多监听器时先到先得）。 */
        fun recordApplied(path: String, results: List<ListenerOutcome>) {
            val merged = results.fold(ReloadApplied()) { acc, outcome -> acc.merge(appliedOf(outcome)) }
            if (merged.isEmpty()) return
            applied[path] = applied[path]?.merge(merged) ?: merged
        }

        /** 单监听器结果 → 自报事实。 */
        private fun appliedOf(outcome: ListenerOutcome): ReloadApplied {
            val result = outcome.result
            return ReloadApplied(
                md5 = result.appliedMd5.orElse(null),
                version = result.appliedVersion.orElse(null),
                count = if (result.appliedCount.isPresent) result.appliedCount.asInt else null,
            )
        }
    }

    /** 一次派发：一个监听器 × 它命中的路径集合。 */
    private class Dispatch(
        val listener: ConfigReloadListener,
        val paths: List<String>,
        val event: ConfigReloadEvent,
        val fullReload: Boolean,
    ) {

        /**
         * 任务结束时需要归还的占用键。
         *
         * 全量重载把哨兵键也带上：它的释放同样必须由**最后一个完成的子任务**执行。
         */
        fun leasePaths(): List<String> =
            if (fullReload) paths + InFlightRegistry.FULL_RELOAD_KEY else paths
    }

    /**
     * 本请求的占用租约：按路径计数，**最后一个完成的子任务**负责归还。
     *
     * 之所以要计数：同一路径可能被多个监听器命中，而"同一时刻不并发"的粒度是路径；
     * 只有等所有相关子任务都结束才能释放，否则第二个监听器还在跑时，同路径的第二份重载
     * 就会被放进来并发写插件状态。
     */
    private class PathLeases(private val inFlight: InFlightRegistry) {

        /** 键 → 尚未完成的子任务数。 */
        private val pending = ConcurrentHashMap<String, AtomicInteger>()

        /** 登记一次派发涉及的键（每个键 +1）。 */
        fun register(paths: Collection<String>) {
            paths.forEach { pending.computeIfAbsent(it) { AtomicInteger(0) }.incrementAndGet() }
        }

        /** 标记一次派发结束；某键计数归零时**由调用方**归还占用（= 任务自身 finally）。 */
        fun complete(paths: Collection<String>) {
            paths.forEach { path ->
                val counter = pending[path] ?: return@forEach
                if (counter.decrementAndGet() > 0) return@forEach
                pending.remove(path, counter)
                inFlight.release(path)
            }
        }
    }

    private companion object {

        /** 收割单片等待上限（毫秒）：规格要求 ≤ 10ms。 */
        const val MAX_HARVEST_SLICE_MS = 10L

        /** 纳秒/毫秒换算（收割 deadline 计算用）。 */
        const val NANOS_PER_MS = 1_000_000L

        /** 回执 message 的字符上限（脱敏后截断；规格未定义独立配置，取与健康回执同量级）。 */
        const val MAX_MESSAGE_CHARS = 512

        /** 读不到配置时的兜底阈值。 */
        val FALLBACK_SETTINGS = ReloadSettings()

        const val MESSAGE_PATH_ESCAPE = "路径越界:不在 plugins/ 子树内,或含 ../ 逃逸"
        const val MESSAGE_SAME_PATH_IN_FLIGHT = "同路径已有重载在途,本次未执行"
        const val MESSAGE_FULL_IN_FLIGHT = "全量重载在途,本次未执行(全量与单路径互斥)"
        const val MESSAGE_STUCK = "上次重载已超时且未返回,疑似卡死,本次未执行"
        const val MESSAGE_BREAKER_OPEN = "该路径处于熔断窗口内(上次超时),本次未执行"
        const val MESSAGE_SUBMIT_REJECTED = "提交被拒绝(worker 池已满或已关闭),本次未执行"
        const val MESSAGE_TIMEOUT_QUEUED = "排队到超时仍未被取走,本次未轮到执行"
        const val MESSAGE_TIMEOUT_RUNNING = "已执行未完成(探针不强杀,该次重载自生自灭)"
        const val MESSAGE_INTERNAL_CRASH = "探针内部错误:子任务异常退出,本次未完成"
        const val MESSAGE_NO_RESULT = "监听器未返回结果(缺结果不得当作成功)"
        const val MESSAGE_EMPTY_PLUGIN_MESSAGE = "插件未给出失败原因"
    }
}
