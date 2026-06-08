package top.wcpe.mc.plugin.serverprobe.core.startup

import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import kotlin.math.abs

/**
 * 启动画像对比器(FR1 启动对比)。
 *
 * 将本次启动画像与上一次对比,生成一行人类可读的中文摘要:总时长变化(±ms 与 ±%)+ 慢插件 Top 变化简述。
 * 无上一次画像(首次记录)时返回固定文案。
 *
 * 无状态纯逻辑(仅依赖入参),以 `object` 实现,不纳入 IOC 容器;不读取任何外部数据,故可直接单测。
 */
object StartupComparator {

    /**
     * 生成本次启动相对上次的对比摘要。
     *
     * @param current 本次启动画像。
     * @param previous 上一次启动画像;首次记录时为 null。
     * @return 单行中文摘要。
     */
    fun summary(current: StartupProfile, previous: StartupProfile?): String {
        if (previous == null) {
            return NO_BASELINE
        }
        return "${totalDeltaText(current.totalMs, previous.totalMs)};${slowestDeltaText(current, previous)}"
    }

    /**
     * 总时长变化文案:`总时长 a.as → b.bs(±x.xs, ±y.y%)`,基线为 0 时省略百分比。
     *
     * @param currentMs 本次总时长(毫秒)。
     * @param previousMs 上次总时长(毫秒)。
     * @return 总时长变化描述。
     */
    private fun totalDeltaText(currentMs: Long, previousMs: Long): String {
        val deltaMs = currentMs - previousMs
        val head = "总时长 ${formatSeconds(previousMs)} → ${formatSeconds(currentMs)}"
        // 上次为 0 无法计算百分比(避免除零),仅给出绝对差
        if (previousMs <= 0L) {
            return "$head(${formatSignedSeconds(deltaMs)})"
        }
        val percent = deltaMs * PERCENT_BASE / previousMs.toDouble()
        return "$head(${formatSignedSeconds(deltaMs)}, ${formatSignedPercent(percent)})"
    }

    /**
     * 最慢插件变化简述:对比两次"最慢插件"(各取 enableMs 最大者)。
     *
     * 同名则给出该插件本次/上次耗时;不同名则提示榜首易主;任一侧无插件数据时给出相应提示。
     *
     * @param current 本次启动画像。
     * @param previous 上一次启动画像。
     * @return 慢插件变化描述。
     */
    private fun slowestDeltaText(current: StartupProfile, previous: StartupProfile): String {
        val cur = current.pluginTimings.maxByOrNull { it.enableMs }
        val prev = previous.pluginTimings.maxByOrNull { it.enableMs }
        if (cur == null) {
            return "最慢插件:本次无插件耗时数据"
        }
        if (prev == null) {
            return "最慢插件:${cur.name} ${formatSeconds(cur.enableMs)}(上次无基线数据)"
        }
        if (cur.name == prev.name) {
            return "最慢插件:${cur.name} ${formatSeconds(prev.enableMs)} → ${formatSeconds(cur.enableMs)}"
        }
        return "最慢插件:由 ${prev.name}(${formatSeconds(prev.enableMs)})变为 ${cur.name}(${formatSeconds(cur.enableMs)})"
    }

    /**
     * 毫秒格式化为保留一位小数的秒(如 `12.3s`)。
     *
     * @param ms 毫秒值。
     * @return 形如 `x.xs` 的字符串。
     */
    private fun formatSeconds(ms: Long): String = String.format("%.1fs", ms / MILLIS_PER_SECOND)

    /**
     * 带符号的秒差(如 `+1.2s` / `-0.5s`)。
     *
     * @param deltaMs 毫秒差。
     * @return 带符号秒差字符串。
     */
    private fun formatSignedSeconds(deltaMs: Long): String {
        val sign = if (deltaMs >= 0) "+" else "-"
        return String.format("%s%.1fs", sign, abs(deltaMs) / MILLIS_PER_SECOND)
    }

    /**
     * 带符号的百分比(如 `+12.3%` / `-4.0%`)。
     *
     * @param percent 百分比数值。
     * @return 带符号百分比字符串。
     */
    private fun formatSignedPercent(percent: Double): String {
        val sign = if (percent >= 0) "+" else "-"
        return String.format("%s%.1f%%", sign, abs(percent))
    }

    /** 首次记录(无上一次画像)时的固定文案。 */
    private const val NO_BASELINE = "无基线对比(首次记录)"

    /** 一秒的毫秒数。 */
    private const val MILLIS_PER_SECOND = 1000.0

    /** 百分比基数。 */
    private const val PERCENT_BASE = 100.0
}
