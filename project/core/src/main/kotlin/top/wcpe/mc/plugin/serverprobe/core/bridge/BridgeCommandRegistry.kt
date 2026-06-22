package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * 治理指令处理器注册中心(FR-067,JianManager ADR-016 探针侧)。
 *
 * 在 [BridgeClient](core,平台无关)与平台层 [BridgeCommandHandler] 实现之间充当解耦装配点:
 * core 不在编译期依赖任何 Bukkit/Bungee API,各平台(platform-bukkit / platform-bungee)于自身就绪后
 * 调用 [register] 把本平台的治理执行器自注册进来;[BridgeClient] 收到 `command` 帧时经 [handler] 取用。
 * 沿用 [AlertChannelRegistry] 的自注册范式(规范:注册中心职责单一,不并入采集用的 ProbeRegistry)。
 *
 * ## 单处理器(与告警的多通道不同)
 * 治理执行对单个探针进程只有**唯一**平台实现(Bukkit 子服端 或 Bungee 代理端,二选一,由平台门决定),
 * 故持有单个 [handler] 而非列表;重复注册以最后一次为准并 WARN(理论上不应发生)。
 * 未注册(探针独立使用、或平台模块缺失)时 [handler] 为 null——[BridgeClient] 据此回 success=false 优雅降级。
 *
 * 并发模型:注册发生于启动期、读取发生于桥读线程,读多写少且仅单值,用 [AtomicReference] 保证可见性与原子替换。
 */
@Service
class BridgeCommandRegistry {

    /** 当前平台治理执行器;未注册时为 null。 */
    private val handlerRef = AtomicReference<BridgeCommandHandler?>(null)

    /**
     * 注册本平台的治理执行器(平台层于就绪后调用)。
     *
     * @param handler 平台治理执行器。
     */
    fun register(handler: BridgeCommandHandler) {
        val prev = handlerRef.getAndSet(handler)
        if (prev != null && prev !== handler) {
            ProbeLogger.warn("治理指令处理器被重复注册,以最新为准:${handler.javaClass.name}")
        } else {
            ProbeLogger.debug("治理指令处理器已注册:${handler.javaClass.name}")
        }
    }

    /**
     * 当前已注册的治理执行器;未注册时为 null。
     *
     * @return 治理执行器或 null。
     */
    val handler: BridgeCommandHandler?
        get() = handlerRef.get()
}
