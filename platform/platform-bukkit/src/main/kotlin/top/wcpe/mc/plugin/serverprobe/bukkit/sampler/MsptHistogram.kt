package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

/**
 * MSPT 定容滑动窗口直方图(FR2.2)。
 *
 * 在无法从平台直接取得 MSPT 分位数据时(Legacy / Self 采样路径),由 tick 时钟逐 tick 喂入
 * 单 tick 耗时(纳秒),本类维护"最近 N 个样本"的环形窗口,按需算出平均值与 p95/p99 分位(毫秒)。
 *
 * 容量上限即窗口大小,超出后最旧样本被覆盖(环形缓冲,O(1) 写入,无扩容)。
 * 分位/均值的具体算法收口于 [Percentiles],本类只负责窗口维护与一致性快照。
 *
 * 并发模型:写入仅发生于服务器主线程(tick 时钟),读取([avgMs]/[p95Ms]/[p99Ms])发生于
 * 编排层异步线程,读多写少。读取时对底层数组做一次性快照后在快照上计算,使临界区最小化,
 * 同时保证读取看到的是一致的窗口而非读到一半被覆盖的脏数据。
 *
 * @property capacity 窗口容量(保留的最近样本数);必须为正。
 */
class MsptHistogram(
    private val capacity: Int = DEFAULT_CAPACITY
) {

    init {
        require(capacity > 0) { "MSPT 直方图容量必须为正,实际为 $capacity" }
    }

    /** 环形缓冲底层数组,存放单 tick 耗时(纳秒)。 */
    private val buffer = LongArray(capacity)

    /** 下一个写入位置(环形游标)。 */
    private var writeIndex = 0

    /** 已写入的有效样本数,封顶为 [capacity]。 */
    private var size = 0

    /**
     * 追加一个 tick 耗时样本。
     *
     * @param tickNanos 单 tick 耗时(纳秒);负值视为异常输入直接忽略,避免污染统计。
     */
    @Synchronized
    fun add(tickNanos: Long) {
        if (tickNanos < 0) {
            return
        }
        buffer[writeIndex] = tickNanos
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) {
            size++
        }
    }

    /**
     * 当前窗口内 MSPT 平均值(毫秒)。
     *
     * @return 平均 MSPT(毫秒);窗口为空时返回 null。
     */
    fun avgMs(): Double? {
        val snapshot = snapshot() ?: return null
        return Percentiles.avgMs(snapshot)
    }

    /**
     * 当前窗口内 MSPT 的 p95 分位(毫秒)。
     *
     * @return p95 MSPT(毫秒);窗口为空时返回 null。
     */
    fun p95Ms(): Double? {
        val snapshot = snapshot() ?: return null
        return Percentiles.percentileMsInPlace(snapshot, P95)
    }

    /**
     * 当前窗口内 MSPT 的 p99 分位(毫秒)。
     *
     * @return p99 MSPT(毫秒);窗口为空时返回 null。
     */
    fun p99Ms(): Double? {
        val snapshot = snapshot() ?: return null
        return Percentiles.percentileMsInPlace(snapshot, P99)
    }

    /**
     * 对当前有效样本做一致性快照。
     *
     * 仅复制 [size] 个有效样本(而非整个 [capacity] 数组),避免未写满时把 0 计入统计;
     * 同时还原为写入时间顺序。快照为独立副本,调用方可安全就地排序。
     *
     * @return 有效样本副本;窗口为空时返回 null。
     */
    @Synchronized
    private fun snapshot(): LongArray? {
        if (size == 0) {
            return null
        }
        val copy = LongArray(size)
        if (size < capacity) {
            // 尚未写满:有效样本位于 [0, size),顺序即写入顺序
            System.arraycopy(buffer, 0, copy, 0, size)
        } else {
            // 已写满:最旧样本位于 writeIndex 处,需分两段拼接还原时间顺序
            val tail = capacity - writeIndex
            System.arraycopy(buffer, writeIndex, copy, 0, tail)
            System.arraycopy(buffer, 0, copy, tail, writeIndex)
        }
        return copy
    }

    private companion object {

        /** 默认窗口容量:最近 600 个 tick(20 TPS 下约 30 秒)。 */
        private const val DEFAULT_CAPACITY = 600

        /** p95 分位。 */
        private const val P95 = 0.95

        /** p99 分位。 */
        private const val P99 = 0.99
    }
}
