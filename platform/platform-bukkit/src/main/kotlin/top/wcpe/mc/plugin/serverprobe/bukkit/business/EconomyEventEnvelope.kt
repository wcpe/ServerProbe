package top.wcpe.mc.plugin.serverprobe.bukkit.business

import top.wcpe.mc.plugin.multicurrencyeconomy.api.event.EconomyChange
import top.wcpe.mc.plugin.multicurrencyeconomy.api.event.PlayerEconomyChangeEvent
import top.wcpe.mc.plugin.multicurrencyeconomy.api.model.CurrencyInfo
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 经济变更事件信封编码与 currencyId→identifier 映射(JBIS FR-122,见 ADR-027/028)。
 *
 * [BukkitEconomyEventListener] 的纯逻辑伴随体:把 mce 的 [PlayerEconomyChangeEvent] / [EconomyChange]
 * 折算成上报信封(全字符串载荷,金额禁浮点),并据 [CurrencyInfo] 列表把 currencyId(Int 主键)映射为
 * 全局稳定的 identifier(英文小写业务键)。抽出本对象的目的:① 让监听器函数数 / 复杂度落在 detekt 阈值内;
 * ② 纯逻辑(无 Bukkit / mce 运行期服务依赖,仅引 mce 数据类型)可脱离游戏服独立单测。
 *
 * ## 为何必须映射 identifier
 * mce 流水以 currencyId(Int 自增主键)承载货币;但跨服 / 跨区该主键不保证一致(各服独立建币),
 * 平台侧聚合若按 Int 主键归并会串味。identifier 是 mce 约定的**全局唯一业务键**(见 [CurrencyInfo.identifier]),
 * 故上报前在本服用 `getActiveCurrencies()` 建 id→identifier 映射并折算;映射缺失(币种已删 / 未启用)时
 * 回退原始 Int 字符串(下游仍可去重,仅聚合维度退化),绝不丢事件。
 *
 * ## 为何带 zoneId
 * mce 账户 / 流水按本服 zoneId 隔离(CoreLib),同名玩家跨区独立。平台聚合必须带 `node→zone` 维度,
 * 故信封原样携带事件 zoneId,CP 据 (node, zone, player, currency) 归并镜像。
 */
object EconomyEventEnvelope {

    /** 经济业务域名,与 Worker / CP 解析约定及 [EconomyEnvelope.DOMAIN] 一致。 */
    const val DOMAIN = EconomyEnvelope.DOMAIN

    // 信封字段键名(须与 CP 侧 business_events.go 的解析约定逐字一致)。
    const val FIELD_PLAYER_NAME = "playerName"
    const val FIELD_CURRENCY_ID = "currencyId"
    const val FIELD_CURRENCY = "currency"
    const val FIELD_ZONE_ID = "zoneId"
    const val FIELD_ENTRY_TYPE = "entryType"
    const val FIELD_SIGNED_AMOUNT = "signedAmount"
    const val FIELD_BALANCE_AFTER = "balanceAfter"
    const val FIELD_LEDGER_ID = "ledgerId"
    const val FIELD_SEQ = "seq"
    const val FIELD_OCCURRED_AT = "occurredAt"

    /**
     * 据启用货币列表建 currencyId(Int)→identifier(String)映射。
     *
     * 调用方在本服 mce 就绪时取 `getActiveCurrencies()` 传入;空列表得空映射(折算时全部回退原始 Int)。
     *
     * @param currencies 启用货币列表。
     * @return id→identifier 映射。
     */
    fun currencyIndex(currencies: List<CurrencyInfo>): Map<Int, String> =
        currencies.associate { it.id to it.identifier }

    /**
     * 把 currencyId 映射为 identifier;映射缺失时回退 Int 字符串(不丢事件,聚合维度退化)。
     *
     * @param currencyId mce 货币 Int 主键。
     * @param index id→identifier 映射(见 [currencyIndex])。
     * @return 货币 identifier 或回退的 Int 字符串。
     */
    fun resolveIdentifier(currencyId: Int, index: Map<Int, String>): String =
        index[currencyId] ?: currencyId.toString()

    /** 上报去重键:对应总账流水 ID(ledgerId)字符串化(全局唯一、稳定,见事件契约)。 */
    fun dedupKey(ledgerId: Long): String = ledgerId.toString()

    /**
     * 把一条 [PlayerEconomyChangeEvent](在线实时流)折算为上报信封字段。
     *
     * @param event mce 经济变更事件。
     * @param index 货币 id→identifier 映射。
     * @return 全字符串信封字段(金额经 [BigDecimal.toPlainString] 防浮点 / 科学计数)。
     */
    fun encodeChange(event: PlayerEconomyChangeEvent, index: Map<Int, String>): Map<String, String> = encode(
        playerName = event.playerName,
        currencyId = event.currencyId,
        zoneId = event.zoneId,
        entryType = event.entryType,
        signedAmount = event.signedAmount,
        balanceAfter = event.balanceAfter,
        ledgerId = event.ledgerId,
        seq = event.seq,
        occurredAt = event.occurredAt,
        index = index,
    )

    /**
     * 把一条 [EconomyChange](上线 catchup 离线补发项)折算为上报信封字段。
     *
     * 字段语义与 [encodeChange] 一致;catchup 与实时流共用同一去重键(ledgerId),CP 据此跨两条路径去重。
     *
     * @param change 单条离线补发经济变更。
     * @param index 货币 id→identifier 映射。
     * @return 全字符串信封字段。
     */
    fun encodeCatchupChange(change: EconomyChange, index: Map<Int, String>): Map<String, String> = encode(
        playerName = change.playerName,
        currencyId = change.currencyId,
        zoneId = change.zoneId,
        entryType = change.entryType,
        signedAmount = change.signedAmount,
        balanceAfter = change.balanceAfter,
        ledgerId = change.ledgerId,
        seq = change.seq,
        occurredAt = change.occurredAt,
        index = index,
    )

    /** 信封字段统一编码:金额字符串化(禁浮点)、currencyId 同时带 Int 原值与映射后 identifier、时间戳为 epoch 毫秒。 */
    @Suppress("LongParameterList")
    private fun encode(
        playerName: String,
        currencyId: Int,
        zoneId: String,
        entryType: String,
        signedAmount: BigDecimal,
        balanceAfter: BigDecimal,
        ledgerId: Long,
        seq: Long,
        occurredAt: LocalDateTime,
        index: Map<Int, String>,
    ): Map<String, String> = linkedMapOf(
        FIELD_PLAYER_NAME to playerName,
        // 同时上报 Int 原值(审计可回溯)与映射后 identifier(聚合主维度)。
        FIELD_CURRENCY_ID to currencyId.toString(),
        FIELD_CURRENCY to resolveIdentifier(currencyId, index),
        FIELD_ZONE_ID to zoneId,
        FIELD_ENTRY_TYPE to entryType,
        FIELD_SIGNED_AMOUNT to signedAmount.toPlainString(),
        FIELD_BALANCE_AFTER to balanceAfter.toPlainString(),
        FIELD_LEDGER_ID to ledgerId.toString(),
        FIELD_SEQ to seq.toString(),
        // occurredAt 无时区,按 UTC 折算 epoch 毫秒(CP 仅作展示 / 排序,口径统一即可)。
        FIELD_OCCURRED_AT to occurredAt.toInstant(ZoneOffset.UTC).toEpochMilli().toString(),
    )
}
