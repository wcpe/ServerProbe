package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.store.InstanceId
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.io.EOFException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 插件桥反向 WS 客户端(FR-065,JianManager ADR-016 的探针侧载体)。
 *
 * 探针启用后主动以实例级 token 反向连入**本机 Worker** 的 `/ws/plugin-bridge`,建立实时双向通道。
 * 上行:连上 → 发 hello → 周期 ping 心跳 → 实时玩家事件(FR-066,经 [emitPlayerEvent])→ 断线指数退避重连。
 * 下行:读循环收 Worker 下发的 `command` 帧(FR-067 治理:踢/封/解封/白名单/在线列表),经
 * [BridgeCommandRegistry] 交平台执行器执行,并回 `command_result`(带回同一 requestId 供 Worker 匹配同步等待者)。
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
     * 治理指令处理器注册中心(FR-067):读循环收到 `command` 帧时经其取平台执行器执行。
     * 平台模块(bukkit/bungee)自注册执行器;未注册(独立使用探针)时回 success=false 优雅降级。
     */
    @Inject
    lateinit var commandRegistry: BridgeCommandRegistry

    /**
     * 业务对接装配 / 派发中心(JBIS,见 ADR-0015):读循环收到带 domain 的业务 `command` 帧时,
     * 经其路由到对应业务域 Provider 执行(事故域隔离);未注册任何 Provider 时业务命令降级为该域不可用。
     */
    @Inject
    lateinit var businessHost: BusinessHost

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
    // 连接/握手/读全过程必须广捕兜底:任何异常都只降级重连、绝不让后台线程挂掉(桥的韧性设计),
    // 故此处 catch(Exception) 是有意为之。
    @Suppress("TooGenericExceptionCaught")
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
            // 指数退避后重连(静默休眠,被打断即返回)
            runCatching { Thread.sleep(backoff) }
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
     * 上报一个 JBIS 业务域事件(FR-122,见 ADR-027/028)。供平台业务监听器(如经济变更监听器)调用。
     *
     * 与 [emitPlayerEvent] 同走 `event` 帧,但额外带顶层 `domain`(业务域命名空间)与 `dedupKey`(去重锚点):
     * Worker 透传两字段,CP 按 `(domain, dedupKey)` 幂等落库(至少一次投递去重)。`fields` 为信封载荷
     * (键名须与 Worker/CP 解析约定一致),空值字段不发。
     *
     * 线程安全且**绝不抛**:未连接(独立使用探针或重连中)静默丢弃——业务事件至少一次投递,断连缺口由
     * 上线 catchup 补发兜底(经济域),漏发不致命;失败仅 WARN 降级。[MinimalWebSocketClient.sendText]
     * 内部写锁串行化,可被任意线程(含 mce 异步事件线程)安全调用。
     *
     * @param domain 业务域(如 economy);空串视为非业务事件,直接丢弃(防误用)。
     * @param dedupKey 去重键(经济域为 ledgerId 字符串);空串仍上报,CP 退化为不去重(由上层保证非空)。
     * @param fields 业务信封结构化字段(currencyId→identifier、zoneId、signedAmount、balanceAfter…)。
     */
    fun emitBusinessEvent(domain: String, dedupKey: String, fields: Map<String, String>) {
        if (domain.isEmpty()) return
        val ws = client ?: return // 未连接:静默丢弃(至少一次投递,断连缺口由 catchup 补发)
        runCatching { ws.sendText(businessEventJson(domain, dedupKey, fields)) }
            .onFailure { ProbeLogger.warn("插件桥上报业务事件失败(domain=$domain),已丢弃:${it.message}") }
    }

    /**
     * 读循环:阻塞读服务端帧(welcome/pong/command),按心跳间隔在读超时后补发 ping。
     *
     * [MinimalWebSocketClient.readMessage] 的 socket soTimeout 即心跳间隔:超时(无下行)时抛
     * SocketTimeoutException,本循环借此节律发 ping;有下行则按帧类型处理。`command` 帧(FR-067)
     * 解析后交平台执行器执行并回 `command_result`;其余帧(welcome/pong)仅记录。
     */
    // 读超时(SocketTimeoutException)是预期的心跳节拍、非错误,捕获后补发 ping 即可,异常对象无需保留,
    // 故有意不记录该异常。
    @Suppress("SwallowedException")
    private fun readLoop(ws: MinimalWebSocketClient) {
        while (running.get()) {
            try {
                val msg = ws.readMessage()
                val frame = runCatching { Json.parse(msg) }.getOrNull()
                if (frame != null && frame.getString("type") == "command") {
                    handleCommand(ws, frame)
                } else {
                    ProbeLogger.debug("插件桥下行:$msg")
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 读超时即心跳节拍:补发一个 ping 维持连接与活性探测
                ws.sendPing()
            }
        }
    }

    /**
     * 处理一帧治理 / 业务指令:解析 → 按 domain 分流执行 → 回 `command_result`(带回 requestId)。
     *
     * domain 空 / core(治理,FR-067)交平台治理执行器 [commandRegistry];domain 非空(业务,JBIS / ADR-0015)
     * 路由到 [businessHost] 对应 Provider(事故域隔离派发)。整体**绝不抛**(读循环不得因单条指令异常中断):
     * 平台未注册执行器、域不可用、执行抛错 / 超时均转为 success=false 的回执。执行在桥读线程串行等待
     * (治理可能切回主线程、业务跑独立线程池,均有界超时),等待期间不处理后续下行——指令为低频管理动作,
     * 可接受;Worker 侧对每条指令有 5s 超时兜底,不会永久阻塞。
     *
     * @param ws 当前连接,用于回写 command_result。
     * @param frame 已解析的 command 帧只读树。
     */
    private fun handleCommand(ws: MinimalWebSocketClient, frame: JsonObject) {
        val requestId = frame.getString("requestId")
        val command = BridgeCommand(
            action = frame.getString("action"),
            target = frame.getString("target"),
            reason = frame.getString("reason"),
            requestId = requestId,
            domain = frame.getString("domain"),
            payload = frame.getString("payloadJson"),
        )
        val result = when {
            // JBIS 元查询:返回各业务 Provider 汇总的能力清单(供 JianManager 动态发现业务能力)。
            isManifestQuery(command) ->
                runCatching { BridgeCommandResult.ok(businessHost.manifest()) }
                    .getOrElse { BridgeCommandResult.fail("业务能力清单获取失败:${it.message}") }
            // 业务命令(JBIS):路由到对应业务域 Provider,事故域隔离派发(超时 / 异常自降级,绝不拖垮读线程)。
            isBusinessDomain(command.domain) ->
                runCatching { businessHost.dispatch(command.domain, command.action, command.payload) }
                    .getOrElse { BridgeCommandResult.fail("业务执行异常:${it.message}") }
            // 治理命令(FR-067):交平台治理执行器;未注册则降级。
            else ->
                runCatching {
                    val handler = commandRegistry.handler
                        ?: return@runCatching BridgeCommandResult.fail("本平台未实现治理执行")
                    handler.handle(command)
                }.getOrElse { BridgeCommandResult.fail("治理执行异常:${it.message}") }
        }

        runCatching { ws.sendText(commandResultJson(requestId, result)) }
            .onFailure { ProbeLogger.warn("插件桥回 command_result 失败(requestId=$requestId):${it.message}") }
    }

    /** JBIS 元查询判定:保留元域 [JBIS_META_DOMAIN] + [ACTION_MANIFEST],返回业务能力清单而非派发到 Provider。 */
    private fun isManifestQuery(command: BridgeCommand): Boolean =
        command.domain == JBIS_META_DOMAIN && command.action == ACTION_MANIFEST

    /** 业务域判定:非空、非内建治理域([BUSINESS_CORE_DOMAIN])、非元域([JBIS_META_DOMAIN])即业务命令,路由到 [BusinessHost]。 */
    private fun isBusinessDomain(domain: String): Boolean =
        domain.isNotEmpty() && domain != BUSINESS_CORE_DOMAIN && domain != JBIS_META_DOMAIN

    /** 解析实例标识:优先用 JianManager 下发的 bridge.instance;为空时回退探针自身实例 ID。 */
    private fun resolveInstance(): String {
        val configured = ProbeConfig.bridgeInstance()
        return if (configured.isNotEmpty()) configured else InstanceId.resolve(ProbeConfig.configuredServerName())
    }

    /** 构造 hello 帧 JSON(经统一 [Json] 适配器,ADR-14)。 */
    private fun helloJson(instance: String): String = Json.encode(
        linkedMapOf(
            "type" to "hello",
            "instance" to instance,
            "platform" to platformName(),
            "version" to PROBE_BRIDGE_VERSION,
        )
    )

    /** 构造业务事件帧 JSON(type=event + 子类型 event)。 */
    private fun eventJson(event: String, instance: String): String = Json.encode(
        linkedMapOf(
            "type" to "event",
            "event" to event,
            "instance" to instance,
            "timestamp" to System.currentTimeMillis(),
        )
    )

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
    private fun playerEventJson(event: String, fields: Map<String, String>): String = Json.encode(
        linkedMapOf(
            "type" to "event",
            "event" to event,
            "instance" to resolveInstance(),
            "timestamp" to System.currentTimeMillis(),
            "data" to fields.filterValues { it.isNotEmpty() }, // 空值不发
        )
    )

    /**
     * 构造 JBIS 业务域事件帧 JSON(FR-122,手拼零依赖)。
     *
     * 形态在 [playerEventJson] 基础上加顶层 `domain` + `dedupKey`(与 Worker `bridgeMessage` 的同名字段对齐):
     * `event` 取业务事件子类型(如 economy_change),业务信封字段统一放嵌套 `data` 内。Worker 透传 domain/dedupKey、
     * 并把整帧原文带给 CP(raw),CP 据此按 (domain,dedupKey) 去重落库并解析 data 为经济镜像。空值字段不写入。
     *
     * @param domain 业务域(economy…)。
     * @param dedupKey 去重键(经济域为 ledgerId 字符串)。
     * @param fields 业务信封结构化字段。
     */
    private fun businessEventJson(domain: String, dedupKey: String, fields: Map<String, String>): String = Json.encode(
        linkedMapOf(
            "type" to "event",
            "event" to "${domain}_change",
            "instance" to resolveInstance(),
            "timestamp" to System.currentTimeMillis(),
            "domain" to domain,
            "dedupKey" to dedupKey,
            "data" to fields.filterValues { it.isNotEmpty() }, // 空值不发
        )
    )

    /**
     * 构造治理指令回执帧 JSON(FR-067,手拼零依赖)。
     *
     * 形态与 Worker 侧 [bridgeMessage] / [commandResultData] 约定一致:顶层 `type=event` +
     * `event=command_result`,结构化字段放在嵌套 `data` 对象内(requestId/success/output/error)。
     * success 为布尔字面量;output/error 为字符串(空值仍写入,便于 Worker 明确取到字段)。
     * requestId 须与下发指令一致,Worker 据此把回执路由给同步等待的调用方。
     *
     * @param requestId 关联回执标识(来自下发的 command 帧)。
     * @param result 平台执行结果。
     */
    private fun commandResultJson(requestId: String, result: BridgeCommandResult): String = Json.encode(
        linkedMapOf(
            "type" to "event",
            "event" to "command_result",
            "instance" to resolveInstance(),
            "timestamp" to System.currentTimeMillis(),
            "data" to linkedMapOf(
                "requestId" to requestId,
                "success" to result.success,
                "output" to result.output,
                "error" to result.error,
            ),
        )
    )

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

    private companion object {

        /** 内建治理域(JBIS,见 ADR-0015):domain 空或为此值即治理命令,走既有平台治理执行器;其余为业务命令。 */
        private const val BUSINESS_CORE_DOMAIN = "core"

        /** JBIS 元域:保留域,配 [ACTION_MANIFEST] 动作返回业务能力清单,不派发到任何业务 Provider。 */
        private const val JBIS_META_DOMAIN = "jbis"

        /** 业务能力清单查询动作(配 [JBIS_META_DOMAIN])。 */
        private const val ACTION_MANIFEST = "manifest"

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
