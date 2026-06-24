package top.wcpe.mc.plugin.serverprobe.bukkit.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.multicurrencyeconomy.api.event.EconomyChange
import top.wcpe.mc.plugin.multicurrencyeconomy.api.model.CurrencyInfo
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [EconomyEventEnvelope] 纯逻辑单元测试(JBIS FR-122)。
 *
 * 覆盖 currencyId→identifier 映射 / 回退、去重键、信封字段编码(金额字符串化、时间 epoch 折算)。
 * 用 [EconomyChange](纯数据类,无 Bukkit 依赖)驱动编码——[PlayerEconomyChangeEvent] 继承 Bukkit `Event`
 * 在无服务端的单测环境构造不便,且与 catchup 共用同一编码骨架,故以 [EconomyChange] 等价覆盖。
 * 真实账务正确性属真机维度,另行真机验收。
 */
class EconomyEventEnvelopeTest {

    private fun currency(id: Int, identifier: String): CurrencyInfo = CurrencyInfo(
        id = id,
        identifier = identifier,
        displayName = identifier,
        symbol = "$",
        precision = 2,
        defaultMaxBalance = -1,
        primary = false,
        enabled = true,
    )

    /** currencyIndex 应按 id→identifier 建映射。 */
    @Test
    fun `currencyIndex 按 id 映射 identifier`() {
        val index = EconomyEventEnvelope.currencyIndex(listOf(currency(1, "coin"), currency(2, "point")))
        assertEquals("coin", index[1])
        assertEquals("point", index[2])
    }

    /** resolveIdentifier 命中映射返回 identifier,未命中回退 Int 字符串(不丢事件)。 */
    @Test
    fun `resolveIdentifier 未命中回退 Int 字符串`() {
        val index = mapOf(1 to "coin")
        assertEquals("coin", EconomyEventEnvelope.resolveIdentifier(1, index))
        assertEquals("99", EconomyEventEnvelope.resolveIdentifier(99, index), "缺映射应回退原始 Int")
    }

    /** dedupKey 为 ledgerId 的字符串化。 */
    @Test
    fun `dedupKey 为 ledgerId 字符串`() {
        assertEquals("123456789", EconomyEventEnvelope.dedupKey(123456789L))
    }

    /** 信封编码:金额 plainString(禁科学计数)、currencyId 同时带 Int 原值与 identifier、occurredAt 折算 UTC epoch 毫秒。 */
    @Test
    fun `encodeCatchupChange 字段编码正确`() {
        val occurredAt = LocalDateTime.of(2026, 6, 25, 12, 0, 0)
        val change = EconomyChange(
            playerName = "Steve",
            currencyId = 1,
            zoneId = "zone-a",
            entryType = "DEPOSIT",
            signedAmount = BigDecimal("100.00"),
            balanceAfter = BigDecimal("100.00"),
            ledgerId = 42L,
            seq = 7L,
            occurredAt = occurredAt,
        )
        val fields = EconomyEventEnvelope.encodeCatchupChange(change, mapOf(1 to "coin"))

        assertEquals("Steve", fields[EconomyEventEnvelope.FIELD_PLAYER_NAME])
        assertEquals("1", fields[EconomyEventEnvelope.FIELD_CURRENCY_ID], "应保留 Int 原值供审计")
        assertEquals("coin", fields[EconomyEventEnvelope.FIELD_CURRENCY], "应折算为 identifier 供聚合")
        assertEquals("zone-a", fields[EconomyEventEnvelope.FIELD_ZONE_ID])
        assertEquals("DEPOSIT", fields[EconomyEventEnvelope.FIELD_ENTRY_TYPE])
        assertEquals("100.00", fields[EconomyEventEnvelope.FIELD_SIGNED_AMOUNT], "金额须 plainString")
        assertEquals("100.00", fields[EconomyEventEnvelope.FIELD_BALANCE_AFTER])
        assertEquals("42", fields[EconomyEventEnvelope.FIELD_LEDGER_ID])
        assertEquals("7", fields[EconomyEventEnvelope.FIELD_SEQ])
        assertEquals(
            occurredAt.toInstant(ZoneOffset.UTC).toEpochMilli().toString(),
            fields[EconomyEventEnvelope.FIELD_OCCURRED_AT],
        )
    }

    /** 大额 / 高精度金额不得退化为科学计数法(toPlainString 保证)。 */
    @Test
    fun `大额金额不退化为科学计数法`() {
        val change = EconomyChange(
            playerName = "Alex",
            currencyId = 5,
            zoneId = "zone-b",
            entryType = "WITHDRAW",
            signedAmount = BigDecimal("-1234567890.123456"),
            balanceAfter = BigDecimal("0.000001"),
            ledgerId = 1L,
            seq = 1L,
            occurredAt = LocalDateTime.of(2026, 1, 1, 0, 0, 0),
        )
        val fields = EconomyEventEnvelope.encodeCatchupChange(change, emptyMap())
        assertEquals("-1234567890.123456", fields[EconomyEventEnvelope.FIELD_SIGNED_AMOUNT])
        assertEquals("0.000001", fields[EconomyEventEnvelope.FIELD_BALANCE_AFTER])
        assertEquals("5", fields[EconomyEventEnvelope.FIELD_CURRENCY], "空映射应回退 Int 字符串")
    }
}
