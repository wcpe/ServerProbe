package top.wcpe.mc.plugin.serverprobe.bukkit.folia

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.platform.Folia
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service

/** Folia 已观测 region 的事件采集入口。 */
@Service
@PlatformSide(Platform.BUKKIT)
class FoliaObservedRegionService {

    private lateinit var tracker: ObservedRegionTracker
    private val listener = object : Listener {}
    private var adapter: FoliaInternalRegionIdentityAdapter? = null

    /** Folia 下注册真实 region tick 的开始/结束监听。 */
    @PostConstruct
    fun start() {
        if (Platform.CURRENT != Platform.BUKKIT || !Folia.isFolia) {
            return
        }
        tracker = ObservedRegionTracker(
            expireAfterMs = ProbeConfig.foliaObservedRegionExpireSeconds() * MILLIS_PER_SECOND
        )
        adapter = FoliaInternalRegionIdentityAdapter.create()
        if (adapter == null || !registerEvents()) {
            ProbeLogger.warn("Folia region 身份适配不可用，已跳过已观测 region 指标")
            return
        }
        ProbeLogger.info("已启用 Folia 已观测 region 真实 tick 采集")
    }

    /** 卸载时取消动态事件监听，避免旧插件实例继续接收 tick。 */
    @PreDestroy
    fun stop() {
        HandlerList.unregisterAll(listener)
    }

    /** 返回当前保留窗口中的 region 明细与世界汇总。 */
    fun snapshot(): ObservedRegionSnapshot = if (::tracker.isInitialized) {
        tracker.snapshot(System.currentTimeMillis())
    } else {
        ObservedRegionSnapshot(emptyList(), emptyList())
    }

    private fun registerEvents(): Boolean = runCatching {
        registerEvent(TICK_START_EVENT, EventExecutor { _, _ -> onTickStart() })
        registerEvent(TICK_END_EVENT, EventExecutor { _, event -> onTickEnd(event) })
        true
    }.getOrDefault(false)

    private fun registerEvent(eventClassName: String, executor: EventExecutor) {
        val eventClass = Class.forName(eventClassName).asSubclass(Event::class.java)
        val plugin = requireNotNull(Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)) { "未找到 ServerProbe 插件实例" }
        Bukkit.getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR, executor, plugin)
    }

    private fun onTickStart() {
        adapter?.current()?.let { tracker.onTickStart(it, System.nanoTime(), System.currentTimeMillis()) }
    }

    private fun onTickEnd(event: Event) {
        val duration = event.javaClass.getMethod("getTickDuration").invoke(event) as Number
        adapter?.current()?.let { tracker.onTickEnd(it, duration.toDouble(), System.currentTimeMillis()) }
    }

    private companion object {
        private const val PLUGIN_NAME = "ServerProbe"
        private const val TICK_START_EVENT = "com.destroystokyo.paper.event.server.ServerTickStartEvent"
        private const val TICK_END_EVENT = "com.destroystokyo.paper.event.server.ServerTickEndEvent"
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
