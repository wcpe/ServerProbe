package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 托管模式 fixture：扮演"**身份文件驱动的控制面**"，在真机服务端上验收托管链路。
 *
 * ## 为什么单列一个场景
 * 既有 `bridge-fixture` 验的是"独立模式的业务链路"（hello / 治理回执 / 业务事件 / 超时恢复）；
 * 托管模式新增的是**配置面**行为——配置下发热应用、状态上报、指标推送，以及"托管下零监听"。
 * 把它们塞进业务链路场景，会让两边的失败原因互相掩盖：一次配置应用失败会表现为"某条命令回执没来"。
 *
 * ## 判定项（逐项写进证据表，由结果文件汇总成 assertions.*）
 * - `helloManaged`：hello 声明 `managed=true` 且能力位含 metric_push / config_push / config_state；
 * - `configApplied`：下发 `config_push`（嵌套 payload 对象形态）后收到 `config_applied(result=applied)`；
 * - `metricPush`：**未下发任何命令**也能周期收到 `metric_snapshot`（证明周期推送自走）；
 * - `configState`：周期收到 `config_state`，且连接态为 `online`；
 * - `metricsNow`：下发 `metrics_now` 后收到同一 requestId 的成功回执；
 * - `zeroBind`：托管模式下 metrics / web 端口均不可连接（模板配置里故意写了 `enabled=true`）。
 *
 * ## 失败必须大声
 * 任何契约漂移都直接记成断言失败（带实际值），绝不静默放过；缺项则由 `awaitEvidence` 超时兜底。
 */
class ManagedConfigFixture private constructor(
    endpoint: FixtureEndpoint,
) : WebSocketFixtureServer(endpoint.serverSocket, endpoint.token, "serverprobe-e2e-managed") {

    /** 已登记的断言：键为「前缀 + 断言名」，值为通过标记或失败详情。 */
    private val assertions = ConcurrentHashMap<String, String>()

    /** 只触发一次的动作（避免重复下发命令把状态机搅乱）。 */
    private val fired = ConcurrentHashMap.newKeySet<String>()

    /** 交卷只允许一次。 */
    private val completed = AtomicBoolean(false)

    /** 配置是否已成功应用（重试线程据此停手）。 */
    @Volatile
    private var configAppliedOk = false

    /** 是否已收到至少一帧指标快照（按需推送需要它）。 */
    @Volatile
    private var metricSeen = false

    /** 最近一次配置回执的实际值（重试耗尽后作为失败详情）。 */
    @Volatile
    private var lastConfigDetail = ""

    override fun handleText(text: String) {
        when (jsonString(text, "type")) {
            "hello" -> onHello(text)
            "event" -> onEvent(text)
            else -> Unit
        }
    }

    /** hello：托管位与能力位是"控制面能不能下发配置"的依据，必须先立住再谈后面。 */
    private fun onHello(text: String) {
        if (!fired.add(STAGE_HELLO)) return
        val managed = jsonBoolean(text, "managed") == true
        val capabilities = jsonStringArray(text, "capabilities")
        val missing = REQUIRED_CAPABILITIES.filterNot { capabilities.contains(it) }
        record(ASSERT_HELLO_MANAGED, managed && missing.isEmpty(), "managed=$managed missing=$missing")
        startZeroBindCheck()
        startConfigPushRetry()
        startMcpProbeRetry()
    }

    private fun onEvent(text: String) {
        when (jsonString(text, "event")) {
            EVENT_METRIC_SNAPSHOT -> {
                fired.add(STAGE_METRIC)
                metricSeen = true
                record(ASSERT_METRIC_PUSH, true, "")
                maybeSendMetricsNow()
                maybeComplete()
            }
            EVENT_CONFIG_APPLIED -> onConfigApplied(text)
            EVENT_CONFIG_STATE -> onConfigState(text)
            EVENT_COMMAND_RESULT -> onCommandResult(text)
            else -> Unit
        }
    }

    /** 配置应用回执：`payload` 里带 result / configVersion（与协议规格 §3.4.2 同形）。 */
    private fun onConfigApplied(text: String) {
        val payload = jsonObjectAt(text, "payload") ?: return
        val result = jsonString(payload, "result")
        val version = jsonInt(payload, "configVersion")
        val applied = result == CONFIG_RESULT_APPLIED && version?.toLong() == CONFIG_VERSION
        lastConfigDetail = "result=$result version=$version"
        if (!applied) {
            // 探针刚启动时自检可能因首份指标快照尚未产出而失败：不立即落断言，交给重试线程。
            return
        }
        configAppliedOk = true
        record(ASSERT_CONFIG_APPLIED, true, "")
        maybeSendMetricsNow()
        maybeComplete()
    }

    /** 状态上报：连接态必须如实给出 online（此时桥确实连着）。 */
    private fun onConfigState(text: String) {
        val payload = jsonObjectAt(text, "payload") ?: return
        val connection = jsonString(payload, "connection")
        record(ASSERT_CONFIG_STATE, connection == CONNECTION_ONLINE, "connection=$connection")
        maybeComplete()
    }

    /** `metrics_now` 的回执必须带同一个 requestId（控制面据此匹配等待者）。 */
    private fun onCommandResult(text: String) {
        val data = jsonObjectAt(text, "data") ?: return
        when (jsonString(data, "requestId")) {
            REQUEST_METRICS_NOW -> {
                val success = jsonBoolean(data, "success") == true
                record(ASSERT_METRICS_NOW, success, jsonString(data, "error") ?: "")
                maybeComplete()
            }
            REQUEST_MCP_INIT -> {
                val output = jsonString(data, "output") ?: ""
                val success = jsonBoolean(data, "success") == true && output.contains("protocolVersion")
                record(ASSERT_MCP_BRIDGE, success, "success=${jsonBoolean(data, "success")} output=${output.take(120)}")
                maybeComplete()
            }
            else -> Unit
        }
    }

    /**
     * 零监听检查：托管模式下 metrics / web 端口必须连不上。
     *
     * 延迟判定而非立即判定：模式判定跑在探针自己的后台线程上，bind 只会更晚；立即去连可能"恰好还没起"
     * 而给出假通过。等到模板配置里的节拍之后仍不可连，才说明强制关断真的生效。
     */
    private fun startZeroBindCheck() {
        Thread({
            runCatching { Thread.sleep(ZERO_BIND_DELAY_MS) }
            val reachable = ZERO_BIND_PORTS.filter { canConnect(it) }
            record(ASSERT_ZERO_BIND, reachable.isEmpty(), "托管模式下仍有端口可连:$reachable")
            maybeComplete()
        }, "serverprobe-e2e-managed-zerobind").apply {
            isDaemon = true
            start()
        }
    }

    /** 能否在回环上建立 TCP 连接（连上即说明端口在监听）。 */
    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS) }
    }.isSuccess

    private fun sendConfigPush() {
        sendText(
            """{"type":"command","domain":"core","action":"config_push",""" +
                """"requestId":"$REQUEST_CONFIG_PUSH","payload":{""" +
                """"configVersion":$CONFIG_VERSION,"baseVersion":0,"values":{"debug":true},""" +
                """"removals":[],"reason":"e2e 托管配置下发","operator":"e2e"}}""",
        )
    }

    /**
     * 重发 `config_push`，直到收到回执或用完次数。
     *
     * 为什么要重试：`config_push` 是探针在**自己的后台线程**里注册的 core 内置动作（等模式判定之后），
     * 而上行连接一建立就可能先把 hello 发过来。两者存在毫秒级先后差，恰好卡在窗口上时首次下发会被
     * 当成“平台未实现”拒掉——那是夹具的时序问题，不是产品缺陷。
     */
    private fun startConfigPushRetry() {
        Thread({
            repeat(CONFIG_PUSH_ATTEMPTS) {
                if (configAppliedOk) return@Thread
                sendConfigPush()
                runCatching { Thread.sleep(CONFIG_PUSH_RETRY_INTERVAL_MS) }
            }
            // 次数用尽仍未生效：把最后一次回执的实际值落成断言（含自检未过的真实原因）。
            if (!configAppliedOk) record(ASSERT_CONFIG_APPLIED, false, lastConfigDetail)
            maybeComplete()
        }, "serverprobe-e2e-managed-push").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 探测 MCP 是否已经挂到桥上：下发一条 MCP `initialize`（JSON-RPC 2.0 原文）。
     *
     * 为什么用 `initialize` 而不 `tools/list`：后者回执可达数十万字符，会触发桥的分片帧而测试桩不重组，
     * 验的就不再是 MCP 通道本身；`initialize` 体积小且能证明“请求确实进了 MCP 分发器”（回体含 protocolVersion）。
     */
    private fun sendMcpProbe() {
        sendText(
            """{"type":"command","domain":"core","action":"mcp","requestId":"$REQUEST_MCP_INIT",""" +
                """"payload":{"jsonrpc":"2.0","id":1,"method":"initialize"}}""",
        )
    }

    /**
     * 重发 MCP 探测，直到拿到回执或用完次数。
     *
     * 与 `config_push` 同理：动作挂载发生在探针自己的后台线程里，与 hello 存在毫秒级先后差。
     */
    private fun startMcpProbeRetry() {
        Thread({
            repeat(MCP_PROBE_ATTEMPTS) {
                if (assertions.containsKey(ASSERT_PREFIX + ASSERT_MCP_BRIDGE)) return@Thread
                sendMcpProbe()
                runCatching { Thread.sleep(MCP_PROBE_RETRY_INTERVAL_MS) }
            }
        }, "serverprobe-e2e-managed-mcp").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 按需推送：必须在「配置已生效」且「已见过至少一帧快照」之后才发。
     *
     * 早发会撞上探针采集器尚未产出首份快照的窗口，回执是"当前无可推送的指标快照"——
     * 那是时序问题，与 `metrics_now` 本身能否工作无关。
     */
    private fun maybeSendMetricsNow() {
        if (!metricSeen || !configAppliedOk) return
        if (!fired.add(STAGE_METRICS_NOW)) return
        sendText(
            """{"type":"command","domain":"core","action":"metrics_now","requestId":"$REQUEST_METRICS_NOW"}""",
        )
    }

    private fun record(name: String, ok: Boolean, detail: String) {
        assertions[ASSERT_PREFIX + name] = if (ok) ASSERT_PASSED else detail.ifEmpty { ASSERT_FAILED }
    }

    /** 六项判定齐了才交卷；少一项就等 `awaitEvidence` 超时暴露问题。 */
    private fun maybeComplete() {
        if (!REQUIRED_ASSERTIONS.all { assertions.containsKey(ASSERT_PREFIX + it) }) return
        if (!completed.compareAndSet(false, true)) return
        completeEvidence(
            HashMap(assertions) + mapOf(
                "instance" to WsFixtureProtocol.INSTANCE_ID,
                "configVersion" to CONFIG_VERSION.toString(),
            ),
        )
    }

    companion object {

        /** 断言键前缀（与其它场景同一约定：`assert.` 开头）。 */
        const val ASSERT_PREFIX = "assert."

        /** 从探针数据目录下的身份文件起服：端口与凭据都由该文件给出，证明"身份文件即开关"。 */
        fun startFromIdentityFile(
            pluginsDirectory: File,
            pluginDataDir: String = WsFixtureProtocol.SERVER_PROBE_PLUGIN,
            scenario: String = "managed-config",
        ): ManagedConfigFixture =
            ManagedConfigFixture(openFixtureEndpointFromIdentity(pluginsDirectory, pluginDataDir, scenario))

        /** 断言通过标记（结果写入器按此判真）。 */
        private const val ASSERT_PASSED = "true"

        private const val ASSERT_FAILED = "false"

        /** 断言名：逐项对应 KDoc 里的判定项。 */
        private const val ASSERT_HELLO_MANAGED = "helloManaged"
        private const val ASSERT_CONFIG_APPLIED = "configApplied"
        private const val ASSERT_METRIC_PUSH = "metricPush"
        private const val ASSERT_CONFIG_STATE = "configState"
        private const val ASSERT_METRICS_NOW = "metricsNow"
        private const val ASSERT_MCP_BRIDGE = "mcpBridge"
        private const val ASSERT_ZERO_BIND = "zeroBind"

        /** 交卷前必须到齐的判定项。 */
        private val REQUIRED_ASSERTIONS = listOf(
            ASSERT_HELLO_MANAGED,
            ASSERT_CONFIG_APPLIED,
            ASSERT_METRIC_PUSH,
            ASSERT_CONFIG_STATE,
            ASSERT_METRICS_NOW,
            ASSERT_ZERO_BIND,
            ASSERT_MCP_BRIDGE,
        )

        /** 托管模式必须声明的能力位。 */
        private val REQUIRED_CAPABILITIES = listOf("metric_push", "config_push", "config_state")

        /** 帧里的事件名。 */
        private const val EVENT_METRIC_SNAPSHOT = "metric_snapshot"
        private const val EVENT_CONFIG_APPLIED = "config_applied"
        private const val EVENT_CONFIG_STATE = "config_state"
        private const val EVENT_COMMAND_RESULT = "command_result"

        /** 一次配置下发的版本号（回执里必须回同一个）。 */
        private const val CONFIG_VERSION = 2L

        /** 配置应用回执的成功结果码。 */
        private const val CONFIG_RESULT_APPLIED = "applied"

        /** 状态上报里的在线连接态。 */
        private const val CONNECTION_ONLINE = "online"

        /** 命令 requestId（回执必须原样带回）。 */
        private const val REQUEST_CONFIG_PUSH = "e2e-managed-config-push"
        private const val REQUEST_METRICS_NOW = "e2e-managed-metrics-now"
        private const val REQUEST_MCP_INIT = "e2e-managed-mcp"

        /** MCP 探测的最多尝试次数与间隔（覆盖“动作尚未挂载”的窗口）。 */
        private const val MCP_PROBE_ATTEMPTS = 12
        private const val MCP_PROBE_RETRY_INTERVAL_MS = 3_000L

        /** 只触发一次的阶段标记。 */
        private const val STAGE_HELLO = "hello"
        private const val STAGE_METRIC = "metric"
        private const val STAGE_METRICS_NOW = "metrics_now"

        /** 配置下发最多尝试次数与间隔：覆盖“探针刚连上、core 动作还未注册”的窗口，并兼容旧版服务端较慢的启动。 */
        private const val CONFIG_PUSH_ATTEMPTS = 12
        private const val CONFIG_PUSH_RETRY_INTERVAL_MS = 3_000L

        /** 模板配置里故意开启的三个监听面端口：托管模式下必须都连不上。 */
        private val ZERO_BIND_PORTS = listOf(19941, 19942, 19943)

        /** 零监听判定的等待时长（毫秒）：覆盖探针后台判定 + 一次采集周期；旧版服务端启动更慢，故取宽。 */
        private const val ZERO_BIND_DELAY_MS = 15_000L

        /** 回环连接判定的超时（毫秒）。 */
        private const val CONNECT_TIMEOUT_MS = 400
    }
}
