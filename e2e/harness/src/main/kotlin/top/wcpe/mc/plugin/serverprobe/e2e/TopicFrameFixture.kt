package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 仅用于（事件主题订阅）的回环 fixture。
 *
 * ## 判据来源
 * `docs/specs/event-topic-subscription.md` §5 的「精确订阅」一行 + §3.5 的两条关键语义：
 * - 订阅 `activity.12345` 后，`activity.12345` 上行、`activity.99999` **一条都不上行**；
 * - 订阅生效期间**所有非业务事件**（`command_result` 等）仍全部上行（证明没误杀）；
 * - 未声明 `topic` 的业务事件**仍放行**（防静默丢事件）。
 *
 * 每条断言都写进证据表，任一失败即抛异常让场景失败——绝不"跑完即 PASS"。
 *
 * ## 为什么断言"未命中事件为 0"要等一段时间
 * "没立刻看到"不等于"不会来"：可靠事件的入队与写出跨线程，迟到是常态。
 * 因此这里不靠"收齐就断言"，而是在收齐命中/未声明两条后进入**静默窗口**（[QUIET_WINDOW_MS]），
 * 期间读循环照常计数；窗口结束后再下发一条 `bridge_status`，以它的回执作为"探针已处理完本轮全部帧"
 * 的顺序栅栏，此时仍未收到即判定为拦截生效。
 *
 * ## 为什么还要看 `sender.filteredOut`
 * 只断言"没收到"无法区分"被主题滤镜拦截"与"事件根本没被受理/写丢了"。
 * 规格 §3.5 规定被滤镜丢弃计入 `filteredOut`、**不计** `reliableRejected`，
 * 因此额外断言"发送前 `filteredOut=0`、发送后 `filteredOut=1`、`reliableRejected=0`"，
 * 把"过滤生效"与"通道失败"彻底分开。
 *
 * ## 独立状态机
 * 与 [ProtocolWorkerFixture]（按 Stage 校验回执）和 [ChunkFrameFixture]（按 groupId 重组）
 * 一样共用 [WebSocketFixtureServer] 的连接与帧编解码，但状态机各写各的：
 * 本场景的判定对象是"哪些业务事件上行"，前置步骤只有订阅、状态查询与一次 emit。
 */
class TopicFrameFixture private constructor(
    endpoint: FixtureEndpoint,
) : WebSocketFixtureServer(endpoint.serverSocket, endpoint.token, "serverprobe-e2e-topic-fixture") {

    /** Provider 注册完成后允许 fixture 下发订阅指令。 */
    private val commandsOpen = AtomicBoolean(false)

    /** hello 只接受一次，且必须带本次场景的实例标识。 */
    private val helloSeen = AtomicBoolean(false)

    /** 当前阶段：决定收到的 `command_result` 应该对应哪条命令。 */
    private val stage = AtomicReference(Stage.WAITING)

    /** 静默窗口只启动一次（emit 回执与两条事件到达顺序不确定，谁最后到都由它触发）。 */
    private val quietStarted = AtomicBoolean(false)

    /** Provider 已确认三条事件都受理（`emitReliable` 全 true）。 */
    private val emitAccepted = AtomicBoolean(false)

    /** 已收到的回执 requestId（按到达顺序）；仅读线程写，故无需同步。 */
    private val receivedRequests = linkedSetOf<String>()

    /** 命中订阅的业务事件原文；只允许恰好 1 条。 */
    private val hitEvents = mutableListOf<String>()

    /** 未声明 topic 的业务事件原文；订阅生效期间也必须放行，故应恰好 1 条。 */
    private val untopicedEvents = mutableListOf<String>()

    /** 未命中订阅却被上行的事件条数；正常路径恒为 0（一旦 >0 立即抛异常让场景失败）。 */
    private var missEvents = 0

    /** 订阅回执里回显的生效集合，用于与 `bridge_status.topics.expressions` 逐字比对。 */
    private var subscribedOutput: String? = null

    /** Provider 注册完成后由 harness 调用，随后立即下发订阅指令。 */
    fun openCommands() {
        commandsOpen.set(true)
        maybeSubscribe()
    }

    /** hello 触发订阅；其余 event 按业务/非业务分类。 */
    override fun handleText(text: String) {
        when (jsonString(text, "type")) {
            "hello" -> handleHello(text)
            "event" -> handleEvent(text)
            else -> throw IllegalStateException("收到未知桥帧:${text.take(PREVIEW_CHARS)}")
        }
    }

    /** 只接受指定实例的 hello；不发 welcome：探针不解析 `welcome`（会记一次 parseError），本场景也不需要。 */
    private fun handleHello(text: String) {
        check(jsonString(text, "instance") == WsFixtureProtocol.INSTANCE_ID) { "hello 实例标识不匹配" }
        check(helloSeen.compareAndSet(false, true)) { "重复收到 hello" }
        maybeSubscribe()
    }

    /** hello 与 Provider 注册都就绪后才下发订阅，避免"注册完成前就宣称已订阅"。 */
    private fun maybeSubscribe() {
        if (!commandsOpen.get() || !helloSeen.get()) return
        if (!stage.compareAndSet(Stage.WAITING, Stage.SUBSCRIBE)) return
        sendCommand(REQUEST_SUBSCRIBE, DOMAIN_CORE, ACTION_SUBSCRIBE, subscribePayload())
    }

    /** 订阅 payload：`{"topics":["activity.12345"]}`（规格 §3.7）。 */
    private fun subscribePayload(): String =
        "{\"topics\":[\"${TopicFixturePayload.SUBSCRIBE_EXPRESSION}\"]}"

    /** 非业务事件一律只做登记，业务事件按 topic 三分类（命中 / 未命中 / 未声明）。 */
    private fun handleEvent(text: String) {
        when (jsonString(text, "event")) {
            EVENT_COMMAND_RESULT -> handleCommandResult(text)
            TopicFixturePayload.BUSINESS_EVENT -> handleBusinessEvent(text)
            "connected" -> Unit
            "player_join", "player_quit", "chat", "cross_server" -> Unit
            else -> throw IllegalStateException("收到未知业务事件:${text.take(PREVIEW_CHARS)}")
        }
    }

    /**
     * 业务事件按 `topic` 分类。
     *
     * 未命中的事件**立即大声失败**：它一旦出现就说明滤镜根本没生效，
     * 继续等下去只会把一次确定的违约变成一次超时（失败信息还会丢掉原始帧）。
     */
    private fun handleBusinessEvent(text: String) {
        check(jsonString(text, "domain") == TopicFixturePayload.DOMAIN) { "业务事件 domain 不匹配" }
        check(jsonString(text, "instance") == WsFixtureProtocol.INSTANCE_ID) { "业务事件 instance 不匹配" }
        when (val topic = jsonString(text, "topic")) {
            TopicFixturePayload.HIT_TOPIC -> collectHit(text, topic)
            TopicFixturePayload.MISS_TOPIC -> {
                missEvents++
                throw IllegalStateException("未命中订阅的事件仍然上行(主题滤镜未生效):${text.take(PREVIEW_CHARS)}")
            }
            null -> collectUntopiced(text)
            else -> throw IllegalStateException("收到订阅表达式之外的 topic:$topic")
        }
    }

    /**
     * 命中的业务事件：除去重键外，还要核对"帧上 `topic`"与"payload 内声明的主题"同源同值
     * （规格 §3.1：两处不一致时过滤行为将无法解释）。
     */
    private fun collectHit(text: String, topic: String) {
        check(jsonString(text, "dedupKey") == TopicFixturePayload.HIT_DEDUP_KEY) { "命中事件的 dedupKey 不匹配" }
        val declared = jsonString(text, TopicFixturePayload.DECLARED_FIELD)
        check(declared == topic) { "帧上 topic 与 payload 内声明的主题不一致:$declared / $topic" }
        hitEvents += text
        maybeStartQuiet()
    }

    /** 未声明 topic 的业务事件：有订阅时仍必须放行（规格 §3.5 的关键决策）。 */
    private fun collectUntopiced(text: String) {
        check(jsonString(text, "dedupKey") == TopicFixturePayload.UNTOPICED_DEDUP_KEY) {
            "未声明 topic 事件的 dedupKey 不匹配"
        }
        untopicedEvents += text
        maybeStartQuiet()
    }

    /** 回执先按"必须成功 + 必须对应已下发命令"做通用校验，再交给阶段处理。 */
    private fun handleCommandResult(text: String) {
        val requestId = checkNotNull(jsonString(text, "requestId")) { "command_result 缺少 requestId" }
        check(receivedRequests.add(requestId)) { "重复收到回执:$requestId" }
        check(jsonBoolean(text, "success") == true) {
            "回执 success 不为 true:requestId=$requestId,error=${jsonString(text, "error")}"
        }
        val expected = expectedRequest()
        check(requestId == expected) { "回执 requestId 不匹配:期望 $expected,实际 $requestId" }
        val output = checkNotNull(jsonString(text, "output")) { "回执缺少 output" }
        when (stage.get()) {
            Stage.SUBSCRIBE -> verifySubscribe(output)
            Stage.STATUS_BEFORE -> verifyStatusBefore(output)
            Stage.EMIT -> verifyEmit(output)
            Stage.STATUS_AFTER -> verifyStatusAfter(output)
            Stage.WAITING, null -> throw IllegalStateException("未下发命令时收到 command_result:$requestId")
        }
    }

    /** 订阅回执必须回显期望的生效集合（规格 §3.7 成功回执形态）。 */
    private fun verifySubscribe(output: String) {
        check(output == TopicFixturePayload.EXPECTED_TOPICS_JSON) {
            "订阅回执回显与期望不一致:期望 ${TopicFixturePayload.EXPECTED_TOPICS_JSON},实际 $output"
        }
        subscribedOutput = output
        advance(Stage.STATUS_BEFORE)
        sendCommand(REQUEST_STATUS_BEFORE, DOMAIN_CORE, ACTION_BRIDGE_STATUS, EMPTY_PAYLOAD)
    }

    /** 订阅生效但尚未发事件：`topics` 必须与回执一致，且 `filteredOut` 基线为 0。 */
    private fun verifyStatusBefore(output: String) {
        check(jsonInt(output, "count") == TOPICS_EXPECTED_COUNT) { "bridge_status.topics.count 不为 1" }
        val expressions = jsonArrayOf(output, "expressions")
        check(expressions == subscribedOutput) {
            "topics.expressions 与订阅回执不一致:${expressions} / ${subscribedOutput}"
        }
        check(jsonInt(output, FIELD_FILTERED_OUT) == 0) {
            "发送前 filteredOut 非 0:${jsonInt(output, FIELD_FILTERED_OUT)}"
        }
        advance(Stage.EMIT)
        sendCommand(REQUEST_EMIT, TopicFixturePayload.DOMAIN, TopicFixturePayload.ACTION_EMIT_TOPIC, EMPTY_PAYLOAD)
    }

    /** Provider 必须报告三条事件全部受理——这是"未命中却返回 true"的规格语义。 */
    private fun verifyEmit(output: String) {
        check(output == TopicFixturePayload.EXPECTED_EMIT_OUTPUT) {
            "三条可靠事件未全部受理:期望 ${TopicFixturePayload.EXPECTED_EMIT_OUTPUT},实际 $output"
        }
        emitAccepted.set(true)
        maybeStartQuiet()
    }

    /**
     * 静默窗口：回执与两条期望事件齐备后，等一个窗口再下发"顺序栅栏"。
     *
     * 窗口期间读循环持续在跑，任何迟到的未命中事件都会被 [handleBusinessEvent] 立刻判失败，
     * 因此"窗口结束后仍为 0"是真断言，而不是'没等到就当作通过'。
     */
    private fun maybeStartQuiet() {
        if (stage.get() != Stage.EMIT || !emitAccepted.get()) return
        if (hitEvents.isEmpty() || untopicedEvents.isEmpty()) return
        if (!quietStarted.compareAndSet(false, true)) return
        Thread({
            Thread.sleep(QUIET_WINDOW_MS)
            requestStatusAfter()
        }, "serverprobe-e2e-topic-quiet").apply { isDaemon = true }.start()
    }

    /** 静默窗口结束后下发状态查询；它的回执同时充当"探针已处理完本轮全部帧"的栅栏。 */
    private fun requestStatusAfter() {
        advance(Stage.STATUS_AFTER)
        sendCommand(REQUEST_STATUS_AFTER, DOMAIN_CORE, ACTION_BRIDGE_STATUS, EMPTY_PAYLOAD)
    }

    /**
     * 终局断言：命中恰 1 条、未声明恰 1 条、未命中 0 条、滤镜计数 +1 且无失败计数、回执一条不少。
     */
    private fun verifyStatusAfter(output: String) {
        check(hitEvents.size == EXPECTED_EVENT_COUNT) { "命中主题的事件应恰好 1 条,实际 ${hitEvents.size}" }
        check(untopicedEvents.size == EXPECTED_EVENT_COUNT) {
            "未声明 topic 的业务事件应恰好 1 条(防静默丢弃),实际 ${untopicedEvents.size}"
        }
        check(jsonInt(output, FIELD_FILTERED_OUT) == 1) {
            "未命中事件未被主题滤镜拦截:filteredOut=${jsonInt(output, FIELD_FILTERED_OUT)}"
        }
        check(jsonInt(output, FIELD_RELIABLE_REJECTED) == 0) {
            "可靠事件出现被拒计数(说明是通道失败而非滤镜拦截):${jsonInt(output, FIELD_RELIABLE_REJECTED)}"
        }
        check(jsonInt(output, FIELD_BEST_EFFORT_DROPPED) == 0) {
            "可丢事件出现丢弃计数:${jsonInt(output, FIELD_BEST_EFFORT_DROPPED)}"
        }
        check(receivedRequests.containsAll(ALL_REQUESTS)) {
            "非业务事件未全部上行:缺少回执 ${ALL_REQUESTS - receivedRequests}"
        }
        completeEvidence(
            mapOf(
                "subscribeSuccess" to "true",
                "topicsExpressions" to (subscribedOutput ?: ""),
                "topicsCount" to TOPICS_EXPECTED_COUNT.toString(),
                "hitTopic" to TopicFixturePayload.HIT_TOPIC,
                "hitEvents" to hitEvents.size.toString(),
                "topicSameAsPayload" to "true",
                "missTopic" to TopicFixturePayload.MISS_TOPIC,
                "missEvents" to missEvents.toString(),
                "quietWindowMs" to QUIET_WINDOW_MS.toString(),
                "filteredOut" to "1",
                "reliableRejected" to "0",
                "untopicedPassed" to "true",
                "commandResults" to receivedRequests.size.toString(),
            ),
        )
    }

    /** 当前阶段期望的 requestId；与下发顺序一一对应。 */
    private fun expectedRequest(): String = when (stage.get()) {
        Stage.SUBSCRIBE -> REQUEST_SUBSCRIBE
        Stage.STATUS_BEFORE -> REQUEST_STATUS_BEFORE
        Stage.EMIT -> REQUEST_EMIT
        Stage.STATUS_AFTER -> REQUEST_STATUS_AFTER
        Stage.WAITING, null -> throw IllegalStateException("未下发命令时收到 command_result")
    }

    /** 阶段推进（读线程与静默窗口线程都会调用，故用原子引用）。 */
    private fun advance(next: Stage) {
        stage.set(next)
    }

    /** 从 JSON 文本取一个数组字段的**原文**（含方括号）；用于逐字比对表达式集合。 */
    private fun jsonArrayOf(text: String, key: String): String {
        val from = text.indexOf("\"$key\"")
        check(from >= 0) { "缺少字段:$key" }
        val start = text.indexOf('[', from)
        check(start >= 0) { "字段 $key 不是数组" }
        val end = text.indexOf(']', start)
        check(end > start) { "字段 $key 数组未闭合" }
        return text.substring(start, end + 1)
    }

    /** 组装与 BridgeClient 当前协议形态一致的 command 文本帧。 */
    private fun sendCommand(requestId: String, domain: String, action: String, payload: String) {
        sendText(
            "{\"type\":\"command\",\"action\":\"$action\",\"target\":\"\",\"reason\":\"\"," +
                "\"requestId\":\"$requestId\",\"domain\":\"$domain\"," +
                "\"payloadJson\":\"${escapeJson(payload)}\"}",
        )
    }

    /** 阶段：每条命令的下发点即阶段切换点，用于校验回执归属。 */
    private enum class Stage {
        WAITING,
        SUBSCRIBE,
        STATUS_BEFORE,
        EMIT,
        STATUS_AFTER,
    }

    companion object {

        /** 从启动前生成的测试配置读取一次性凭据，并在回环端口启动主题订阅 fixture。 */
        fun startFromProbeConfiguration(pluginsDirectory: File): TopicFrameFixture =
            TopicFrameFixture(openFixtureEndpoint(pluginsDirectory, ""))

        /** 帧类型：事件（与 `OutboundFrame.TYPE_EVENT` 一致）。 */
        private const val EVENT_COMMAND_RESULT = "command_result"

        /** 治理域与动作名（规格 §3.7）。 */
        private const val DOMAIN_CORE = "core"
        private const val ACTION_SUBSCRIBE = "subscribe"
        private const val ACTION_BRIDGE_STATUS = "bridge_status"

        /** 命令标识：顺序下发的四条命令，回执必须逐条对上。 */
        private const val REQUEST_SUBSCRIBE = "e2e-topic-subscribe"
        private const val REQUEST_STATUS_BEFORE = "e2e-topic-status-before"
        private const val REQUEST_EMIT = "e2e-topic-emit"
        private const val REQUEST_STATUS_AFTER = "e2e-topic-status-after"

        /** 全部命令标识：用于断言"非业务事件（回执）在订阅生效期间一条不少"。 */
        private val ALL_REQUESTS = setOf(REQUEST_SUBSCRIBE, REQUEST_STATUS_BEFORE, REQUEST_EMIT, REQUEST_STATUS_AFTER)

        /** `bridge_status` 的空 payload（路由不读它）。 */
        private const val EMPTY_PAYLOAD = "{}"

        /** `bridge_status.topics.count` 的期望值：只订阅了 1 个精确表达式。 */
        private const val TOPICS_EXPECTED_COUNT = 1

        /** 每条期望事件的条数（命中 / 未声明各恰好 1 条）。 */
        private const val EXPECTED_EVENT_COUNT = 1

        /** `bridge_status.sender` 的字段名（规格 §3.5 的计数语义）。 */
        private const val FIELD_FILTERED_OUT = "filteredOut"
        private const val FIELD_RELIABLE_REJECTED = "reliableRejected"
        private const val FIELD_BEST_EFFORT_DROPPED = "bestEffortDropped"

        /**
         * 静默窗口长度。
         *
         * 3 秒远大于"同连接内三帧的写出间隔"（微秒级），因此足以让任何迟到帧暴露；
         * 又远小于 fixture 证据超时，失败路径也不会拖长。
         */
        private const val QUIET_WINDOW_MS = 3_000L

        /** 失败信息里回显的帧长度上限，避免把整帧文本打进日志。 */
        private const val PREVIEW_CHARS = 512
    }
}
