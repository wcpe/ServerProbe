package top.wcpe.mc.plugin.serverprobe.core.alert

/**
 * 告警级别(FR5)。
 *
 * 用于区分告警的严重程度,影响通道的呈现方式(如日志通道 [WARN] 走 warn、[CRITICAL] 走 error)。
 * 仅两档,避免过度分级带来运维心智负担。
 */
enum class AlertLevel {

    /** 警告级:指标越线但尚不致命,提示关注。 */
    WARN,

    /** 严重级:指标显著恶化,需要立即处置。 */
    CRITICAL
}
