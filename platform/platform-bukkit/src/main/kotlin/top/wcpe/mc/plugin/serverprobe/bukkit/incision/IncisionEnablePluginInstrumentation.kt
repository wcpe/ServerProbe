package top.wcpe.mc.plugin.serverprobe.bukkit.incision

import taboolib.module.incision.annotation.Lead
import taboolib.module.incision.annotation.Surgeon
import taboolib.module.incision.annotation.Trail
import taboolib.module.incision.api.Theatre
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.incision.IncisionStartupDataStore
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import java.util.ArrayDeque

/**
 * Bukkit 插件启用耗时的 Incision 注解式织入（FR7）。
 *
 * 接入方式与 VanillaModify 一致：由 [Surgeon] 在 CONST 阶段发现 [Lead]/[Trail]，
 * 不手动加载 Incision 基础设施或自行管理字节码回滚。配置关闭时 advice 立即返回，
 * 不记录任何启动画像数据，也不影响原始插件启用流程。
 */
@Surgeon
object IncisionEnablePluginInstrumentation {

    /** Incision 启动期数据缓冲，默认关闭时保持空快照。 */
    @Inject
    lateinit var startupDataStore: IncisionStartupDataStore

    private val enableScopes = ThreadLocal.withInitial { ArrayDeque<EnableScope>() }

    /** 在配置与 IOC 就绪后记录本次启动请求，避免首个 enablePlugin 回调早于字段注入。 */
    @Awake(LifeCycle.ENABLE)
    fun markRequested() {
        if (isStoreReady() && isEnabled()) {
            startupDataStore.markEnabled()
        }
    }

    /** 在目标方法入口记录插件名与单调时钟。 */
    @Lead(scope = ENABLE_PLUGIN_TARGET)
    fun beforeEnablePlugin(theatre: Theatre) {
        if (!isStoreReady() || !isEnabled()) {
            return
        }
        runCatching {
            enableScopes.get().addLast(EnableScope(pluginName(theatre.arg(0)), System.nanoTime()))
            startupDataStore.markEnabled()
            startupDataStore.markActive()
        }.onFailure { error ->
            ProbeLogger.warn("Incision 启用计时失败，已跳过本次记录:${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** 在目标方法所有出口记录耗时；异常出口同样清理线程局部状态。 */
    @Trail(scope = ENABLE_PLUGIN_TARGET)
    fun afterEnablePlugin(theatre: Theatre) {
        if (!isStoreReady() || !isEnabled()) {
            return
        }
        runCatching {
            val scopes = enableScopes.get()
            val scope = scopes.pollLast() ?: return@runCatching
            if (scopes.isEmpty()) {
                enableScopes.remove()
            }
            startupDataStore.recordPluginEnable(scope.pluginName, System.nanoTime() - scope.startedAtNanos)
            if (theatre.throwable != null) {
                ProbeLogger.debug("Incision 已记录异常退出的插件启用耗时:${scope.pluginName}")
            }
        }.onFailure { error ->
            ProbeLogger.warn("Incision 结束计时失败，已跳过本次记录:${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** 配置注入异常时按关闭路径处理，绝不影响被织入的方法。 */
    private fun isEnabled(): Boolean = runCatching { ProbeConfig.incisionEnabled() }.getOrDefault(false)

    /** Incision 的首个回调可能早于 IOC 字段注入，未就绪时直接跳过。 */
    private fun isStoreReady(): Boolean = ::startupDataStore.isInitialized

    /** 不直接引用 Bukkit 类型，避免代理端扫描注解时链接平台类。 */
    private fun pluginName(plugin: Any?): String = runCatching {
        plugin?.javaClass?.getMethod("getName")?.invoke(plugin) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: UNKNOWN_PLUGIN_NAME

    private data class EnableScope(val pluginName: String, val startedAtNanos: Long)

    private const val ENABLE_PLUGIN_TARGET =
        "method:org.bukkit.plugin.SimplePluginManager#enablePlugin(org.bukkit.plugin.Plugin)V"

    private const val UNKNOWN_PLUGIN_NAME = "未知插件"
}
