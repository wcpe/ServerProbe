package top.wcpe.mc.plugin.serverprobe.core.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 可靠事件序号分配器（spec §3.2 / §3.3 / §5）。
 *
 * ## 语义
 * - **per-domain 独立递增**：每个业务域一条序列，不同域互不干扰（不是全局单调）。
 * - **严格递增、不跳号**：唯一分配点是 [next]，`AtomicLong.getAndIncrement` 天然无空洞。
 * - **可丢事件不占序列**：`emit` 路径根本不调本类（见 `BusinessEventSinkImpl`），
 *   所以可丢事件丢多少都不会在可靠序列里留下缺口。这不是"顺手优化"，而是
 *   "有缺口 = 真的漏了"这条判据成立的前提。
 * - **补发不重新分配**：调用方指定序号的入口只用传入值，不推进序列——
 *   理由见 `BusinessEventSink.emitReliable(...,seq)`。若传入值比当前上限还大，
 *   则把上限抬高到它之后（`observe`）：抬高只会让后续新事件从更大的号开始，
 *   不会让新事件撞进历史区间；传入过去的号则什么都不做（回填历史）。
 *
 * ## 重启续接（spec §3.3 方案 B）
 * 启动时从 [EventSeqStore] 读出每域已分配的最大序号，从 `max + 1` 继续：
 *
 * - **有持久化状态** → 逐域续接，下游的位点可以跨重启直接使用；
 * - **文件不存在（首次启动）** → 从 **1** 开始（spec §3.2「起始 1」），epoch 不变；
 * - **文件损坏 / 丢失** → 起点取 `当前时间戳毫秒 << 20`
 *   （约 `1.8e18`；正数上限 `9.2e18`，余量充足），并递增 epoch。
 *   为什么不从 1 重新开始：文件丢失时旧值仍可能留在下游，从 1 开始会让**新事件冒充旧位点**；
 *   一个"巨大且远高于任何历史值"的起点保证不会冲突。
 *
 * 三种情形必须分开，否则要么全新服务器上 spec §6 验收 1（seq 1..100）永远不成立，
 * 要么一次磁盘故障就让序号静默回退。
 *
 * ## epoch 什么时候递增
 * spec §3.3 原文是「重启时若走方案 A 则递增」。本实现选方案 B（续接），
 * 因此**正常重启不递增 epoch**——下游看到同一个 epoch 就能确信"序号仍可跨重启比对"。
 * 只有"序号语义确实重置"的两种情况才递增：
 *
 * 1. 持久化状态丢失（序号从时间戳起跳，与历史不可比）；
 * 2. long 溢出前的重置（spec §5「极不可能」的边界）。
 *
 * ## 持久化时机
 * 每条事件都落盘会为每次上报付一次磁盘 IO（热路径）。折中：内存记录增量，
 * **每 [PERSIST_EVERY] 条**落一次盘。崩溃时最多丢这么多条号段的续接信息，
 * 重启后走时间戳起跳——仍不会冲突，只是下游会看到一次序号跳变并对账。
 * 正常停机（[shutdown]）强制落盘，把缺口抹平。
 *
 * ## 线程安全
 * [next] 是 `AtomicLong.getAndIncrement`，无锁；`domains` 是 `ConcurrentHashMap`。
 * 本类可在事件线程与业务线程并发调用。
 *
 * @param store 持久化接缝；null 表示不持久化（单测 / `bridge.line.spill-enabled=false`）
 * @param clock 时钟（毫秒）；单测可注入固定值以精确断言时间戳兜底起点
 */
class EventSeqAllocator(
    initialStore: EventSeqStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 持久化接缝；可在装配期后置绑定（[bind]）。
     *
     * 为什么可变：本实例的生命周期**长于桥连接**——序号必须跨"桥重启"（stop → start）续接，
     * 而落盘目录只有在 `doStart` 读到配置后才知道。若让构造器强制传入，
     * 装配处就只能每次 start 新建一个分配器，于是桥内部重启会让序号回到 1。
     */
    @Volatile
    private var store: EventSeqStore? = initialStore

    /** 域 → 该域**下一个**待分配的序号（即已分配的最大值 + 1）。 */
    private val domains = ConcurrentHashMap<String, AtomicLong>()

    /** 域 → 上次落盘时的最大序号。 */
    private val persistedMax = ConcurrentHashMap<String, Long>()

    /** 全局 epoch。 */
    private val epoch = AtomicLong(EventSeqStore.INITIAL_EPOCH)

    /** 启动装载是否已执行（幂等保护）。 */
    private val loadState = AtomicLong(0)

    /** 自上次落盘以来的分配条数（跨域合计，够数即落盘）。 */
    private val sinceLastPersist = AtomicLong(0)

    /**
     * 无持久化状态时的时间戳兜底起点；0 表示"未装载"（单测直接构造）→ 从 1 开始。
     *
     * 所有新域共用同一个基数：序号是 per-domain 的，不同域从同一基数起步完全正常
     * （下游只比同一域内的前后两条）。
     */
    @Volatile
    private var fallbackBase: Long = 0L

    /**
     * 绑定持久化接缝（装配期调用一次）。之后的 [load] 才会真正读盘。
     *
     * 幂等：重复绑定同一个 store 无副作用；换成另一个 store 时下次 [load] 重新装载。
     *
     * @param target 持久化目录；null 表示不持久化
     */
    fun bind(target: EventSeqStore?) {
        if (store === target) return
        store = target
        // 换了持久化目标 → 允许重新装载（否则新目录的状态永远读不进来）。
        loadState.set(0L)
    }

    /**
     * 从持久化状态装载并续接。幂等；未注入 store 时是无操作。
     *
     * 由桥装配处**在开始上报之前**调用（`BridgeClient.doStart`）；单测里显式调用以模拟"重启"。
     * 重复调用不会重复推进序号。
     */
    fun load() {
        val target = store ?: return
        if (!loadState.compareAndSet(0L, 1L)) return
        when (val result = target.load()) {
            is EventSeqStore.LoadResult.Present -> {
                val state = result.state
                epoch.set(state.epoch)
                state.maxSeqs.forEach { (domain, maxSeq) ->
                    persistedMax[domain] = maxSeq
                    domains[domain] = AtomicLong(maxSeq + 1)
                }
                ProbeLogger.info("事件序号状态已装载:epoch=${state.epoch} 域数=${state.maxSeqs.size}")
            }
            EventSeqStore.LoadResult.Absent -> {
                // 全新安装：从 1 起、epoch 不变（spec §3.2「起始 1」）。
                // 这里不落盘：一个还没发过任何事件的服务端不该留下空状态文件。
                ProbeLogger.info("事件序号无历史状态(首次启动),从 ${INITIAL_SEQ} 开始")
            }
            EventSeqStore.LoadResult.Corrupt -> {
                // 持久化丢失：从时间戳起跳 + 递增 epoch，让下游知道"这一段与之前不可比"。
                val base = fallbackBase()
                fallbackBase = base
                epoch.set(EventSeqStore.INITIAL_EPOCH + 1)
                ProbeLogger.warn("事件序号状态已丢失,从时间戳起跳:epoch=${epoch.get()} base=$base")
                // **故意不落盘**。此刻每个域都还没有序号记录，一旦写下去，
                // 文件就变成"可读但域表为空"，下次启动会被判成 Present → 各域从 1 起跳，
                // 与刚刚发出去的 1.8e18 号段正面冲突。保持文件损坏态更安全：
                // 下次仍是 Corrupt → 用**新的更大时间戳**重新起跳，永不冲突。
                // 正常停机（[shutdown]）会把真实的域表写下来，届时状态自然恢复可读。
            }
        }
    }

    /**
     * 分配下一个序号（per-domain，从 1 或续接值开始，严格递增无跳号）。
     *
     * **纯内存、无锁、不抛**：跑在事件上报的热路径上，任何锁或 IO 都会变成业务线程的延迟。
     *
     * @param domain 业务域；空串返回 0（调用方已校验，这里只做防御）
     * @return 本条事件的序号，恒 &gt; 0
     */
    fun next(domain: String): Long {
        if (domain.isEmpty()) return 0L
        val counter = domains.computeIfAbsent(domain) { AtomicLong(startFor()) }
        val seq = counter.getAndIncrement()
        if (seq <= 0L) {
            // long 溢出（spec §5 的边界）：重置该域序列并推 epoch，下游据此全量对账。
            // 现实中需要每秒十亿条连发数百年，但"极不可能"不等于"不可能"。
            overflowReset(domain, counter)
            return next(domain)
        }
        maybePersist(domain, seq)
        return seq
    }

    /**
     * 记录"调用方指定的序号"（补发场景）。
     *
     * 传入值**大于等于**当前已分配最大值时把上限抬高到 `seq + 1`；小于时不做任何事。
     *
     * @param domain 业务域
     * @param seq 调用方给的序号；`<= 0` 忽略
     */
    fun observe(domain: String, seq: Long) {
        if (domain.isEmpty() || seq <= 0L) return
        val counter = domains.computeIfAbsent(domain) { AtomicLong(startFor()) }
        counter.updateAndGet { current -> if (seq >= current) seq + 1 else current }
        maybePersist(domain, seq)
    }

    /** 当前 epoch（透传到帧）。 */
    fun currentEpoch(): Long = epoch.get()

    /**
     * 某域已分配的最大序号；从未分配过返回 0。
     *
     * 供诊断与单测使用（不对外暴露为帧字段）。
     */
    fun maxSeqOf(domain: String): Long {
        val counter = domains[domain] ?: return 0L
        return (counter.get() - 1).coerceAtLeast(0L)
    }

    /** 强制落盘（停机时调用，把"未落盘的号段"缺口抹平）。 */
    fun flush() {
        persistNow()
    }

    /** 停机：落盘。幂等（重复调用只是多写一次同样的内容）。 */
    fun shutdown() {
        flush()
    }

    /** 该域首次分配时的起点。 */
    private fun startFor(): Long {
        val base = fallbackBase
        return if (base <= 0L) INITIAL_SEQ else base
    }

    /** 溢出重置：该域从起点重新开始，epoch 递增。 */
    private fun overflowReset(domain: String, counter: AtomicLong) {
        epoch.incrementAndGet()
        counter.set(startFor())
        ProbeLogger.warn("事件序号已溢出并重置:domain=$domain epoch=${epoch.get()}")
    }

    /** 该域出现新最大值时累加计数；够 [PERSIST_EVERY] 条就落盘。 */
    private fun maybePersist(domain: String, seq: Long) {
        if (store == null) return
        if (seq <= (persistedMax[domain] ?: 0L)) return
        if (sinceLastPersist.incrementAndGet() < PERSIST_EVERY) return
        persistNow()
    }

    /** 立刻把全部域的最大序号整份写盘。 */
    private fun persistNow() {
        val target = store ?: return
        sinceLastPersist.set(0L)
        val snapshot = HashMap<String, Long>(domains.size)
        domains.forEach { (domain, counter) ->
            val max = (counter.get() - 1).coerceAtLeast(0L)
            if (max > 0L) {
                snapshot[domain] = max
                persistedMax[domain] = max
            }
        }
        target.persist(epoch.get(), snapshot)
    }

    /** 时间戳兜底起点：`(当前时间戳毫秒 << 20)`，保证远高于任何历史值且不溢出。 */
    private fun fallbackBase(): Long {
        val stamp = runCatching { clock() }.getOrDefault(0L)
        // 右移检查：极端未来时间戳（> 8.8e15 ms ≈ 28 万年）才可能溢出，届时退回 1。
        val shifted = if (stamp in 1..MAX_SHIFTABLE_MILLIS) stamp shl SHIFT_BITS else 0L
        return if (shifted > 0L) shifted else INITIAL_SEQ
    }

    companion object {

        /** 序号起始值（无持久化状态且无可用时钟时的兜底）。 */
        const val INITIAL_SEQ = 1L

        /** 每分配多少条落盘一次（崩溃时最多丢这么多条号段的续接信息）。 */
        const val PERSIST_EVERY = 256L

        /** 时间戳兜底起点的左移位数。 */
        const val SHIFT_BITS = 20

        /** 可安全左移 [SHIFT_BITS] 位的最大毫秒时间戳（留出正数余量）。 */
        const val MAX_SHIFTABLE_MILLIS = Long.MAX_VALUE ushr SHIFT_BITS
    }
}
