package top.wcpe.mc.plugin.serverprobe.bukkit.business

import top.wcpe.mc.plugin.multicurrencyeconomy.api.context.OperationContext
import top.wcpe.mc.plugin.multicurrencyeconomy.api.context.OperationContexts
import top.wcpe.mc.plugin.multicurrencyeconomy.api.enums.InitiatorType
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.IdempotencyMode
import top.wcpe.mc.plugin.multicurrencyeconomy.api.result.BalanceOperationResult
import top.wcpe.mc.plugin.multicurrencyeconomy.api.result.ConsumeResult
import top.wcpe.mc.plugin.multicurrencyeconomy.api.result.OperationStatus
import top.wcpe.mc.plugin.multicurrencyeconomy.api.result.RefundResult
import top.wcpe.mc.plugin.multicurrencyeconomy.api.result.TransferOperationResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.math.BigDecimal

/**
 * 经济域信封编解码与校验(JBIS FR-118/120)。
 *
 * [EconomyProvider] 的纯逻辑伴随体:动作常量、manifest、payload 校验、金额解析、幂等键、结果→信封编码。
 * 抽出本对象的目的:① 让 [EconomyProvider] 函数数 / 复杂度落在 detekt 阈值内;② 纯逻辑可脱离 Bukkit 运行期单测。
 * 仍住 platform-bukkit(引用 mce result 类型);不碰 Bukkit API、不调真实服务,故可独立测试。
 */
object EconomyEnvelope {

    /** 经济业务域名,与 Worker 下发的 domain 对应。 */
    const val DOMAIN = "economy"

    /** JianManager 作为 mce 写操作的来源插件名(幂等键命名空间)。 */
    const val PLUGIN_NAME = "JianManager"

    const val ACTION_BALANCE = "balance"
    const val ACTION_DEPOSIT = "deposit"
    const val ACTION_WITHDRAW = "withdraw"
    const val ACTION_ADJUST = "adjust"
    const val ACTION_SET = "set"
    const val ACTION_TRANSFER = "transfer"
    const val ACTION_CONSUME = "consume"
    const val ACTION_REFUND = "refund"

    // ======================== manifest ========================

    /** 经济域能力清单:只读 `balance` + 七个写动作。 */
    fun manifest(): Map<String, Any?> = mapOf(
        "actions" to listOf(
            action(ACTION_BALANCE, listOf("player", "currency"), readOnly = true),
            action(ACTION_DEPOSIT, listOf("player", "currency", "amount", "taskId"), readOnly = false),
            action(ACTION_WITHDRAW, listOf("player", "currency", "amount", "taskId"), readOnly = false),
            action(
                ACTION_ADJUST, listOf("player", "currency", "amount", "taskId"), readOnly = false,
                note = "amount 为有符号差额(正加负减)"
            ),
            action(
                ACTION_SET, listOf("player", "currency", "target", "taskId"), readOnly = false,
                note = "无原生设值,经查余额→算差额→adjust 实现,非原子"
            ),
            action(ACTION_TRANSFER, listOf("from", "to", "currency", "amount", "taskId"), readOnly = false),
            action(ACTION_CONSUME, listOf("player", "currency", "amount", "taskId"), readOnly = false),
            action(
                ACTION_REFUND, listOf("consumeTransactionNo", "refundAmount", "taskId"), readOnly = false,
                note = "按消费流水号退款;refundAmount 缺省为全额"
            ),
        )
    )

    /** manifest 单条动作描述;note 非空时附带(如 adjust 差额语义 / set 非原子)。 */
    fun action(name: String, args: List<String>, readOnly: Boolean, note: String? = null): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>("action" to name, "args" to args, "readOnly" to readOnly)
        if (note != null) entry["note"] = note
        return entry
    }

    // ======================== 校验 / 工具 ========================

    /** 校验单玩家写动作的公共参数:player/currency 非空、taskId 非空;通过返回 null,否则返回失败结果。 */
    fun requireWriteCommon(player: String, currency: String, taskId: String): BridgeCommandResult? = when {
        player.isEmpty() || currency.isEmpty() -> BridgeCommandResult.fail("缺少 player 或 currency")
        taskId.isBlank() -> missingTaskId()
        else -> null
    }

    /** 解析金额字符串为 [BigDecimal];空白或非法返回 null。符号 / 零的语义校验交 mce(各动作不同)。 */
    fun parseAmount(raw: String): BigDecimal? =
        raw.takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

    /** 取可选金额字段(如转账手续费);缺失或非法回退为 0。 */
    fun optionalAmount(req: JsonObject, key: String): BigDecimal =
        if (req.contains(key)) parseAmount(req.getString(key)) ?: BigDecimal.ZERO else BigDecimal.ZERO

    /** 业务单幂等键:pluginName=JianManager + BusinessOrder(taskId)。 */
    fun businessOrder(taskId: String): IdempotencyMode = IdempotencyMode.BusinessOrder(taskId)

    /**
     * 构造 mce 操作上下文:把 JianManager 操作者身份透传进 mce 审计流水(FR-121「操作者映射进插件流水」)。
     *
     * operator 空(无操作者信息,如系统自发)回退 [OperationContexts.system]();否则 PLUGIN 类型、
     * initiatorName=JianManager、operator=管理员、sourceAction=`economy.<action>`、nodeId 入 metadata 供平台侧追溯。
     */
    fun operationContext(operator: String, nodeId: String, action: String): OperationContext =
        if (operator.isBlank()) {
            OperationContexts.system()
        } else {
            OperationContexts.of(
                operator = operator,
                initiatorType = InitiatorType.PLUGIN,
                initiatorName = PLUGIN_NAME,
                sourceAction = "economy.$action",
                metadata = if (nodeId.isBlank()) emptyMap() else mapOf("nodeId" to nodeId),
            )
        }

    /** 操作原因:取 payload `reason`,缺省用 `JianManager economy.<action>`(mce 要求 reason 非空)。 */
    fun writeReason(req: JsonObject, action: String): String =
        req.getString("reason").ifBlank { "JianManager economy.$action" }

    fun notReady(): BridgeCommandResult = BridgeCommandResult.fail("经济插件(MultiCurrencyEconomy)未就绪")

    fun missingTaskId(): BridgeCommandResult =
        BridgeCommandResult.fail("缺少幂等键 taskId(写操作必填,重试须复用同键)")

    fun invalidAmount(raw: String): BridgeCommandResult = BridgeCommandResult.fail("金额非法:$raw")

    fun executeError(action: String, t: Throwable): BridgeCommandResult =
        BridgeCommandResult.fail("$action 执行异常:${t.message}")

    // ======================== 结果编码(信封,金额字符串化) ========================

    /** 编码只读余额查询结果。 */
    fun encodeBalanceQuery(player: String, currency: String, balance: BigDecimal): String = Json.encode(
        linkedMapOf(
            "player" to player,
            "currency" to currency,
            "balance" to balance.toPlainString(),
        )
    )

    /** 编码 balance 写结果(deposit/withdraw/adjust/set);nonAtomic 标记 set 的非原子取舍。 */
    fun encodeBalance(r: BalanceOperationResult, nonAtomic: Boolean = false): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to r.success,
            "status" to r.status.name,
            "player" to r.playerName,
            "currency" to r.currencyId,
            "changeAmount" to r.changeAmount.toPlainString(),
            "beforeBalance" to (r.beforeBalance?.toPlainString() ?: ""),
            "afterBalance" to (r.afterBalance?.toPlainString() ?: ""),
            "ledgerId" to (r.ledgerId?.toString() ?: ""),
            "idempotentHit" to (r.status == OperationStatus.DUPLICATE_REQUEST),
            "message" to r.message,
            "errorCode" to (r.errorCode ?: ""),
            "nonAtomic" to nonAtomic,
        )
    )

    /** set 时目标值已等于当前余额,无需调整。 */
    fun encodeNoChange(player: String, currency: String, current: BigDecimal): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to true,
            "status" to OperationStatus.SUCCESS.name,
            "player" to player,
            "currency" to currency,
            "changeAmount" to BigDecimal.ZERO.toPlainString(),
            "afterBalance" to current.toPlainString(),
            "idempotentHit" to false,
            "message" to "余额已是目标值,无需调整",
            "nonAtomic" to true,
        )
    )

    /** 编码转账结果。 */
    fun encodeTransfer(r: TransferOperationResult): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to r.success,
            "status" to r.status.name,
            "from" to r.fromPlayerName,
            "to" to r.toPlayerName,
            "currency" to r.currencyId,
            "amount" to r.amount.toPlainString(),
            "fromAfterBalance" to (r.fromAfterBalance?.toPlainString() ?: ""),
            "toAfterBalance" to (r.toAfterBalance?.toPlainString() ?: ""),
            "transactionNo" to r.transactionNo,
            "idempotentHit" to (r.status == OperationStatus.DUPLICATE_REQUEST),
            "message" to r.message,
            "errorCode" to (r.errorCode ?: ""),
        )
    )

    /** 编码消费结果。 */
    fun encodeConsume(r: ConsumeResult): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to r.success,
            "transactionNo" to r.transactionNo,
            "consumedAmount" to r.consumedAmount.toPlainString(),
            "afterBalance" to r.balanceAfter.toPlainString(),
            "idempotentHit" to r.idempotentHit,
            "message" to r.message,
            "errorCode" to (r.errorCode ?: ""),
        )
    )

    /** 编码退款结果。 */
    fun encodeRefund(r: RefundResult): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to r.success,
            "transactionNo" to r.transactionNo,
            "consumeTransactionNo" to r.consumeTransactionNo,
            "refundAmount" to r.refundAmount.toPlainString(),
            "totalRefunded" to r.totalRefunded.toPlainString(),
            "remainingRefundable" to r.remainingRefundable.toPlainString(),
            "afterBalance" to r.balanceAfter.toPlainString(),
            "refundType" to r.refundType,
            "message" to r.message,
            "errorCode" to (r.errorCode ?: ""),
        )
    )
}
