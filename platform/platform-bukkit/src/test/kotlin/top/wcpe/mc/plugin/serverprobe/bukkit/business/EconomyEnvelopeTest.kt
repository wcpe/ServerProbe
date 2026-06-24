package top.wcpe.mc.plugin.serverprobe.bukkit.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.multicurrencyeconomy.api.request.IdempotencyMode
import java.math.BigDecimal

/**
 * [EconomyEnvelope] 纯逻辑单元测试(JBIS FR-120)。
 *
 * 只覆盖不触 [top.wcpe.mc.plugin.serverprobe.core.json.Json] 编解码的纯函数(manifest 契约 / 金额解析 /
 * 写参数校验 / 幂等键),不连真实 mce、不连服务端——金额加扣转账的真实账务正确性属真机维度,另行真机验收。
 * (结果信封编码走 TabooLib 运行期 codec,单测环境不可用,故不在此覆盖。)
 */
class EconomyEnvelopeTest {

    /** manifest 应声明 1 只读 + 7 写共 8 个动作,readOnly 标志正确,差额/非原子/退款动作带 note。 */
    @Test
    fun `manifest 声明 8 个动作且 readOnly 标志正确`() {
        val actions = (EconomyEnvelope.manifest()["actions"] as List<*>).map { it as Map<*, *> }
        assertEquals(8, actions.size, "应为 balance + 7 写动作")

        val byName = actions.associateBy { it["action"] }
        assertTrue(
            byName.keys.containsAll(
                listOf("balance", "deposit", "withdraw", "adjust", "set", "transfer", "consume", "refund")
            ),
            "缺动作:${byName.keys}"
        )
        assertEquals(true, byName["balance"]!!["readOnly"], "balance 应为只读")
        assertEquals(false, byName["deposit"]!!["readOnly"], "deposit 应为写")
        assertEquals(false, byName["transfer"]!!["readOnly"], "transfer 应为写")
        // 差额语义 / 非原子 / 退款全额 三处 note 必须在场,供 JianManager 渲染告知运营
        assertTrue((byName["adjust"]!!["note"] as String).isNotBlank(), "adjust 应标差额语义")
        assertTrue((byName["set"]!!["note"] as String).isNotBlank(), "set 应标非原子")
        assertTrue((byName["refund"]!!["note"] as String).isNotBlank(), "refund 应标全额缺省")
        // 普通动作不挂 note
        assertFalse(byName["balance"]!!.containsKey("note"), "balance 不应有 note")
    }

    /** 金额解析:合法数字(含有符号差额)成功,空白/非法返回 null(交调用方回 invalidAmount)。 */
    @Test
    fun `parseAmount 合法成功非法返回 null`() {
        assertEquals(0, BigDecimal("100").compareTo(EconomyEnvelope.parseAmount("100")), "整数")
        assertEquals(0, BigDecimal("12.50").compareTo(EconomyEnvelope.parseAmount("12.50")), "小数")
        assertEquals(0, BigDecimal("-5").compareTo(EconomyEnvelope.parseAmount("-5")), "有符号差额(adjust 用)")
        assertNull(EconomyEnvelope.parseAmount(""), "空串")
        assertNull(EconomyEnvelope.parseAmount("   "), "纯空白")
        assertNull(EconomyEnvelope.parseAmount("abc"), "非数字")
        assertNull(EconomyEnvelope.parseAmount("1.2.3"), "畸形数字")
    }

    /** 单玩家写参数校验:齐全通过(null);缺 player/currency 或 taskId 空白返回失败结果。 */
    @Test
    fun `requireWriteCommon 齐全通过缺失拒绝`() {
        assertNull(EconomyEnvelope.requireWriteCommon("Steve", "coin", "jm-task-1"), "齐全应通过")

        val missingPlayer = EconomyEnvelope.requireWriteCommon("", "coin", "jm-task-1")
        assertNotNull(missingPlayer)
        assertFalse(missingPlayer!!.success, "缺 player 应失败")

        val missingCurrency = EconomyEnvelope.requireWriteCommon("Steve", "", "jm-task-1")
        assertNotNull(missingCurrency)
        assertFalse(missingCurrency!!.success, "缺 currency 应失败")

        val blankTask = EconomyEnvelope.requireWriteCommon("Steve", "coin", "   ")
        assertNotNull(blankTask)
        assertFalse(blankTask!!.success, "taskId 空白应失败")
        assertTrue(blankTask.error.contains("taskId"), "失败原因应点名 taskId:${blankTask.error}")
    }

    /** 幂等键:写动作映射为业务单幂等 BusinessOrder(taskId),外部单号即 taskId(重试须复用)。 */
    @Test
    fun `businessOrder 映射为业务单幂等键`() {
        val key = EconomyEnvelope.businessOrder("jm-task-42")
        assertTrue(key is IdempotencyMode.BusinessOrder, "应为业务单幂等")
        assertEquals("jm-task-42", (key as IdempotencyMode.BusinessOrder).externalOrderNo)
    }

    /** Provider 级失败结果:未就绪 / 缺幂等键 / 金额非法 均为失败且原因可读。 */
    @Test
    fun `失败结果工具均不成功且原因可读`() {
        assertFalse(EconomyEnvelope.notReady().success, "未就绪应失败")

        val missingTask = EconomyEnvelope.missingTaskId()
        assertFalse(missingTask.success)
        assertTrue(missingTask.error.contains("taskId"), "应点名 taskId")

        val invalid = EconomyEnvelope.invalidAmount("xyz")
        assertFalse(invalid.success)
        assertTrue(invalid.error.contains("xyz"), "应回显非法金额原值")
    }
}
