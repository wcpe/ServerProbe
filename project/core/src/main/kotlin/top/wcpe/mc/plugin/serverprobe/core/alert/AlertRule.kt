package top.wcpe.mc.plugin.serverprobe.core.alert

/**
 * 一条告警规则(FR5)。
 *
 * 描述"对哪个指标、超过什么阈值、连续多少个采集周期、以什么级别"告警。规则集由
 * [top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig] 从 `config.yml` 的 `alert.rules` 段解析而来。
 *
 * @property type 告警类型,决定取值与比较口径(见 [AlertType])。
 * @property threshold 阈值,语义随 [type] 而定(如 TPS 下限、MSPT 上限、堆占用率上限、死锁个数下限)。
 * @property sustainCycles 持续越线达到几个采集周期才真正触发(防抖),最小为 1(即立即触发,如死锁)。
 * @property level 告警级别(见 [AlertLevel])。
 * @property enabled 是否启用本条规则;false 时引擎跳过。
 */
data class AlertRule(
    val type: AlertType,
    val threshold: Double,
    val sustainCycles: Int,
    val level: AlertLevel,
    val enabled: Boolean
)
