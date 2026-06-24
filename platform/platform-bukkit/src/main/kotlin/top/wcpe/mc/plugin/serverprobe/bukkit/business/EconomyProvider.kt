package top.wcpe.mc.plugin.serverprobe.bukkit.business

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.multicurrencyeconomy.api.MultiCurrencyEconomyApi
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 经济业务 Provider(JBIS,见 ADR-0015 / JianManager FR-118)。
 *
 * 对接 **MultiCurrencyEconomy** 插件:经其公开 api(`MultiCurrencyEconomyApi`,`compileOnly` 依赖、运行期由
 * 目标服务端提供)读经济数据。住在 platform-bukkit——业务对接层、唯一认识 mce 具体 API 的地方;
 * 经 [BusinessHost] 注册为 `economy` 域 Provider,由 [BusinessHost] 在独立业务线程池调用(事故域隔离)。
 *
 * ## 降级即默认
 * mce 未安装 / 未就绪时,各动作降级为失败(`经济插件未就绪`),绝不抛、绝不拖垮探针(守 ADR-0015 事故域隔离)。
 *
 * ## 线程模型
 * 由 [BusinessHost] 在业务线程池(非主线程、非桥读线程)调用;mce 读为同步阻塞、其约定要求异步线程调用,
 * 此处天然满足(业务线程池即异步)。当前 `balance` 为只读,无主线程依赖。
 *
 * ## 形态与自注册
 * `@Service` + `@PlatformSide(BUKKIT)`;[register] 在 [PostConstruct] 显式平台门 + 桥开关门,仅 Bukkit 端、
 * 插件桥开启时自注册(沿用 `BukkitBridgeCommandHandler` 范式)。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class EconomyProvider : BusinessProvider {

    /** 业务对接装配中心(core),初始化完成后自注册本 Provider。 */
    @Inject
    lateinit var businessHost: BusinessHost

    override val domain: String = DOMAIN

    /**
     * 依赖注入完成后做平台门 + 桥开关门并自注册。
     *
     * 仅 Bukkit 端、且插件桥开启时注册:独立使用探针(桥关闭)或代理端不承接业务对接。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        if (!ProbeConfig.bridgeEnabled()) return
        businessHost.register(this)
        ProbeLogger.info("经济业务 Provider 已注册(domain=$DOMAIN,对接 MultiCurrencyEconomy)")
    }

    /** 经济域能力清单:当前仅只读 `balance`(按 player + currency 查余额)。 */
    override fun manifest(): Map<String, Any?> = mapOf(
        "actions" to listOf(
            mapOf("action" to ACTION_BALANCE, "args" to listOf("player", "currency"), "readOnly" to true)
        )
    )

    /**
     * 执行一条经济动作。当前支持只读 `balance`;未知动作降级失败。
     *
     * @param action 动作名。
     * @param payload 结构化参数 JSON。
     * @return 执行结果;成功时 output 为余额 JSON。
     */
    override fun dispatch(action: String, payload: String): BridgeCommandResult = when (action) {
        ACTION_BALANCE -> balance(payload)
        else -> BridgeCommandResult.fail("未知经济动作:$action")
    }

    /**
     * 查询某玩家某币种余额(只读)。
     *
     * mce 未就绪 / 参数缺失 / 查询异常一律降级失败;金额以 [java.math.BigDecimal.toPlainString] **字符串**承载
     * (信封禁用浮点,防多币种不同精度失真)。
     */
    private fun balance(payload: String): BridgeCommandResult {
        if (!MultiCurrencyEconomyApi.isReady()) {
            return BridgeCommandResult.fail("经济插件(MultiCurrencyEconomy)未就绪")
        }
        val req = Json.parse(payload)
        val player = req.getString("player")
        val currency = req.getString("currency")
        if (player.isEmpty() || currency.isEmpty()) {
            return BridgeCommandResult.fail("balance 缺少 player 或 currency")
        }
        val amount = runCatching { MultiCurrencyEconomyApi.getBalance(player, currency) }
            .getOrElse { return BridgeCommandResult.fail("查询余额失败:${it.message}") }
        val output = Json.encode(
            mapOf(
                "player" to player,
                "currency" to currency,
                "balance" to amount.toPlainString(),
            )
        )
        return BridgeCommandResult.ok(output)
    }

    private companion object {
        /** 经济业务域名,与 Worker 下发的 domain 对应。 */
        private const val DOMAIN = "economy"

        /** 查余额动作名。 */
        private const val ACTION_BALANCE = "balance"
    }
}
