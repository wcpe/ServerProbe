package top.wcpe.mc.plugin.serverprobe.bukkit.collector

import top.wcpe.mc.plugin.serverprobe.api.model.PingBucket

/**
 * 在线玩家 ping 分布分桶器(FR2.4)。
 *
 * 平台无关纯函数:把一组玩家 RTT(毫秒)归入固定区间桶,输出有序的 [PingBucket] 列表。
 * 固定分桶规则(与命令展示、Prometheus 标签口径一致):
 * - `&lt;50ms`:[0, 50)
 * - `50-100ms`:[50, 100)
 * - `100-200ms`:[100, 200)
 * - `200-500ms`:[200, 500)
 * - `500ms+`:[500, ∞)
 *
 * 设计为无状态 object,便于单元测试(给定 ping 列表即可断言各桶计数)。
 */
object PingBuckets {

    /** 固定分桶边界(毫秒,升序);桶 i 覆盖 [buckets[i], buckets[i+1]),最后一桶无上限。 */
    private val BOUNDS = intArrayOf(0, 50, 100, 200, 500)

    /** 桶数 = 边界数(每个边界起一个桶)。 */
    private const val BUCKET_COUNT = 5

    /**
     * 把一组玩家 RTT 归入固定区间桶。
     *
     * 负值/非法样本直接忽略(视为不可用)。返回恒为 5 个桶(固定顺序,计数可能为 0),
     * 便于展示与导出时保持口径一致。
     *
     * @param pings 各在线玩家的 RTT 毫秒列表;非法值会被过滤。
     * @return 有序桶列表(恒 5 桶)。
     */
    fun bucket(pings: List<Int>): List<PingBucket> {
        val counts = IntArray(BUCKET_COUNT)
        pings.forEach { ping ->
            if (ping < 0) return@forEach
            val idx = bucketIndex(ping)
            if (idx >= 0) counts[idx]++
        }
        return (0 until BUCKET_COUNT).map { i ->
            val min = BOUNDS[i]
            val max = if (i == BUCKET_COUNT - 1) -1 else BOUNDS[i + 1]
            PingBucket.builder()
                .label(labelOf(min, max))
                .minMs(min)
                .maxMs(max)
                .count(counts[i])
                .build()
        }
    }

    /**
     * 计算某 RTT 命中的桶下标。
     *
     * @param ping RTT 毫秒(已过滤非负)。
     * @return 桶下标;理论上 [0, BUCKET_COUNT),异常值返回 -1(调用方忽略)。
     */
    private fun bucketIndex(ping: Int): Int {
        for (i in BOUNDS.indices) {
            val max = if (i == BUCKET_COUNT - 1) Int.MAX_VALUE else BOUNDS[i + 1]
            if (ping >= BOUNDS[i] && ping < max) return i
        }
        return -1
    }

    /**
     * 生成桶标签(中英通用,命令展示直用)。
     *
     * @param min 区间下限(毫秒,含)。
     * @param max 区间上限(毫秒,不含);-1 表示无上限。
     * @return 如 `&lt;50ms`、`50-100ms`、`500ms+`。
     */
    private fun labelOf(min: Int, max: Int): String = when {
        max < 0 -> "${min}ms+"
        min == 0 -> "<${max}ms"
        else -> "$min-${max}ms"
    }
}
