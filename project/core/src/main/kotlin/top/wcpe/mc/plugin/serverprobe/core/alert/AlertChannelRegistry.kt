package top.wcpe.mc.plugin.serverprobe.core.alert

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 告警通道注册中心(FR5)。
 *
 * 在 [AlertEngine] 与各 [AlertChannel] 实现之间充当解耦的装配点:引擎不在编译期依赖任何具体通道,
 * 各通道(core 的日志/Webhook、platform-bukkit 的游戏内)于自身就绪后调用 [register] 自注册,
 * 引擎据 [channels] 统一广播。沿用 [top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry] 的自注册范式。
 *
 * **职责单一(规范第 1/10 条)**:专司告警通道注册,刻意**不**并入采集器用的 `ProbeRegistry`——
 * 二者关注点不同(采集 vs 告警呈现),分开避免注册中心上帝化。
 *
 * 并发模型:注册多发生于启动期、读取发生于编排线程(异步)调 [AlertEngine.evaluate] 时,读多写少,
 * 故底层用 [CopyOnWriteArrayList] 保证读无锁、写安全。
 */
@Service
class AlertChannelRegistry {

    /** 已注册的告警通道集合。 */
    private val channelList = CopyOnWriteArrayList<AlertChannel>()

    /**
     * 注册一个告警通道;重复实例会被忽略。
     *
     * @param channel 待注册的通道。
     */
    fun register(channel: AlertChannel) {
        if (channelList.addIfAbsent(channel)) {
            ProbeLogger.debug("告警通道已注册:${channel.javaClass.name}")
        } else {
            ProbeLogger.warn("重复注册告警通道,已忽略:${channel.javaClass.name}")
        }
    }

    /**
     * 已注册的告警通道只读视图。
     *
     * @return 当前已注册通道的不可变快照副本。
     */
    val channels: List<AlertChannel>
        get() = channelList.toList()
}
