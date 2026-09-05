package top.wcpe.mc.plugin.serverprobe.integration.multicurrencyeconomy

import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.OptionalEvent
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.taboolib.ioc.annotation.Inject

/** MultiCurrencyEconomy 启停时刷新 Provider，避免重载后保留旧服务实例。 */
@PlatformSide(Platform.BUKKIT)
object MultiCurrencyEconomyLifecycleListener {

    /** 由 IOC 注入监听器所需的经济 Provider 生命周期能力。 */
    @Inject
    lateinit var provider: EconomyProviderLifecycle

    /** 外部插件启用后重新发现其公开服务。 */
    @SubscribeEvent(bind = PLUGIN_ENABLE_EVENT)
    fun onPluginEnable(optional: OptionalEvent) {
        val event = optional.get<PluginEnableEvent>()
        if (event.plugin.name == MCE_PLUGIN) provider.refresh()
    }

    /** 外部插件禁用时先撤销 Provider，后续命令会安全降级为业务域不可用。 */
    @SubscribeEvent(bind = PLUGIN_DISABLE_EVENT)
    fun onPluginDisable(optional: OptionalEvent) {
        val event = optional.get<PluginDisableEvent>()
        if (event.plugin.name == MCE_PLUGIN) provider.unregister()
    }

    private const val MCE_PLUGIN = "MultiCurrencyEconomy"
    private const val PLUGIN_ENABLE_EVENT = "org.bukkit.event.server.PluginEnableEvent"
    private const val PLUGIN_DISABLE_EVENT = "org.bukkit.event.server.PluginDisableEvent"
}
