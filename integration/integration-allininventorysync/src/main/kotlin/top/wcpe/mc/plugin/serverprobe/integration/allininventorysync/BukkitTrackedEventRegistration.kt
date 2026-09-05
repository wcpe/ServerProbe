package top.wcpe.mc.plugin.serverprobe.integration.allininventorysync

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/** 把 AIS 外部 ClassLoader 的公开事件绑定为 Bukkit 原生监听，隔离 Bukkit 类型避免影响软依赖扫描。 */
internal class BukkitTrackedEventRegistration private constructor(
    private val listener: Listener,
) {

    fun unregister() {
        HandlerList.unregisterAll(listener)
    }

    companion object {

        fun register(handler: (Any) -> Unit): BukkitTrackedEventRegistration {
            val externalPlugin = requireNotNull(Bukkit.getPluginManager().getPlugin(AIS_PLUGIN)?.takeIf { it.isEnabled }) {
                "AllinInventorySync 未启用"
            }
            val serverProbe = requireNotNull(Bukkit.getPluginManager().getPlugin(SERVER_PROBE_PLUGIN)) {
                "未找到 ServerProbe 插件实例"
            }
            val eventClass = externalPlugin.javaClass.classLoader.loadClass(TRACKED_EVENT).asSubclass(Event::class.java)
            val listener = object : Listener {}
            Bukkit.getPluginManager().registerEvent(
                eventClass,
                listener,
                EventPriority.MONITOR,
                EventExecutor { _, event -> handler(event) },
                serverProbe,
            )
            val handlerList = eventClass.getMethod("getHandlerList").invoke(null) as HandlerList
            check(handlerList.registeredListeners.any { it.listener === listener && it.plugin === serverProbe }) {
                "AIS 重点物品追踪事件未进入 Bukkit HandlerList"
            }
            return BukkitTrackedEventRegistration(listener)
        }

        private const val TRACKED_EVENT = "top.wcpe.mc.plugin.allininventorysync.api.event.TrackedItemActionEvent"
        private const val AIS_PLUGIN = "AllinInventorySync"
        private const val SERVER_PROBE_PLUGIN = "ServerProbe"
    }
}
