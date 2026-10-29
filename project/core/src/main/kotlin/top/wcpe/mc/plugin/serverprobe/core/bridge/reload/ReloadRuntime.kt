package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import top.wcpe.mc.plugin.serverprobe.core.config.ReloadSettings

/**
 * 重载编排所需的三个"表/池"，以及它们的构造口径（配置重载规格 §3.1、§3.5、§3.6）。
 *
 * ## 为什么合成一个对象
 * 三者的生命周期与阈值**同源**（都来自 `bridge.reload.*`，都由桥的启停管理），
 * 且协调器总是同时需要它们。分成三个构造参数只会让调用点写三遍同样的推导，
 * 也让"停机时要 shutdownNow 谁"散落在调用方。
 *
 * ## 阈值的读取时机
 * - `timeout-ms` / `max-paths-per-request`：协调器**每次请求现读**（最常调，值得热更新）；
 * - `stuck-after-ms` / 熔断五项：在 [of] 里**固化**——它们是在途表与熔断表的构造参数，
 *   运行期变更需要重连（桥重启）才生效。这是刻意的取舍：让这两个表保持无锁、无重配置路径。
 *
 * @param workerPool 监听器子任务池（**必须**与协调池不是同一个）。
 * @param inFlight 在途表。
 * @param breaker 熔断表。
 */
class ReloadRuntime(
    val workerPool: ReloadWorkerPool,
    val inFlight: InFlightRegistry,
    val breaker: CircuitBreaker,
) {

    /** 停机：打断在途监听器回调（与 reload 协调池同批）。 */
    fun shutdownNow() {
        workerPool.shutdownNow()
    }

    companion object {

        /** 按 `bridge.reload.*` 构造全部三者（worker 池一并创建，由本对象持有生命周期）。 */
        fun of(settings: ReloadSettings): ReloadRuntime =
            from(settings, ReloadWorkerPool(settings.workerPoolSize))

        /**
         * 用外部提供的 worker 池组装（单测需要自己控制池容量/线程名时用）。
         *
         * @param settings 阈值来源。
         * @param workerPool 监听器子任务池。
         */
        fun from(settings: ReloadSettings, workerPool: ReloadWorkerPool): ReloadRuntime = ReloadRuntime(
            workerPool = workerPool,
            inFlight = InFlightRegistry(settings.stuckAfterMs),
            breaker = CircuitBreaker(
                failureWindowMs = settings.breakerFailureWindowMs,
                openDurationMs = settings.breakerOpenDurationMs,
                failureThreshold = settings.breakerFailureThreshold,
                maxEntries = settings.breakerMaxEntries,
                maxOpenDurationMs = settings.breakerMaxOpenDurationMs,
            ),
        )
    }
}
