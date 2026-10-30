package top.wcpe.mc.plugin.serverprobe.bukkit.reload

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.PlatformReloadExecutor
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.PlatformReloadExecutorRegistration
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bukkit 端的主线程重载执行器（配置重载 / 规格 §3.10）。
 *
 * ## 为什么必须切主线程
 * Bukkit 插件的 `reloadConfig()` 与大部分 `FileConfiguration.load()` 调用都要求主线程；
 * 若在工作线程直接调用，轻则读到半更新状态，重则触发 `IllegalStateException: Asynchronous ...`。
 *
 * ## 范式
 * 复用 [top.wcpe.mc.plugin.serverprobe.bukkit.bridge.BukkitBridgeCommandHandler] 的
 * `submit(async = false) + CountDownLatch`：切回主线程执行，调用线程**有界等待**
 * （上限为调用方给的 `timeoutMs`，绝不无限等），超时返回 null 且**不强杀**目标任务
 * （Java 无法强杀线程；主线程上的任务会继续跑完，与规格 §3.9 的"自生自灭"一致）。
 *
 * ## 形态与自注册
 * `@Service` + `@PlatformSide(BUKKIT)`；IoC 不感知 [PlatformSide] 会在所有平台实例化，
 * 故 [register] 在 [PostConstruct] 显式平台门，仅 Bukkit 端自注册到
 * [PlatformReloadExecutorRegistration]（沿用 [top.wcpe.mc.plugin.serverprobe.bukkit.mcp.BukkitPlatformControl] 范式）。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitReloadExecutor : PlatformReloadExecutor {

    /** 平台执行器注册点（按**接口**注入，注入实现类会报 `missing-bean`）。 */
    @Inject
    lateinit var registry: PlatformReloadExecutorRegistration

    /** 依赖注入完成后做平台门并自注册。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        registry.register(this)
        ProbeLogger.info("Bukkit 重载执行器已启用(配置重载可切主线程执行)")
    }

    /** 插件卸载时撤销当前实现，禁止关闭后的任务继续进入 Bukkit 主线程。 */
    @PreDestroy
    fun unregister() {
        registry.unregister(this)
    }

    /**
     * 在 Bukkit 主线程执行 [task]，最多等 [timeoutMs]。
     *
     * @return 任务结果；超时、被中断、平台已关闭或任务抛异常时返回 null。
     */
    override fun <T> onMainThread(task: () -> T, timeoutMs: Long): T? {
        if (timeoutMs <= 0L) return null
        val holder = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        val scheduled = runCatching {
            submit(async = false) {
                // 调度被接受后才会走到这里；异常一律吞掉并转换为 null（契约要求永不抛）。
                holder.set(runCatching { task() }.getOrNull())
                latch.countDown()
            }
        }.isSuccess
        if (!scheduled) return null
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) holder.get() else null
    }
}
