package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 自建 tick 时钟(Legacy / Self 采样路径的数据源)。
 *
 * 通过 TabooLib `submit(period = 1, async = false)` 每 tick 在服务器主线程触发一次,完成两件事:
 * 1. 将"距上一 tick 的实际间隔(纳秒)"喂入 [histogram],作为 MSPT 估算来源;
 * 2. 记录每个 tick 的发生时刻(纳秒),供 [SelfTickSampler] 按 1m/5m/15m 时间窗推算 TPS。
 *
 * 说明:此处用 tick 间隔近似单 tick 耗时(MSPT)。在主线程不卡顿时,间隔 ≈ 50ms + 真实 tick 耗时
 * 中的调度抖动;它能反映"掉 tick"趋势但并非服务端内部精确 MSPT。**M2 完善**:Legacy/Self 路径下
 * 可改为在 tick 起止各采一次时间戳以更贴近真实单 tick 耗时,当前以间隔近似务实落地。
 *
 * 生命周期由 [BukkitServerCollector] 持有并驱动:仅在选中 Legacy/Self 采样器时 [start],
 * 在插件卸载时 [stop]。非线程安全的启停约定:[start]/[stop] 仅由 collector 在生命周期回调中调用。
 *
 * @property histogram 接收 tick 间隔样本的 MSPT 直方图。
 */
class TickClock(
    val histogram: MsptHistogram
) {

    /** tick 时间戳环形缓冲(纳秒),容量覆盖最近约 15 分钟;为 0 表示该槽尚未写入。 */
    private val tickTimestamps = LongArray(TIMESTAMP_CAPACITY)

    /** 时间戳环形游标;读写均在 @Synchronized 方法内,由 monitor 保护,无需 @Volatile。 */
    private var timestampIndex = 0

    /** 上一 tick 的纳秒时刻;首 tick 时为 0(跳过首个间隔,避免把启动间隔计入)。仅在主线程 onTick 中访问,无需同步。 */
    private var lastTickNanos = 0L

    /** 调度任务句柄,用于 [stop] 取消;未启动时为 null。 */
    private var task: PlatformExecutor.PlatformTask? = null

    /**
     * 启动每 tick 调度(幂等:重复调用忽略)。
     */
    fun start() {
        if (task != null) {
            return
        }
        task = submit(period = TICK_PERIOD, async = false) { onTick() }
        ProbeLogger.debug("tick 时钟已启动(每 tick 采样)")
    }

    /**
     * 停止调度并释放任务句柄(幂等)。
     */
    fun stop() {
        task?.cancel()
        task = null
        ProbeLogger.debug("tick 时钟已停止")
    }

    /**
     * 单 tick 回调:记录间隔与时间戳。仅由调度器在主线程调用。
     */
    private fun onTick() {
        val now = System.nanoTime()
        if (lastTickNanos != 0L) {
            histogram.add(now - lastTickNanos)
        }
        lastTickNanos = now
        recordTimestamp(now)
    }

    /**
     * 写入一个 tick 时间戳到环形缓冲。
     *
     * @param now 当前 tick 纳秒时刻。
     */
    @Synchronized
    private fun recordTimestamp(now: Long) {
        tickTimestamps[timestampIndex] = now
        timestampIndex = (timestampIndex + 1) % TIMESTAMP_CAPACITY
    }

    /**
     * 统计最近 [windowSeconds] 秒内发生的 tick 数,据此推算该窗口平均 TPS。
     *
     * 供 [SelfTickSampler] 调用。窗口内尚无足够样本时返回 null(避免给出虚低 TPS)。
     *
     * @param windowSeconds 统计窗口(秒)。
     * @return 窗口平均 TPS;无样本时返回 null。
     */
    fun tpsOver(windowSeconds: Int): Double? {
        val now = System.nanoTime()
        val threshold = now - windowSeconds.toLong() * NANOS_PER_SECOND
        val snapshot = snapshotTimestamps()
        var count = 0
        for (ts in snapshot) {
            // 0 为未写入槽;只统计落在窗口内的有效时间戳
            if (ts != 0L && ts >= threshold) {
                count++
            }
        }
        if (count == 0) {
            return null
        }
        return count.toDouble() / windowSeconds
    }

    /**
     * 对时间戳环形缓冲做一致性快照。
     *
     * @return 时间戳数组副本(含未写入的 0 槽,由调用方过滤)。
     */
    @Synchronized
    private fun snapshotTimestamps(): LongArray = tickTimestamps.copyOf()

    private companion object {

        /** 调度周期:每 1 tick。 */
        private const val TICK_PERIOD = 1L

        /** 一秒的纳秒数。 */
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * tick 时间戳缓冲容量:覆盖最近 15 分钟。
         *
         * 20 TPS × 60 秒 × 15 分钟 = 18000,留出冗余取整为 18000;约 144KB,可忽略。
         */
        private const val TIMESTAMP_CAPACITY = 18_000
    }
}
