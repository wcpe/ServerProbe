package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 治理指令处理器注册中心(控制面 ADR-016 探针侧)。
 *
 * 在 [BridgeClient](core,平台无关)与平台层 [BridgeCommandHandler] 实现之间充当解耦装配点:
 * core 不在编译期依赖任何 Bukkit/Bungee API,各平台(platform-bukkit / platform-bungee)于自身就绪后
 * 调用 [register] 把本平台的治理执行器自注册进来;[BridgeClient] 收到 `command`帧时经 [handler] 取用。
 * 沿用 [AlertChannelRegistry] 的自注册范式(规范:注册中心职责单一,不并入采集用的 ProbeRegistry)。
 *
 * ## 单处理器(与告警的多通道不同)
 * 治理执行对单个探针进程只有**唯一**平台实现(Bukkit 子服端 或 Bungee 代理端,二选一,由平台门决定),
 * 故持有单个 [handler] 而非列表;重复注册以最后一次为准并 WARN(理论上不应发生)。
 * 未注册(探针独立使用、或平台模块缺失)时 [handler] 为 null——[BridgeClient] 据此回 success=false 优雅降级。
 *
 * ## core 内置动作(FR-46)
 * 除平台治理动作外,还有一类**不需要平台 API** 的指令(如 `metrics_now`):它们由 core 组件自注册并按
 * 动作名分发([registerCoreAction] / [dispatchCoreAction])。这样 `metrics_now` 与治理指令共用同一套
 * 分发中心与同一份回执语义,不必在桥里为每个新动作加分支。
 *
 * 并发模型:注册发生于启动期、读取发生于桥读线程,读多写少且仅单值,治理执行器用 [AtomicReference]
 * 保证可见性与原子替换;内置动作表用 [ConcurrentHashMap]。
 */
@Service
class BridgeCommandRegistry {

    /** 当前平台治理执行器;未注册时为 null。 */
    private val handlerRef = AtomicReference<BridgeCommandHandler?>(null)

    /** core 内置动作:动作名 → 执行器。 */
    private val coreActions = ConcurrentHashMap<String, BridgeActionHandler>()

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

    /**
     * 注册一个 core 内置动作(平台无关,不需要平台执行器)。
     *
     * @param action 动作名(与下发帧的 `action` 精确匹配,如 `metrics_now`)。
     * @param handler 执行器;须线程安全且绝不抛(抛错由 [dispatchCoreAction] 统一转为失败回执)。
     */
    fun registerCoreAction(action: String, handler: BridgeActionHandler) {
        val prev = coreActions.put(action, handler)
        if (prev != null && prev !== handler) {
            ProbeLogger.warn("内置动作 \"$action\" 被重复注册,以最新为准")
        } else {
            ProbeLogger.debug("内置动作已注册:$action")
        }
    }

    /**
     * 注销一个 core 内置动作。
     *
     * 供服务停止时调用：注销后同名指令自然回落到平台执行器（或回“未实现”失败回执），
     * 不会再把指令交给一个已经停掉/已释放执行体的处理器。
     *
     * @param action 动作名。
     * @return true 表示确实注销了一个已注册的动作。
     */
    fun unregisterCoreAction(action: String): Boolean {
        val removed = coreActions.remove(action) != null
        if (removed) {
            ProbeLogger.debug("内置动作已注销:$action")
        }
        return removed
    }

    /**
     * 分发一条 core 内置动作。
     *
     * @param command 下行指令。
     * @return 执行结果;该动作未注册时返回 null(调用方回落到平台治理执行器)。
     */
    fun dispatchCoreAction(command: BridgeCommand): BridgeCommandResult? {
        val action = coreActions[command.action] ?: return null
        // 内置动作的执行契约是"绝不抛",此处再兜一层:单条指令异常不得打断桥读循环
        return runCatching { action.handle(command) }
            .getOrElse { BridgeCommandResult.fail("内置动作执行异常:${it.message}") }
    }
}
