package top.wcpe.mc.plugin.serverprobe.bungee.reload

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
 * BungeeCord 端的主线程重载执行器（配置重载 / 规格 §3.10）。
 *
 * ## 为什么是"直通"
 * 代理端**没有主线程约束**：BungeeCord 的插件配置读写不要求回到事件线程，也没有
 * "异步访问报错"的守卫。因此这里不切线程、不做 latch —— 直接在调用线程执行就是把
 * 语义做对了。刻意保留一个空壳实现（而不是让 core 的默认实现兜底）是为了：
 * - 在 `bridge_status` 之类的诊断里能看出"当前平台确实注册了一个执行器"；
 * - 将来若代理端引入调度约束，只需改本类，不动 core。
 *
 * ## 形态与自注册
 * `@Service` + `@PlatformSide(BUNGEE)`；IoC 不感知 [PlatformSide] 会在所有平台实例化，
 * 故 [register] 在 [PostConstruct] 显式平台门，仅 BungeeCord 端自注册。
 */
@Service
@PlatformSide(Platform.BUNGEE)
class BungeeReloadExecutor : PlatformReloadExecutor {

    /** 平台执行器注册点（按**接口**注入，注入实现类会报 `missing-bean`）。 */
    @Inject
    lateinit var registry: PlatformReloadExecutorRegistration

    /** 依赖注入完成后做平台门并自注册。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUNGEE) return
        registry.register(this)
        ProbeLogger.info("BungeeCord 重载执行器已启用(代理端无主线程约束,直通执行)")
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
