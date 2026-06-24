package top.wcpe.mc.plugin.serverprobe.bukkit.business

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.multicurrencyeconomy.api.MultiCurrencyEconomyApi
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.AdjustRequest
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.ConsumeRequest
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.DepositRequest
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.IdempotencyMode
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.RefundRequest
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.TransferRequest
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.WithdrawRequest
import top.wcpe.mc.plugin.multicurrencyeconomy.api.result.BalanceOperationResult
import top.wcpe.mc.plugin.multicurrencyeconomy.api.service.MultiCurrencyEconomyService
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.math.BigDecimal

/**
 * 经济业务 Provider(JBIS,见 ADR-0015 / JianManager FR-118 只读、FR-120 写)。
 *
 * 对接 **MultiCurrencyEconomy** 插件:经其公开 api(`MultiCurrencyEconomyApi`,`compileOnly` 依赖、运行期由
 * 目标服务端提供)读写经济数据。住在 platform-bukkit——业务对接层、唯一认识 mce 具体 API 的地方;
 * 经 [BusinessHost] 注册为 `economy` 域 Provider,由 [BusinessHost] 在独立业务线程池调用(事故域隔离)。
 * 纯解析 / 校验 / 编码逻辑下沉 [EconomyEnvelope](无 Bukkit 依赖、可单测),本类只管装配与真实 API 调用。
 *
 * ## 动作
 * - 只读:`balance`(查余额)。
 * - 写:`deposit`(加)/`withdraw`(扣)/`adjust`(有符号差额校正)/`set`(设为目标值,非原子 read-then-adjust)/
 *   `transfer`(玩家间转账)/`consume`(原子消费,产流水号)/`refund`(按消费流水号退款)。
 *
 * ## 写硬约束(守 mce 契约,JianManager FR-120)
 * - **幂等键**:每个写动作必带 `taskId`(JianManager 侧生成的稳定业务单号,FR-121),映射为
 *   `pluginName="JianManager" + IdempotencyMode.BusinessOrder(taskId)`。**重试须复用同一 taskId**——
 *   同键不同额会触发 mce 高危幂等冲突(MCE-LEDGER-0001);缺 taskId 直接拒绝(不做无幂等保护的写)。
 * - **金额字符串承载**:信封内金额为 [java.math.BigDecimal] 字符串(禁浮点,防多币种精度失真)。
 * - **错误码透传**:mce 业务失败(余额不足/账户冻结/金额非法…)以结构化结果回传(success=false + status + errorCode),
 *   不吞码;仅 Provider 级错误(未就绪/参数缺失/解析失败/调用抛异常)回 [BridgeCommandResult.fail]。
 * - **非原子取舍**:`set` 无原生设值,经「查余额→算差额→adjust」实现,两步之间余额可能被它处改动(标注 nonAtomic)。
 *
 * ## 线程模型
 * 由 [BusinessHost] 在业务线程池(非主线程、非桥读线程)调用;mce 写为同步阻塞且约定要求异步线程,此处天然满足。
 *
 * ## 降级即默认
 * mce 未安装 / 未就绪时各动作降级失败(`经济插件未就绪`),绝不抛、绝不拖垮探针(守 ADR-0015 事故域隔离)。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class EconomyProvider : BusinessProvider {

    /** 业务对接装配中心(core),初始化完成后自注册本 Provider。 */
    @Inject
    lateinit var businessHost: BusinessHost

    override val domain: String = EconomyEnvelope.DOMAIN

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
        ProbeLogger.info("经济业务 Provider 已注册(domain=${EconomyEnvelope.DOMAIN},对接 MultiCurrencyEconomy,只读+写)")
    }

    /** 经济域能力清单:只读 `balance` + 七个写动作(委托 [EconomyEnvelope.manifest])。 */
    override fun manifest(): Map<String, Any?> = EconomyEnvelope.manifest()

    /**
     * 执行一条经济动作。未知动作降级失败。
     *
     * @param action 动作名。
     * @param payload 结构化参数 JSON。
     */
    override fun dispatch(action: String, payload: String): BridgeCommandResult = when (action) {
        EconomyEnvelope.ACTION_BALANCE -> balance(payload)
        EconomyEnvelope.ACTION_DEPOSIT -> deposit(payload)
        EconomyEnvelope.ACTION_WITHDRAW -> withdraw(payload)
        EconomyEnvelope.ACTION_ADJUST -> adjust(payload)
        EconomyEnvelope.ACTION_SET -> set(payload)
        EconomyEnvelope.ACTION_TRANSFER -> transfer(payload)
        EconomyEnvelope.ACTION_CONSUME -> consume(payload)
        EconomyEnvelope.ACTION_REFUND -> refund(payload)
        else -> BridgeCommandResult.fail("未知经济动作:$action")
    }

    // ======================== 只读 ========================

    /** 查询某玩家某币种余额(只读)。mce 未就绪 / 参数缺失 / 查询异常一律降级失败。 */
    private fun balance(payload: String): BridgeCommandResult {
        if (!MultiCurrencyEconomyApi.isReady()) return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val currency = req.getString("currency")
        if (player.isEmpty() || currency.isEmpty()) return BridgeCommandResult.fail("balance 缺少 player 或 currency")
        val amount = runCatching { MultiCurrencyEconomyApi.getBalance(player, currency) }
            .getOrElse { return BridgeCommandResult.fail("查询余额失败:${it.message}") }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeBalanceQuery(player, currency, amount))
    }

    // ======================== 写 ========================

    /** 存款:把 amount 加到玩家余额。 */
    private fun deposit(payload: String): BridgeCommandResult =
        balanceWrite(payload, EconomyEnvelope.ACTION_DEPOSIT) { mce, p, c, amount, key, reason ->
            mce.balanceService.deposit(DepositRequest(p, c, amount, EconomyEnvelope.PLUGIN_NAME, key, reason))
        }

    /** 取款:从玩家余额扣除 amount(余额不足由 mce 返错码)。 */
    private fun withdraw(payload: String): BridgeCommandResult =
        balanceWrite(payload, EconomyEnvelope.ACTION_WITHDRAW) { mce, p, c, amount, key, reason ->
            mce.balanceService.withdraw(WithdrawRequest(p, c, amount, EconomyEnvelope.PLUGIN_NAME, key, reason))
        }

    /** 校正:以有符号差额(amount)修正余额(正加负减)。 */
    private fun adjust(payload: String): BridgeCommandResult =
        balanceWrite(payload, EconomyEnvelope.ACTION_ADJUST) { mce, p, c, amount, key, reason ->
            mce.balanceService.adjust(AdjustRequest(p, c, amount, EconomyEnvelope.PLUGIN_NAME, key, reason))
        }

    /**
     * 设值:把余额设为 target。**非原子**——经「查当前余额→算差额→adjust」实现(mce 无原生设值);
     * 两步之间余额若被它处改动,最终值可能偏离 target(已在 manifest 与输出 nonAtomic 标注)。
     */
    private fun set(payload: String): BridgeCommandResult {
        val mce = readyService() ?: return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val currency = req.getString("currency")
        val taskId = req.getString("taskId")
        EconomyEnvelope.requireWriteCommon(player, currency, taskId)?.let { return it }
        val target = EconomyEnvelope.parseAmount(req.getString("target"))
            ?: return EconomyEnvelope.invalidAmount(req.getString("target"))
        val outcome = runCatching {
            val current = mce.accountQueryService.getBalance(player, currency)
            val delta = target.subtract(current)
            if (delta.signum() == 0) {
                EconomyEnvelope.encodeNoChange(player, currency, current)
            } else {
                EconomyEnvelope.encodeBalance(
                    mce.balanceService.adjust(
                        AdjustRequest(
                            player, currency, delta, EconomyEnvelope.PLUGIN_NAME,
                            EconomyEnvelope.businessOrder(taskId), EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_SET)
                        )
                    ),
                    nonAtomic = true
                )
            }
        }.getOrElse { return EconomyEnvelope.executeError(EconomyEnvelope.ACTION_SET, it) }
        return BridgeCommandResult.ok(outcome)
    }

    /** 转账:玩家间点对点转账(mce 原子双腿)。 */
    private fun transfer(payload: String): BridgeCommandResult {
        val mce = readyService() ?: return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val from = req.getString("from")
        val to = req.getString("to")
        val currency = req.getString("currency")
        val taskId = req.getString("taskId")
        when {
            from.isEmpty() || to.isEmpty() || currency.isEmpty() ->
                return BridgeCommandResult.fail("transfer 缺少 from / to / currency")
            taskId.isBlank() -> return EconomyEnvelope.missingTaskId()
        }
        val amount = EconomyEnvelope.parseAmount(req.getString("amount"))
            ?: return EconomyEnvelope.invalidAmount(req.getString("amount"))
        val fee = EconomyEnvelope.optionalAmount(req, "fee")
        val result = runCatching {
            mce.transferService.transfer(
                TransferRequest(
                    fromPlayerName = from, toPlayerName = to, currencyId = currency, amount = amount, feeAmount = fee,
                    pluginName = EconomyEnvelope.PLUGIN_NAME, idempotency = EconomyEnvelope.businessOrder(taskId),
                    reason = EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_TRANSFER)
                )
            )
        }.getOrElse { return EconomyEnvelope.executeError(EconomyEnvelope.ACTION_TRANSFER, it) }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeTransfer(result))
    }

    /** 原子消费:扣减余额并产可追溯流水号(供后续退款定位)。 */
    private fun consume(payload: String): BridgeCommandResult {
        val mce = readyService() ?: return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val currency = req.getString("currency")
        val taskId = req.getString("taskId")
        EconomyEnvelope.requireWriteCommon(player, currency, taskId)?.let { return it }
        val amount = EconomyEnvelope.parseAmount(req.getString("amount"))
            ?: return EconomyEnvelope.invalidAmount(req.getString("amount"))
        val result = runCatching {
            mce.transactionService.consume(
                ConsumeRequest(
                    player, currency, amount, EconomyEnvelope.PLUGIN_NAME,
                    EconomyEnvelope.businessOrder(taskId), EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_CONSUME)
                )
            )
        }.getOrElse { return EconomyEnvelope.executeError(EconomyEnvelope.ACTION_CONSUME, it) }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeConsume(result))
    }

    /** 退款:按消费流水号(或消费幂等键)回退余额,支持部分退款(refundAmount 缺省为全额)。 */
    private fun refund(payload: String): BridgeCommandResult {
        val mce = readyService() ?: return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val taskId = req.getString("taskId")
        val consumeTxNo = req.getString("consumeTransactionNo").ifBlank { null }
        val consumeReqId = req.getString("consumeRequestId").ifBlank { null }
        when {
            taskId.isBlank() -> return EconomyEnvelope.missingTaskId()
            consumeTxNo == null && consumeReqId == null ->
                return BridgeCommandResult.fail("refund 须提供 consumeTransactionNo 或 consumeRequestId 定位原消费单")
        }
        val refundAmount = if (req.contains("refundAmount")) {
            val raw = req.getString("refundAmount")
            EconomyEnvelope.parseAmount(raw) ?: return EconomyEnvelope.invalidAmount(raw)
        } else {
            null
        }
        val result = runCatching {
            mce.transactionService.refund(
                RefundRequest(
                    consumeTransactionNo = consumeTxNo, consumeRequestId = consumeReqId, refundAmount = refundAmount,
                    pluginName = EconomyEnvelope.PLUGIN_NAME, idempotency = EconomyEnvelope.businessOrder(taskId),
                    reason = EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_REFUND)
                )
            )
        }.getOrElse { return EconomyEnvelope.executeError(EconomyEnvelope.ACTION_REFUND, it) }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeRefund(result))
    }

    // ======================== 写公共骨架 / 服务获取 ========================

    /**
     * deposit / withdraw / adjust 三个 balance 写动作的公共骨架:校验 player/currency/taskId/amount,
     * 调 [call] 执行真实 mce 写,统一编码 [BalanceOperationResult]。
     */
    private inline fun balanceWrite(
        payload: String,
        action: String,
        call: (MultiCurrencyEconomyService, String, String, BigDecimal, IdempotencyMode, String) -> BalanceOperationResult,
    ): BridgeCommandResult {
        val mce = readyService() ?: return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val currency = req.getString("currency")
        val taskId = req.getString("taskId")
        EconomyEnvelope.requireWriteCommon(player, currency, taskId)?.let { return it }
        val amount = EconomyEnvelope.parseAmount(req.getString("amount"))
            ?: return EconomyEnvelope.invalidAmount(req.getString("amount"))
        val result = runCatching {
            call(mce, player, currency, amount, EconomyEnvelope.businessOrder(taskId), EconomyEnvelope.writeReason(req, action))
        }.getOrElse { return EconomyEnvelope.executeError(action, it) }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeBalance(result))
    }

    /** mce 就绪则返回主服务,否则 null(就绪窗口内 service 抛异常亦兜为 null)。 */
    private fun readyService(): MultiCurrencyEconomyService? =
        if (MultiCurrencyEconomyApi.isReady()) runCatching { MultiCurrencyEconomyApi.service }.getOrNull() else null
}
