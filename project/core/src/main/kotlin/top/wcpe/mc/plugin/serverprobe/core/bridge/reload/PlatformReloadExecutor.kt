package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * 平台主线程执行接缝（配置重载规格 §3.10）。
 *
 * core 平台无关，不能直接调 Bukkit/Bungee/Velocity 的调度 API；需要"回主线程重载"的场景
 * （典型是 Bukkit 插件用 `JavaPlugin.reloadConfig()`，那条路径必须走主线程）经本接口下沉。
 *
 * ## 实现约定
 * - **自己切线程、自己施加有界等待**：超时返回 null，绝不无限等（桥是实时通道，
 *   一次无界等待就会把重载池的槽永久占住）；
 * - 超时后**不强杀**目标任务（Java 无法强杀线程），与 §3.9 的"自生自灭"一致；
 * - 绝不抛异常：失败以 null 表达。
 *
 * ## 谁在用
 * 监听器回调本身在 worker 池上触发；需要主线程的监听器**可自行** `submit(async=false)` 切过去
 * （见 [top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener] 的线程约定）。
 * 本接口提供给探针自身与平台侧代码复用的统一入口，避免每处各写一套 latch 范式。
 */
interface PlatformReloadExecutor {

    /**
     * 在平台主线程执行 [task]，最多等 [timeoutMs]。
     *
     * @param task 待执行的平台操作。
     * @param timeoutMs 有界等待上限（毫秒）；`<= 0` 表示不等待，直接返回 null。
     * @return 任务结果；超时、被中断或任务抛异常时返回 null。
     */
    fun <T> onMainThread(task: () -> T, timeoutMs: Long): T?
}

/** 平台执行器的生命周期注册契约，平台模块不依赖具体持有者。 */
interface PlatformReloadExecutorRegistration {

    /** 注册当前运行平台唯一的执行器。 */
    fun register(executor: PlatformReloadExecutor)

    /** 仅当当前注册者仍是给定实例时注销。 */
    fun unregister(executor: PlatformReloadExecutor)
}

/**
 * 平台执行器的默认实现与装配点（规格 §4 的"默认空实现"）。
 *
 * 未注册任何平台执行器时（代理端、单测、或平台模块缺席）**直接在调用线程执行**：
 * 那是"无主线程约束"场景下唯一正确的语义，也让重载在桥未接平台时仍能工作。
 *
 * `@Service` 使 IoC 同时把它暴露为 [PlatformReloadExecutor] 与
 * [PlatformReloadExecutorRegistration]；平台模块按后者注入并自注册（与
 * [top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControlRegistry] 同一范式，
 * 避免按具体实现类注入触发 `missing-bean`）。
 */
@Service
class PlatformReloadExecutorHolder : PlatformReloadExecutor, PlatformReloadExecutorRegistration {

    /** 当前平台执行器；null 表示"直通调用线程"。 */
    private val executor = AtomicReference<PlatformReloadExecutor?>(null)

    override fun register(executor: PlatformReloadExecutor) {
        val previous = this.executor.getAndSet(executor)
        if (previous != null && previous !== executor) {
            ProbeLogger.warn("平台重载执行器被重复注册，已替换为：${executor.javaClass.name}")
        }
    }

    override fun unregister(executor: PlatformReloadExecutor) {
        this.executor.compareAndSet(executor, null)
    }

    /**
     * 委派给已注册的平台执行器；未注册时在当前线程直接执行。
     *
     * @return 任务结果；超时或抛异常返回 null。
     */
    override fun <T> onMainThread(task: () -> T, timeoutMs: Long): T? {
        val current = executor.get() ?: return runInline(task)
        return current.onMainThread(task, timeoutMs)
    }

    /** 直通执行：仍守住"绝不抛"的契约。 */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> runInline(task: () -> T): T? = try {
        task()
    } catch (e: Throwable) {
        // 契约要求永不抛：任何异常都降级为"没有结果"。
        ProbeLogger.warn("重载平台任务在调用线程执行失败:${e.javaClass.simpleName}")
        null
    }
}
