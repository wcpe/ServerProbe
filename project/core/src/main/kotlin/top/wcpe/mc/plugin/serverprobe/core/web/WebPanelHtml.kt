package top.wcpe.mc.plugin.serverprobe.core.web

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ObservedRegionWorldMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage

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
                appendObservedRegions(server)
            }
        }
        return page("总览", body)
    }

    /** 追加 Folia 已观测 region 的汇总与明细；没有该字段时代表当前平台不支持或尚未采集。 */
    private fun StringBuilder.appendObservedRegions(server: ServerMetrics?) {
        val worlds = server?.observedRegionWorlds
        val regions = server?.observedRegions
        if (worlds == null && regions == null) {
            return
        }
        append("<h2>Folia 已观测 region</h2>")
        append("<p class=\"muted\">Folia 全局 TPS/MSPT：N/A</p>")
        if (worlds.isNullOrEmpty() && regions.isNullOrEmpty()) {
            append("<p class=\"muted\">暂无已观测 region。</p>")
            return
        }
        appendObservedWorlds(worlds.orEmpty())
        appendObservedRegionDetails(regions.orEmpty())
    }

    /** 追加世界级样本加权汇总。 */
    private fun StringBuilder.appendObservedWorlds(worlds: List<ObservedRegionWorldMetrics>) {
        append("<h3>已观测 region 世界汇总</h3>")
        append("<table><tr><th>世界</th><th>region</th><th>玩家</th><th>样本</th><th>TPS(avg/p95/p99)</th><th>MSPT(avg/p95/p99)</th></tr>")
        worlds.forEach { world ->
            append("<tr><td>").append(esc(world.worldName)).append("</td><td>")
                .append(world.activeRegions).append("</td><td>").append(world.playerCount).append("</td><td>")
                .append(world.sampleCount).append("</td><td>").append(tickStats(world.tpsAvg, world.tpsP95, world.tpsP99))
                .append("</td><td>").append(tickStats(world.msptAvg, world.msptP95, world.msptP99, "ms")).append("</td></tr>")
        }
        append("</table>")
    }

    /** 追加每个真实 region 的滚动窗口明细。 */
    private fun StringBuilder.appendObservedRegionDetails(regions: List<ObservedRegionMetrics>) {
        append("<h3>已观测 region 明细</h3>")
        append(
            "<table><tr><th>世界</th><th>真实 region id</th><th>序号</th><th>中心区块</th><th>玩家</th><th>样本</th>" +
                "<th>TPS(avg/p95/p99)</th><th>MSPT(avg/p95/p99)</th><th>最后观测</th></tr>",
        )
        regions.forEach { region ->
            append("<tr><td>").append(esc(region.worldName)).append("</td><td>").append(region.foliaRegionId)
                .append("</td><td>").append(region.regionSequence)
                .append("</td><td>").append(region.centerChunkX).append(", ").append(region.centerChunkZ)
                .append("</td><td>").append(region.playerCount).append("</td><td>").append(region.sampleCount)
                .append("</td><td>").append(tickStats(region.tpsAvg, region.tpsP95, region.tpsP99))
                .append("</td><td>").append(tickStats(region.msptAvg, region.msptP95, region.msptP99, "ms"))
                .append("</td><td>").append(esc(fmtTime(region.lastSeenMs))).append("</td></tr>")
        }
        append("</table>")
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

    /** 渲染已鉴权网络取证查询结果，完整 IP 与白名单载荷仅在此页面出现。 */
    fun renderNetworkForensics(
        packetPage: NetworkPacketPage,
        status: NetworkForensicsStatus,
        query: NetworkForensicsWebQuery,
    ): String = page("网络包取证", buildString {
        append("<h2>网络包取证</h2>")
        appendForensicsStatus(status)
        appendNetworkQueryForm(query)
        appendNetworkPacketRows(packetPage)
        appendNextPage(packetPage, query)
    })

    /** 首次打开只展示查询表单，不读取数据库，避免在未给时间范围时产生全表查询。 */
    fun renderNetworkForensicsGuide(): String = page("网络包取证", buildString {
        append("<h2>网络包取证</h2><p class=\"muted\">请先填写开始和结束时间，再查询完整取证记录。</p>")
        appendNetworkQueryForm(null)
    })

    /** 显示 SQLite 可用性与有界队列丢弃数，不暴露运行期敏感配置。 */
    private fun StringBuilder.appendForensicsStatus(status: NetworkForensicsStatus) {
        if (status.available) {
            append("<p>取证存储：<b>可用</b> · 队列丢弃：<b>").append(status.droppedRecords).append("</b></p>")
        } else {
            append("<p class=\"muted\">取证存储不可用：").append(esc(status.unavailableReason ?: "未知原因")).append("</p>")
        }
    }

    /** 提供所有允许过滤器；开始和结束时间由 required 约束强制填写。 */
    private fun StringBuilder.appendNetworkQueryForm(query: NetworkForensicsWebQuery?) {
        append("<form method=\"get\" action=\"/network-forensics\">")
        appendInput("sinceMs", query?.sinceMs?.toString().orEmpty(), "开始时间", true)
        appendInput("untilMs", query?.untilMs?.toString().orEmpty(), "结束时间", true)
        appendInput("direction", query?.direction?.name.orEmpty(), "方向")
        appendInput("packetType", query?.packetType.orEmpty(), "包类型")
        appendInput("playerUuid", query?.playerUuid.orEmpty(), "玩家 UUID")
        appendInput("playerName", query?.playerName.orEmpty(), "玩家名称")
        appendInput("ip", query?.ip.orEmpty(), "完整 IP")
        appendInput("limit", query?.limit?.toString() ?: "100", "每页条数", true)
        append("<button type=\"submit\">查询</button></form>")
    }

    /** 输出单个安全转义的查询输入框。 */
    private fun StringBuilder.appendInput(name: String, value: String, label: String, required: Boolean = false) {
        append("<label>").append(esc(label)).append(" <input name=\"").append(name).append("\" value=\"")
            .append(esc(value)).append("\"")
        if (required) append(" required")
        append("></label> ")
    }

    /** 输出完整取证记录，载荷字段只展示 SQLite 已保存的白名单内容。 */
    private fun StringBuilder.appendNetworkPacketRows(packetPage: NetworkPacketPage) {
        append(
            "<table><tr><th>时间</th><th>方向</th><th>玩家</th><th>完整 IP</th><th>包类型</th><th>通道</th><th>长度</th>" +
                "<th>SHA-256</th><th>白名单载荷(Base64)</th></tr>",
        )
        if (packetPage.records.isEmpty()) append("<tr><td colspan=\"9\" class=\"muted\">该时间范围内没有记录。</td></tr>")
        packetPage.records.forEach { record ->
            append("<tr><td>").append(esc(fmtTime(record.capturedAtMs))).append("</td><td>")
                .append(esc(record.direction.name)).append("</td><td>").append(esc(record.playerName ?: record.playerUuid ?: "-"))
                .append("</td><td>").append(esc(record.ip ?: "-")).append("</td><td>").append(esc(record.packetType))
                .append("</td><td>").append(esc(record.channel ?: "-")).append("</td><td>").append(record.originalLength)
                .append("</td><td class=\"payload\">").append(esc(record.payloadSha256)).append("</td><td class=\"payload\">")
                .append(esc(record.payloadBase64 ?: "未捕获")).append("</td></tr>")
        }
        append("</table>")
    }

    /** 存在下一页时保留过滤器并只替换完整游标。 */
    private fun StringBuilder.appendNextPage(packetPage: NetworkPacketPage, query: NetworkForensicsWebQuery) {
        if (!packetPage.hasNextPage) return
        val time = packetPage.nextCursorCapturedAtMs ?: return
        val id = packetPage.nextCursorId ?: return
        append("<p><a href=\"/network-forensics?").append(esc(query.nextPageQuery(time, id))).append("\">下一页</a></p>")
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
        form{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0} label{font-size:.9em} input{max-width:160px}
        .payload{max-width:300px;word-break:break-all;font-family:ui-monospace,monospace}
        </style>
        </head>
        <body>
        <header>
          <span class="brand">ServerProbe</span>
          <a href="/">总览</a>
          <a href="/startup">启动画像</a>
          <a href="/history">历史趋势</a>
          <a href="/network-forensics">网络包取证</a>
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

    /** 三个统计分位格式化。 */
    private fun tickStats(avg: Double?, p95: Double?, p99: Double?, suffix: String = ""): String =
        listOf(avg, p95, p99).joinToString(" / ") { value -> value?.let { "%.1f%s".format(it, suffix) } ?: "N/A" }

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
