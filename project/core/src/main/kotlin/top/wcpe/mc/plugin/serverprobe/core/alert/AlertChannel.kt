package top.wcpe.mc.plugin.serverprobe.core.alert

/**
 * 告警通道(FR5):把一次 [AlertEvent] 呈现/投递出去。
 *
 * 通道在编译期与具体平台解耦——core 内置日志([top.wcpe.mc.plugin.serverprobe.core.alert.channel.LogAlertChannel])
 * 与 Webhook([top.wcpe.mc.plugin.serverprobe.core.alert.channel.WebhookAlertChannel])两种;游戏内通道位于
 * platform-bukkit。各实现于初始化完成后自注册到 [AlertChannelRegistry],由 [AlertEngine] 统一广播。
 *
 * 约定:[publish] 应自行容错、不向调用方抛出异常(引擎已用 runCatching 兜底,但实现仍应避免无意义抛错),
 * 且不得长时间阻塞编排线程——需要 IO(如 Webhook)的实现应转异步执行。
 */
interface AlertChannel {

    /**
     * 呈现/投递一次告警事件。
     *
     * @param event 待呈现的告警事件(触发或恢复)。
     */
    fun publish(event: AlertEvent)
}
