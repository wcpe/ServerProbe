package top.wcpe.mc.plugin.serverprobe.bukkit.cpu

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.cpu.PluginClassLoaderRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * Bukkit 插件 ClassLoader 注册(FR2.6)。
 *
 * 在 Bukkit 端把各插件的 ClassLoader 登记到 [PluginClassLoaderRegistry],
 * 供 core 的 [CpuAttributionSampler] 把线程栈帧归并到插件。
 *
 * 通过 `JavaPlugin.classLoader` 反射取每个插件的 ClassLoader(旧版 Bukkit API
 * 无公开 getter,用反射安全获取;失败则跳过该插件,不影响整体)。
 *
 * 仅在 Bukkit 平台生效([PlatformSide]);作为 IOC [Service],初始化完成后注册。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitPluginClassLoaderRegistrar {

    /** 插件 ClassLoader 注册表(core)。 */
    @Inject
    lateinit var registry: PluginClassLoaderRegistry

    /**
     * 依赖注入完成后遍历已加载插件并注册其 ClassLoader。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        Bukkit.getPluginManager().plugins.forEach { plugin ->
            val loader = pluginClassLoader(plugin)
            if (loader != null) {
                registry.register(plugin.name, loader)
            }
        }
        ProbeLogger.info("已注册 ${registry.pluginNames().size} 个插件 ClassLoader(运行期 CPU 归因用)")
    }

    /**
     * 反射获取插件 ClassLoader(兼容各版本可见性差异);失败返回 null 并跳过。
     *
     * `JavaPlugin.classLoader` 字段在不同版本可见性不一(老版本 public、新版本 private),
     * 统一经字段反射 + setAccessible 安全读取;失败即跳过该插件,不影响整体。
     *
     * @param plugin Bukkit 插件实例。
     * @return 插件的 ClassLoader;不可用时为 null。
     */
    private fun pluginClassLoader(plugin: org.bukkit.plugin.Plugin): ClassLoader? {
        if (plugin !is JavaPlugin) return null
        return runCatching {
            val field = JavaPlugin::class.java.getDeclaredField("classLoader")
            field.isAccessible = true
            field.get(plugin) as ClassLoader
        }.getOrNull()
    }
}
