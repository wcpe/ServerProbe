package top.wcpe.mc.plugin.serverprobe.bukkit.business

import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.math.BigDecimal

/**
 * 经济域信封编解码与校验(JBIS FR-118/120)。
 *
 * [EconomyProvider] 的纯逻辑伴随体:动作常量、manifest、payload 校验、金额解析、幂等键、结果→信封编码。
 * 本对象不在方法签名中暴露 MultiCurrencyEconomy API 类型，避免独立服未安装 mce 时 IoC 反射扫描触发缺类。
 * 真实经济域调用发生时 mce 已就绪，此处再通过反射构造幂等键 / 上下文并读取结果 DTO。
 */
@Suppress("TooManyFunctions")
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
    fun businessOrder(taskId: String): Any {
        val type = Class.forName(IDEMPOTENCY_BUSINESS_ORDER)
        return type.getConstructor(String::class.java).newInstance(taskId)
    }

    /** 构造 mce 操作上下文:把 JianManager 操作者身份透传进 mce 审计流水。 */
    fun operationContext(operator: String, nodeId: String, action: String): Any {
        val contexts = Class.forName(OPERATION_CONTEXTS)
        if (operator.isBlank()) return contexts.methods.first { it.name == "system" && it.parameterCount == 0 }.invoke(null)
        val initiatorType = Class.forName(INITIATOR_TYPE)
        val plugin = initiatorType.enumConstants.first { (it as Enum<*>).name == "PLUGIN" }
        val metadata = if (nodeId.isBlank()) emptyMap<String, String>() else mapOf("nodeId" to nodeId)
        val method = contexts.methods.first { it.name == "of" && it.parameterCount == 5 }
        return method.invoke(null, operator, plugin, PLUGIN_NAME, "economy.$action", metadata)
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

    /** 编码只读余额查询结果。 */
    fun encodeBalanceQuery(player: String, currency: String, balance: BigDecimal): String = Json.encode(
        linkedMapOf(
            "player" to player,
            "currency" to currency,
            "balance" to balance.toPlainString(),
        )
    )

    /** 编码 balance 写结果(deposit/withdraw/adjust/set);nonAtomic 标记 set 的非原子取舍。 */
    fun encodeBalance(r: Any, nonAtomic: Boolean = false): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to read(r, "success"),
            "status" to statusName(r),
            "player" to read(r, "playerName"),
            "currency" to read(r, "currencyId"),
            "changeAmount" to amountText(read(r, "changeAmount")),
            "beforeBalance" to amountText(read(r, "beforeBalance")),
            "afterBalance" to amountText(read(r, "afterBalance")),
            "ledgerId" to (read(r, "ledgerId")?.toString() ?: ""),
            "idempotentHit" to (statusName(r) == STATUS_DUPLICATE_REQUEST),
            "message" to read(r, "message"),
            "errorCode" to (read(r, "errorCode") ?: ""),
            "nonAtomic" to nonAtomic,
        )
    )

    /** set 时目标值已等于当前余额,无需调整。 */
    fun encodeNoChange(player: String, currency: String, current: BigDecimal): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to true,
            "status" to STATUS_SUCCESS,
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
    fun encodeTransfer(r: Any): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to read(r, "success"),
            "status" to statusName(r),
            "from" to read(r, "fromPlayerName"),
            "to" to read(r, "toPlayerName"),
            "currency" to read(r, "currencyId"),
            "amount" to amountText(read(r, "amount")),
            "fromAfterBalance" to amountText(read(r, "fromAfterBalance")),
            "toAfterBalance" to amountText(read(r, "toAfterBalance")),
            "transactionNo" to read(r, "transactionNo"),
            "idempotentHit" to (statusName(r) == STATUS_DUPLICATE_REQUEST),
            "message" to read(r, "message"),
            "errorCode" to (read(r, "errorCode") ?: ""),
        )
    )

    /** 编码消费结果。 */
    fun encodeConsume(r: Any): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to read(r, "success"),
            "transactionNo" to read(r, "transactionNo"),
            "consumedAmount" to amountText(read(r, "consumedAmount")),
            "afterBalance" to amountText(read(r, "balanceAfter")),
            "idempotentHit" to read(r, "idempotentHit"),
            "message" to read(r, "message"),
            "errorCode" to (read(r, "errorCode") ?: ""),
        )
    )

    /** 编码退款结果。 */
    fun encodeRefund(r: Any): String = Json.encode(
        linkedMapOf<String, Any?>(
            "success" to read(r, "success"),
            "transactionNo" to read(r, "transactionNo"),
            "consumeTransactionNo" to read(r, "consumeTransactionNo"),
            "refundAmount" to amountText(read(r, "refundAmount")),
            "totalRefunded" to amountText(read(r, "totalRefunded")),
            "remainingRefundable" to amountText(read(r, "remainingRefundable")),
            "afterBalance" to amountText(read(r, "balanceAfter")),
            "refundType" to read(r, "refundType"),
            "message" to read(r, "message"),
            "errorCode" to (read(r, "errorCode") ?: ""),
        )
    )

    private fun statusName(result: Any): String = read(result, "status")?.toString() ?: ""

    private fun amountText(raw: Any?): String = when (raw) {
        null -> ""
        is BigDecimal -> raw.toPlainString()
        else -> raw.toString()
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

    private const val IDEMPOTENCY_BUSINESS_ORDER =
        "top.wcpe.mc.plugin.multicurrencyeconomy.api.request.IdempotencyMode\$BusinessOrder"
    private const val OPERATION_CONTEXTS = "top.wcpe.mc.plugin.multicurrencyeconomy.api.context.OperationContexts"
    private const val INITIATOR_TYPE = "top.wcpe.mc.plugin.multicurrencyeconomy.api.enums.InitiatorType"
    private const val STATUS_SUCCESS = "SUCCESS"
    private const val STATUS_DUPLICATE_REQUEST = "DUPLICATE_REQUEST"
}
