package top.wcpe.mc.plugin.serverprobe.bukkit.business

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.OptionalEvent
import taboolib.common.platform.event.SubscribeEvent
import top.wcpe.mc.plugin.multicurrencyeconomy.api.MultiCurrencyEconomyApi
import top.wcpe.mc.plugin.multicurrencyeconomy.api.event.PlayerEconomyCatchupEvent
import top.wcpe.mc.plugin.multicurrencyeconomy.api.event.PlayerEconomyChangeEvent
import top.wcpe.mc.plugin.multicurrencyeconomy.api.model.CurrencyInfo
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject

/**
 * Bukkit 经济变更事件监听器(JBIS FR-122,见 ADR-016 探针侧 / ADR-027/028)。
 *
 * 订阅 **MultiCurrencyEconomy** 的持久化投递流 [PlayerEconomyChangeEvent](覆盖 web 后台 / 跨服**所有**余额变更,
 * 下游唯一可靠源)与上线补发 [PlayerEconomyCatchupEvent](离线缺口),折算成 JBIS 业务事件经 [BridgeClient]
 * 反向 WS 桥上报本机 Worker(→ gRPC → CP),CP 按 ledgerId 去重落库 + 按 node→zone 聚合经济镜像。
 *
 * ## 与 [EconomyProvider] 的分工
 * [EconomyProvider] 是**下行**命令执行(CP 主动查 / 写余额);本监听器是**上行**事件汇聚(mce 自发的余额变更
 * 反向冒泡)。二者同住 platform-bukkit 业务对接层(唯一认识 mce 具体事件的地方),共用经济域名与信封口径。
 *
 * ## 可选插件事件:按名绑定([SubscribeEvent.bind])
 * mce 是**软依赖**(探针可独立运行、无 mce 时本监听器零副作用)。`@SubscribeEvent` 默认在探针 enable 时按方法
 * 反射参数类型解析事件类——但探针 enable 时刻 mce 事件类未必已被本插件 ClassLoader 解析(即便 softdepend 保证
 * mce 先 enable,TabooLib 反射解析仍可能"事件未能找到"漏注册)。故改用 **`bind = 事件全限定名`**(按名注册、
 * 不在 enable 时解析类)+ 处理器收 [OptionalEvent]、事件真正触发时再 [OptionalEvent.get] 取强类型事件:
 * mce 不在场则该 bind 自然不触发(零副作用),在场则正常冒泡。
 *
 * ## 投递语义(守 mce 契约)
 * - **至少一次**:实时流断连缺口由 catchup 上线补发兜底;CP 按 **ledgerId** 去重,重发不重复计数。
 * - **不阻塞主线程 / 绝不抛**:两事件均为 mce 异步事件([org.bukkit.event.Event.isAsynchronous] = true),监听器在
 *   非主线程触发,[BridgeClient.emitBusinessEvent] 内部写锁串行化且整体不抛(未连接静默丢弃);本类回调再包
 *   runCatching 兜底,任何异常仅 WARN 降级,**绝不**让经济事件处理拖垮探针或 mce(守 ADR-0015 事故域隔离)。
 *
 * ## currencyId 映射
 * mce 事件携带 currencyId(Int 主键),上报前经 [MultiCurrencyEconomyApi.getActiveCurrencies] 折算为全局稳定的
 * identifier(见 [EconomyEventEnvelope]):跨服 / 跨区同币种主键可能不同,按 identifier 聚合方不串味。
 *
 * ## 形态与开关
 * `object` + `@SubscribeEvent`(TabooLib 要求事件宿主为 object,见 [top.wcpe.mc.plugin.serverprobe.bukkit.startup.StartupLoadListener]);
 * `@Inject` 注入 [BridgeClient]。仅 Bukkit 平台([PlatformSide])、仅插件桥开启([ProbeConfig.bridgeEnabled])时上报;
 * 关闭(独立使用探针)或 mce 未就绪时回调直接返回,零副作用。
 */
@PlatformSide(Platform.BUKKIT)
object BukkitEconomyEventListener {

    /** mce 经济事件全限定名:按名绑定,避免探针 enable 早于 / 解析不到 mce 事件类时漏注册。 */
    private const val CHANGE_EVENT = "top.wcpe.mc.plugin.multicurrencyeconomy.api.event.PlayerEconomyChangeEvent"
    private const val CATCHUP_EVENT = "top.wcpe.mc.plugin.multicurrencyeconomy.api.event.PlayerEconomyCatchupEvent"

    /** 插件桥客户端(core),业务事件上报出口。 */
    @Inject
    lateinit var bridgeClient: BridgeClient

    /**
     * 经济变更(在线实时流):折算单条变更并上报。
     *
     * 覆盖 web 后台 / 跨服 / 本服一切余额变更(mce relay 顺序投递)。按 ledgerId 去重锚点上报;
     * mce 未就绪(理论上事件不会在未就绪时触发,防御性判断)或上报异常一律降级,绝不抛。
     *
     * @param optional mce 玩家经济变更事件(按 [CHANGE_EVENT] 绑定,经 [OptionalEvent.get] 取强类型)。
     */
    @SubscribeEvent(bind = CHANGE_EVENT)
    fun onEconomyChange(optional: OptionalEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        val event = optional.get<PlayerEconomyChangeEvent>()
        runCatching {
            val index = currencyIndex()
            bridgeClient.emitBusinessEvent(
                EconomyEventEnvelope.DOMAIN,
                EconomyEventEnvelope.dedupKey(event.ledgerId),
                EconomyEventEnvelope.encodeChange(event, index),
            )
        }.onFailure { ProbeLogger.warn("上报经济变更事件失败(ledgerId=${event.ledgerId}),已丢弃:${it.message}") }
    }

    /**
     * 上线补发(离线缺口):逐条折算 [PlayerEconomyCatchupEvent.changes] 并上报。
     *
     * 玩家上线时一次性带回其在本区离线期间错过的经济变更;与实时流共用 ledgerId 去重键,
     * CP 跨两条路径去重(在线已收到的不重复计数)。changes 为空(无离线缺口 / 首次登录)时不发任何帧。
     * 整段包 runCatching 兜底:单条折算 / 上报异常不中断其余条目处理边界,绝不抛。
     *
     * @param optional mce 玩家上线补发事件(按 [CATCHUP_EVENT] 绑定,经 [OptionalEvent.get] 取强类型)。
     */
    @SubscribeEvent(bind = CATCHUP_EVENT)
    fun onEconomyCatchup(optional: OptionalEvent) {
        if (!ProbeConfig.bridgeEnabled()) return
        val event = optional.get<PlayerEconomyCatchupEvent>()
        if (event.changes.isEmpty()) return
        runCatching {
            val index = currencyIndex()
            for (change in event.changes) {
                bridgeClient.emitBusinessEvent(
                    EconomyEventEnvelope.DOMAIN,
                    EconomyEventEnvelope.dedupKey(change.ledgerId),
                    EconomyEventEnvelope.encodeCatchupChange(change, index),
                )
            }
        }.onFailure {
            ProbeLogger.warn(
                "上报经济补发事件失败(player=${event.playerName},${event.fromSeq}->${event.toSeq}),已丢弃:${it.message}"
            )
        }
    }

    /**
     * 取本服 currencyId→identifier 映射(每次事件实时取,容忍运行期增删币种;mce 未就绪 / 查询异常回退空映射)。
     *
     * 不缓存:币种为低频管理数据但仍可运行期变更,每条经济事件查一次 [MultiCurrencyEconomyApi.getActiveCurrencies]
     * (内存读,极轻);未就绪或异常时回退空映射,折算时各 currencyId 退化为 Int 字符串(仍可去重,聚合维度退化)。
     */
    private fun currencyIndex(): Map<Int, String> {
        if (!MultiCurrencyEconomyApi.isReady()) return emptyMap()
        val currencies: List<CurrencyInfo> = runCatching { MultiCurrencyEconomyApi.getActiveCurrencies() }.getOrElse {
            return emptyMap()
        }
        return EconomyEventEnvelope.currencyIndex(currencies)
    }
}
