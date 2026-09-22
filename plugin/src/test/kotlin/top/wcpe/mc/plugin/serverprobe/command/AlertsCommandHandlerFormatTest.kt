package top.wcpe.mc.plugin.serverprobe.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [AlertsCommandHandler] 纯格式化函数单测(技术债 #26-⑦)。
 *
 * /probe alerts 的数值与级别呈现规则:整数省小数、非整数按至多 3 位小数四舍五入后
 * 去尾随零、级别颜色映射。
 */
class AlertsCommandHandlerFormatTest {

    /** 数值格式化:整数去 .0;非整数保留有效精度(默认阈值 0.05 不得被显示成 0.1)。 */
    @Test
    fun `数值格式化整数省小数非整数保留有效精度`() {
        assertEquals("45", AlertsCommandHandler.formatNumber(45.0))
        assertEquals("0", AlertsCommandHandler.formatNumber(0.0))
        assertEquals("45.2", AlertsCommandHandler.formatNumber(45.2))
        // Old GC 默认阈值:真机验收发现原实现把它显示成 0.1(2 倍失真),阈值是运维调参依据,必须如实呈现
        assertEquals("0.05", AlertsCommandHandler.formatNumber(0.05))
        assertEquals("0.2", AlertsCommandHandler.formatNumber(0.20036064916850332))
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
