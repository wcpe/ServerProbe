package top.wcpe.mc.plugin.serverprobe.bukkit.alert

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.submit
import taboolib.platform.util.sendLang
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannel
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannelRegistry
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertEvent
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertLevel
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertType
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 游戏内告警通道(FR5,Bukkit 端):把告警事件以聊天消息推送给在线的 OP 或持有告警权限的玩家。
 *
 * 仅 Bukkit 平台具备"玩家"概念,故本通道落位于 platform-bukkit;文案走 i18n 语言文件
 * (面向玩家的呈现面,与日志通道的中文日志不同)。
 *
 * ## 线程模型:切回主线程发消息
 * [publish] 由编排线程(异步)调用,而 Bukkit 在线玩家遍历与发消息应在服务器主线程进行。
 * 故经 `submit(async = false)` 切回主线程执行实际推送(规范:Bukkit API 须主线程访问)。
 *
 * ## 接收对象
 * 在线玩家中,`isOp` 或持有 `serverprobe.alert` 权限者。避免向普通玩家刷告警。
 *
 * 生命周期:作为 IOC [Service] 由容器实例化并注入 [registry];[register] 在 [PostConstruct] 中
 * 先做**平台门**(IOC 不感知 [PlatformSide],会在所有平台实例化本类),仅 Bukkit 端按
 * `alert.channels.in-game` 开关自注册到 [AlertChannelRegistry]。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class InGameAlertChannel : AlertChannel {

    /** 告警通道注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: AlertChannelRegistry

    /**
     * 依赖注入完成后做平台门并按配置自注册。
     *
     * IOC 容器不感知 [PlatformSide],会在所有平台实例化本类;故在此显式平台门:
     * 仅 Bukkit 端、且 `alert.channels.in-game` 开启时才注册,避免代理端触碰缺失的 Bukkit 类。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        if (!ProbeConfig.alertChannelInGame()) return
        registry.register(this)
        ProbeLogger.info("游戏内告警通道已启用")
    }

    /**
     * 切回主线程,把告警事件以本地化消息推送给 OP / 有权限的在线玩家。
     *
     * @param event 告警事件(触发或恢复)。
     */
    override fun publish(event: AlertEvent) {
        // 切回主线程:遍历在线玩家与 sendMessage 须在主线程
        submit(async = false) {
            val recipients = Bukkit.getOnlinePlayers().filter { it.isOp || it.hasPermission(PERMISSION_ALERT) }
            if (recipients.isEmpty()) return@submit
            recipients.forEach { sendTo(it, event) }
        }
    }

    /**
     * 按事件语义选取语言键并发送给单个玩家。
     *
     * 键选取:恢复 → `alert-recovered`;触发且为死锁([AlertType.DEADLOCK])→ `alert-deadlock`(事件型独立文案);
     * 触发且 [AlertLevel.CRITICAL] → `alert-firing-critical`;触发且 [AlertLevel.WARN] → `alert-firing-warn`。
     * 占位实参统一为 `(类型, 观测值, 阈值)`,与语言文件占位一一对应。
     *
     * @param player 接收玩家。
     * @param event 告警事件。
     */
    private fun sendTo(player: Player, event: AlertEvent) {
        val rule = event.rule
        val type = rule.type.name
        // 死锁为计数型,观测值按整数渲染(避免出现"1.00 个");其余指标(TPS/MSPT/百分比)仍保留两位小数
        val isDeadlock = rule.type == AlertType.DEADLOCK
        val value = if (isDeadlock) event.value.toInt().toString() else format(event.value)
        val threshold = format(rule.threshold)
        val langKey = when {
            !event.firing -> LANG_RECOVERED
            isDeadlock -> LANG_DEADLOCK
            rule.level == AlertLevel.CRITICAL -> LANG_FIRING_CRITICAL
            else -> LANG_FIRING_WARN
        }
        player.sendLang(langKey, type, value, threshold)
    }

    /**
     * 数值格式化为保留两位小数的字符串。
     *
     * @param value 数值。
     * @return 两位小数字符串。
     */
    private fun format(value: Double): String = String.format("%.2f", value)

    private companion object {

        /** 接收告警所需权限(OP 亦可接收)。 */
        private const val PERMISSION_ALERT = "serverprobe.alert"

        /** 语言键:触发(警告级)。 */
        private const val LANG_FIRING_WARN = "alert-firing-warn"

        /** 语言键:触发(严重级)。 */
        private const val LANG_FIRING_CRITICAL = "alert-firing-critical"

        /** 语言键:恢复。 */
        private const val LANG_RECOVERED = "alert-recovered"

        /** 语言键:死锁(事件型独立文案)。 */
        private const val LANG_DEADLOCK = "alert-deadlock"
    }
}
