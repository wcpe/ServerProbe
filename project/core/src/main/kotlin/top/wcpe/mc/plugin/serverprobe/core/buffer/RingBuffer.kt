package top.wcpe.mc.plugin.serverprobe.core.buffer

/**
 * 泛型定容环形缓冲。
 *
 * 以固定容量保存最近写入的若干元素:写满后新元素覆盖最旧元素(FIFO 滚动),
 * 用于在内存中保留一段近期历史(如近期指标快照),供呈现层回看而无需落盘。
 *
 * 线程模型:写([add])多发生于异步采集线程,读([snapshot])贯穿运行期且可能跨线程,
 * 故全部公开方法以 `@Synchronized` 串行化,保证并发写入不丢失、读取得到一致视图。
 *
 * 内部实现:底层数组 + 写游标。游标只增不回绕,以"逻辑写入序号"对容量取模定位物理槽,
 * 从而无需单独维护 size 标志即可同时表达"未写满"与"已滚动"两种状态。
 *
 * @param T 元素类型。
 * @property capacity 缓冲容量;必须为正。
 */
class RingBuffer<T>(private val capacity: Int) {

    init {
        require(capacity > 0) { "环形缓冲容量必须为正,实际为 $capacity" }
    }

    /** 底层存储槽;尚未写入的槽为 null。 */
    private val slots = arrayOfNulls<Any?>(capacity)

    /**
     * 逻辑写入计数(只增不减):既是下一个待写入元素的逻辑序号,也等于累计写入总数。
     *
     * 物理槽位 = `writeCount % capacity`;当 `writeCount >= capacity` 时表示已发生滚动覆盖。
     */
    private var writeCount = 0L

    /**
     * 追加一个元素;缓冲已满时覆盖最旧元素。
     *
     * @param item 待写入元素。
     */
    @Synchronized
    fun add(item: T) {
        slots[(writeCount % capacity).toInt()] = item
        writeCount++
    }

    /**
     * 获取最近写入的至多 [limit] 个元素,**按"新 → 旧"顺序返回**(下标 0 为最新写入的元素)。
     *
     * 即:返回列表的第一个元素是最后一次 [add] 进来的,随下标递增依次回溯到更早的元素。
     * 实际返回数量为 `min(limit, 当前已存元素数)`;[limit] ≤ 0 时返回空列表。
     *
     * @param limit 期望返回的最大条数。
     * @return 最近元素列表(新→旧);无数据或 [limit] 非正时为空列表。
     */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun snapshot(limit: Int): List<T> {
        if (limit <= 0) {
            return emptyList()
        }
        // 当前已存元素数:未写满时为 writeCount,写满后恒为 capacity
        val stored = minOf(writeCount, capacity.toLong()).toInt()
        val count = minOf(limit, stored)
        if (count == 0) {
            return emptyList()
        }
        val result = ArrayList<T>(count)
        // 从最新写入序号(writeCount-1)起向前回溯 count 个,逐个对容量取模定位物理槽
        var seq = writeCount - 1
        repeat(count) {
            val index = (seq % capacity).toInt()
            result.add(slots[index] as T)
            seq--
        }
        return result
    }

    /**
     * 当前已存元素数(写满后恒等于 [capacity])。
     *
     * @return 已存元素数量。
     */
    @Synchronized
    fun size(): Int = minOf(writeCount, capacity.toLong()).toInt()
}
