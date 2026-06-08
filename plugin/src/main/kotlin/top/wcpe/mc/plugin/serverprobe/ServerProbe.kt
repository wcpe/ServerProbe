package top.wcpe.mc.plugin.serverprobe

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.function.info

/**
 * ServerProbe 插件主入口。
 *
 * 采用 TabooLib 生命周期钩子([Awake]),不继承 JavaPlugin,也不纳入 IOC 容器
 * (object 不能作为 `top.wcpe.taboolib.ioc` 的受管 bean;它是容器启动者)。
 *
 * 启动分段打点已下沉到 core 的 `PhaseTimingRecorder`(同样经 `@Awake` 在各生命周期记录 `nanoTime`),
 * 故主类不再维护时间戳,仅保留启用/就绪/卸载的关键中文日志。
 */
object ServerProbe {

    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        info("ServerProbe 正在启用……")
    }

    @Awake(LifeCycle.ACTIVE)
    fun onActive() {
        info("ServerProbe 已就绪(服务器启动完成)。")
    }

    @Awake(LifeCycle.DISABLE)
    fun onDisable() {
        info("ServerProbe 正在卸载……")
    }
}
