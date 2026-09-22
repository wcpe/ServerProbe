package top.wcpe.mc.plugin.serverprobe.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [AlertsCommandHandler] 纯格式化函数单测(技术债 #26-⑦)。
 *
 * /probe alerts 的数值与级别呈现规则:整数省小数、非整数保留一位、级别颜色映射。
 */
class AlertsCommandHandlerFormatTest {

    /** 数值格式化:整数去 .0,非整数保留一位小数。 */
    @Test
    fun `数值格式化整数省小数非整数一位小数`() {
        assertEquals("45", AlertsCommandHandler.formatNumber(45.0))
        assertEquals("0", AlertsCommandHandler.formatNumber(0.0))
        assertEquals("45.2", AlertsCommandHandler.formatNumber(45.2))
        assertEquals("0.1", AlertsCommandHandler.formatNumber(0.05)) // 告警默认 Old GC 阈值
        assertEquals("180", AlertsCommandHandler.formatNumber(180.0)) // 启动慢默认阈值
    }

    /** 级别颜色:WARN=黄(e)、CRITICAL=红(c)、其余=灰(7)。 */
    @Test
    fun `级别颜色映射`() {
        assertEquals("e", AlertsCommandHandler.levelColor("WARN"))
        assertEquals("c", AlertsCommandHandler.levelColor("CRITICAL"))
        assertEquals("7", AlertsCommandHandler.levelColor("INFO"))
        assertEquals("7", AlertsCommandHandler.levelColor(""))
    }
}
