package top.wcpe.mc.plugin.serverprobe.integration.allininventorysync

import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.OptionalEvent
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.taboolib.ioc.annotation.Inject

/** AllinInventorySync 启停时刷新 Provider，确保业务域始终使用当前公开 API。 */
@PlatformSide(Platform.BUKKIT)
object AllinInventorySyncLifecycleListener {

    /** 由 IOC 注入监听器所需的背包 Provider 生命周期能力。 */
    @Inject
    lateinit var provider: InventoryProviderLifecycle

    /** 外部插件启用后重新发现 API。 */
    @SubscribeEvent(bind = PLUGIN_ENABLE_EVENT)
    fun onPluginEnable(optional: OptionalEvent) {
        val event = optional.get<PluginEnableEvent>()
        if (event.plugin.name == AIS_PLUGIN) {
            provider.refresh()
            BukkitInventoryEventListener.refresh()
        }
    }

    /** 外部插件禁用时撤销 Provider，避免桥请求触及已卸载 ClassLoader。 */
    @SubscribeEvent(bind = PLUGIN_DISABLE_EVENT)
    fun onPluginDisable(optional: OptionalEvent) {
        val event = optional.get<PluginDisableEvent>()
        if (event.plugin.name == AIS_PLUGIN) {
            provider.unregister()
            BukkitInventoryEventListener.unregister()
        }
    }

    private const val AIS_PLUGIN = "AllinInventorySync"
    private const val PLUGIN_ENABLE_EVENT = "org.bukkit.event.server.PluginEnableEvent"
    private const val PLUGIN_DISABLE_EVENT = "org.bukkit.event.server.PluginDisableEvent"
}
