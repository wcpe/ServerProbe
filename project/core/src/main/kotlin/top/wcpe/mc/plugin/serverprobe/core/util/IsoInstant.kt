package top.wcpe.mc.plugin.serverprobe.core.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ISO-8601 UTC 时间文本(秒精度)的格式化与解析。
 *
 * 协议规格 §3.4.2 / §3.4.6 的时间字段形如 `2026-10-19T03:12:44Z`,而 `Instant.toString()`
 * 会带上毫秒(`...44.123Z`)——控制面对时间字段做等值比较/落库时会因毫秒产生噪音,
 * 故收口到本工具统一成"秒精度 + Z"的固定形态。
 *
 * 解析侧只做**宽容读取**(解析失败返回 null):托管缓存等落盘文件的时间字段来自上一版进程,
 * 绝不允许格式不符导致整份缓存被判损坏——那样会因为一个时间戳丢掉整份有效配置。
 */
internal object IsoInstant {

    /** 输出格式(秒精度,UTC)。 */
    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /**
     * 当前时刻的 ISO-8601 文本。
     *
     * @return 形如 `2026-10-19T03:12:44Z` 的文本。
     */
    fun now(): String = format(System.currentTimeMillis())

    /**
     * 把 epoch 毫秒格式化为 ISO-8601 文本。
     *
     * @param epochMillis epoch 毫秒。
     * @return 秒精度 UTC 文本。
     */
    fun format(epochMillis: Long): String = FORMATTER.format(Instant.ofEpochMilli(epochMillis))

    /**
     * 解析 ISO-8601 文本为 epoch 秒。
     *
     * @param text 时间文本。
     * @return epoch 秒;解析失败(含空白/缺 Z/非时间)返回 null。
     */
    fun parseEpochSeconds(text: String): Long? =
        runCatching { Instant.parse(text).epochSecond }.getOrNull()
}
