package top.wcpe.mc.plugin.serverprobe.core.mcp

import java.lang.management.GarbageCollectorMXBean

/** 一次 GC 采集样本（单调计数与耗时，可含 lastGcInfo 的堆使用）。 */
data class GcSample(
    val collector: String,
    val count: Long,
    val timeMs: Long,
    val heapAfterGcKb: Long?,
)

/** 两个样本的差分事件。 */
data class GcEvent(
    val collector: String,
    val countDelta: Long,
    val timeDeltaMs: Long,
    val heapAfterGcKb: Long?,
)

/** GC 单调计数两点差分的纯函数（FR-22）；并发回退时钳制归零，不抛异常。 */
object GcDiff {

    /** 从 MXBean 采集样本快照；heapAfterGcKb 仅当 lastGcInfo 可用时填充（反射读取，避免依赖 com.sun 扩展类型）。 */
    fun sample(beans: List<GarbageCollectorMXBean>): List<GcSample> = beans.map { bean ->
        GcSample(
            collector = bean.name,
            count = bean.collectionCount,
            timeMs = bean.collectionTime,
            heapAfterGcKb = heapAfterGcKb(bean),
        )
    }

    /** 反射读取 lastGcInfo.memoryUsageAfterGc 首个池的 used；任何失败（类不支持/无信息）返回 null。 */
    private fun heapAfterGcKb(bean: GarbageCollectorMXBean): Long? = runCatching {
        val info = bean.javaClass.getMethod("getLastGcInfo").invoke(bean) ?: return null
        val after = info.javaClass.getMethod("getMemoryUsageAfterGc").invoke(info) as Map<*, *>
        val first = after.values.firstOrNull() as? java.lang.management.MemoryUsage ?: return null
        first.used / 1024
    }.getOrNull()

    /** 计算差分事件；previous 为 null（首采）时 delta 全零。计数/耗时回退（并发采样）时该收集器整体钳制归零。 */
    fun diff(after: List<GcSample>, previous: List<GcSample>?): List<GcEvent> {
        val baseline = previous?.associateBy { it.collector }.orEmpty()
        return after.map { sample ->
            val before = baseline[sample.collector]
            if (before == null) {
                GcEvent(sample.collector, 0L, 0L, sample.heapAfterGcKb)
            } else {
                val countDelta = (sample.count - before.count).coerceAtLeast(0L)
                val timeDeltaMs = if (sample.count < before.count) 0L
                else (sample.timeMs - before.timeMs).coerceAtLeast(0L)
                GcEvent(sample.collector, countDelta, timeDeltaMs, sample.heapAfterGcKb)
            }
        }
    }
}
