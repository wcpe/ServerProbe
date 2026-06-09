package top.wcpe.mc.plugin.serverprobe.command

import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.module.lang.asLangText
import taboolib.module.lang.sendLang
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample
import top.wcpe.mc.plugin.serverprobe.api.model.WorldMetrics
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.taboolib.ioc.annotation.Inject

/**
 * `/probe` 运维探针命令(FR4.1,M1 用户可见核心)。
 *
 * 提供六个只读子命令(health/startup/tps/gc/world/proxy),把探针采集到的指标与启动画像
 * 以中文/英文(i18n)文本呈现给运维。所有用户可见文案均经 [sendLang]/[asLangText] 走语言文件,
 * 逻辑层不散落硬编码文案(规范第 6 条);数值格式化统一委托 [ProbeFormat]。
 *
 * **取值来源**:经 [ProbeReadApi](FR8.1 只读开放接口)读取内存中的最新快照 / 近期历史 / 最近启动画像。
 * 命令层仅做"取值 + 格式化 + 发送",**无业务逻辑、无阻塞 IO**——[ProbeReadApi] 读的是内存快照,轻量;
 * 故不另起异步任务(规范:命令禁止主线程阻塞,此处不阻塞,见 Card 03)。
 *
 * **依赖获取方式(FR8.1 容器内取用的依据)**:本类是 Kotlin `object`,**不**标注 `@Service`
 * (Kotlin `object` 构造私有,不可作为受管 bean——与主类 `ServerProbe` 同理)。
 * 其 [readApi] 字段标注 `top.wcpe.taboolib.ioc` 的 [Inject]:IOC 的 `ObjectInjector` 会在 `ENABLE`
 * 阶段扫描 jar 内所有含 `@Inject` 字段的 `object`,按类型从容器解析并注入到 `object` 的 `INSTANCE` 单例;
 * 而 TabooLib 命令扫描器(`SimpleCommandRegister`)注册命令时取的恰是同一 `INSTANCE`
 * (`ClassVisitor.findInstance` 对 `object` 返回其单例),故运行期执行子命令时 [readApi] 已注入就绪。
 * 接口 [ProbeReadApi] 经 `BeanResolver` 按类型解析到 `@Service` 实现 `ProbeReadApiImpl`。
 *
 * **权限**:主命令 `serverprobe.command`;各子命令 `serverprobe.command.<sub>`。
 * `permissionMessage` 为纯文本(高版本 Paper 的 Adventure 链路下避免 legacy 色码告警)。
 *
 * **进度说明**:proxy 子命令已于 P9 接入代理端真实数据(总在线 + 各子服在线);
 * world 子命令已于 M2 接入世界/实体采集(各世界区块/实体/方块实体数,见其子命令 KDoc)。
 */
@CommandHeader(
    name = "probe",
    permission = "serverprobe.command",
    permissionMessage = "你没有权限使用该命令"
)
object ProbeCommand {

    /**
     * 只读开放接口(FR8.1),由 IOC `ObjectInjector` 在 `ENABLE` 阶段按类型注入(见类 KDoc)。
     *
     * 声明为 `lateinit`:注入发生在容器初始化之后、命令实际执行之前,执行期访问安全。
     */
    @Inject
    lateinit var readApi: ProbeReadApi

    /**
     * 主命令 / 帮助:列出全部子命令(全程 i18n)。
     *
     * 不使用 `createHelper()`(其 `§cUsage:` 前缀为内置英文,无法走语言文件),改为逐行 [sendLang]
     * 输出本地化帮助,保证中英一致呈现。
     */
    @CommandBody
    val main = mainCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            sender.sendLang("command-help-header")
            sender.sendLang("command-help-health")
            sender.sendLang("command-help-startup")
            sender.sendLang("command-help-tps")
            sender.sendLang("command-help-gc")
            sender.sendLang("command-help-world")
            sender.sendLang("command-help-proxy")
        }
    }

    /**
     * `/probe health`:总体概览(TPS/MSPT/堆已用·最大/在线人数/运行时长)。
     *
     * 取 [ProbeReadApi.latestSnapshot];尚无采样时提示"采集中"。服务器维度字段(TPS/在线/运行时长)
     * 在代理端快照为 null,以 N/A / 占位呈现。
     */
    @CommandBody(permission = "serverprobe.command.health")
    val health = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            val snapshot = readApi.latestSnapshot()
            if (snapshot == null) {
                sender.sendLang("command-no-data")
                return@execute
            }
            sendHealth(sender, snapshot)
        }
    }

    /**
     * `/probe startup`:最近一次启动画像(验收核心,FR1)。
     *
     * 取 [ProbeReadApi.lastStartupProfile]:输出端到端总时长、慢插件 Top-N(条数取 [ProbeConfig.startupTopN])、
     * 各世界耗时;无画像时提示"尚无启动画像"。
     *
     * 注:与"上次启动"的 Δ 对比由启动监听器在就绪时算出并存入内存,经 [ProbeReadApi.lastStartupComparisonSummary]
     * 在命令末尾呈现;首次启动(无上一份)时给出"无基线"提示。
     */
    @CommandBody(permission = "serverprobe.command.startup")
    val startup = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            val profile = readApi.lastStartupProfile()
            if (profile == null) {
                sender.sendLang("command-startup-none")
                return@execute
            }
            sendStartup(sender, profile)
        }
    }

    /**
     * `/probe tps`:TPS(1/5/15 分钟)与 MSPT(avg/p95/p99)。
     *
     * 取 `latestSnapshot()?.server?.tick`:字段为 null(Folia 无全局 TPS 或不可用)时显示 N/A;
     * server 为 null(代理端语义)时提示该端无此指标。
     */
    @CommandBody(permission = "serverprobe.command.tps")
    val tps = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            val snapshot = readApi.latestSnapshot()
            if (snapshot == null) {
                sender.sendLang("command-no-data")
                return@execute
            }
            val server = snapshot.server
            if (server == null) {
                sender.sendLang("command-server-only")
                return@execute
            }
            sendTps(sender, server.tick)
            // 聚合补充行(FR3.3):对近 N 份快照做跨快照统计,窗口大小取配置
            sendTpsAggregation(sender)
        }
    }

    /**
     * `/probe gc`:GC(young/old 的 count/timeMs)与堆/非堆/关键内存池。
     *
     * 取 `latestSnapshot()?.jvm`;尚无采样时提示"采集中"。JVM 指标全平台通用,代理端同样可用。
     */
    @CommandBody(permission = "serverprobe.command.gc")
    val gc = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            val snapshot = readApi.latestSnapshot()
            if (snapshot == null) {
                sender.sendLang("command-no-data")
                return@execute
            }
            sendGc(sender, snapshot.jvm)
        }
    }

    /**
     * `/probe world`:各世界指标(已加载区块数、实体数、方块实体数;FR2.3)。
     *
     * 取 `latestSnapshot()?.server?.worlds`:server 为 null(代理端语义)时提示该端无此指标;
     * worlds 为 null/空(世界采样尚未产出,采样周期独立于主采集)时提示"采集中"。
     * Folia 受限项(实体/方块实体数为 -1)以 N/A 文案兜底。
     */
    @CommandBody(permission = "serverprobe.command.world")
    val world = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            val snapshot = readApi.latestSnapshot()
            if (snapshot == null) {
                sender.sendLang("command-no-data")
                return@execute
            }
            val server = snapshot.server
            if (server == null) {
                sender.sendLang("command-server-only")
                return@execute
            }
            sendWorld(sender, server)
        }
    }

    /**
     * `/probe proxy`:代理端总在线与各子服在线(M1,A 方案)。
     *
     * 取 `latestSnapshot()?.proxy`:在代理端(BungeeCord)呈现总在线 + 各子服 `name: online`;
     * 在服务端 `proxy` 为 null,提示"此为服务端,代理端请在 BungeeCord 执行 /probe proxy";
     * 尚无采样时提示"采集中"。子服 ping/可达性、玩家路由留 M2。
     */
    @CommandBody(permission = "serverprobe.command.proxy")
    val proxy = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            val snapshot = readApi.latestSnapshot()
            if (snapshot == null) {
                sender.sendLang("command-no-data")
                return@execute
            }
            val proxy = snapshot.proxy
            if (proxy == null) {
                sender.sendLang("command-proxy-server-side")
                return@execute
            }
            sendProxy(sender, proxy)
        }
    }

    /**
     * 渲染 health 概览。
     *
     * server 维度(TPS/在线/运行时长)在代理端为 null,统一以 N/A 文案兜底,保证一条命令在任意端都有可读输出。
     *
     * @param sender 命令发送者。
     * @param snapshot 最新指标快照。
     */
    private fun sendHealth(sender: ProxyCommandSender, snapshot: MetricSnapshot) {
        val na = sender.asLangText("command-na")
        val server = snapshot.server
        val tps1m = server?.let { ProbeFormat.tpsOrNull(it.tick.tps1m) } ?: na
        val msptAvg = server?.let { ProbeFormat.msptOrNull(it.tick.msptAvg) } ?: na
        val players = server?.let { "${it.onlinePlayers}/${it.maxPlayers}" } ?: na
        val uptime = server?.let { ProbeFormat.duration(it.uptimeMs) } ?: na
        sender.sendLang("command-health-title")
        sender.sendLang("command-health-tps", tps1m, msptAvg)
        sender.sendLang(
            "command-health-heap",
            ProbeFormat.bytes(snapshot.jvm.heapUsedBytes),
            heapMaxText(sender, snapshot.jvm.heapMaxBytes)
        )
        // CPU 占用率:processCpuLoad/systemCpuLoad 为 -1.0(JDK 不提供)时 percentOrNull 返回 null,以 N/A 兜底
        sender.sendLang(
            "command-health-cpu",
            ProbeFormat.percentOrNull(snapshot.jvm.processCpuLoad) ?: na,
            ProbeFormat.percentOrNull(snapshot.jvm.systemCpuLoad) ?: na
        )
        sender.sendLang("command-health-players", players)
        sender.sendLang("command-health-uptime", uptime)
    }

    /**
     * 渲染 startup 启动画像。
     *
     * 基础段(总时长 / 慢插件 / 世界耗时 / 对比)对全部画像呈现;**agent 增强段**(A3)按
     * [StartupProfile.agentAttached] 分支:挂载时追加库下载 Top-N、主线程热点 Top-N 与 agent 精确逐插件
     * load/enable Top-N;未挂载时给出一行启用提示([sendAgentSection])。
     *
     * @param sender 命令发送者。
     * @param profile 最近一次启动画像。
     */
    private fun sendStartup(sender: ProxyCommandSender, profile: StartupProfile) {
        sender.sendLang("command-startup-title")
        sender.sendLang("command-startup-total", ProbeFormat.seconds(profile.totalMs))

        val topN = ProbeConfig.startupTopN()
        val slowPlugins = profile.pluginTimings.sortedByDescending { it.enableMs }.take(topN)
        sender.sendLang("command-startup-plugins-title", topN)
        if (slowPlugins.isEmpty()) {
            sender.sendLang("command-startup-plugins-empty")
        } else {
            slowPlugins.forEach { timing ->
                sender.sendLang("command-startup-plugin-line", timing.name, ProbeFormat.seconds(timing.enableMs))
            }
        }

        sender.sendLang("command-startup-worlds-title")
        if (profile.worldTimings.isEmpty()) {
            sender.sendLang("command-startup-worlds-empty")
        } else {
            profile.worldTimings.forEach { timing ->
                sender.sendLang("command-startup-world-line", timing.name, ProbeFormat.seconds(timing.loadMs))
            }
        }

        // agent 增强段(A3):挂载时展示库下载 / 主线程热点 / agent 精确逐插件耗时;未挂载时一行启用提示
        sendAgentSection(sender, profile, topN)

        // 与上次启动的 Δ 对比:启动时已由监听器算出并存入内存,此处直接取用;无上一份(首次启动)时给出提示。
        // 注:对比摘要内容由 StartupComparator 生成,目前为中文;M1 作为 {0} 原样填入,其内容 i18n 留 M2。
        val comparison = readApi.lastStartupComparisonSummary()
        if (comparison != null) {
            sender.sendLang("command-startup-compare", comparison)
        } else {
            sender.sendLang("command-startup-compare-none")
        }
    }

    /**
     * 渲染 startup 的 agent 增强段(A3)。
     *
     * **未挂载**([StartupProfile.agentAttached] 为 false):仅输出一行提示,引导运维在启动命令加
     * `-javaagent:plugins/ServerProbe.jar` 以获取库下载 / 主线程热点等更早期数据。
     *
     * **已挂载**:依次输出——
     * - 库下载 Top-N(按耗时降序;来自 [StartupProfile.libraryTimings]);
     * - 主线程热点 Top-N(按命中降序,agent 已排序;来自 [StartupProfile.mainThreadHotspots]);
     * - agent 精确逐插件 enable Top-N(按 onEnable 耗时降序;来自 [StartupProfile.agentPluginEnableTimings],
     *   精度高于日志解析的慢插件榜)。
     *
     * 各列表为 null/空时以对应"空"文案兜底。
     *
     * @param sender 命令发送者。
     * @param profile 最近一次启动画像。
     * @param topN 展示条数(取 [ProbeConfig.startupTopN])。
     */
    private fun sendAgentSection(sender: ProxyCommandSender, profile: StartupProfile, topN: Int) {
        if (!profile.agentAttached) {
            sender.sendLang("command-startup-agent-absent")
            return
        }

        sender.sendLang("command-startup-libs-title", topN)
        val libraries = profile.libraryTimings.orEmpty().sortedByDescending { it.loadMs }.take(topN)
        if (libraries.isEmpty()) {
            sender.sendLang("command-startup-libs-empty")
        } else {
            libraries.forEach { timing ->
                sender.sendLang("command-startup-lib-line", timing.name, ProbeFormat.seconds(timing.loadMs))
            }
        }

        sender.sendLang("command-startup-hotspots-title", topN)
        val hotspots = profile.mainThreadHotspots.orEmpty().take(topN)
        if (hotspots.isEmpty()) {
            sender.sendLang("command-startup-hotspots-empty")
        } else {
            hotspots.forEach { hotspot ->
                sender.sendLang("command-startup-hotspot-line", hotspot.frame, hotspot.sampleCount)
            }
        }

        sender.sendLang("command-startup-agent-plugins-title", topN)
        val agentPlugins = profile.agentPluginEnableTimings.orEmpty().sortedByDescending { it.enableMs }.take(topN)
        if (agentPlugins.isEmpty()) {
            sender.sendLang("command-startup-agent-plugins-empty")
        } else {
            agentPlugins.forEach { timing ->
                sender.sendLang("command-startup-agent-plugin-line", timing.name, ProbeFormat.seconds(timing.enableMs))
            }
        }
    }

    /**
     * 渲染 tps 详情。可空字段为 null 时显示 N/A(Folia 无全局值或不可用)。
     *
     * @param sender 命令发送者。
     * @param tick tick 采样数据。
     */
    private fun sendTps(sender: ProxyCommandSender, tick: TickSample) {
        val na = sender.asLangText("command-na")
        sender.sendLang("command-tps-title")
        sender.sendLang(
            "command-tps-line",
            ProbeFormat.tpsOrNull(tick.tps1m) ?: na,
            ProbeFormat.tpsOrNull(tick.tps5m) ?: na,
            ProbeFormat.tpsOrNull(tick.tps15m) ?: na
        )
        sender.sendLang(
            "command-mspt-line",
            ProbeFormat.msptOrNull(tick.msptAvg) ?: na,
            ProbeFormat.msptOrNull(tick.msptP95) ?: na,
            ProbeFormat.msptOrNull(tick.msptP99) ?: na
        )
    }

    /**
     * 渲染 tps 的聚合补充行(FR3.3):对近 N 份快照统计 TPS 均值 / MSPT p95·p99 / GC young·old 速率。
     *
     * 取 [ProbeReadApi.aggregated],窗口大小取 [ProbeConfig.aggregationWindow]。优雅降级:
     * 窗口内无任何快照(`windowSampleCount == 0`,如刚启动)时仅提示"暂无聚合数据";有快照但
     * 单项不可计算(无 TPS 样本、GC 速率差分样本不足等)时该项以 N/A 兜底。
     *
     * @param sender 命令发送者。
     */
    private fun sendTpsAggregation(sender: ProxyCommandSender) {
        val aggregated = readApi.aggregated(ProbeConfig.aggregationWindow())
        sender.sendLang("command-tps-agg-title")
        if (aggregated.windowSampleCount == 0) {
            sender.sendLang("command-tps-agg-empty")
            return
        }
        val na = sender.asLangText("command-na")
        sender.sendLang("command-tps-agg-window", aggregated.windowSampleCount)
        sender.sendLang(
            "command-tps-agg-tps",
            ProbeFormat.tpsOrNull(aggregated.tpsAvg) ?: na,
            ProbeFormat.msptOrNull(aggregated.msptP95) ?: na,
            ProbeFormat.msptOrNull(aggregated.msptP99) ?: na
        )
        sender.sendLang(
            "command-tps-agg-gc",
            gcRateText(aggregated.gcYoungRatePerSec, na),
            gcRateText(aggregated.gcOldRatePerSec, na)
        )
    }

    /**
     * GC 速率文案:可计算时格式化为保留两位小数的"次/秒",不可计算(null,如样本不足/重启回绕)时为 N/A。
     *
     * @param rate GC 次数速率(次/秒);不可计算时为 null。
     * @param na N/A 占位文案(由调用方经 i18n 取得,避免重复取值)。
     * @return 可读的速率文案。
     */
    private fun gcRateText(rate: Double?, na: String): String =
        rate?.let { String.format("%.2f", it) } ?: na

    /**
     * 渲染 world 详情:逐世界输出已加载区块数、实体数、方块实体数(FR2.3)。
     *
     * worlds 为 null(尚未采样,采样周期独立于主采集)或空时提示"采集中";Folia 受限项(实体/
     * 方块实体数为 -1)以 N/A 文案兜底。
     *
     * @param sender 命令发送者。
     * @param server 服务器指标(其 worlds 字段为世界明细)。
     */
    private fun sendWorld(sender: ProxyCommandSender, server: ServerMetrics) {
        val worlds = server.worlds
        if (worlds.isNullOrEmpty()) {
            sender.sendLang("command-no-data")
            return
        }
        sender.sendLang("command-world-title")
        worlds.forEach { world ->
            sender.sendLang(
                "command-world-line",
                world.name,
                world.loadedChunks,
                worldCountText(sender, world.entityCount),
                worldCountText(sender, world.tileEntityCount)
            )
        }
    }

    /**
     * 世界计数文案:-1(Folia 受限,N/A)时显示 N/A 文案,否则为原始数值字符串。
     *
     * @param sender 命令发送者(用于取 i18n 文案)。
     * @param count 计数值;-1 表示该项不可用(N/A)。
     * @return 可读的计数文案。
     */
    private fun worldCountText(sender: ProxyCommandSender, count: Int): String =
        if (count < 0) sender.asLangText("command-na") else count.toString()

    /**
     * 渲染 gc 详情:GC young/old 聚合 + 堆/非堆 + 各内存池。
     *
     * @param sender 命令发送者。
     * @param jvm JVM 指标。
     */
    private fun sendGc(sender: ProxyCommandSender, jvm: JvmMetrics) {
        sender.sendLang("command-gc-title")
        sender.sendLang("command-gc-young", jvm.gcYoungCount, ProbeFormat.millis(jvm.gcYoungTimeMs))
        sender.sendLang("command-gc-old", jvm.gcOldCount, ProbeFormat.millis(jvm.gcOldTimeMs))
        sender.sendLang(
            "command-gc-heap",
            ProbeFormat.bytes(jvm.heapUsedBytes),
            heapMaxText(sender, jvm.heapMaxBytes)
        )
        sender.sendLang(
            "command-gc-nonheap",
            ProbeFormat.bytes(jvm.nonHeapUsedBytes),
            heapMaxText(sender, jvm.nonHeapMaxBytes)
        )
        sender.sendLang("command-gc-pools-title")
        jvm.memoryPools.forEach { pool ->
            sender.sendLang(
                "command-gc-pool-line",
                pool.name,
                ProbeFormat.bytes(pool.usedBytes),
                heapMaxText(sender, pool.maxBytes)
            )
        }
    }

    /**
     * 渲染 proxy 详情:代理总在线 + 各子服在线明细(M1,A 方案)。
     *
     * @param sender 命令发送者。
     * @param proxy 代理端指标。
     */
    private fun sendProxy(sender: ProxyCommandSender, proxy: ProxyMetrics) {
        sender.sendLang("command-proxy-title")
        sender.sendLang("command-proxy-total", proxy.totalOnline)
        sender.sendLang("command-proxy-backends-title")
        if (proxy.backends.isEmpty()) {
            sender.sendLang("command-proxy-backends-empty")
        } else {
            proxy.backends.forEach { backend ->
                sender.sendLang("command-proxy-backend-line", backend.name, backend.online)
            }
        }
    }

    /**
     * 内存"最大值"文案:-1(JVM 约定的"无上限")时显示无上限文案,否则为可读字节数。
     *
     * @param sender 命令发送者(用于取 i18n 文案)。
     * @param maxBytes 最大字节数;-1 表示无上限。
     * @return 可读的最大值文案。
     */
    private fun heapMaxText(sender: ProxyCommandSender, maxBytes: Long): String =
        if (maxBytes < 0) sender.asLangText("command-unlimited") else ProbeFormat.bytes(maxBytes)
}
