package top.wcpe.mc.plugin.serverprobe.core.alert

/**
 * 一次告警事件(FR5)。
 *
 * 由 [AlertEngine] 在规则状态发生跃迁时产出,经各 [AlertChannel] 呈现。同时承载"触发"与"恢复"
 * 两种语义,由 [firing] 区分:[firing] = true 表示越线触发,= false 表示从越线回落到正常(恢复)。
 *
 * @property rule 触发本事件的规则(含类型/阈值/级别等)。
 * @property firing 是否为触发态;true=触发(越线),false=恢复(回落正常)。
 * @property value 产出事件时的观测值(触发时为越线值,恢复时为回落后的正常值)。
 * @property serverId 实例标识,与快照口径一致([top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot.serverId])。
 * @property timestampMs 事件产出时刻(epoch 毫秒)。
 */
data class AlertEvent(
    val rule: AlertRule,
    val firing: Boolean,
    val value: Double,
    val serverId: String,
    val timestampMs: Long
)
