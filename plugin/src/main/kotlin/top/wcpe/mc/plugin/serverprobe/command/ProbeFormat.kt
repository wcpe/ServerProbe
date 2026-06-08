package top.wcpe.mc.plugin.serverprobe.command

/**
 * `/probe` 命令的数值格式化工具(FR4.1 呈现辅助)。
 *
 * 仅做"取值后的纯文本格式化"(字节 → 可读单位、毫秒、百分比、可空数值 → N/A),
 * 是无副作用的纯函数集合,以 `object` 实现且不纳入 IOC 容器(无状态、无生命周期)。
 *
 * 设计意图:把分散在各子命令里的单位换算集中收口,避免复制粘贴(规范第 5 条)与魔法值(规范第 6 条);
 * "N/A"等不可用占位文案由命令层经 i18n 决定,本工具只负责数值本身的格式化,不直接产出业务文案。
 */
internal object ProbeFormat {

    /**
     * 将字节数格式化为自适应单位的可读字符串(B / KB / MB / GB / TB)。
     *
     * 取小于 1024 进制的最大合适单位,保留两位小数(B 单位无小数);负数(约定的"无上限/无数据")
     * 原样返回供调用方自行判断,不在此处臆断业务语义。
     *
     * @param bytes 字节数;负值表示"无上限/无数据"(由调用方决定如何呈现)。
     * @return 形如 `512.00 MB` 的字符串;负值时返回原始数值字符串。
     */
    fun bytes(bytes: Long): String {
        if (bytes < 0) {
            return bytes.toString()
        }
        if (bytes < UNIT_STEP) {
            return "$bytes B"
        }
        var value = bytes.toDouble()
        var unitIndex = 0
        // 逐级 /1024 直到落入当前单位区间或用尽单位表
        while (value >= UNIT_STEP && unitIndex < BYTE_UNITS.lastIndex) {
            value /= UNIT_STEP
            unitIndex++
        }
        return String.format("%.2f %s", value, BYTE_UNITS[unitIndex])
    }

    /**
     * 将毫秒格式化为整数毫秒文本(如 `1234ms`)。
     *
     * @param ms 毫秒值。
     * @return 形如 `1234ms` 的字符串。
     */
    fun millis(ms: Long): String = "${ms}ms"

    /**
     * 将毫秒格式化为保留一位小数的秒(如 `12.3s`),用于较大耗时的概览展示。
     *
     * @param ms 毫秒值。
     * @return 形如 `12.3s` 的字符串。
     */
    fun seconds(ms: Long): String = String.format("%.1fs", ms / MILLIS_PER_SECOND)

    /**
     * 将运行时长(毫秒)格式化为 `Xd Yh Zm` 形式,自动省略为 0 的高位单位。
     *
     * 全为 0 时返回 `0m`,保证始终有可读输出。
     *
     * @param ms 运行时长(毫秒)。
     * @return 形如 `1d 2h 3m` 的字符串。
     */
    fun duration(ms: Long): String {
        val totalMinutes = ms / MILLIS_PER_MINUTE
        val days = totalMinutes / MINUTES_PER_DAY
        val hours = (totalMinutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        val builder = StringBuilder()
        if (days > 0) {
            builder.append("${days}d ")
        }
        if (days > 0 || hours > 0) {
            builder.append("${hours}h ")
        }
        builder.append("${minutes}m")
        return builder.toString()
    }

    /**
     * 将 0.0–1.0 的占用率格式化为百分比(如 `42.3%`)。
     *
     * 入参为负(约定的"该指标不可用")时返回 null,交由调用方以 i18n 的 N/A 文案呈现。
     *
     * @param ratio 占用率(0.0–1.0);负值表示不可用。
     * @return 百分比字符串;不可用时为 null。
     */
    fun percentOrNull(ratio: Double): String? {
        if (ratio < 0) {
            return null
        }
        return String.format("%.1f%%", ratio * PERCENT_BASE)
    }

    /**
     * 将可空 TPS 数值格式化为保留两位小数的文本;为 null 时返回 null(调用方据此显示 N/A)。
     *
     * @param tps TPS 值;N/A 时为 null。
     * @return 形如 `19.98` 的字符串;为 null 时返回 null。
     */
    fun tpsOrNull(tps: Double?): String? = tps?.let { String.format("%.2f", it) }

    /**
     * 将可空 MSPT 数值格式化为保留两位小数加 `ms`;为 null 时返回 null(调用方据此显示 N/A)。
     *
     * @param mspt MSPT 值(毫秒);N/A 时为 null。
     * @return 形如 `3.21ms` 的字符串;为 null 时返回 null。
     */
    fun msptOrNull(mspt: Double?): String? = mspt?.let { String.format("%.2fms", it) }

    /** 字节进制步长(1024)。 */
    private const val UNIT_STEP = 1024.0

    /** 字节单位表(自适应升级用)。 */
    private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

    /** 一秒的毫秒数。 */
    private const val MILLIS_PER_SECOND = 1000.0

    /** 一分钟的毫秒数。 */
    private const val MILLIS_PER_MINUTE = 60_000L

    /** 一小时的分钟数。 */
    private const val MINUTES_PER_HOUR = 60L

    /** 一天的分钟数。 */
    private const val MINUTES_PER_DAY = 1440L

    /** 百分比基数。 */
    private const val PERCENT_BASE = 100.0
}
