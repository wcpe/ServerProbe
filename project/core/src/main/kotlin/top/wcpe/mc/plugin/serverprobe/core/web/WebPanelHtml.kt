package top.wcpe.mc.plugin.serverprobe.core.web

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample

/**
 * Web 面板 HTML 渲染器(FR4.3,M3,P2)。
 *
 * 无状态纯函数 object:把快照 / 启动画像渲染为自包含 HTML(内联 CSS、无 CDN、无 JS 依赖),
 * 便于在 HTTP 线程并发调用,也便于单元测试(给定数据即可断言关键片段)。
 *
 * 页面:
 * - [renderHome]:总览页——最新快照的 JVM/TPS/在线/内存概览 + 导航链接;
 * - [renderStartup]:最近一次启动画像详情——总时长 / 慢插件 Top-N / 世界耗时;
 * - [renderHistory]:近期历史趋势——最近 N 份快照的 TPS/MSPT/在线人数时序表。
 *
 * 所有文本按 HTML 转义([escape]),防止注入。
 */
object WebPanelHtml {

    /** 页面标题。 */
    private const val TITLE = "ServerProbe"

    /**
     * 渲染总览页。
     *
     * @param snapshot 最新指标快照;为 null(尚无采样)时展示"采集中"占位。
     * @return 完整 HTML 页面。
     */
    fun renderHome(snapshot: MetricSnapshot?): String {
        val body = if (snapshot == null) {
            "<p class=\"muted\">采集中(尚无任何指标采样)…</p>"
        } else {
            val jvm = snapshot.jvm
            val server = snapshot.server
            val tick = server?.tick
            buildString {
                append("<h2>最新快照</h2>")
                append("<p>实例 <b>").append(esc(snapshot.serverId)).append("</b> · ")
                append("平台 <b>").append(esc(snapshot.platform.name)).append("</b> · ")
                append("时间 <b>").append(esc(fmtTime(snapshot.timestampMs))).append("</b></p>")
                append("<table><tr><th>指标</th><th>值</th></tr>")
                appendRow("TPS(1/5/15m)", tick?.let { tpsText(it) } ?: "N/A")
                appendRow("MSPT(avg/p95/p99)", tick?.let { msptText(it) } ?: "N/A")
                appendRow("在线人数", server?.let { "${it.onlinePlayers}/${it.maxPlayers}" } ?: "N/A")
                appendRow("堆内存", bytesText(jvm.heapUsedBytes, jvm.heapMaxBytes))
                appendRow("线程", jvm.threadCount.toString())
                appendRow("进程 CPU", jvm.processCpuLoad.takeIf { it >= 0 }?.let { "%.1f%%".format(it * 100) } ?: "N/A")
                appendRow("运行时长", fmtDuration(jvm.uptimeMs))
                append("</table>")
            }
        }
        return page("总览", body)
    }

    /**
     * 渲染最近启动画像详情页。
     *
     * @param profile 最近一次启动画像;为 null 时展示"尚无画像"占位。
     * @return 完整 HTML 页面。
     */
    fun renderStartup(profile: StartupProfile?): String {
        val body = if (profile == null) {
            "<p class=\"muted\">尚无启动画像(服务器就绪后自动生成)。</p>"
        } else {
            buildString {
                append("<h2>最近启动画像</h2>")
                append("<p>总时长 <b>").append(esc(fmtDuration(profile.totalMs))).append("</b> · ")
                append("平台 <b>").append(esc(profile.platform.name)).append("</b> · ")
                append("MC 版本 <b>").append(esc(profile.mcVersion)).append("</b></p>")
                append("<h3>慢插件 Top-N</h3>")
                append("<table><tr><th>插件</th><th>onEnable 耗时</th></tr>")
                profile.pluginTimings.sortedByDescending { it.enableMs }.take(TOP_N).forEach {
                    append("<tr><td>").append(esc(it.name)).append("</td><td>")
                        .append(esc(fmtDuration(it.enableMs))).append("</td></tr>")
                }
                append("</table>")
                append("<h3>世界加载耗时</h3>")
                append("<table><tr><th>世界</th><th>加载耗时</th></tr>")
                if (profile.worldTimings.isEmpty()) {
                    append("<tr><td colspan=\"2\" class=\"muted\">无</td></tr>")
                }
                profile.worldTimings.forEach {
                    append("<tr><td>").append(esc(it.name)).append("</td><td>")
                        .append(esc(fmtDuration(it.loadMs))).append("</td></tr>")
                }
                append("</table>")
            }
        }
        return page("启动画像", body)
    }

    /**
     * 渲染近期历史趋势页(最近 N 份快照的 TPS/MSPT/在线人数时序表)。
     *
     * @param snapshots 近期快照(按时间正序展示);为空时展示"暂无历史"占位。
     * @return 完整 HTML 页面。
     */
    fun renderHistory(snapshots: List<MetricSnapshot>): String {
        val body = if (snapshots.isEmpty()) {
            "<p class=\"muted\">暂无历史数据(采集启动后自动累积)。</p>"
        } else {
            buildString {
                append("<h2>近期历史趋势(最近 ").append(snapshots.size).append(" 份快照)</h2>")
                append("<table><tr><th>时间</th><th>TPS(1m)</th><th>MSPT(p95)</th><th>在线</th><th>堆已用</th></tr>")
                snapshots.forEach { s ->
                    val tick = s.server?.tick
                    append("<tr><td>").append(esc(fmtTime(s.timestampMs))).append("</td><td>")
                        .append(tick?.tps1m?.let { "%.1f".format(it) } ?: "N/A").append("</td><td>")
                        .append(tick?.msptP95?.let { "%.1f".format(it) } ?: "N/A").append("</td><td>")
                        .append(s.server?.onlinePlayers ?: "-").append("</td><td>")
                        .append(bytesText(s.jvm.heapUsedBytes, -1)).append("</td></tr>")
                }
                append("</table>")
            }
        }
        return page("历史趋势", body)
    }

    /** 组装完整页面(统一导航 + 内联样式)。 */
    private fun page(title: String, body: String): String = """
        <!DOCTYPE html>
        <html lang="zh">
        <head>
        <meta charset="UTF-8">
        <title>$TITLE - $title</title>
        <style>
        body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0;padding:0;background:#f5f6f8;color:#222}
        header{background:#1f2937;color:#fff;padding:12px 24px;display:flex;gap:20px;align-items:center}
        header a{color:#93c5fd;text-decoration:none;font-weight:600}
        header a:hover{text-decoration:underline}
        header .brand{font-weight:700;font-size:1.1em;color:#fff}
        main{padding:20px 24px;max-width:960px;margin:0 auto}
        h2{margin-top:0} h3{margin-bottom:6px}
        table{border-collapse:collapse;width:100%;background:#fff;border:1px solid #e2e5ea}
        th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}
        th{background:#f0f2f5;font-weight:600}
        .muted{color:#888}
        </style>
        </head>
        <body>
        <header>
          <span class="brand">ServerProbe</span>
          <a href="/">总览</a>
          <a href="/startup">启动画像</a>
          <a href="/history">历史趋势</a>
        </header>
        <main>$body</main>
        </body>
        </html>
    """.trimIndent()

    /** 追加一行表格。 */
    private fun StringBuilder.appendRow(label: String, value: String) {
        append("<tr><td>").append(esc(label)).append("</td><td>").append(esc(value)).append("</td></tr>")
    }

    /** TPS 文本。 */
    private fun tpsText(tick: TickSample): String = listOf(tick.tps1m, tick.tps5m, tick.tps15m)
        .joinToString(" / ") { it?.let { v -> "%.1f".format(v) } ?: "N/A" }

    /** MSPT 文本。 */
    private fun msptText(tick: TickSample): String = listOf(tick.msptAvg, tick.msptP95, tick.msptP99)
        .joinToString(" / ") { it?.let { v -> "%.1fms".format(v) } ?: "N/A" }

    /** 字节文本。 */
    private fun bytesText(used: Long, max: Long): String =
        "${fmtBytes(used)}${if (max >= 0) " / ${fmtBytes(max)}" else ""}"

    /** 字节格式化(B/KB/MB/GB)。 */
    private fun fmtBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    /** 时长文本。 */
    private fun fmtDuration(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val sec = ms / 1000.0
        if (sec < 60) return "%.1fs".format(sec)
        val min = sec / 60.0
        return "%.1fmin".format(min)
    }

    /** 时间文本(HH:mm:ss)。 */
    private fun fmtTime(epochMs: Long): String = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(epochMs))

    /** HTML 转义(防注入)。 */
    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /** 慢插件榜展示条数。 */
    private const val TOP_N = 10
}
