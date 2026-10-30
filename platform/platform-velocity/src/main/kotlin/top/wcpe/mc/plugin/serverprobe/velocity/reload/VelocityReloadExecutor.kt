package top.wcpe.mc.plugin.serverprobe.velocity.reload

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.PlatformReloadExecutor
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.PlatformReloadExecutorRegistration
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * Velocity 端的主线程重载执行器（配置重载 / 规格 §3.10）。
 *
 * ## 为什么是"直通"
 * Velocity 同样**没有主线程约束**：插件配置读写不要求回到事件循环线程。
 * 因此不切线程、不做 latch —— 直接在调用线程执行即正确语义。
 *
 * ## 与 API 3 / 4 双版本兼容
 * 本类**不 import 任何 Velocity 类型**（只用 core 接口 + TabooLib IoC 注解），
 * 因此不需要为 API 4 兼容编译门另开源集（见 ADR-26）。
 *
 * ## 形态与自注册
 * `@Service` + `@PlatformSide(VELOCITY)`；IoC 不感知 [PlatformSide] 会在所有平台实例化，
 * 故 [register] 在 [PostConstruct] 显式平台门，仅 Velocity 端自注册。
 */
@Service
@PlatformSide(Platform.VELOCITY)
class VelocityReloadExecutor : PlatformReloadExecutor {

    /** 平台执行器注册点（按**接口**注入，注入实现类会报 `missing-bean`）。 */
    @Inject
    lateinit var registry: PlatformReloadExecutorRegistration

    /** 依赖注入完成后做平台门并自注册。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.VELOCITY) return
        registry.register(this)
        ProbeLogger.info("Velocity 重载执行器已启用(代理端无主线程约束,直通执行)")
    }

    /** 插件卸载时撤销当前实现。 */
    @PreDestroy
    fun unregister() {
        registry.unregister(this)
    }

    /**
     * 直接在当前线程执行 [task]。
     *
     * @return 任务结果；任务抛异常时返回 null（契约要求永不抛）。
     */
    override fun <T> onMainThread(task: () -> T, timeoutMs: Long): T? = runCatching { task() }.getOrNull()
}
