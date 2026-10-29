package top.wcpe.mc.plugin.serverprobe.e2e

import java.util.concurrent.atomic.AtomicInteger

/**
 * reload-fixture 场景里**监听器侧与 fixture 侧共用**的观测点。
 *
 * ## 为什么计数必须放在监听器内部
 * `docs/specs/config-reload.md` §5「成功」一行的最后半句是"**监听器调用计数 +1**"。
 * 只断言回执里的 `reloaded` 无法证明这件事——探针完全可以不调用任何监听器就把它填上。
 * 计数写在监听器**自己的** `onConfigReload` 里，是从被调用方看的独立事实：
 * 回执说成功、计数也确实 +1，才说明"通知真的送达了"。
 *
 * ## 为什么用原子计数而不是普通 int
 * 监听器回调跑在探针的 `serverprobe-reload-worker-N` 池上（§3.1），与 fixture 的 driver 线程
 * 是两个线程；断言需要读到"回执返回之后"的确定值。`AtomicInteger` 的读写在 happens-before 上
 * 已经足够（回执必须先于监听器返回才可能产生），而 `@Volatile` 的事件快照同理。
 *
 * ## 事件快照的用途
 * 一次命令对**每个监听器**只回调一次，`matchedPaths` 是本次命中它的全部路径（§3.1）。
 * fixture 据此断言"一个事件覆盖两条命中路径只算一次调用"，避免把"每条路径一次回调"
 * 这种错误实现误当成正确。
 */
internal class ReloadFixtureState {

    /** 精确路径监听器被调用的次数。 */
    val exactCalls = AtomicInteger()

    /** 目录前缀监听器被调用的次数。 */
    val prefixCalls = AtomicInteger()

    /** 拒绝监听器被调用的次数。 */
    val rejectCalls = AtomicInteger()

    /** 精确监听器最后一次收到的事件里的命中路径。 */
    @Volatile
    var exactLastMatched: List<String> = emptyList()

    /** 前缀监听器最后一次收到的事件里的命中路径。 */
    @Volatile
    var prefixLastMatched: List<String> = emptyList()

    /** 前缀监听器最后一次收到的事件里的请求路径。 */
    @Volatile
    var prefixLastRequested: List<String> = emptyList()

    /** 前缀监听器最后一次收到的事件是否为全量重载。 */
    @Volatile
    var prefixLastFullReload: Boolean = false

    /** 三个监听器的调用次数快照；一次取齐，避免逐字段读到的不是同一时刻的值。 */
    fun snapshot(): Counts = Counts(exactCalls.get(), prefixCalls.get(), rejectCalls.get())

    /**
     * 一组调用计数。
     *
     * @property exact 精确路径监听器次数。
     * @property prefix 目录前缀监听器次数。
     * @property reject 拒绝监听器次数。
     */
    data class Counts(val exact: Int, val prefix: Int, val reject: Int) {

        /** 相对 [before] 的增量（逐项相减）；用于断言"本次命令让谁被调用、让谁保持不动"。 */
        fun deltaFrom(before: Counts): Counts =
            Counts(exact - before.exact, prefix - before.prefix, reject - before.reject)

        override fun toString(): String = "exact=$exact,prefix=$prefix,reject=$reject"
    }

    companion object {

        /**
         * 全流程结束后的期望累计次数。
         *
         * 逐项推导（每条命令对**每个命中监听器**只回调一次）：
         * - 成功请求（[ReloadFixturePayload.EXACT_PATH]）：精确 +1、前缀 +1；
         * - 无人认领请求（[ReloadFixturePayload.UNCLAIMED_PATH]）：都不动；
         * - 自报字段请求（[ReloadFixturePayload.PARTIAL_PATH]）：前缀 +1；
         * - 部分成功请求（ok.yml + reject.yml）：精确 +1、前缀 +1、拒绝 +1；
         * - 越界请求（`../../etc/passwd`）：规范化阶段就拒绝（§3.1 步骤 1），都不动。
         *
         * 合计：精确 2、前缀 3、拒绝 1。
         */
        val EXPECTED_CALLS = Counts(exact = 2, prefix = 3, reject = 1)
    }
}
