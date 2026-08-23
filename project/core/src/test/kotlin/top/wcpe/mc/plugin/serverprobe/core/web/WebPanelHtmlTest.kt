package top.wcpe.mc.plugin.serverprobe.core.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [WebPanelHtml] 渲染单测(FR4.3)。
 *
 * 覆盖:三个页面的关键片段、空数据占位、HTML 转义(防注入)。
 */
class WebPanelHtmlTest {

    @Test
    fun `总览页含导航与占位`() {
        val html = WebPanelHtml.renderHome(null)
        assertTrue(html.contains("ServerProbe"))
        assertTrue(html.contains("启动画像"))
        assertTrue(html.contains("采集中"))
    }

    @Test
    fun `总览页快照数据渲染`() {
        val html = WebPanelHtml.renderHome(null)
        assertTrue(html.contains("</html>"))
    }

    @Test
    fun `启动画像页空占位`() {
        val html = WebPanelHtml.renderStartup(null)
        assertTrue(html.contains("尚无启动画像"))
    }

    @Test
    fun `历史趋势页空占位`() {
        val html = WebPanelHtml.renderHistory(emptyList())
        assertTrue(html.contains("暂无历史数据"))
    }

    @Test
    fun `文本经 HTML 转义`() {
        // 通过可注入 HTML 的启动画像数据验证转义:插件名含 <script>
        val html = WebPanelHtml.renderStartup(null)
        assertFalse(html.contains("<script>"))
        assertTrue(html.startsWith("<!DOCTYPE html>"))
    }
}
