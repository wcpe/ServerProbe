package top.wcpe.mc.plugin.serverprobe.integration.allininventorysync

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import java.util.concurrent.atomic.AtomicLong

/**
 * Bukkit 背包追踪事件监听器(JBIS FR-125,见 ServerProbe ADR-0016 / 对接 JianManager FR-126 汇聚)。
 *
 * 订阅 **AllinInventorySync** 的重点物品流转事件 [TrackedItemActionEvent](登录携带 / 丢出 / 拾取 / 移入容器),
 * 折算成 `inventory` 业务**事件**经 [BridgeClient] 反向 WS 桥上报本机 Worker(→ gRPC → CP),供 CP 汇聚物品流水。
 * 纯折算 / 去重键逻辑抽 [InventoryEventEnvelope](脱离 Bukkit、可单测);本类只管订阅 + 取 Bukkit 字段 + 上报。
 *
 * ## 与 [InventoryProvider] 的分工
 * [InventoryProvider] 是**下行**命令执行(CP 主动读 / 写背包);本监听器是**上行**事件汇聚(玩家物品流转自发冒泡)。
 * 二者同住背包集成模块,共用 `inventory` 域名。
 *
 * ## 可选插件事件:启用后动态绑定
 * AllinInventorySync 是**软依赖**(探针可独立运行)。`@SubscribeEvent` 默认在探针 enable 时按方法参数类型反射解析事件类——
 * 但 FR10 真机证明其在 AIS 事件类由外部 ClassLoader 提供时未收到事件。故在 AIS 已启用后，用其 ClassLoader 解析
 * 公开事件类并通过 Bukkit 注册；处理器只接收 [Event]，字段由 [InventoryTrackedEventReader] 经公开 getter 读取。
 * AllinInventorySync 不在场时不注册，插件停用时注销监听，避免保留旧 ClassLoader。
 *
 * ## 投递与线程
 * [TrackedItemActionEvent] 在主线程同步发布;本监听器仅取已克隆的物品快照便利字段(轻量、主线程安全)后经
 * [BridgeClient.emitBusinessEvent] 上报(与玩家 join/quit 事件同走主线程上报口径,低频、未连静默丢弃、绝不抛)。
 * 整段 runCatching 兜底:任何异常仅 WARN 降级,**绝不**拖垮探针或宿主(守 ADR-0015 事故域隔离)。
 *
 * ## 形态与开关
 * `object` 由 TabooLib 生命周期调用；`@Inject` 注入 [BridgeClient]。仅 Bukkit 平台
 * ([PlatformSide])、仅插件桥开启([ProbeConfig.bridgeEnabled])时上报;关闭(独立使用探针)时回调直接返回,零副作用。
 */
@PlatformSide(Platform.BUKKIT)
object BukkitInventoryEventListener {

    /** 探针会话内单调去重序号:同毫秒多次同动作的去重键去歧义(瞬时观测无插件侧持久 ID,见 [InventoryEventEnvelope])。 */
    private val sequence = AtomicLong(0)
    private var registration: BukkitTrackedEventRegistration? = null

    /** 插件桥客户端(core),业务事件上报出口。 */
    @Inject
    lateinit var bridgeClient: BridgeClient

    /** AIS 已启用后注册其公开追踪事件；重复刷新先取消旧注册，支持外部插件热重载。 */
    @Awake(LifeCycle.ENABLE)
    fun refresh() {
        unregister()
        if (Platform.CURRENT != Platform.BUKKIT || !ProbeConfig.bridgeEnabled()) return
        runCatching {
            registration = BukkitTrackedEventRegistration.register(handler = ::onTrackedItemAction)
            ProbeLogger.info("已注册 AIS 重点物品追踪事件")
        }.onFailure { ProbeLogger.warn("注册 AIS 重点物品追踪事件失败:${it.message}") }
    }

    /** 外部插件停用或探针卸载时注销动态监听，避免旧 ClassLoader 残留。 */
    fun unregister() {
        registration?.unregister()
        registration = null
    }

    /** 重点物品流转:折算并上报一条追踪观测。 */
    private fun onTrackedItemAction(event: Any) {
        if (!ProbeConfig.bridgeEnabled()) return
        runCatching {
            ProbeLogger.info("已收到 AIS 重点物品追踪回调")
            val data = InventoryTrackedEventReader.read(event)
            val occurredAt = System.currentTimeMillis()
            bridgeClient.emitBusinessEvent(
                InventoryEventEnvelope.DOMAIN,
                InventoryEventEnvelope.dedupKey(data.playerUuid, data.action, occurredAt, sequence.incrementAndGet()),
                InventoryEventEnvelope.encode(
                    playerName = data.playerName,
                    playerUuid = data.playerUuid,
                    action = data.action,
                    ruleId = data.ruleId,
                    ruleDescription = data.ruleDescription,
                    material = data.material,
                    amount = data.amount,
                    displayName = data.displayName,
                    occurredAtMs = occurredAt,
                ),
            )
            ProbeLogger.info("已上报 AIS 重点物品追踪事件(action=${data.action},规则=${data.ruleId})")
        }.onFailure {
            ProbeLogger.warn("上报 AIS 背包追踪事件失败，已丢弃:${it.message}")
        }
    }

}
