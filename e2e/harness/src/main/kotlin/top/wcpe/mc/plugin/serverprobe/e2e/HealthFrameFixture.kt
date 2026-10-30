package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅用于（Provider 健康状态）的回环 fixture。
 *
 * ## 判据来源
 * `docs/specs/provider-health.md` §5 的「真机」一行 + §3.4 的回执形状：
 * 注册正常 / 抛异常 / 卡死三类 Provider，断言 `business_status` **三者区分呈现**且整段 `success=true`；
 * 另按 §5「查询不派发」连续查询若干次，断言每域 `dispatch` 计数**始终为 0**。
 *
 * 每条断言都写进证据表，任一失败即抛异常让场景失败——绝不"跑完即 PASS"。
 * 成功路径还会把逐项断言以 [HealthFixturePayload.ASSERT_PREFIX] 为前缀登记，
 * 由 harness 转成结果文件里的 `assertions.total/passed`，让 PASS 可被逐条核对。
 *
 * ## 独立状态机
 * 与 [ProtocolWorkerFixture]（按 Stage 校验回执）、[ChunkFrameFixture]（按 groupId 重组）、
 * [TopicFrameFixture]（按 topic 过滤）一样共用 [WebSocketFixtureServer] 的连接与帧编解码，
 * 但状态机各写各的：本场景的判定对象是"同一份回执里三个域的呈现差异"，前置步骤只有 hello 与查询。
 *
 * ## 为什么断言在独立 driver 线程上做
 * 读线程只负责"收帧 + 入队"，绝不做等待或断言：
 * 卡死域会让刷新慢上几百毫秒，若在读线程上轮询，既会阻塞后续帧的接收，
 * 也会让"查询幂等只读"这条结论变得不可信（读不动就重发）。driver 线程一条一条发、
 * 一条一条等回执，时序完全确定。
 *
 * ## 为什么用 `business_status` 而不是 `bridge_status` 作栅栏
 * 本场景要观察的就是该回执本身。栅栏期间发出的多次查询同时证明了"查询是幂等只读的"：
 * 反复查不改变任何状态，也不触发任何派发。
 */
class HealthFrameFixture private constructor(
    endpoint: FixtureEndpoint,
) : WebSocketFixtureServer(endpoint.serverSocket, endpoint.token, "serverprobe-e2e-health-fixture") {

    /** hello 只接受一次，且必须带本次场景的实例标识。 */
    private val helloSeen = AtomicBoolean(false)

    /** harness 已注册三个 Provider，允许 fixture 下发查询。 */
    private val commandsOpen = AtomicBoolean(false)

    /** driver 只启动一次（hello 与 openCommands 谁后到都由它触发）。 */
    private val driverStarted = AtomicBoolean(false)

    /** 已发出的栅栏查询次数（证据用）。 */
    @Volatile
    private var fenceQueries = 0

    /**
     * 回执队列：协议读线程只入队，断言全部在 driver 线程上做。
     *
     * 容量不设限：查询是串行的（同一时刻至多一条在途），正常路径队列长度恒为 0/1；
     * 设上限只会让"探针意外多发一条回执"变成阻塞读线程的隐患。
     */
    private val receipts = LinkedBlockingQueue<String>()

    /** Provider 注册完成后由 harness 调用，随后开始栅栏查询。 */
    fun openCommands() {
        commandsOpen.set(true)
        maybeStartDriver()
    }

    /** hello 触发 driver；回执入队；其余事件忽略。 */
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
        maybeStartDriver()
    }

    /** 回执只入队，不在读线程上做任何断言；未知事件立即大声失败。 */
    private fun handleEvent(text: String) {
        when (jsonString(text, "event")) {
            EVENT_COMMAND_RESULT -> receipts.add(text)
            "connected" -> Unit
            "player_join", "player_quit", "chat", "cross_server" -> Unit
            else -> throw IllegalStateException("收到未知业务事件:${text.take(PREVIEW_CHARS)}")
        }
    }

    /** hello 与 Provider 注册都就绪后才开始查询，避免"域还没注册就问"的空回执。 */
    private fun maybeStartDriver() {
        if (!helloSeen.get() || !commandsOpen.get()) return
        if (!driverStarted.compareAndSet(false, true)) return
        Thread({ runCatching { drive() }.onFailure { evidence.completeExceptionally(it) } }, "serverprobe-e2e-health-driver")
            .apply { isDaemon = true }
            .start()
    }

    /** 驱动全流程：栅栏 → 三域区分呈现 → 查询不派发。 */
    private fun drive() {
        val assertions = linkedMapOf<String, String>()
        val receipt = awaitRefreshedReceipt()
        record(assertions, ASSERT_FENCE_RECEIPT_SUCCESS, fenceQueries.toString())
        verifyDomains(receipt, assertions)
        verifyRefreshSection(receipt, assertions)
        verifyDomainStatusExtension(receipt, assertions)
        verifyQueriesDoNotDispatch(assertions)
        completeEvidence(
            assertions + mapOf(
                "healthTimeoutMs" to HealthFixturePayload.HEALTH_TIMEOUT_MS.toString(),
                "stuckBlockMs" to HealthFixturePayload.STUCK_BLOCK_MS.toString(),
                "fenceQueries" to fenceQueries.toString(),
                "dispatchProbeQueries" to DISPATCH_PROBE_QUERIES.toString(),
                "domains" to HealthFixturePayload.ALL_DOMAINS.joinToString(FAILED_NAME_SEPARATOR),
            ),
        )
    }

    /**
     * 的域状态可视化字段（`docs/specs/business-domain-status-and-seq/spec.md` §4 / §6）。
     *
     * 真机侧能验证的是**字段形状与语义一致性**，而不是"指纹算法对不对"（那是 core 单测的事）；
     * 但真机这层能抓到单测抓不到的问题：字段编码成 JSON 后丢失、
     * 或 `available` 与 `health` 在某条分支上不一致。
     *
     * ## 为什么对同一份回执查两次 digest
     * 指纹必须**稳定**。Provider 的 manifest 每次现算，若指纹算法漏了排序，
     * 同一份内容两次查询就会给出不同的值。用同一份回执无法发现这点，
     * 故这里再查一次并比对。
     */
    private fun verifyDomainStatusExtension(receipt: String, assertions: MutableMap<String, String>) {
        val domains = objectAt(receipt, FIELD_DOMAINS)
        val fields = listOf(
            FIELD_AVAILABLE, FIELD_DEGRADED_REASON, FIELD_MANIFEST_DIGEST,
            FIELD_LAST_DISPATCH_AT, FIELD_DISPATCH_COUNT, FIELD_FAILURE_COUNT,
        )
        HealthFixturePayload.ALL_DOMAINS.forEach { domain ->
            val text = objectAt(domains, domain)
            fields.forEach { field -> check(hasField(text, field)) { "$domain 缺少 字段:$field" } }
        }
        record(assertions, ASSERT_DOMAIN_STATUS_FIELDS, fields.joinToString(FAILED_NAME_SEPARATOR))

        // 正常域：可用 + 无降级原因 + 有指纹。
        val ok = objectAt(domains, HealthFixturePayload.OK_DOMAIN)
        check(jsonBoolean(ok, FIELD_AVAILABLE) == true) {
            "正常域应 available=true，实际=${jsonBoolean(ok, FIELD_AVAILABLE)}"
        }
        record(assertions, ASSERT_HEALTHY_AVAILABLE)
        val okDigest = jsonString(ok, FIELD_MANIFEST_DIGEST)
        check(okDigest != null && okDigest.isNotEmpty()) { "正常域缺少 manifestDigest" }
        check(okDigest.length == DIGEST_CHARS) { "manifestDigest 应为 $DIGEST_CHARS 位，实际=${okDigest.length}" }
        record(assertions, ASSERT_DIGEST_PRESENT, okDigest)

        // 降级域（卡死）：不可用 + 有降级原因。
        val stuck = objectAt(domains, HealthFixturePayload.STUCK_DOMAIN)
        check(jsonBoolean(stuck, FIELD_AVAILABLE) == false) { "降级域应 available=false" }
        record(assertions, ASSERT_DEGRADED_UNAVAILABLE)
        val degraded = jsonString(stuck, FIELD_DEGRADED_REASON)
        check(degraded != null && degraded.isNotEmpty()) { "降级域的 degradedReason 不得为空" }
        record(assertions, ASSERT_DEGRADED_REASON, degraded)

        // 查询不派发 → 计数必须仍是 0，且 dispatchCount 与 dispatch.total 同源。
        HealthFixturePayload.ALL_DOMAINS.forEach { domain ->
            val text = objectAt(domains, domain)
            val total = checkNotNull(jsonLong(objectAt(text, FIELD_DISPATCH), FIELD_DISPATCH_TOTAL))
            val count = checkNotNull(jsonLong(text, FIELD_DISPATCH_COUNT)) { "$domain 缺少 dispatchCount" }
            val failed = checkNotNull(jsonLong(text, FIELD_FAILURE_COUNT)) { "$domain 缺少 failureCount" }
            check(total == count) { "$domain 的 dispatchCount 与 dispatch.total 不一致:count=$count total=$total" }
            check(failed <= count) { "$domain 的 failureCount($failed) 大于 dispatchCount($count)" }
            check(jsonLong(text, FIELD_LAST_DISPATCH_AT) == 0L) { "$domain 未派发却已有 lastDispatchAt" }
        }
        record(assertions, ASSERT_COUNTERS_CONSISTENT)

        // 再查一次：指纹必须与上一次相同（内容没变）。
        val again = objectAt(objectAt(query("$REQUEST_DIGEST_PREFIX-1"), FIELD_DOMAINS), HealthFixturePayload.OK_DOMAIN)
        check(jsonString(again, FIELD_MANIFEST_DIGEST) == okDigest) {
            "同一份 manifest 两次查询的指纹不同:${jsonString(again, FIELD_MANIFEST_DIGEST)} vs $okDigest"
        }
        record(assertions, ASSERT_DIGEST_STABLE, okDigest)
    }

    /**
     * 栅栏：反复查 `business_status`，直到三个域的 `refreshedAtMs` 都 > 0。
     *
     * ## 为什么必须有栅栏
     * 注册只触发"请求刷新"，真正的 `health()` 在 `serverprobe-health-probe` 线程上跑；
     * 卡死域还要跑满 `bridge.health.timeout-ms` 才被归类。直接断言迟早会撞上
     * "刷新尚未完成"的假失败——那既不是功能缺陷，也无法与真缺陷区分。
     *
     * ## 为什么用 `refreshedAtMs` 而不是 `health` 是否已变
     * `refreshedAtMs` 是探针自己的采集时刻（§3.4），未刷新过恒为 0。
     * 用等级作判据会误判：正常域的"未刷新"与"已刷新且 AVAILABLE"在等级上无法区分。
     */
    private fun awaitRefreshedReceipt(): String {
        var last = ""
        repeat(MAX_FENCE_QUERIES) { attempt ->
            fenceQueries = attempt + 1
            last = query("$REQUEST_FENCE_PREFIX-$attempt")
            if (allRefreshed(last)) return last
            Thread.sleep(FENCE_RETRY_INTERVAL_MS)
        }
        error("三个域的健康值未在 $MAX_FENCE_QUERIES 次栅栏查询内全部刷新:${refreshedDetail(last)}")
    }

    /** 三个域是否都已真实采集过一次。 */
    private fun allRefreshed(receipt: String): Boolean {
        val domains = objectAt(receipt, FIELD_DOMAINS)
        return HealthFixturePayload.ALL_DOMAINS.all { domain ->
            val refreshedAtMs = jsonLong(objectAt(domains, domain), FIELD_REFRESHED_AT_MS)
            refreshedAtMs != null && refreshedAtMs > 0L
        }
    }

    /** 失败信息里回显三域的 `refreshedAtMs`，便于一眼看出卡在哪个域。 */
    private fun refreshedDetail(receipt: String): String = runCatching {
        val domains = objectAt(receipt, FIELD_DOMAINS)
        HealthFixturePayload.ALL_DOMAINS.joinToString(FAILED_NAME_SEPARATOR) { domain ->
            "$domain=${jsonLong(objectAt(domains, domain), FIELD_REFRESHED_AT_MS)}"
        }
    }.getOrElse { receipt.take(PREVIEW_CHARS) }

    /** 三域必须被区分呈现（规格 §5 的核心判据）。 */
    private fun verifyDomains(receipt: String, assertions: MutableMap<String, String>) {
        val domains = objectAt(receipt, FIELD_DOMAINS)
        verifyOkDomain(objectAt(domains, HealthFixturePayload.OK_DOMAIN), assertions)
        verifyThrowDomain(objectAt(domains, HealthFixturePayload.THROW_DOMAIN), assertions)
        verifyStuckDomain(objectAt(domains, HealthFixturePayload.STUCK_DOMAIN), assertions)
        check(HealthFixturePayload.ALL_DOMAINS.all { registeredIn(objectAt(domains, it)) }) {
            "三个域必须都呈现为已注册"
        }
        record(assertions, ASSERT_DOMAINS_DISTINGUISHED)
    }

    /**
     * 正常域：AVAILABLE + reason 为空 + 未隔离 + `refreshedAtMs > 0`。
     *
     * `refreshedAtMs > 0` 是"真采集过"的证据：未刷新过的条目该字段恒为 0（§3.4）。
     * 只断言 AVAILABLE 是不够的——它无法区分"Provider 自报可用"与"恰好没被刷新"。
     */
    private fun verifyOkDomain(domain: String, assertions: MutableMap<String, String>) {
        check(levelOf(domain) == HealthFixturePayload.LEVEL_AVAILABLE) {
            "正常域等级不是 AVAILABLE:${levelOf(domain)}"
        }
        record(assertions, ASSERT_OK_LEVEL)
        check(jsonString(domain, FIELD_REASON).isNullOrEmpty()) {
            "正常域 reason 应为空:${jsonString(domain, FIELD_REASON)}"
        }
        record(assertions, ASSERT_OK_REASON_EMPTY)
        check(jsonBoolean(domain, FIELD_QUARANTINED) == false) {
            "正常域被错误隔离:quarantined=${jsonBoolean(domain, FIELD_QUARANTINED)}"
        }
        record(assertions, ASSERT_OK_NOT_QUARANTINED)
        val refreshedAtMs = checkNotNull(jsonLong(domain, FIELD_REFRESHED_AT_MS)) { "正常域缺少 refreshedAtMs" }
        check(refreshedAtMs > 0L) { "正常域未真正采集过(refreshedAtMs=$refreshedAtMs)" }
        record(assertions, ASSERT_OK_REFRESHED, refreshedAtMs.toString())
    }

    /**
     * 抛异常域：DEGRADED + reason 含「health() 抛出异常」+ 含该 Provider 自己的异常消息 + 已隔离。
     *
     * 额外断言 `sinceMs=0`：规格 §3.1 明确"探针不替 Provider 兜底填 sinceMs"，
     * 探针替 Provider 下的结论也不该伪造起始时间。
     */
    private fun verifyThrowDomain(domain: String, assertions: MutableMap<String, String>) {
        check(levelOf(domain) == HealthFixturePayload.LEVEL_DEGRADED) {
            "抛异常域等级不是 DEGRADED:${levelOf(domain)}"
        }
        record(assertions, ASSERT_THROW_LEVEL)
        val reason = checkNotNull(jsonString(domain, FIELD_REASON)) { "抛异常域缺少 reason" }
        check(reason.contains(HealthFixturePayload.THROW_REASON_PREFIX)) {
            "抛异常域 reason 未含「${HealthFixturePayload.THROW_REASON_PREFIX}」:$reason"
        }
        record(assertions, ASSERT_THROW_REASON, reason)
        check(reason.contains(HealthFixturePayload.THROW_MESSAGE)) {
            "抛异常域 reason 未透出 Provider 的异常消息「${HealthFixturePayload.THROW_MESSAGE}」:$reason"
        }
        check(jsonBoolean(domain, FIELD_QUARANTINED) == true) { "抛异常域未进入隔离窗口" }
        record(assertions, ASSERT_THROW_QUARANTINED)
        check(jsonLong(domain, FIELD_SINCE_MS) == 0L) {
            "探针替 Provider 兜底填了 sinceMs:${jsonLong(domain, FIELD_SINCE_MS)}"
        }
        record(assertions, ASSERT_THROW_EMPTY_SINCE)
    }

    /**
     * 卡死域：DEGRADED + reason 含「health() 超时」+ 已隔离 + `refreshedAtMs > 0`。
     *
     * 这条断言的分量来自 harness 侧的实现：该域 `health()` **真阻塞** 5s 且不响应中断，
     * 因此 DEGRADED 只可能来自"探针的有界等待到点"，不可能来自"Provider 自己返回降级"。
     */
    private fun verifyStuckDomain(domain: String, assertions: MutableMap<String, String>) {
        check(levelOf(domain) == HealthFixturePayload.LEVEL_DEGRADED) {
            "卡死域等级不是 DEGRADED:${levelOf(domain)}"
        }
        record(assertions, ASSERT_STUCK_LEVEL)
        val reason = checkNotNull(jsonString(domain, FIELD_REASON)) { "卡死域缺少 reason" }
        check(reason.contains(HealthFixturePayload.TIMEOUT_REASON)) {
            "卡死域 reason 未含「${HealthFixturePayload.TIMEOUT_REASON}」:$reason"
        }
        record(assertions, ASSERT_STUCK_REASON, reason)
        check(jsonBoolean(domain, FIELD_QUARANTINED) == true) { "卡死域未进入隔离窗口" }
        record(assertions, ASSERT_STUCK_QUARANTINED)
        val refreshedAtMs = checkNotNull(jsonLong(domain, FIELD_REFRESHED_AT_MS)) { "卡死域缺少 refreshedAtMs" }
        check(refreshedAtMs > 0L) { "卡死域超时结论未落地(refreshedAtMs=$refreshedAtMs)" }
    }

    /** 回执必须含 `refresh` 段（五个字段齐备）与正的 `timestamp`（§3.4）。 */
    private fun verifyRefreshSection(receipt: String, assertions: MutableMap<String, String>) {
        val refresh = objectAt(receipt, FIELD_REFRESH)
        REFRESH_FIELDS.forEach { field -> check(hasField(refresh, field)) { "refresh 段缺少字段:$field" } }
        record(assertions, ASSERT_REFRESH_SECTION, REFRESH_FIELDS.joinToString(FAILED_NAME_SEPARATOR))
        val timestamp = checkNotNull(jsonLong(receipt, FIELD_TIMESTAMP)) { "回执缺少 timestamp" }
        check(timestamp > 0L) { "回执 timestamp 非正:$timestamp" }
        record(assertions, ASSERT_TIMESTAMP, timestamp.toString())
    }

    /**
     * 查询不派发（规格 §5）：刷新完成后连续查询 [DISPATCH_PROBE_QUERIES] 次，
     * 每域 `dispatch.total` 必须**始终为 0**。
     *
     * 只断言"最后一次是 0"不够：真正的违约形态是"某几次查询顺手派发了"，
     * 逐次断言才能覆盖到。`failed` 一并断言，避免"派发了但都成功"这种隐性违约。
     */
    private fun verifyQueriesDoNotDispatch(assertions: MutableMap<String, String>) {
        repeat(DISPATCH_PROBE_QUERIES) { index ->
            val domains = objectAt(query("$REQUEST_DISPATCH_PREFIX-$index"), FIELD_DOMAINS)
            HealthFixturePayload.ALL_DOMAINS.forEach { domain ->
                val dispatch = objectAt(objectAt(domains, domain), FIELD_DISPATCH)
                val total = checkNotNull(jsonLong(dispatch, FIELD_DISPATCH_TOTAL)) { "$domain 缺少 dispatch.total" }
                val failed = checkNotNull(jsonLong(dispatch, FIELD_DISPATCH_FAILED)) { "$domain 缺少 dispatch.failed" }
                check(total == 0L) { "第 ${index + 1} 次查询触发了业务派发:$domain dispatch.total=$total" }
                check(failed == 0L) { "第 ${index + 1} 次查询出现派发失败计数:$domain dispatch.failed=$failed" }
            }
        }
        record(assertions, ASSERT_QUERY_NO_DISPATCH, DISPATCH_PROBE_QUERIES.toString())
        record(assertions, ASSERT_QUERY_RECEIPTS_SUCCESS, DISPATCH_PROBE_QUERIES.toString())
    }

    /**
     * 下发一条 `core.business_status` 并取回 `output`（= 回执 JSON 文本）。
     *
     * 三条通用校验在此收口，保证后面所有断言面对的都是"成功且归属正确"的回执：
     * 回执未超时、`requestId` 对得上、整段 `success=true`（单域降级不得影响整段）。
     */
    private fun query(requestId: String): String {
        sendText(
            "{\"type\":\"command\",\"action\":\"${HealthFixturePayload.ACTION_BUSINESS_STATUS}\",\"target\":\"\",\"reason\":\"\"," +
                "\"requestId\":\"$requestId\",\"domain\":\"${HealthFixturePayload.DOMAIN_CORE}\"," +
                "\"payloadJson\":\"${escapeJson(EMPTY_PAYLOAD)}\"}",
        )
        val frame = receipts.poll(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: error("${QUERY_TIMEOUT_MS}ms 内未收到 business_status 回执:$requestId")
        check(jsonString(frame, FIELD_REQUEST_ID) == requestId) {
            "回执 requestId 不匹配:期望 $requestId,实际 ${jsonString(frame, FIELD_REQUEST_ID)}"
        }
        check(jsonBoolean(frame, FIELD_SUCCESS) == true) {
            "回执 success 不为 true(单域降级影响了整段):error=${jsonString(frame, FIELD_ERROR)}"
        }
        return checkNotNull(jsonString(frame, FIELD_OUTPUT)) { "回执缺少 output" }
    }

    /** 域的 `health` 字段（等级名）。 */
    private fun levelOf(domain: String): String? = jsonString(domain, FIELD_HEALTH)

    /** 域的 `registered` 字段。 */
    private fun registeredIn(domain: String): Boolean = jsonBoolean(domain, FIELD_REGISTERED) == true

    /**
     * 登记一条已成立的断言（失败路径已在 `check` 处抛出，绝不会走到这里）。
     *
     * 两个键都写进证据表：
     * - `assert.<名字>=true` → harness 会把它转登成结果文件里的断言，PASS 因此是逐项汇总的；
     * - `<名字>=<detail>` → 纯证据，供人核对实际值（如 `refreshedAtMs`、脱敏后的 `reason`）。
     * 两者同名不同前缀，既不会互相覆盖，也不会让断言被 detail 值污染。
     */
    private fun record(assertions: MutableMap<String, String>, name: String, detail: String = "") {
        assertions[HealthFixturePayload.ASSERT_PREFIX + name] = ASSERT_PASSED
        if (detail.isNotEmpty()) assertions[name] = detail
    }

    /**
     * 取 [key] 之后的**完整 JSON 对象**原文（花括号配对）。
     *
     * 基类只提供"扁平取字段"，而本场景必须逐域取子对象（三个域、每域再取 `dispatch`）。
     * 括号配对时跳过字符串字面量内部：`reason` 里完全可能出现 `{`/`}`（第三方文本），
     * 不跳过就会截断对象并让后续断言读到错误片段。
     */
    private fun objectAt(text: String, key: String): String {
        val marker = "\"$key\""
        val from = text.indexOf(marker)
        check(from >= 0) { "回执缺少字段:$key" }
        var index = from + marker.length
        while (index < text.length && text[index].isWhitespace()) index++
        check(index < text.length && text[index] == ':') { "字段 $key 后缺少冒号" }
        index++
        while (index < text.length && text[index].isWhitespace()) index++
        check(index < text.length && text[index] == '{') { "字段 $key 不是 JSON 对象" }
        return balancedObject(text, index)
    }

    /** 从 [start] 处的 `{` 开始做括号配对，返回含首尾括号的原文。 */
    private fun balancedObject(text: String, start: Int): String {
        var depth = 0
        var index = start
        var inString = false
        while (index < text.length) {
            when {
                // 转义序列整体跳过：否则 `\"` 会被当成字符串结束，括号计数随即失真。
                inString && text[index] == '\\' -> index++
                text[index] == '"' -> inString = !inString
                inString -> Unit
                text[index] == '{' -> depth++
                text[index] == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
            index++
        }
        error("JSON 对象未闭合:$start")
    }

    /** 字段是否存在（存在且后面跟冒号）。 */
    private fun hasField(text: String, key: String): Boolean =
        Regex("\"${Regex.escape(key)}\"\\s*:").containsMatchIn(text)

    /**
     * 取整数字段并按 Long 解析。
     *
     * 不能用基类的 [WebSocketFixtureServer.jsonInt]：`refreshedAtMs` / `timestamp` 是 epoch 毫秒
     * （约 1.7e12），超出 Int 范围会让它返回 null，断言就变成"字段缺失"的误导性失败。
     */
    private fun jsonLong(text: String, key: String): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()

    companion object {

        /** 从启动前生成的测试配置读取一次性凭据，并在回环端口启动健康状态 fixture。 */
        fun startFromProbeConfiguration(pluginsDirectory: File): HealthFrameFixture =
            HealthFrameFixture(openFixtureEndpoint(pluginsDirectory, ""))

        /** 帧类型：事件（与 `OutboundFrame.TYPE_EVENT` 一致）。 */
        private const val EVENT_COMMAND_RESULT = "command_result"

        /** 回执字段名（§3.4 与 command_result 信封）。 */
        private const val FIELD_REQUEST_ID = "requestId"
        private const val FIELD_SUCCESS = "success"
        private const val FIELD_OUTPUT = "output"
        private const val FIELD_ERROR = "error"
        private const val FIELD_DOMAINS = "domains"
        private const val FIELD_REFRESH = "refresh"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_REGISTERED = "registered"
        private const val FIELD_HEALTH = "health"
        private const val FIELD_REASON = "reason"
        private const val FIELD_SINCE_MS = "sinceMs"
        private const val FIELD_REFRESHED_AT_MS = "refreshedAtMs"
        private const val FIELD_QUARANTINED = "quarantined"
        private const val FIELD_DISPATCH = "dispatch"
        private const val FIELD_DISPATCH_TOTAL = "total"
        private const val FIELD_DISPATCH_FAILED = "failed"

        /** 新增的逐域字段（域状态可视化数据面）。 */
        private const val FIELD_AVAILABLE = "available"
        private const val FIELD_DEGRADED_REASON = "degradedReason"
        private const val FIELD_MANIFEST_DIGEST = "manifestDigest"
        private const val FIELD_LAST_DISPATCH_AT = "lastDispatchAt"
        private const val FIELD_DISPATCH_COUNT = "dispatchCount"
        private const val FIELD_FAILURE_COUNT = "failureCount"

        /** `refresh` 段的字段集（§3.4）；缺任何一个都说明 schema 退化。 */
        private val REFRESH_FIELDS = listOf("inFlight", "stalled", "quarantined", "lastSweepAtMs", "skippedThisSweep")

        /** 逐项断言名（结果文件里以 [HealthFixturePayload.ASSERT_PREFIX] 为前缀）。 */
        private const val ASSERT_FENCE_RECEIPT_SUCCESS = "fence-receipt-success"
        private const val ASSERT_DOMAINS_DISTINGUISHED = "domains-distinguished"
        private const val ASSERT_OK_LEVEL = "ok-level-available"
        private const val ASSERT_OK_REASON_EMPTY = "ok-reason-empty"
        private const val ASSERT_OK_NOT_QUARANTINED = "ok-not-quarantined"
        private const val ASSERT_OK_REFRESHED = "ok-refreshed"
        private const val ASSERT_THROW_LEVEL = "throw-level-degraded"
        private const val ASSERT_THROW_REASON = "throw-reason-prefix"
        private const val ASSERT_THROW_QUARANTINED = "throw-quarantined"
        private const val ASSERT_THROW_EMPTY_SINCE = "throw-since-ms-zero"
        private const val ASSERT_STUCK_LEVEL = "stuck-level-degraded"
        private const val ASSERT_STUCK_REASON = "stuck-reason-timeout"
        private const val ASSERT_STUCK_QUARANTINED = "stuck-quarantined"
        private const val ASSERT_REFRESH_SECTION = "refresh-section-present"
        private const val ASSERT_TIMESTAMP = "timestamp-present"
        private const val ASSERT_QUERY_NO_DISPATCH = "query-does-not-dispatch"
        private const val ASSERT_QUERY_RECEIPTS_SUCCESS = "query-receipts-success"

        /** 的逐项断言名。 */
        private const val ASSERT_DOMAIN_STATUS_FIELDS = "domain-status-fields-present"
        private const val ASSERT_DIGEST_STABLE = "manifest-digest-stable"
        private const val ASSERT_DIGEST_PRESENT = "manifest-digest-present"
        private const val ASSERT_HEALTHY_AVAILABLE = "healthy-domain-available"
        private const val ASSERT_DEGRADED_UNAVAILABLE = "degraded-domain-unavailable"
        private const val ASSERT_DEGRADED_REASON = "degraded-domain-reason"
        private const val ASSERT_COUNTERS_CONSISTENT = "dispatch-counters-consistent"

        /** 命令标识：栅栏查询与"查询不派发"查询各自连续编号，便于失败时定位是第几次。 */
        private const val REQUEST_FENCE_PREFIX = "e2e-health-fence"
        private const val REQUEST_DISPATCH_PREFIX = "e2e-health-dispatch"

        /** 指纹稳定性复检用的请求前缀。 */
        private const val REQUEST_DIGEST_PREFIX = "e2e-health-digest"

        /** `manifestDigest` 的长度（sha256 前 16 位十六进制）。 */
        private const val DIGEST_CHARS = 16

        /** `business_status` 的空 payload：不传 `domain` 即全量返回（路由不读其它字段）。 */
        private const val EMPTY_PAYLOAD = "{}"

        /**
         * 栅栏查询上限与间隔。
         *
         * 单域刷新是毫秒级，卡死域要跑满 `bridge.health.timeout-ms`（配置 300ms），
         * 因此 150ms × 60 ≈ 9s 的窗口远够；上限的作用是让"永远刷不出来"变成一次快速失败，
         * 而不是等满 fixture 证据超时。
         */
        private const val MAX_FENCE_QUERIES = 60
        private const val FENCE_RETRY_INTERVAL_MS = 150L

        /** 单条查询的回执等待上限：命令池只在极慢时才可能排队，10s 已经非常宽松。 */
        private const val QUERY_TIMEOUT_MS = 10_000L

        /** 「查询不派发」的连续查询次数（规格 §5 写 10 次）。 */
        private const val DISPATCH_PROBE_QUERIES = 10

        /** 证据表里的分隔符。 */
        private const val FAILED_NAME_SEPARATOR = ","

        /** 断言成立时写入的固定值；harness 侧按它判定该条断言通过。 */
        private const val ASSERT_PASSED = "true"

        /** 失败信息里回显的帧长度上限，避免把整段回执打进日志。 */
        private const val PREVIEW_CHARS = 512
    }
}
