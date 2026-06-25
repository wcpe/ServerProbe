package top.wcpe.mc.plugin.serverprobe.bukkit.business

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.OptionalEvent
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.mc.plugin.allininventorysync.api.event.TrackedItemActionEvent
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
 * 二者同住 platform-bukkit 业务对接层,共用 `inventory` 域名。
 *
 * ## 可选插件事件:按名绑定([SubscribeEvent.bind])
 * AllinInventorySync 是**软依赖**(探针可独立运行)。`@SubscribeEvent` 默认在探针 enable 时按方法参数类型反射解析事件类——
 * 但探针 enable 时刻该事件类未必已被解析(即便 softdepend 保证其先 enable,TabooLib 反射仍可能"事件未能找到"漏注册,
 * 见经济监听器同款教训)。故改用 **`bind = 事件全限定名`**(按名注册、不在 enable 时解析类)+ 处理器收 [OptionalEvent]、
 * 事件真正触发时再 [OptionalEvent.get] 取强类型:AllinInventorySync 不在场则该 bind 自然不触发(零副作用)。
 *
 * ## 投递与线程
 * [TrackedItemActionEvent] 在主线程同步发布;本监听器仅取已克隆的物品快照便利字段(轻量、主线程安全)后经
 * [BridgeClient.emitBusinessEvent] 上报(与玩家 join/quit 事件同走主线程上报口径,低频、未连静默丢弃、绝不抛)。
 * 整段 runCatching 兜底:任何异常仅 WARN 降级,**绝不**拖垮探针或宿主(守 ADR-0015 事故域隔离)。
 *
 * ## 形态与开关
 * `object` + `@SubscribeEvent`(TabooLib 要求事件宿主为 object);`@Inject` 注入 [BridgeClient]。仅 Bukkit 平台
 * ([PlatformSide])、仅插件桥开启([ProbeConfig.bridgeEnabled])时上报;关闭(独立使用探针)时回调直接返回,零副作用。
 */
@PlatformSide(Platform.BUKKIT)
object BukkitInventoryEventListener {

    /** AllinInventorySync 追踪事件全限定名:按名绑定,避免探针 enable 早于 / 解析不到事件类时漏注册。 */
    private const val TRACKED_EVENT = "top.wcpe.mc.plugin.allininventorysync.api.event.TrackedItemActionEvent"

    /** 探针会话内单调去重序号:同毫秒多次同动作的去重键去歧义(瞬时观测无插件侧持久 ID,见 [InventoryEventEnvelope])。 */
    private val sequence = AtomicLong(0)

    /** 插件桥客户端(core),业务事件上报出口。 */
    @Inject
    lateinit var bridgeClient: BridgeClient

    /**
     * 重点物品流转:折算并上报一条追踪观测。
     *
     * 桥未开启直接返回;AllinInventorySync 不在场则本 bind 不触发。取物品克隆快照的便利字段(material / amount /
     * displayName,无全保真 nbtBase64——其 codec 在 AllinInventorySync core 非 api)与玩家 / 规则信息,经
     * [InventoryEventEnvelope] 编码后上报;去重键带会话单调序号。任何异常仅 WARN 降级,绝不抛。
     *
     * @param optional AllinInventorySync 重点物品动作事件(按 [TRACKED_EVENT] 绑定,经 [OptionalEvent.get] 取强类型)。
     */
    @SubscribeEvent(bind = TRACKED_EVENT)
    fun onTrackedItemAction(optional: OptionalEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        val event = optional.get<TrackedItemActionEvent>()
        runCatching {
            val item = event.item
            val meta = if (item.hasItemMeta()) item.itemMeta else null
            val displayName = meta?.takeIf { it.hasDisplayName() }?.displayName ?: ""
            val playerUuid = event.player.uniqueId.toString()
            val action = event.action.name
            val occurredAt = System.currentTimeMillis()
            bridgeClient.emitBusinessEvent(
                InventoryEventEnvelope.DOMAIN,
                InventoryEventEnvelope.dedupKey(playerUuid, action, occurredAt, sequence.incrementAndGet()),
                InventoryEventEnvelope.encode(
                    playerName = event.player.name,
                    playerUuid = playerUuid,
                    action = action,
                    ruleId = event.rule.id,
                    ruleDescription = event.rule.description,
                    material = item.type.name,
                    amount = item.amount,
                    displayName = displayName,
                    occurredAtMs = occurredAt,
                ),
            )
        }.onFailure {
            ProbeLogger.warn("上报背包追踪事件失败(player=${event.player.name}),已丢弃:${it.message}")
        }
    }
}
