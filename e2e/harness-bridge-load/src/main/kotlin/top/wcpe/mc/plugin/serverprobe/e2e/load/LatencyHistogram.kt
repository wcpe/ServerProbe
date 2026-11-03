package top.wcpe.mc.plugin.serverprobe.e2e.load

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 延迟直方图 —— 无锁分位数统计。
 *
 * ## 为什么不用"存原始值再排序"
 *
 * 压测一次可能灌入几十万条事件。把每个延迟值都存进 `ArrayList` 再 `sorted()`：
 * 内存随样本量线性增长，而排序是 O(n log n)、每次取分位都得重排。
 * 压测器会先被自己的度量拖死。
 *
 * ## 方案：指数分桶
 *
 * 延迟分布是**长尾**的（中位数几十微秒，长尾能到几十毫秒）。线性分桶要么低端
 * 分辨率不够，要么桶数爆炸；指数分桶让相对误差恒定，几十个桶就跨若干数量级。
 *
 * 分位数是**近似值**——对「链路堵不堵」而言，p99 差几微秒无关紧要，
 * 要紧的是别把 8ms 报成 85µs。取桶**上界**作估值：宁可略高，不可报低，
 * 后者会掩盖尖刺。
 *
 * ## 为什么是 AtomicLongArray 而不是锁
 *
 * N 个线程会同时 `record()`。加锁会让「测量」与「被测物」争锁，把测出的延迟抬高。
 * `snapshot()` 只在结束时调一次，那时已无并发写。
 *
 * 内部以**微秒**计数（整数，避免浮点），根桶宽度 1µs。
 */
internal class LatencyHistogram(
    maxTrackedMillis: Long = DEFAULT_MAX_TRACKED_MILLIS,
) {

    /** 线性区桶数：覆盖 0..[subBucketCount] 微秒时精度为 1µs。 */
    private val subBucketCount: Int = 1024

    /** 线性区位数（subBucketCount 是 2 的幂，取其位移）。 */
    private val subBucketShift: Int = Integer.numberOfTrailingZeros(subBucketCount)

    /** 需要多少个桶才能覆盖到 [maxTrackedMillis]。 */
    private val bucketCount: Int = computeBucketCount(maxTrackedMillis)

    private val counts = AtomicLongArray(bucketCount)
    private val overflowCount = AtomicLong(0)
    private val totalCount = AtomicLong(0)
    private val minNanos = AtomicLong(Long.MAX_VALUE)
    private val maxNanos = AtomicLong(0)

    /** 记录一次观测（纳秒）。负值归零，**不丢弃样本**（丢弃会让分位偷低）。 */
    fun record(nanos: Long) {
        val value = if (nanos < 0) 0 else nanos
        totalCount.incrementAndGet()
        updateMin(value)
        updateMax(value)

        val micros = value / 1000
        val index = indexOf(micros)
        if (index >= bucketCount) {
            overflowCount.incrementAndGet()
        } else {
            counts.incrementAndGet(index)
        }
    }

    fun snapshot(): Snapshot {
        val total = totalCount.get()
        if (total == 0L) return Snapshot.EMPTY

        // 一次性导出到普通数组：之后只读。
        val local = LongArray(bucketCount) { counts.get(it) }
        val maxNs = maxNanos.get()
        val minNs = if (minNanos.get() == Long.MAX_VALUE) 0L else minNanos.get()

        return Snapshot(
            count = total,
            minNanos = minNs,
            maxNanos = maxNs,
            p50Nanos = percentile(local, total, 50.0, maxNs),
            p95Nanos = percentile(local, total, 95.0, maxNs),
            p99Nanos = percentile(local, total, 99.0, maxNs),
            p999Nanos = percentile(local, total, 99.9, maxNs),
        )
    }

    /**
     * 计算分位：`rank = ceil(pct/100 * total)`（1-based），
     * 从最低桶向上累加，第一个使累计数 >= rank 的桶即目标桶，取其**上界**。
     */
    private fun percentile(buckets: LongArray, total: Long, target: Double, maxNs: Long): Long {
        val rank = Math.ceil(target / 100.0 * total).toLong().coerceIn(1L, total)
        var cumulative = 0L
        for (i in buckets.indices) {
            cumulative += buckets[i]
            if (cumulative >= rank) {
                return microsToNanos(bucketUpperBoundMicros(i))
            }
        }
        // 仅有溢出桶样本（超过 maxTrackedMillis）：用最大值兜底。
        return maxNs
    }

    /**
     * 微秒值 → 桶下标。
     *
     * ## 布局（经典 HdrHistogram 两级桶）
     *
     * - **线性区** `[0, subBucketCount)`：一值一桶，1µs 精度。
     * - **指数区**：每翻一倍数量级占 `subBucketCount/2` 个桶。
     *   下标 `bucketIndex` 的桶覆盖 `[2^(bucketIndex-1)*subBucketCount, 2^bucketIndex*subBucketCount)`，
     *   其中 `subIndex` 归一化到 `[subBucketCount/2, subBucketCount)`。
     *
     * ## 曾经踩过的坑（这段注释就是那次失败病历）
     *
     * 第一版写成 `(subIndex - subBucketCount)`，对 `micros` 在 `[subBucketCount, 2*subBucketCount)`
     * 区间时 `subIndex` 只到 `subBucketCount-1`，相减得**负数索引**——长尾样本要么没落进桶、
     * 要么落到错误位置。后果是 p99 报成 50µs 而真值是 20ms：**长尾被静默吞掉**。
     * 这正是分位数实现最危险的失败模式——不报错，只是让“链路已经堵了”显示成健康。
     * 正确形式是归一到 `subBucketCount/2` 并对下标做偏移，见下方实现。
     */
    private fun indexOf(micros: Long): Int {
        if (micros < subBucketCount) return micros.toInt()
        // subBucketCount 是 2 的幂；micros >= 它时 "有效位数 - 位移" 恰为指数区级数（从 1 开始）。
        val significantBits = 64 - java.lang.Long.numberOfLeadingZeros(micros)
        val bucketIndex = significantBits - subBucketShift
        val subIndex = micros ushr bucketIndex
        // subIndex ∈ [subBucketCount/2, subBucketCount)，归一到 [0, subBucketCount/2)
        val normalized = subIndex - (subBucketCount ushr 1)
        return subBucketCount + (bucketIndex - 1) * (subBucketCount ushr 1) + normalized.toInt()
    }

    /** 桶下标 → 该桶覆盖的微秒**上界**（保守估值，反函数）。 */
    private fun bucketUpperBoundMicros(index: Int): Long {
        if (index < subBucketCount) return index.toLong()
        val offset = index - subBucketCount
        val halfBucket = subBucketCount ushr 1
        val bucketIndex = offset / halfBucket + 1
        val normalized = offset % halfBucket
        val subIndex = normalized + halfBucket
        // 高 halfBucket 个值映射到同一桶，实际覆盖到 (subIndex+1)<<bucketIndex - 1
        return ((subIndex.toLong() + 1) shl bucketIndex) - 1
    }

    private fun microsToNanos(micros: Long): Long = micros * 1000

    private fun computeBucketCount(maxMillis: Long): Int {
        val maxMicros = maxMillis * 1000
        val significantBits = 64 - java.lang.Long.numberOfLeadingZeros(maxMicros)
        // 指数区级数：从 1 到 (significantBits - subBucketShift)
        val exponentLevels = (significantBits - subBucketShift).coerceAtLeast(1)
        // 线性区 subBucketCount 个 + 指数区 exponentLevels * (subBucketCount/2) 个，再留一级余量。
        return subBucketCount + (exponentLevels + 1) * (subBucketCount ushr 1)
    }

    private fun updateMin(value: Long) {
        while (true) {
            val current = minNanos.get()
            if (value >= current) return
            if (minNanos.compareAndSet(current, value)) return
        }
    }

    private fun updateMax(value: Long) {
        while (true) {
            val current = maxNanos.get()
            if (value <= current) return
            if (maxNanos.compareAndSet(current, value)) return
        }
    }

    /** 分位数快照。 */
    data class Snapshot(
        val count: Long,
        val minNanos: Long,
        val maxNanos: Long,
        val p50Nanos: Long,
        val p95Nanos: Long,
        val p99Nanos: Long,
        val p999Nanos: Long,
    ) {
        fun p50Millis(): Double = p50Nanos / 1_000_000.0
        fun p95Millis(): Double = p95Nanos / 1_000_000.0
        fun p99Millis(): Double = p99Nanos / 1_000_000.0
        fun p999Millis(): Double = p999Nanos / 1_000_000.0
        fun minMillis(): Double = minNanos / 1_000_000.0
        fun maxMillis(): Double = maxNanos / 1_000_000.0

        companion object {
            val EMPTY = Snapshot(0, 0, 0, 0, 0, 0, 0)
        }
    }

    private companion object {
        /** 默认跟踪到 60 秒（足够覆盖任何「堵住」的观测）。 */
        const val DEFAULT_MAX_TRACKED_MILLIS = 60_000L
    }
}
