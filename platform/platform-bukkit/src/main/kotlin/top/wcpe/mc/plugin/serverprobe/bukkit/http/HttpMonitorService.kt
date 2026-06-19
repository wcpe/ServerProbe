package top.wcpe.mc.plugin.serverprobe.bukkit.http

import org.bukkit.Bukkit
import org.bukkit.event.server.ServerLoadEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.api.model.HttpCall
import top.wcpe.mc.plugin.serverprobe.core.agent.AgentDataReader
import top.wcpe.mc.plugin.serverprobe.core.agent.HttpCallAttributor
import top.wcpe.mc.plugin.serverprobe.core.agent.HttpCallStore
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * HTTP/TCP 外呼监控服务(M5,运行期常驻;Bukkit 端)。
 *
 * 启动 agent 在 `HttpURLConnection.getInputStream` / `Socket.connect` 处插桩,把外呼记录写入 agent 侧有界缓冲;
 * 本服务在服务器就绪后周期性**增量拉取**这些记录,据调用栈**归因到触发的插件**,然后:
 * - 实时打印中文日志(可配关闭);
 * - 追加落盘到 `data/http/`(可配关闭);
 * - 写入 [HttpCallStore] 供 `/probe http` 回看。
 *
 * **未挂载 agent**:[AgentDataReader.readHttpCallsSince] 返回空,各环节自然空转,无副作用。
 * **形态**:`object` + `@SubscribeEvent`(TabooLib 要求事件宿主为 object);`@Inject` 由 IOC 注入。
 */
@PlatformSide(Platform.BUKKIT)
object HttpMonitorService {

    /** 外呼近期缓冲(core),供 `/probe http` 读取。 */
    @Inject
    lateinit var store: HttpCallStore

    /** 增量拉取游标:已读到的最大序号。 */
    private var lastSeq = 0L

    /** 是否打印控制台日志(就绪时从配置读入)。 */
    private var logToConsole = true

    /** 日志是否含请求头。 */
    private var logHeaders = false

    /** 是否落盘外呼明细。 */
    private var fileEnabled = true

    /** 插件包前缀缓存(归因兜底用):插件数变化时重建。 */
    @Volatile
    private var cachedPrefixes: List<Pair<String, String>> = emptyList()

    /** 插件 ClassLoader 身份哈希 → 插件名缓存(归因首选):插件数变化时重建。 */
    @Volatile
    private var cachedLoaderHashes: Map<Int, String> = emptyMap()

    /** 上次构建缓存时的插件数。 */
    @Volatile
    private var cachedPluginCount = -1

    /**
     * 服务器就绪后启动外呼监控拉取循环(仅 [ServerLoadEvent.LoadType.STARTUP])。
     *
     * 读配置:把开关同步给 agent;按配置容量初始化缓冲;按配置周期起一个异步重复任务增量拉取并呈现。
     * agent 默认即开始记录(覆盖启动期外呼,如 TabooLib 下载),首次拉取会把这段积压一并补出。
     *
     * @param event 服务器加载事件。
     */
    @SubscribeEvent
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type != ServerLoadEvent.LoadType.STARTUP) {
            return
        }
        val enabled = ProbeConfig.httpMonitorEnabled()
        // 把开关同步到 agent:关闭则 agent hook 立即返回不记录
        AgentDataReader.setHttpMonitorEnabled(enabled)
        if (!enabled) {
            return
        }
        logToConsole = ProbeConfig.httpMonitorLogToConsole()
        logHeaders = ProbeConfig.httpMonitorLogHeaders()
        fileEnabled = ProbeConfig.httpMonitorFileEnabled()
        store.configure(ProbeConfig.httpMonitorRecentCapacity())
        val period = ProbeConfig.httpMonitorDrainPeriodTicks()
        // 异步重复任务:读盘/网络无关的轻量拉取 + 日志/落盘,均不在主线程
        submit(period = period, async = true) { drainOnce() }
        ProbeLogger.info("外呼监控已启用(拉取周期 ${period} ticks,控制台日志=$logToConsole,落盘=$fileEnabled)")
    }

    /**
     * 拉取一轮新外呼记录并呈现(归因 + 日志 + 落盘 + 入缓冲)。失败静默降级,绝不影响业务。
     */
    private fun drainOnce() {
        runCatching {
            val (calls, newSeq) = AgentDataReader.readHttpCallsSince(lastSeq)
            if (calls.isEmpty()) {
                return@runCatching
            }
            lastSeq = newSeq
            ensureCache()
            calls.forEach { raw ->
                val plugin = HttpCallAttributor
                    .attribute(raw, cachedLoaderHashes, cachedPrefixes)
                    .ifEmpty { UNKNOWN_PLUGIN }
                val call = raw.copy(plugin = plugin)
                store.add(call)
                if (logToConsole) {
                    ProbeLogger.info(formatLine(call))
                }
                if (fileEnabled) {
                    appendFile(call)
                }
            }
        }.onFailure { ProbeLogger.warn("外呼监控拉取失败,跳过本轮:${it.message}") }
    }

    /**
     * 确保归因缓存为最新:插件数变化时重建"ClassLoader 身份哈希→插件名"映射(首选)与"主类包前缀"列表(兜底)。
     *
     * ClassLoader 身份哈希用 [System.identityHashCode] 取自各插件的 ClassLoader——与 agent 侧对同一对象取值一致,
     * 可跨 ClassLoader 比对。前缀按长度降序,使更具体的前缀优先命中。
     */
    private fun ensureCache() {
        val plugins = Bukkit.getPluginManager().plugins
        if (plugins.size == cachedPluginCount) {
            return
        }
        cachedPrefixes = plugins.mapNotNull { p ->
            val pkg = p.javaClass.name.substringBeforeLast('.', "")
            if (pkg.isEmpty()) null else p.name to pkg
        }.sortedByDescending { it.second.length }
        cachedLoaderHashes = plugins.mapNotNull { p ->
            p.javaClass.classLoader?.let { System.identityHashCode(it) to p.name }
        }.toMap()
        cachedPluginCount = plugins.size
    }

    /**
     * 拼装一行中文外呼日志:`外呼 [插件] 方法 主机 (响应码, 耗时) URL ← 触发处`。
     *
     * @param c 已归因的外呼记录。
     * @return 日志行。
     */
    private fun formatLine(c: HttpCall): String {
        val code = when {
            c.responseCode >= 0 -> c.responseCode.toString()
            c.error -> "ERR"
            else -> "—"
        }
        val caller = c.callerFrames.firstOrNull { isAppFrame(it) } ?: c.callerFrames.firstOrNull() ?: ""
        val sb = StringBuilder("外呼 [${c.plugin}] ${c.method} ${c.host} ($code, ${c.durationMs}ms) ${c.url}")
        if (caller.isNotEmpty()) {
            sb.append(" ← ").append(caller)
        }
        if (logHeaders && c.headers.isNotEmpty()) {
            sb.append(" 头=[").append(c.headers.joinToString("; ")).append(']')
        }
        return sb.toString()
    }

    /** 追加一条外呼明细到 `data/http/http-<日期>.log`(制表符分隔)。失败静默。 */
    private fun appendFile(c: HttpCall) {
        runCatching {
            val dir = File(getDataFolder(), "http")
            dir.mkdirs()
            val now = Date()
            val file = File(dir, "http-${DATE_FMT.format(now)}.log")
            val line = "${TS_FMT.format(now)}\t[${c.plugin}]\t${c.method}\t${c.host}\t${c.responseCode}\t" +
                "${c.durationMs}ms\t${c.url}\t${c.callerFrames.joinToString(" <- ")}\n"
            file.appendText(line)
        }
    }

    /** 是否"应用层"帧(排除 JDK/JVM 帧),用于挑选日志里的触发处。 */
    private fun isAppFrame(frame: String): Boolean =
        !(frame.startsWith("java.") || frame.startsWith("sun.") || frame.startsWith("jdk.") || frame.startsWith("javax."))

    /** 无法归因时的占位插件名。 */
    private const val UNKNOWN_PLUGIN = "未知"

    /** 日志文件名日期格式。 */
    private val DATE_FMT = SimpleDateFormat("yyyyMMdd")

    /** 行时间戳格式。 */
    private val TS_FMT = SimpleDateFormat("HH:mm:ss.SSS")
}
