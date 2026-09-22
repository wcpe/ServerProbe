package top.wcpe.mc.plugin.serverprobe.command

import taboolib.common.platform.ProxyCommandSender
import taboolib.module.lang.sendLang
import top.wcpe.mc.plugin.serverprobe.core.alert.channel.AlertEventRecord
import top.wcpe.mc.plugin.serverprobe.core.alert.channel.AlertHistoryChannel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `/probe alerts` 的取值与渲染(FR-29)。
 *
 * 独立成文件的原因与 [McpCommandHandler] 一致:命令宿主 [ProbeCommand] 是单 object,
 * 再内联本组动作会超出 detekt 对单 object 函数数的阈值;且"读历史 + 格式化"自成闭环,
 * 与其余只读子命令无共享状态。命令层保持无状态、仅做分发。
 */
internal object AlertsCommandHandler {

    /** `/probe alerts` 展示的最近告警事件条数。 */
    private const val DISPLAY_LIMIT = 10

    /** 展示时间格式(实例本地时区)。 */
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    /**
     * 读取最近告警事件并输出。
     *
     * 读取告警历史 JSONL(按日分桶),由新到旧展示至多 [DISPLAY_LIMIT] 条;
     * 读盘为一次性小文件读取(告警事件量级极低),命令线程可承受。
     * 引擎未开启或无历史时提示"暂无告警历史"。
     *
     * @param sender 命令发送者(控制台/玩家,仅读 i18n 输出,不触碰世界状态)。
     * @param alertHistory 告警历史通道(IOC 注入)。
     */
    fun render(sender: ProxyCommandSender, alertHistory: AlertHistoryChannel) {
        val records = alertHistory.recentEvents(DISPLAY_LIMIT)
        if (records.isEmpty()) {
            sender.sendLang("command-alerts-empty")
            return
        }
        sender.sendLang("command-alerts-title", records.size)
        records.forEach { record ->
            sender.sendLang(
                "command-alerts-row",
                TIME_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(record.timestampMs)),
                levelColor(record.level),
                if (record.action == "FIRE") "▲" else "▼",
                record.type,
                formatNumber(record.value),
                formatNumber(record.threshold),
            )
        }
    }

    /** 告警级别颜色:WARN=黄、CRITICAL=红、其余=灰。 */
    private fun levelColor(level: String): String = when (level) {
        "WARN" -> "e"
        "CRITICAL" -> "c"
        else -> "7"
    }

    /** 观测值/阈值展示:整数省小数,非整数保留一位。 */
    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else "%.1f".format(value)
}
