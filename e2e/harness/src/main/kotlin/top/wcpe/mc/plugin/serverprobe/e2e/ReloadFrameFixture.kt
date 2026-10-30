package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅用于（配置重载通知）的回环 fixture。
 *
 * ## 判据来源
 * `docs/specs/config-reload.md` §5 的「成功 / 无人认领 / 插件拒绝 / 部分成功 / 越界」五行，
 * 加上 §3.7 的回执形态与唯一权威判定式 `success = failed.isEmpty() && error.isEmpty()`。
 * 最核心的一条是 §5「成功」的末半句：**监听器调用计数 +1**——
 * 探针完全可以不调用任何监听器就把 `reloaded` 填上，所以只断言回执是不够的，
 * 计数必须来自监听器自身（见 [ReloadFixtureState]）。
 *
 * ## 独立状态机
 * 与 [ProtocolWorkerFixture]（按 Stage 校验回执）、[ChunkFrameFixture]（按 groupId 重组）、
 * [TopicFrameFixture]（按 topic 过滤）、[HealthFrameFixture]（按域区分呈现）一样，
 * 共用 [WebSocketFixtureServer] 的连接与帧编解码，但状态机各写各的：
 * 本场景是"顺序下发五条 `core.config_reload` + 逐条核对回执并读取监听器计数"。
 *
 * ## 为什么断言在独立 driver 线程上做
 * 读线程只负责"收帧 + 入队"，绝不做等待或断言：命令是串行的，若在读线程上等待，
 * 就会阻塞后续帧的接收，把一次确定的违约变成一次超时（失败信息还会丢掉原始帧）。
 *
 * ## 为什么"部分成功"与"插件拒绝"合成一条命令
 * §3.7 明确"任一失败 → 整体失败，但 `reloaded` 仍如实列出成功的部分"，
 * 而 `ConfigReloadRoute` 在失败时用 `BridgeCommandResult.ofFailure(output, error)` 回帧——
 * **`output` 仍是完整回执 JSON**。把一条成功路径和一条失败路径放进同一次请求，
 * 才能同时验证"失败不吞掉成功项"与"失败仍带结构化回执"这两件事。
 */
class ReloadFrameFixture private constructor(
    endpoint: FixtureEndpoint,
    /** 监听器侧的观测点：与 harness 注册的三个监听器共享同一实例。 */
    private val state: ReloadFixtureState,
) : WebSocketFixtureServer(endpoint.serverSocket, endpoint.token, "serverprobe-e2e-reload-fixture") {

    /** hello 只接受一次，且必须带本次场景的实例标识。 */
    private val helloSeen = AtomicBoolean(false)

    /** harness 已注册三个监听器，允许 fixture 下发重载命令。 */
    private val commandsOpen = AtomicBoolean(false)

    /** driver 只启动一次（hello 与 openCommands 谁后到都由它触发）。 */
    private val driverStarted = AtomicBoolean(false)

    /**
     * 回执队列：协议读线程只入队，断言全部在 driver 线程上做。
     *
     * 容量不设限：命令严格串行（同一时刻至多一条在途），正常路径队列长度恒为 0/1；
     * 设上限只会让"探针意外多发一条回执"变成阻塞读线程的隐患。
     */
    private val receipts = LinkedBlockingQueue<String>()

    /** 监听器注册完成后由 harness 调用，随后开始顺序下发重载命令。 */
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

    /** hello 与监听器注册都就绪后才开始下发，避免"监听器还没注册就问"的空回执。 */
    private fun maybeStartDriver() {
        if (!helloSeen.get() || !commandsOpen.get()) return
        if (!driverStarted.compareAndSet(false, true)) return
        Thread({ runCatching { drive() }.onFailure { evidence.completeExceptionally(it) } }, "serverprobe-e2e-reload-driver")
            .apply { isDaemon = true }
            .start()
    }

    /** 驱动全流程：成功 → 无人认领 → 自报字段 → 部分成功（失败仍带回执）→ 越界 → 累计计数。 */
    private fun drive() {
        val assertions = linkedMapOf<String, String>()
        verifySuccess(assertions)
        verifyUnclaimed(assertions)
        verifyPartialApplied(assertions)
        verifyPartialSuccess(assertions)
        verifyPathEscape(assertions)
        verifyListenerTotals(assertions)
        completeEvidence(
            assertions + mapOf(
                "reloadTimeoutMs" to ReloadFixturePayload.TIMEOUT_MS.toString(),
                "exactPath" to ReloadFixturePayload.EXACT_PATH,
                "prefix" to ReloadFixturePayload.PREFIX,
                "unclaimedPath" to ReloadFixturePayload.UNCLAIMED_PATH,
                "partialPath" to ReloadFixturePayload.PARTIAL_PATH,
                "rejectPath" to ReloadFixturePayload.REJECT_PATH,
                "escapePath" to ReloadFixturePayload.ESCAPE_PATH,
            ),
        )
    }

    /**
     * 成功路径（规格 §5「成功」）。
     *
     * 除回执字段外，额外断言**两个监听器各被调用一次**：一条路径同时被"精确声明"和
     * "目录前缀声明"命中，二者都必须收到通知（§3.4 的匹配语义），且一次命令对每个监听器
     * 只回调一次（§3.1：一次回调覆盖它的全部 `matchedPaths`）。
     */
    private fun verifySuccess(assertions: MutableMap<String, String>) {
        val before = state.snapshot()
        val reply = reload(ReloadFixturePayload.REQUEST_OK, listOf(ReloadFixturePayload.EXACT_PATH))
        val after = state.snapshot()

        check(jsonBoolean(reply.frame, FIELD_SUCCESS) == true) {
            "帧信封 success 不为 true:error=${jsonString(reply.frame, FIELD_ERROR)}"
        }
        record(assertions, ASSERT_SUCCESS_FRAME_SUCCESS)

        check(jsonBoolean(reply.receipt, FIELD_SUCCESS) == true) {
            "回执 success 不为 true:${preview(reply.receipt)}"
        }
        record(assertions, ASSERT_SUCCESS_RECEIPT_SUCCESS)

        val reloaded = jsonStringArray(reply.receipt, FIELD_RELOADED)
        check(reloaded == listOf(ReloadFixturePayload.EXACT_PATH)) { "reloaded 与期望不一致:实际 $reloaded" }
        record(assertions, ASSERT_SUCCESS_RELOADED, reloaded.joinToString(SEPARATOR))

        val failed = failuresIn(reply.receipt)
        check(failed.isEmpty()) { "failed 应为空:实际 $failed" }
        record(assertions, ASSERT_SUCCESS_FAILED_EMPTY)

        val receiptError = jsonString(reply.receipt, FIELD_ERROR).orEmpty()
        check(receiptError.isEmpty()) { "error 应为空:实际 $receiptError" }
        record(assertions, ASSERT_SUCCESS_ERROR_EMPTY)

        // costMs 必须是**存在的数字**且非负：缺失会被 jsonLongField 返回 null，负数会被原样读出。
        val costMs = checkNotNull(jsonLongField(reply.receipt, FIELD_COST_MS)) { "回执缺少数字型 costMs" }
        check(costMs >= 0L) { "costMs 为负:$costMs" }
        record(assertions, ASSERT_SUCCESS_COST_MS, costMs.toString())

        val delta = after.deltaFrom(before)
        check(delta.exact == 1) { "精确路径监听器应恰好被调用 1 次:实际 ${delta.exact}" }
        record(assertions, ASSERT_SUCCESS_EXACT_LISTENER_CALLED, delta.exact.toString())
        check(delta.prefix == 1) { "目录前缀监听器应恰好被调用 1 次:实际 ${delta.prefix}" }
        record(assertions, ASSERT_SUCCESS_PREFIX_LISTENER_CALLED, delta.prefix.toString())

        val exactMatched = state.exactLastMatched
        check(exactMatched == listOf(ReloadFixturePayload.EXACT_PATH)) {
            "精确监听器收到的 matchedPaths 与声明不一致:$exactMatched"
        }
        record(assertions, ASSERT_SUCCESS_EXACT_MATCHED_PATHS, exactMatched.joinToString(SEPARATOR))

        verifyApplied(assertions, reply.receipt)
    }

    /** `applied` 三个自报字段必须原样透传（§3.7）。 */
    private fun verifyApplied(assertions: MutableMap<String, String>, receipt: String) {
        val applied = appliedOf(receipt, ReloadFixturePayload.EXACT_PATH)
        val md5 = jsonString(applied, FIELD_MD5)
        check(md5 == ReloadFixturePayload.MD5) { "applied.md5 未原样透传:实际 $md5" }
        record(assertions, ASSERT_SUCCESS_APPLIED_MD5, md5)

        val version = jsonString(applied, FIELD_VERSION)
        check(version == ReloadFixturePayload.VERSION) { "applied.version 未原样透传:实际 $version" }
        record(assertions, ASSERT_SUCCESS_APPLIED_VERSION, version)

        val count = jsonInt(applied, FIELD_COUNT)
        check(count == ReloadFixturePayload.COUNT) { "applied.count 未原样透传:实际 $count" }
        record(assertions, ASSERT_SUCCESS_APPLIED_COUNT, count.toString())
    }

    /**
     * 无人认领路径（规格 §5「无人认领」）：进 `bestEffort`，`reloaded` 与 `failed` 均空，
     * 整体仍 `success=true`，且**任何监听器都不该被调用**。
     */
    private fun verifyUnclaimed(assertions: MutableMap<String, String>) {
        val before = state.snapshot()
        val reply = reload(ReloadFixturePayload.REQUEST_UNCLAIMED, listOf(ReloadFixturePayload.UNCLAIMED_PATH))
        val after = state.snapshot()

        check(jsonBoolean(reply.receipt, FIELD_SUCCESS) == true) { "无人认领应仍算成功:${preview(reply.receipt)}" }
        record(assertions, ASSERT_UNCLAIMED_SUCCESS)

        val bestEffort = jsonStringArray(reply.receipt, FIELD_BEST_EFFORT)
        check(bestEffort == listOf(ReloadFixturePayload.UNCLAIMED_PATH)) { "bestEffort 与期望不一致:实际 $bestEffort" }
        record(assertions, ASSERT_UNCLAIMED_BEST_EFFORT, bestEffort.joinToString(SEPARATOR))

        val reloaded = jsonStringArray(reply.receipt, FIELD_RELOADED)
        check(reloaded.isEmpty()) { "无人认领时 reloaded 应为空:实际 $reloaded" }
        record(assertions, ASSERT_UNCLAIMED_RELOADED_EMPTY)

        val failed = failuresIn(reply.receipt)
        check(failed.isEmpty()) { "无人认领时 failed 应为空:实际 $failed" }
        record(assertions, ASSERT_UNCLAIMED_FAILED_EMPTY)

        val delta = after.deltaFrom(before)
        check(delta == ZERO_DELTA) { "无人认领路径不该调用任何监听器:实际 $delta" }
        record(assertions, ASSERT_UNCLAIMED_LISTENERS_NOT_CALLED, delta.toString())
    }

    /**
     * 自报字段的"缺席"语义（§3.7）：`count=0` 原样出现，未上报的 `md5`/`version`
     * **整个键都不出现**——写 0 或空串都会让 JM 把"未上报"误读成"上报了 0"。
     */
    private fun verifyPartialApplied(assertions: MutableMap<String, String>) {
        val reply = reload(ReloadFixturePayload.REQUEST_PARTIAL_APPLIED, listOf(ReloadFixturePayload.PARTIAL_PATH))

        check(jsonBoolean(reply.receipt, FIELD_SUCCESS) == true) { "自报字段场景应成功:${preview(reply.receipt)}" }
        val reloaded = jsonStringArray(reply.receipt, FIELD_RELOADED)
        check(reloaded == listOf(ReloadFixturePayload.PARTIAL_PATH)) { "reloaded 与期望不一致:实际 $reloaded" }
        record(assertions, ASSERT_PARTIAL_APPLIED_RELOADED, reloaded.joinToString(SEPARATOR))

        val applied = appliedOf(reply.receipt, ReloadFixturePayload.PARTIAL_PATH)
        check(jsonHasField(applied, FIELD_COUNT)) { "applied 缺少 count 键:$applied" }
        val count = jsonInt(applied, FIELD_COUNT)
        check(count == ReloadFixturePayload.PARTIAL_COUNT) {
            "count=0 是合法事实,必须原样出现:实际 $count"
        }
        record(assertions, ASSERT_PARTIAL_APPLIED_COUNT_ZERO, count.toString())

        check(!jsonHasField(applied, FIELD_MD5)) { "未上报的 md5 不应出现:$applied" }
        record(assertions, ASSERT_PARTIAL_APPLIED_MD5_ABSENT)
        check(!jsonHasField(applied, FIELD_VERSION)) { "未上报的 version 不应出现:$applied" }
        record(assertions, ASSERT_PARTIAL_APPLIED_VERSION_ABSENT)
    }

    /**
     * 部分成功（规格 §5「部分成功」+ §3.7 "reloaded 仍如实列出成功项"）。
     *
     * 这是本场景唯一覆盖 `ofFailure` 的用例：`ConfigReloadRoute` 在失败时把**完整回执 JSON**
     * 放进 `output`、把可读摘要放进 `error`。若退回 `fail(...)`，`output` 会变成空串，
     * 机器可读明细整段丢失——因此这里逐项核对回执的七个字段是否都在。
     */
    private fun verifyPartialSuccess(assertions: MutableMap<String, String>) {
        val before = state.snapshot()
        val reply = reload(
            ReloadFixturePayload.REQUEST_PARTIAL_SUCCESS,
            listOf(ReloadFixturePayload.EXACT_PATH, ReloadFixturePayload.REJECT_PATH),
        )
        val after = state.snapshot()

        check(jsonBoolean(reply.frame, FIELD_SUCCESS) == false) { "部分成功时帧信封 success 应为 false" }
        record(assertions, ASSERT_PARTIAL_SUCCESS_FRAME_FAILURE)

        check(jsonBoolean(reply.receipt, FIELD_SUCCESS) == false) {
            "部分成功时回执 success 应为 false(§3.7 严格口径):${preview(reply.receipt)}"
        }
        record(assertions, ASSERT_PARTIAL_SUCCESS_RECEIPT_FAILURE)

        val failed = failuresIn(reply.receipt)
        check(failed.size == 1) { "failed 应恰好 1 条:实际 $failed" }
        check(failed[0].path == ReloadFixturePayload.REJECT_PATH) { "failed[0].path 不匹配:${failed[0].path}" }
        check(failed[0].code == ReloadFixturePayload.CODE_PLUGIN_REJECTED) { "failed[0].code 不匹配:${failed[0].code}" }
        check(failed[0].message == ReloadFixturePayload.REJECT_MESSAGE) { "failed[0].message 未原样透出:${failed[0].message}" }
        record(assertions, ASSERT_PARTIAL_SUCCESS_FAILED_CODE, "${failed[0].path}=${failed[0].code}")

        val reloaded = jsonStringArray(reply.receipt, FIELD_RELOADED)
        check(reloaded == listOf(ReloadFixturePayload.EXACT_PATH)) {
            "失败不得吞掉成功项:reloaded 实际 $reloaded"
        }
        record(assertions, ASSERT_PARTIAL_SUCCESS_RELOADED_KEPT, reloaded.joinToString(SEPARATOR))

        val receiptError = jsonString(reply.receipt, FIELD_ERROR).orEmpty()
        check(receiptError.isEmpty()) { "路径级失败不该写请求级 error:实际 $receiptError" }
        record(assertions, ASSERT_PARTIAL_SUCCESS_ERROR_EMPTY)

        // `output` 必须是**完整回执**：七个字段一个不缺（`ofFailure` 修复的判据）。
        val missing = RECEIPT_FIELDS.filterNot { jsonHasField(reply.receipt, it) }
        check(missing.isEmpty()) { "失败回执缺少字段(说明 output 被丢掉了):$missing" }
        record(assertions, ASSERT_PARTIAL_SUCCESS_OUTPUT_COMPLETE, RECEIPT_FIELDS.joinToString(SEPARATOR))

        val frameError = jsonString(reply.frame, FIELD_ERROR).orEmpty()
        check(frameError.isNotEmpty()) { "帧信封 error 应带可读摘要" }
        record(assertions, ASSERT_PARTIAL_SUCCESS_FRAME_ERROR, frameError.take(PREVIEW_CHARS))

        check(jsonObjectAt(reply.receipt, ReloadFixturePayload.REJECT_PATH) == null) {
            "失败路径不应出现在 applied 里(§3.7:applied 只随 reloaded 上报)"
        }
        record(assertions, ASSERT_PARTIAL_SUCCESS_APPLIED_EXCLUDES_FAILED)

        val delta = after.deltaFrom(before)
        check(delta.exact == 1) { "精确监听器应被调用 1 次:实际 ${delta.exact}" }
        check(delta.prefix == 1) { "前缀监听器应被调用 1 次(一条事件覆盖两条命中路径):实际 ${delta.prefix}" }
        check(delta.reject == 1) { "拒绝监听器应被调用 1 次:实际 ${delta.reject}" }
        record(assertions, ASSERT_PARTIAL_SUCCESS_LISTENER_DELTAS, delta.toString())

        val expectedMatched = listOf(ReloadFixturePayload.EXACT_PATH, ReloadFixturePayload.REJECT_PATH).sorted()
        check(state.prefixLastMatched == expectedMatched) {
            "前缀监听器的 matchedPaths 应覆盖两条路径:实际 ${state.prefixLastMatched}"
        }
        record(assertions, ASSERT_PARTIAL_SUCCESS_PREFIX_MATCHED_PATHS, state.prefixLastMatched.joinToString(SEPARATOR))

        check(!state.prefixLastFullReload) { "按路径重载不得被标记成全量" }
        record(assertions, ASSERT_PARTIAL_SUCCESS_NOT_FULL_RELOAD)
    }

    /**
     * 路径越界（规格 §5「越界」）：规范化阶段就拒绝（§3.1 步骤 1），
     * 因此**监听器一次都不被调用**，回执里保留原始输入便于 JM 对账（§3.3 注）。
     */
    private fun verifyPathEscape(assertions: MutableMap<String, String>) {
        val before = state.snapshot()
        val reply = reload(ReloadFixturePayload.REQUEST_ESCAPE, listOf(ReloadFixturePayload.ESCAPE_PATH))
        val after = state.snapshot()

        check(jsonBoolean(reply.receipt, FIELD_SUCCESS) == false) { "越界请求应整体失败" }
        record(assertions, ASSERT_ESCAPE_SUCCESS_FALSE)

        val failed = failuresIn(reply.receipt)
        check(failed.size == 1) { "越界应恰好 1 条失败:实际 $failed" }
        check(failed[0].code == ReloadFixturePayload.CODE_PATH_ESCAPE) { "越界错误码不匹配:${failed[0].code}" }
        check(failed[0].path == ReloadFixturePayload.ESCAPE_PATH) { "越界项应回显原始输入:${failed[0].path}" }
        record(assertions, ASSERT_ESCAPE_FAILED_CODE, "${failed[0].path}=${failed[0].code}")

        val delta = after.deltaFrom(before)
        check(delta == ZERO_DELTA) { "越界路径不得通知任何监听器:实际 $delta" }
        record(assertions, ASSERT_ESCAPE_LISTENERS_NOT_CALLED, delta.toString())
    }

    /**
     * 累计调用次数：证明整段流程里"没有任何多余调用"。
     *
     * 逐项对照见 [ReloadFixtureState.EXPECTED_CALLS] 的推导注释——它同时固化了两条语义：
     * 精确与前缀在成功路径上各命中一次；一条事件覆盖两条命中路径只算一次回调。
     */
    private fun verifyListenerTotals(assertions: MutableMap<String, String>) {
        val totals = state.snapshot()
        check(totals == ReloadFixtureState.EXPECTED_CALLS) {
            "监听器累计调用次数不符:期望 ${ReloadFixtureState.EXPECTED_CALLS},实际 $totals"
        }
        record(assertions, ASSERT_LISTENER_TOTALS, totals.toString())
    }

    /**
     * 下发一条 `core.config_reload` 并取回帧信封与其中携带的回执 JSON。
     *
     * 只在此处收口"requestId 对得上、`output` 存在"两条通用校验，
     * 帧级 `success` 的期望值逐场景不同（部分成功与越界都必须是 false），故交给各断言各自判定。
     */
    private fun reload(requestId: String, paths: List<String>): ReloadReply {
        sendCommand(requestId, payloadOf(paths))
        val frame = receipts.poll(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: error("${QUERY_TIMEOUT_MS}ms 内未收到 config_reload 回执:$requestId")
        check(jsonString(frame, FIELD_REQUEST_ID) == requestId) {
            "回执 requestId 不匹配:期望 $requestId,实际 ${jsonString(frame, FIELD_REQUEST_ID)}"
        }
        val output = checkNotNull(jsonString(frame, FIELD_OUTPUT)) {
            "回执缺少 output(失败时也必须带完整回执 JSON):${preview(frame)}"
        }
        return ReloadReply(frame, output)
    }

    /** `payload={"paths":[...]}`（§3.1）；空列表表示全量，本场景不使用。 */
    private fun payloadOf(paths: List<String>): String =
        "{\"paths\":[" + paths.joinToString(",") { "\"${escapeJson(it)}\"" } + "]}"

    /** 取回执里某条路径的自报子对象；不存在即失败（调用点都已确认该路径成功）。 */
    private fun appliedOf(receipt: String, path: String): String {
        val applied = checkNotNull(jsonObjectAt(receipt, FIELD_APPLIED)) { "回执缺少 applied 对象" }
        return checkNotNull(jsonObjectAt(applied, path)) { "applied 缺少路径 $path:实际 $applied" }
    }

    /** 解析 `failed[]`；数组缺失即失败（§3.7 要求该键恒在）。 */
    private fun failuresIn(receipt: String): List<FailureItem> {
        val array = checkNotNull(jsonArrayAt(receipt, FIELD_FAILED)) { "回执缺少 failed 数组" }
        return jsonObjectsIn(array).map { item ->
            FailureItem(
                path = jsonString(item, FIELD_PATH).orEmpty(),
                code = jsonString(item, FIELD_CODE).orEmpty(),
                message = jsonString(item, FIELD_MESSAGE).orEmpty(),
            )
        }
    }

    /** 组装与 BridgeClient 当前协议形态一致的 command 文本帧。 */
    private fun sendCommand(requestId: String, payload: String) {
        sendText(
            "{\"type\":\"command\",\"action\":\"${ReloadFixturePayload.ACTION_CONFIG_RELOAD}\",\"target\":\"\",\"reason\":\"\"," +
                "\"requestId\":\"$requestId\",\"domain\":\"${ReloadFixturePayload.DOMAIN_CORE}\"," +
                "\"payloadJson\":\"${escapeJson(payload)}\"}",
        )
    }

    /**
     * 登记一条已成立的断言（失败路径已在 `check` 处抛出，绝不会走到这里）。
     *
     * 两个键都写进证据表：
     * - `assert.<名字>=true` → harness 会把它转登成结果文件里的断言，PASS 因此是逐项汇总的；
     * - `<名字>=<detail>` → 纯证据，供人核对实际值（如 `costMs`、调用次数）。
     */
    private fun record(assertions: MutableMap<String, String>, name: String, detail: String = "") {
        assertions[ReloadFixturePayload.ASSERT_PREFIX + name] = ASSERT_PASSED
        if (detail.isNotEmpty()) assertions[name] = detail
    }

    /** 失败信息里回显的回执长度上限，避免把整段回执打进日志。 */
    private fun preview(text: String): String = text.take(PREVIEW_CHARS)

    companion object {

        /** 从启动前生成的测试配置读取一次性凭据，并在回环端口启动配置重载 fixture。 */
        internal fun startFromProbeConfiguration(pluginsDirectory: File, state: ReloadFixtureState): ReloadFrameFixture =
            ReloadFrameFixture(openFixtureEndpoint(pluginsDirectory, ""), state)

        /** 帧类型：事件（与 `OutboundFrame.TYPE_EVENT` 一致）。 */
        private const val EVENT_COMMAND_RESULT = "command_result"

        /** 帧信封与回执字段名（§3.7 与 command_result 信封）。 */
        private const val FIELD_REQUEST_ID = "requestId"
        private const val FIELD_SUCCESS = "success"
        private const val FIELD_OUTPUT = "output"
        private const val FIELD_ERROR = "error"
        private const val FIELD_RELOADED = "reloaded"
        private const val FIELD_BEST_EFFORT = "bestEffort"
        private const val FIELD_FAILED = "failed"
        private const val FIELD_APPLIED = "applied"
        private const val FIELD_COST_MS = "costMs"
        private const val FIELD_PATH = "path"
        private const val FIELD_CODE = "code"
        private const val FIELD_MESSAGE = "message"
        private const val FIELD_MD5 = "md5"
        private const val FIELD_VERSION = "version"
        private const val FIELD_COUNT = "count"

        /** §3.7 回执的七个字段：失败回执也必须齐全（否则说明 output 被 `fail(...)` 丢掉了）。 */
        private val RECEIPT_FIELDS = listOf(
            FIELD_SUCCESS,
            FIELD_RELOADED,
            FIELD_BEST_EFFORT,
            FIELD_FAILED,
            FIELD_APPLIED,
            FIELD_COST_MS,
            FIELD_ERROR,
        )

        /** 逐项断言名（结果文件里以 [ReloadFixturePayload.ASSERT_PREFIX] 为前缀）。 */
        private const val ASSERT_SUCCESS_FRAME_SUCCESS = "success-frame-success"
        private const val ASSERT_SUCCESS_RECEIPT_SUCCESS = "success-receipt-success"
        private const val ASSERT_SUCCESS_RELOADED = "success-reloaded-contains-path"
        private const val ASSERT_SUCCESS_FAILED_EMPTY = "success-failed-empty"
        private const val ASSERT_SUCCESS_ERROR_EMPTY = "success-error-empty"
        private const val ASSERT_SUCCESS_COST_MS = "success-cost-ms-non-negative"
        private const val ASSERT_SUCCESS_EXACT_LISTENER_CALLED = "success-exact-listener-called"
        private const val ASSERT_SUCCESS_PREFIX_LISTENER_CALLED = "success-prefix-listener-called"
        private const val ASSERT_SUCCESS_EXACT_MATCHED_PATHS = "success-exact-matched-paths"
        private const val ASSERT_SUCCESS_APPLIED_MD5 = "success-applied-md5"
        private const val ASSERT_SUCCESS_APPLIED_VERSION = "success-applied-version"
        private const val ASSERT_SUCCESS_APPLIED_COUNT = "success-applied-count"
        private const val ASSERT_UNCLAIMED_SUCCESS = "unclaimed-success-true"
        private const val ASSERT_UNCLAIMED_BEST_EFFORT = "unclaimed-best-effort-contains-path"
        private const val ASSERT_UNCLAIMED_RELOADED_EMPTY = "unclaimed-reloaded-empty"
        private const val ASSERT_UNCLAIMED_FAILED_EMPTY = "unclaimed-failed-empty"
        private const val ASSERT_UNCLAIMED_LISTENERS_NOT_CALLED = "unclaimed-listeners-not-called"
        private const val ASSERT_PARTIAL_APPLIED_RELOADED = "partial-applied-reloaded"
        private const val ASSERT_PARTIAL_APPLIED_COUNT_ZERO = "partial-applied-count-zero"
        private const val ASSERT_PARTIAL_APPLIED_MD5_ABSENT = "partial-applied-md5-absent"
        private const val ASSERT_PARTIAL_APPLIED_VERSION_ABSENT = "partial-applied-version-absent"
        private const val ASSERT_PARTIAL_SUCCESS_FRAME_FAILURE = "partial-success-frame-failure"
        private const val ASSERT_PARTIAL_SUCCESS_RECEIPT_FAILURE = "partial-success-receipt-failure"
        private const val ASSERT_PARTIAL_SUCCESS_FAILED_CODE = "partial-success-failed-code"
        private const val ASSERT_PARTIAL_SUCCESS_RELOADED_KEPT = "partial-success-reloaded-kept"
        private const val ASSERT_PARTIAL_SUCCESS_ERROR_EMPTY = "partial-success-error-empty"
        private const val ASSERT_PARTIAL_SUCCESS_OUTPUT_COMPLETE = "partial-success-output-complete"
        private const val ASSERT_PARTIAL_SUCCESS_FRAME_ERROR = "partial-success-frame-error"
        private const val ASSERT_PARTIAL_SUCCESS_APPLIED_EXCLUDES_FAILED = "partial-success-applied-excludes-failed"
        private const val ASSERT_PARTIAL_SUCCESS_LISTENER_DELTAS = "partial-success-listener-deltas"
        private const val ASSERT_PARTIAL_SUCCESS_PREFIX_MATCHED_PATHS = "partial-success-prefix-matched-paths"
        private const val ASSERT_PARTIAL_SUCCESS_NOT_FULL_RELOAD = "partial-success-not-full-reload"
        private const val ASSERT_ESCAPE_SUCCESS_FALSE = "escape-success-false"
        private const val ASSERT_ESCAPE_FAILED_CODE = "escape-failed-code"
        private const val ASSERT_ESCAPE_LISTENERS_NOT_CALLED = "escape-listeners-not-called"
        private const val ASSERT_LISTENER_TOTALS = "listener-totals"

        /** 单条命令的回执等待上限：重载预算 [ReloadFixturePayload.TIMEOUT_MS] 远小于它，失败即超时。 */
        private const val QUERY_TIMEOUT_MS = 15_000L

        /** 证据表里的分隔符。 */
        private const val SEPARATOR = ","

        /** 断言成立时写入的固定值；harness 侧按它判定该条断言通过。 */
        private const val ASSERT_PASSED = "true"

        /** 零增量：任何一条路径只要被通知到，这个值就会变。 */
        private val ZERO_DELTA = ReloadFixtureState.Counts(exact = 0, prefix = 0, reject = 0)

        /** 失败信息里回显的文本长度上限。 */
        private const val PREVIEW_CHARS = 512
    }
}

/** 一条 `config_reload` 回执的原始两段文本：帧信封与其中携带的回执 JSON。 */
private class ReloadReply(val frame: String, val receipt: String)

/** `failed[]` 里的一条失败项（§3.7）。 */
private class FailureItem(val path: String, val code: String, val message: String) {
    override fun toString(): String = "$path=$code($message)"
}
