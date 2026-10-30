package top.wcpe.mc.plugin.serverprobe.api

import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 对外桥门面的装配器。
 *
 * ## 为什么必须与 `ServerProbeAPI` 同包
 * `ServerProbeAPI.install/uninstall` 是**包内可见**（package-private）的，只有同包类能调用。
 * 本类因此声明在 `top.wcpe.mc.plugin.serverprobe.api` 包内——与 api 模块的同名包形成跨模块
 * split package。classpath 模式下（普通 Bukkit 插件、非 JPMS）两个 jar 的同名包合并为同一个
 * 运行时包，故 package-private 访问合法且生效。
 *
 * **这只是可见性收窄，不是安全边界**：同 JVM 的代码若刻意声明同包仍能调用 install，
 * 详见 [ServerProbeAPI] 的类注释。
 *
 * ## 为什么需要单独的类
 * `ServerProbe` 是 IOC 容器的启动者（`object`），**不是受管 bean**——它既不能 `@Inject`
 * 拿业务宿主，也没有 `@PostEnable`/`@PreDestroy` 回调。因此装配点必须放在一个真正的
 * `@Service` 上：IOC 完成依赖注入后 install，容器销毁前 uninstall。
 *
 * ## 顺序保证
 * - `@PostConstruct` 在 [BusinessHostImpl] 已注入之后执行 → 门面拿到的宿主一定可用；
 * - `@PreDestroy` 在宿主销毁之前执行 → 外部插件的 `close()` 回调仍能安全访问实现。
 *
 * ## 未接线子门面
 * 后续批次（配置重载、桥状态）会在 [BridgeServices] 上追加 `default` 方法。
 * 本类只覆写当前已实现的部分；未覆写的走 `api` 的降级实现（安全但无功能），
 * 并由 `bridge_status` 的 `wired` 字段暴露实际接线情况。
 */
@Service
class BridgeFacadeInstaller {

    /**
     * 按**接口**注入，与 [top.wcpe.mc.plugin.serverprobe.command.ProbeCommand] 注入 `ProbeReadApi` 同一惯例。
     *
     * 不要写成具体实现类 `BusinessHostImpl`：IOC 静态分析按 `@Service` 暴露的类型匹配注入点，
     * 依赖实现类会报 `missing-bean`（`candidateBeans` 为空）并让 `:plugin:build` 直接失败。
     * 门面本来就只需要 `api` 的契约类型，注入接口也更贴合「对外只暴露 api」的意图。
     */
    @Inject
    lateinit var businessHost: BusinessHost

    /** 业务事件上报实现（事件主题订阅）；同样按接口注入。 */
    @Inject
    lateinit var eventSink: BusinessEventSink

    /**
     * 配置重载监听器注册表（配置重载）；同样按接口注入。
     *
     * 实现类 `ConfigReloadRegistryImpl` 同时被 IoC 暴露为 `ConfigReloadRegistry` 与 core 内部的
     * `ReloadListenerSource`，此处按**对外契约**注入。
     */
    @Inject
    lateinit var configReloadRegistry: ConfigReloadRegistry

    /**
     * 只读数据出口；按**接口**注入，实现类为 core 的 `ProbeReadApiImpl`。
     *
     * 注入它与 `ProbeCommand` 注入 `ProbeReadApi` 是同一个 bean：门面只是把它挂到
     * `ServerProbeAPI.read()` 的稳定代理后面，不另建实例，故两条路读到的是同一份数据。
     */
    @Inject
    lateinit var probeReadApi: ProbeReadApi

    @PostConstruct
    fun install() {
        runCatching {
            ServerProbeAPI.install(object : BridgeServices {
                override fun businessHost(): BusinessHost = businessHost

                override fun eventSink(): BusinessEventSink = eventSink

                override fun configReloads(): ConfigReloadRegistry = configReloadRegistry

                override fun read(): ProbeReadApi = probeReadApi
            })
            ProbeLogger.info("对外桥门面已装载:业务域注册入口、事件上报入口、配置重载注册入口与只读数据入口可用")
        }.onFailure {
            // 装配失败只降级（门面保持未就绪），绝不让探针启动失败。
            ProbeLogger.warn("对外桥门面装载失败,外部业务注册将不可用:${it.javaClass.simpleName}")
        }
    }

    @PreDestroy
    fun uninstall() {
        runCatching { ServerProbeAPI.uninstall() }
        ProbeLogger.info("对外桥门面已卸载")
    }
}
