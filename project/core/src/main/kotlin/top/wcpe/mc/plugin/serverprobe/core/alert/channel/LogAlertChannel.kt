package top.wcpe.mc.plugin.serverprobe.core.alert.channel

import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannel
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannelRegistry
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertEvent
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertLevel
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 日志告警通道(FR5):把告警事件写入服务器控制台/日志文件。
 *
 * 平台无关、零外部依赖,是默认且最可靠的告警出口。日志级别按事件语义映射(规范第 12 条):
 * - 触发 + [AlertLevel.CRITICAL] → ERROR;
 * - 触发 + [AlertLevel.WARN] → WARN;
 * - 恢复 → INFO。
 *
 * 文案为中文日志(日志属运维内部视图,非面向玩家的 i18n 呈现面,故不走语言文件)。
 *
 * 生命周期:作为 IOC [Service] 由容器实例化并注入 [registry];[register] 在依赖注入完成后
 * ([PostConstruct])据 `alert.channels.log` 开关决定是否自注册到 [AlertChannelRegistry]。
 */
@Service
class LogAlertChannel : AlertChannel {

    /** 告警通道注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: AlertChannelRegistry

    /**
     * 依赖注入完成后按配置开关自注册。
     *
     * 采用 [PostConstruct] 确保 [registry] 已注入;`alert.channels.log` 为 false 时不注册,
     * 引擎广播时自然不含本通道。
     */
    @PostConstruct
    fun register() {
        if (!ProbeConfig.alertChannelLog()) return
        registry.register(this)
    }

    /**
     * 按级别将告警事件写入日志。
     *
     * @param event 告警事件(触发或恢复)。
     */
    override fun publish(event: AlertEvent) {
        if (!event.firing) {
            ProbeLogger.info("[告警恢复] ${describe(event)}")
            return
        }
        val message = "[告警触发] ${describe(event)}"
        when (event.rule.level) {
            AlertLevel.CRITICAL -> ProbeLogger.error(message)
            AlertLevel.WARN -> ProbeLogger.warn(message)
        }
    }

    /**
     * 拼接事件的可读中文描述:实例、类型、观测值、阈值。
     *
     * @param event 告警事件。
     * @return 形如 `实例=srv-1 类型=TPS_LOW 观测值=12.34 阈值=15.00` 的描述串。
     */
    private fun describe(event: AlertEvent): String {
        val rule = event.rule
        return "实例=${event.serverId} 类型=${rule.type} 级别=${rule.level} " +
            "观测值=${format(event.value)} 阈值=${format(rule.threshold)}"
    }

    /**
     * 数值格式化为保留两位小数的字符串。
     *
     * @param value 数值。
     * @return 两位小数字符串。
     */
    private fun format(value: Double): String = String.format("%.2f", value)
}
