package top.wcpe.mc.plugin.serverprobe.bukkit.business

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.math.BigDecimal

/**
 * 经济业务 Provider(JBIS,见 ADR-0015 / JianManager FR-118 只读、FR-120 写)。
 *
 * MultiCurrencyEconomy 是软依赖，本类不能在方法描述符中暴露其 API 类型，否则独立服未安装 mce 时，IoC 反射扫描
 * `declaredMethods` 会因缺类失败并影响探针启用。真实经济域调用只在插件桥开启且 mce 已就绪时发生，届时通过
 * 反射访问 API / 服务 / 请求 DTO；未安装 / 未就绪统一降级为业务失败，不影响监控采集与插件桥心跳。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class EconomyProvider : BusinessProvider {

    /** 业务对接装配中心(core),初始化完成后自注册本 Provider。 */
    @Inject
    lateinit var businessHost: BusinessHost

    override val domain: String = EconomyEnvelope.DOMAIN

    /** 依赖注入完成后做平台门 + 桥开关门并自注册。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        if (!ProbeConfig.bridgeEnabled()) return
        businessHost.register(this)
        ProbeLogger.info("经济业务 Provider 已注册(domain=${EconomyEnvelope.DOMAIN},对接 MultiCurrencyEconomy,只读+写)")
    }

    /** 经济域能力清单:只读 `balance` + 七个写动作。 */
    override fun manifest(): Map<String, Any?> = EconomyEnvelope.manifest()

    /** 执行一条经济动作。未知动作降级失败。 */
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

    /** 查询某玩家某币种余额(只读)。mce 未就绪 / 参数缺失 / 查询异常一律降级失败。 */
    private fun balance(payload: String): BridgeCommandResult {
        val api = readyApiClass() ?: return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val currency = req.getString("currency")
        if (player.isEmpty() || currency.isEmpty()) return BridgeCommandResult.fail("balance 缺少 player 或 currency")
        val amount = runCatching { callStatic(api, "getBalance", player, currency) as BigDecimal }
            .getOrElse { return BridgeCommandResult.fail("查询余额失败:${it.message}") }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeBalanceQuery(player, currency, amount))
    }

    /** 存款:把 amount 加到玩家余额。 */
    private fun deposit(payload: String): BridgeCommandResult =
        balanceWrite(payload, EconomyEnvelope.ACTION_DEPOSIT, DEPOSIT_REQUEST, "deposit")

    /** 取款:从玩家余额扣除 amount(余额不足由 mce 返错码)。 */
    private fun withdraw(payload: String): BridgeCommandResult =
        balanceWrite(payload, EconomyEnvelope.ACTION_WITHDRAW, WITHDRAW_REQUEST, "withdraw")

    /** 校正:以有符号差额(amount)修正余额(正加负减)。 */
    private fun adjust(payload: String): BridgeCommandResult =
        balanceWrite(payload, EconomyEnvelope.ACTION_ADJUST, ADJUST_REQUEST, "adjust")

    /** 设值:把余额设为 target。无原生设值,经「查余额→算差额→adjust」实现。 */
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
            val query = read(mce, "accountQueryService") ?: error("缺少账户查询服务")
            val current = call(query, "getBalance", player, currency) as BigDecimal
            val delta = target.subtract(current)
            if (delta.signum() == 0) {
                EconomyEnvelope.encodeNoChange(player, currency, current)
            } else {
                val balanceService = read(mce, "balanceService") ?: error("缺少余额服务")
                EconomyEnvelope.encodeBalance(
                    call(
                        balanceService,
                        "adjust",
                        newRequest(
                            ADJUST_REQUEST,
                            player,
                            currency,
                            delta,
                            EconomyEnvelope.PLUGIN_NAME,
                            EconomyEnvelope.businessOrder(taskId),
                            EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_SET),
                            contextOf(req, EconomyEnvelope.ACTION_SET),
                        )
                    ),
                    nonAtomic = true,
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
            val transferService = read(mce, "transferService") ?: error("缺少转账服务")
            call(
                transferService,
                "transfer",
                newRequest(
                    TRANSFER_REQUEST,
                    from,
                    to,
                    currency,
                    amount,
                    fee,
                    EconomyEnvelope.PLUGIN_NAME,
                    EconomyEnvelope.businessOrder(taskId),
                    EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_TRANSFER),
                    contextOf(req, EconomyEnvelope.ACTION_TRANSFER),
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
            val transactionService = read(mce, "transactionService") ?: error("缺少交易服务")
            call(
                transactionService,
                "consume",
                newRequest(
                    CONSUME_REQUEST,
                    player,
                    currency,
                    amount,
                    EconomyEnvelope.PLUGIN_NAME,
                    EconomyEnvelope.businessOrder(taskId),
                    EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_CONSUME),
                    contextOf(req, EconomyEnvelope.ACTION_CONSUME),
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
            val transactionService = read(mce, "transactionService") ?: error("缺少交易服务")
            call(
                transactionService,
                "refund",
                newRequest(
                    REFUND_REQUEST,
                    consumeTxNo,
                    consumeReqId,
                    refundAmount,
                    EconomyEnvelope.PLUGIN_NAME,
                    EconomyEnvelope.businessOrder(taskId),
                    EconomyEnvelope.writeReason(req, EconomyEnvelope.ACTION_REFUND),
                    contextOf(req, EconomyEnvelope.ACTION_REFUND),
                )
            )
        }.getOrElse { return EconomyEnvelope.executeError(EconomyEnvelope.ACTION_REFUND, it) }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeRefund(result))
    }

    /** deposit / withdraw / adjust 三个 balance 写动作的公共骨架。 */
    private fun balanceWrite(payload: String, action: String, requestClass: String, methodName: String): BridgeCommandResult {
        val mce = readyService() ?: return EconomyEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val currency = req.getString("currency")
        val taskId = req.getString("taskId")
        EconomyEnvelope.requireWriteCommon(player, currency, taskId)?.let { return it }
        val amount = EconomyEnvelope.parseAmount(req.getString("amount"))
            ?: return EconomyEnvelope.invalidAmount(req.getString("amount"))
        val result = runCatching {
            val balanceService = read(mce, "balanceService") ?: error("缺少余额服务")
            call(
                balanceService,
                methodName,
                newRequest(
                    requestClass,
                    player,
                    currency,
                    amount,
                    EconomyEnvelope.PLUGIN_NAME,
                    EconomyEnvelope.businessOrder(taskId),
                    EconomyEnvelope.writeReason(req, action),
                    contextOf(req, action),
                )
            )
        }.getOrElse { return EconomyEnvelope.executeError(action, it) }
        return BridgeCommandResult.ok(EconomyEnvelope.encodeBalance(result))
    }

    /** 从 payload 取操作者身份(FR-121 注入的 operator/nodeId)构造 mce 操作上下文,透传进 mce 审计流水。 */
    private fun contextOf(req: JsonObject, action: String): Any =
        EconomyEnvelope.operationContext(req.getString("operator"), req.getString("nodeId"), action)

    /** mce 就绪则返回主服务,否则 null(未安装 / 就绪窗口异常均降级为 null)。 */
    private fun readyService(): Any? {
        val api = readyApiClass() ?: return null
        return runCatching { callStatic(api, "getService") }.getOrNull()
    }

    /** mce API 类存在且 isReady=true 则返回类对象。 */
    private fun readyApiClass(): Class<*>? {
        val api = runCatching { Class.forName(MCE_API) }.getOrNull() ?: return null
        val ready = runCatching { callStatic(api, "isReady") as? Boolean }.getOrNull() ?: false
        return if (ready) api else null
    }

    private fun newRequest(className: String, vararg args: Any?): Any {
        val type = Class.forName(className)
        val ctor = type.constructors.firstOrNull { it.parameterCount == args.size }
            ?: error("未找到请求构造器:$className/${args.size}")
        return ctor.newInstance(*args)
    }

    private fun call(target: Any, methodName: String, vararg args: Any?): Any {
        val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == args.size }
            ?: error("未找到 MCE 方法:$methodName/${args.size}")
        return method.invoke(target, *args)
    }

    private fun callStatic(type: Class<*>, methodName: String, vararg args: Any?): Any {
        val method = type.methods.firstOrNull { it.name == methodName && it.parameterCount == args.size }
            ?: error("未找到 MCE 静态方法:$methodName/${args.size}")
        return method.invoke(null, *args)
    }

    private fun read(target: Any, property: String): Any? {
        val suffix = property.substring(0, 1).uppercase() + property.substring(1)
        for (methodName in listOf("get$suffix", "is$suffix")) {
            val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            if (method != null) return method.invoke(target)
        }
        val field = target.javaClass.fields.firstOrNull { it.name == property }
        return field?.get(target)
    }

    private companion object {
        const val MCE_API = "top.wcpe.mc.plugin.multicurrencyeconomy.api.MultiCurrencyEconomyApi"
        const val DEPOSIT_REQUEST = "top.wcpe.mc.plugin.multicurrencyeconomy.api.request.DepositRequest"
        const val WITHDRAW_REQUEST = "top.wcpe.mc.plugin.multicurrencyeconomy.api.request.WithdrawRequest"
        const val ADJUST_REQUEST = "top.wcpe.mc.plugin.multicurrencyeconomy.api.request.AdjustRequest"
        const val TRANSFER_REQUEST = "top.wcpe.mc.plugin.multicurrencyeconomy.api.request.TransferRequest"
        const val CONSUME_REQUEST = "top.wcpe.mc.plugin.multicurrencyeconomy.api.request.ConsumeRequest"
        const val REFUND_REQUEST = "top.wcpe.mc.plugin.multicurrencyeconomy.api.request.RefundRequest"
    }
}
