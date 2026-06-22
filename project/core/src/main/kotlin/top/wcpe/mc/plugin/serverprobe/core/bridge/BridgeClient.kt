package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.store.InstanceId
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.io.EOFException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 插件桥反向 WS 客户端(FR-065,JianManager ADR-016 的探针侧载体)。
 *
 * 探针启用后主动以实例级 token 反向连入**本机 Worker** 的 `/ws/plugin-bridge`,建立实时双向通道,
 * 为后续玩家事件(FR-066)/治理(FR-067)/在线更新(FR-068)铺底。**本地基阶段只打通**:
 * 连上 → 发 hello → 周期 ping 心跳 → 发一个 demo `connected` 事件 → 断线指数退避重连;
 * 玩家事件/治理执行留后续 FR。
 *
 * ## 零第三方依赖 / JDK 8
 * 探针锁 Java 8 且约定不引第三方 HTTP/JSON 库(见 PrometheusExporter / WebhookAlertChannel)。
 * 故用 [MinimalWebSocketClient](裸 Socket 手写 RFC 6455)+ 手拼极简 JSON,不引任何库。
 *
 * ## 线程模型:独立后台线程,绝不阻塞服务器
 * 连接/读循环/重连跑在本类自起的 **daemon 线程**(非主线程、非 TabooLib 调度线程),
 * 网络阻塞不影响宿主;心跳由读线程按节奏在循环内发出。整体 try/catch:任何异常仅 WARN 降级、
 * 绝不抛出影响插件(探针不成事故源)。
 *
 * ## 安全
 * token 仅用于握手 query 参数,**绝不写入日志**;日志只记 URL host/port 与实例标识。
 *
 * 生命周期:作为 IOC [Service],[start] 于 `@PostEnable` 起后台线程、[stop] 于 `@PreDestroy` 停。
 */
@Service
class BridgeClient {

    /** 运行标志:start 置 true,stop 置 false;读循环与重连据此决定是否继续。 */
    private val running = AtomicBoolean(false)

    /** 后台连接线程;未启动时为 null。 */
    @Volatile
    private var thread: Thread? = null

    /** 当前活动连接;用于 stop 时主动断开以打断读阻塞,以及心跳/事件发送。 */
    @Volatile
    private var client: MinimalWebSocketClient? = null

    /**
     * 启动插件桥客户端。
     *
     * 开关关闭或 url/token 未配置则跳过(独立使用探针时不连);否则起一个 daemon 线程跑连接-读-重连循环。
     * 整体不抛:配置异常/线程起不来仅 WARN 降级。
     */
    @PostEnable
    fun start() {
        if (!ProbeConfig.bridgeEnabled()) {
            ProbeLogger.info("插件桥未开启(bridge.enabled=false),已跳过反向 WS 连接")
            return
        }
        val url = ProbeConfig.bridgeUrl()
        val token = ProbeConfig.bridgeToken()
        if (url.isEmpty() || token.isEmpty()) {
            ProbeLogger.warn("插件桥已开启但 bridge.url 或 bridge.token 为空,已跳过(请检查 JianManager 下发的探针配置)")
            return
        }
        if (running.getAndSet(true)) return // 幂等:重复回调不重复起线程

        val t = Thread({ runLoop(url, token) }, "ServerProbe-Bridge")
        t.isDaemon = true
        thread = t
        t.start()
        // 安全:只记 host,不打 token
        ProbeLogger.info("插件桥反向 WS 客户端已启动,目标 ${safeAuthority(url)}")
    }

    /**
     * 停止插件桥客户端。置停标志、主动断开当前连接以打断读阻塞、join 后台线程(限时)。
     */
    @PreDestroy
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { client?.close() }
        client = null
        thread?.let { t ->
            runCatching { t.join(JOIN_TIMEOUT_MS) }
        }
        thread = null
        ProbeLogger.info("插件桥反向 WS 客户端已停止")
    }

    /**
     * 连接-读-重连主循环(跑在后台线程)。
     *
     * 每轮:建一条连接 → 握手 → 发 hello + demo connected 事件 → 读循环(内部按节奏发 ping 心跳);
     * 连接异常/断开后,若仍 running 则按指数退避(上限 [MAX_BACKOFF_MS])后重连。
     * 成功连上后退避重置为初始值。
     */
    private fun runLoop(url: String, token: String) {
        val instance = resolveInstance()
        var backoff = INITIAL_BACKOFF_MS
        while (running.get()) {
            try {
                val target = URI("$url?token=$token&instance=$instance")
                val ws = MinimalWebSocketClient(target, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                ws.connect()
                client = ws
                backoff = INITIAL_BACKOFF_MS // 连上即重置退避
                ProbeLogger.info("插件桥已连入 Worker(instance=$instance)")

                onConnected(ws, instance)
                readLoop(ws)
            } catch (e: EOFException) {
                ProbeLogger.info("插件桥连接关闭:${e.message}")
            } catch (e: Exception) {
                // 连接/握手/读失败:降级,不抛
                ProbeLogger.warn("插件桥连接异常,将重连:${e.message}")
            } finally {
                runCatching { client?.close() }
                client = null
            }
            if (!running.get()) break
            // 指数退避后重连
            sleepQuietly(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * 连接建立后的初始上行:发 hello(自报平台/版本/实例)+ 一个 `connected` 业务事件。
     */
    private fun onConnected(ws: MinimalWebSocketClient, instance: String) {
        ws.sendText(helloJson(instance))
        ws.sendText(eventJson("connected", instance))
    }

    /**
     * 上报一个玩家/跨服业务事件(FR-066)。供平台监听器(Bukkit join/quit/chat、BC 跨服路由)调用。
     *
     * 线程安全且**绝不抛**:未连接(探针独立使用或正在重连)时静默丢弃该事件——玩家事件是实时增量,
     * 漏一两条不影响整体(连上后由后续事件与名册重置自愈);[MinimalWebSocketClient.sendText] 的写
     * 已由其内部写锁串行化,可被任意线程(含 Bukkit 主线程、BC 事件线程)安全调用。失败仅 WARN 降级。
     *
     * @param eventType 事件子类型:player_join | player_quit | chat | cross_server。
     * @param fields 结构化字段(playerName/playerUuid/message/server/fromServer/toServer 等),空值不发。
     */
    fun emitPlayerEvent(eventType: String, fields: Map<String, String>) {
        val ws = client ?: return // 未连接:静默丢弃(实时事件,漏即弃)
        runCatching { ws.sendText(playerEventJson(eventType, fields)) }
            .onFailure { ProbeLogger.warn("插件桥上报事件失败($eventType),已丢弃:${it.message}") }
    }

    /**
     * 读循环:阻塞读服务端帧(welcome/pong/command),按心跳间隔在读超时后补发 ping。
     *
     * [MinimalWebSocketClient.readMessage] 的 socket soTimeout 即心跳间隔:超时(无下行)时抛
     * SocketTimeoutException,本循环借此节律发 ping;有下行则处理后继续。命令(command)地基阶段仅记录。
     */
    private fun readLoop(ws: MinimalWebSocketClient) {
        while (running.get()) {
            try {
                val msg = ws.readMessage()
                // 地基阶段:仅记录服务端下行(welcome/command 等),不解析执行(留 FR-067)
                ProbeLogger.debug("插件桥下行:$msg")
            } catch (e: java.net.SocketTimeoutException) {
                // 读超时即心跳节拍:补发一个 ping 维持连接与活性探测
                ws.sendPing()
            }
        }
    }

    /** 解析实例标识:优先用 JianManager 下发的 bridge.instance;为空时回退探针自身实例 ID。 */
    private fun resolveInstance(): String {
        val configured = ProbeConfig.bridgeInstance()
        return if (configured.isNotEmpty()) configured else InstanceId.resolve(ProbeConfig.configuredServerName())
    }

    /** 构造 hello 帧 JSON(手拼,零依赖)。 */
    private fun helloJson(instance: String): String = buildString {
        append('{')
        append("\"type\":\"hello\",")
        append("\"instance\":\"").append(escapeJson(instance)).append("\",")
        append("\"platform\":\"").append(escapeJson(platformName())).append("\",")
        append("\"version\":\"").append(escapeJson(PROBE_BRIDGE_VERSION)).append('"')
        append('}')
    }

    /** 构造业务事件帧 JSON(type=event + 子类型 event)。 */
    private fun eventJson(event: String, instance: String): String = buildString {
        append('{')
        append("\"type\":\"event\",")
        append("\"event\":\"").append(escapeJson(event)).append("\",")
        append("\"instance\":\"").append(escapeJson(instance)).append("\",")
        append("\"timestamp\":").append(System.currentTimeMillis())
        append('}')
    }

    /**
     * 构造带结构化载荷的玩家事件帧 JSON(FR-066,手拼零依赖)。
     *
     * 形态与 Worker 侧 [bridgeMessage] 约定一致:顶层 `type=event` + `event` 子类型 + `instance` + `timestamp`,
     * 结构化字段统一放在嵌套 `data` 对象内(Worker 仅解析 `data` 填充 workerpb.PluginEvent)。空值字段不写入,
     * 减小帧体并避免下游误判。
     *
     * @param event 事件子类型。
     * @param fields 结构化字段(键名须与 Worker 解析约定一致:playerName/playerUuid/message/server/fromServer/toServer)。
     */
    private fun playerEventJson(event: String, fields: Map<String, String>): String = buildString {
        append('{')
        append("\"type\":\"event\",")
        append("\"event\":\"").append(escapeJson(event)).append("\",")
        append("\"instance\":\"").append(escapeJson(resolveInstance())).append("\",")
        append("\"timestamp\":").append(System.currentTimeMillis()).append(',')
        append("\"data\":{")
        var first = true
        for ((k, v) in fields) {
            if (v.isEmpty()) continue // 空值不发
            if (!first) append(',')
            append('"').append(escapeJson(k)).append("\":\"").append(escapeJson(v)).append('"')
            first = false
        }
        append('}')
        append('}')
    }

    /**
     * 推断平台名(bukkit/bungee/unknown):探针单 jar 多端,以类是否可加载粗判,
     * 不引平台 API 依赖到 core(core 平台无关)。
     */
    private fun platformName(): String = when {
        canLoad("org.bukkit.Bukkit") -> "bukkit"
        canLoad("net.md_5.bungee.api.ProxyServer") -> "bungee"
        else -> "unknown"
    }

    /** 类是否可加载(用于平台粗判),异常即视为不可加载。 */
    private fun canLoad(name: String): Boolean = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

    /** 从 ws URL 提取仅 host:port 的可记日志权威部分(去掉 query,避免 token 入日志)。 */
    private fun safeAuthority(url: String): String = runCatching {
        val u = URI(url)
        "${u.host}:${if (u.port > 0) u.port else 80}"
    }.getOrDefault("(地址解析失败)")

    /** 转义 JSON 字符串值中的特殊字符,保证产出合法 JSON。 */
    private fun escapeJson(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    /** 静默休眠(被打断即返回)。 */
    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private companion object {

        /** 连接超时(毫秒)。 */
        private const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * 读超时(毫秒),兼作心跳间隔:无下行时每此时长发一次 ping。
         * 须明显小于 Worker 侧读超时(bridgePongWait=90s),确保 Worker 在判定断线前能收到心跳。
         */
        private const val READ_TIMEOUT_MS = 30_000

        /** 重连初始退避(毫秒)。 */
        private const val INITIAL_BACKOFF_MS = 1_000L

        /** 重连退避上限(毫秒)。 */
        private const val MAX_BACKOFF_MS = 30_000L

        /** stop 时等待后台线程退出的上限(毫秒)。 */
        private const val JOIN_TIMEOUT_MS = 3_000L

        /** 探针桥协议版本(随 hello 上报,供 Worker/CP 展示)。 */
        private const val PROBE_BRIDGE_VERSION = "1"
    }
}
