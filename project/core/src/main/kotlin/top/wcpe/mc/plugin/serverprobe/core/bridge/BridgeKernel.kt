package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.Principal
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 桥内核：下行帧的解析与分发（线路治理规格 §3.2）。
 *
 * ## 唯一职责：把"读"和"做"分开
 * 桥读线程只调用 [onFrame]，本类**绝不阻塞、绝不抛**：
 * 解析 → 匹配路由 → 投池 → 立即返回。真正的执行在对应池上发生，回执由池线程发出。
 *
 * 改造前 `BridgeClient.handleCommand` 在读线程里同步 `future.get(5000ms)`，
 * 一个卡死的业务 Provider 会让读线程 5 秒不处理任何下行帧（含心跳），
 * 对端据此判定探针失联。这是本次重构要消灭的核心问题。
 *
 * ## 池饱和不得升级为断连
 * 三池都是有界 + `AbortPolicy`，所以投递可能被拒。**拒绝必须在这里就地消化**：
 * 命令类生成一条"通道饱和"失败回执、事件类计数、重载类生成失败回执。
 * 若让 [java.util.concurrent.RejectedExecutionException] 冒到读循环的 `catch (Exception)`，
 * 就会关连接 + 退避重连 —— 把"命令通道饱和"升级成"网桥掉线 + catchup 风暴"。
 *
 * ## 回执发送不得阻塞读线程
 * 饱和回执走 [FrameSender.offerReceipt]（非阻塞），不用 [FrameSender.submitReceipt]（有界阻塞 500ms）。
 * 读线程连续处理 100 条饱和命令时，后者会把读线程卡 50 秒，超过读超时。
 *
 * ## 可替换的解析器
 * [parse] 默认用 [Json.parse]（依赖运行期 nightconfig）。单测可注入纯逻辑解析器，
 * 从而在没有 nightconfig 的裸类路径上验证调度正确性——解析与调度本就是两件事。
 */
class BridgeKernel(
    private val sender: FrameSender,
    private val pools: BridgePools,
    /** 帧解析器：入参原始文本，出参命令载体；解析失败应返回 null。 */
    private val parse: (String) -> BridgeCommand? = ::parseCommand,
    /** 回执编码器：入参回执字段，出参完整帧文本；失败应返回 null。 */
    private val encodeReceipt: (String, BridgeCommandResult) -> String? = ::encodeCommandResult,
) {

    /** 下行路由，按 `order` 升序匹配。 */
    private val routes = ArrayList<InboundRoute>()

    private val inboundFrames = AtomicLong(0)
    private val parseErrors = AtomicLong(0)
    private val tooLarge = AtomicLong(0)
    private val saturated = AtomicLong(0)
    private val receiptEncodeFailures = AtomicLong(0)

    /** "无 requestId 可回写"的 WARN 去重标记（只告警一次，避免超大帧风暴刷屏）。 */
    private val missingRequestIdWarned = AtomicBoolean(false)

    /** "失败回执自身也被拒"的 WARN 去重标记；同时是"绝不重试"的守卫。 */
    private val rewriteRejectedWarned = AtomicBoolean(false)

    /** 注册一条下行路由;须在启动读循环前调用。 */
    fun addRoute(route: InboundRoute) {
        routes.add(route)
        routes.sortBy { it.order }
    }

    /**
     * 处理一帧下行文本（**读线程唯一入口**）。
     *
     * 本方法绝不阻塞、绝不抛异常。任何内部异常都就地吞掉并计数，
     * 否则会冒到读循环的兜底 catch 触发断连。
     *
     * @param raw 原始帧文本。
     */
    fun onFrame(raw: String) {
        inboundFrames.incrementAndGet()
        runCatching { dispatch(raw) }
            .onFailure {
                parseErrors.incrementAndGet()
                ProbeLogger.debug("桥下行帧处理失败,已忽略:${it.javaClass.simpleName}")
            }
    }

    /** 记录一次入站超限(由读循环在捕获 [FrameTooLargeException] 时调用)。 */
    fun onInboundTooLarge(payloadSkipped: Boolean) {
        tooLarge.incrementAndGet()
        ProbeLogger.warn("桥收到超限帧,已丢弃(payloadSkipped=$payloadSkipped)")
    }

    /** 内核状态(供 `bridge_status`)。 */
    fun status(): KernelStatus = KernelStatus(
        inboundFrames = inboundFrames.get(),
        parseErrors = parseErrors.get(),
        tooLarge = tooLarge.get(),
        saturated = saturated.get(),
        receiptEncodeFailures = receiptEncodeFailures.get(),
        routeNames = routes.map { it.javaClass.simpleName },
    )

    /** 解析并按路由分发。 */
    private fun dispatch(raw: String) {
        val command = parse(raw) ?: run {
            parseErrors.incrementAndGet()
            return
        }
        val route = routes.firstOrNull { runCatching { it.claims(command) }.getOrDefault(false) }
        if (route == null) {
            // 无路由命中:不阻塞读线程,直接投到命令池回一条"未实现"回执。
            submitToPool(
                kind = ExecutorKind.COMMAND,
                command = command,
                fallback = { BridgeCommandResult.fail("未识别的命令:${command.action}") },
            )
            return
        }
        submitToPool(
            kind = route.executor,
            command = command,
            fallback = { BridgeCommandResult.fail("通道饱和,命令未执行:${command.action}") },
            body = { route.handle(command) },
        )
    }

    /**
     * 把路由执行投递到对应池；被拒时就地生成失败回执（不抛、不断连）。
     *
     * @param body 实际执行体；为 null 表示"仅回一条兜底失败"。
     */
    private fun submitToPool(
        kind: ExecutorKind,
        command: BridgeCommand,
        fallback: () -> BridgeCommandResult,
        body: (() -> BridgeCommandResult)? = null,
    ) {
        val task = Runnable {
            val result = runCatching { body?.invoke() ?: fallback() }
                .getOrElse { BridgeCommandResult.fail("执行异常:${it.javaClass.simpleName}") }
            // 池线程可承受有界阻塞（submitReceipt 至多阻塞 receiptOfferBudgetMs），
            // 故允许它为这条回执短暂等待容量——命令回执是本桥最高优先级的流量。
            sendReceipt(command.requestId, result, fromReadThread = false)
        }
        if (!pools.submit(kind, task::run)) {
            saturated.incrementAndGet()
            // 池已饱和:不能再投递,只能由读线程就地回一条失败。用非阻塞入口。
            sendReceipt(command.requestId, fallback(), fromReadThread = true)
        }
    }

    /**
     * 发回执，并按 [SubmitOutcome] 处置两种硬失败（帧分片规格 §3.3）。
     *
     * 编码失败**不得让池线程死掉**：`encodeReceipt` 可能因运行期 JSON 库缺失等原因返回 null，
     * 此时只计数并放弃该条回执（JM 侧超时兜底），不能让 `NoClassDefFoundError` 冒出去。
     *
     * ## 为什么超限要在这里重写
     * 切分发生在 [FrameSender] 内，那里只有已序列化的 payload，拿不到 `BridgeCommandResult`，
     * 所以只能如实返回 [SubmitOutcome.TOO_LARGE]；**能拿到 `requestId` 的层是这里**，
     * 由命令池把回执改写成一条明确的失败回执再送出。
     *
     * @param fromReadThread true 表示当前在读线程上（只允许非阻塞入口）。
     */
    private fun sendReceipt(requestId: String, result: BridgeCommandResult, fromReadThread: Boolean) {
        val frame = buildReceiptFrame(requestId, result) ?: return
        val outcome = if (fromReadThread) sender.offerReceipt(frame) else sender.submitReceipt(frame)
        handleOutcome(requestId, outcome)
    }

    /**
     * 按提交结果降级：`TOO_LARGE` / `REJECTED` 各重写一条失败回执。
     *
     * 只有这两种"硬失败"需要回写；`QUEUED`/`FILTERED_OUT`/`DROPPED` 都无需命令侧介入
     * （`FILTERED_OUT` 不算失败，`DROPPED` 只发生在可丢事件上）。
     */
    private fun handleOutcome(requestId: String, outcome: SubmitOutcome) {
        when (outcome) {
            SubmitOutcome.TOO_LARGE -> rewriteAsFailure(requestId, REASON_TOO_LARGE)
            SubmitOutcome.REJECTED -> rewriteAsFailure(requestId, REASON_SATURATED)
            else -> Unit
        }
    }

    /**
     * 用原命令的 `requestId` 重写一条失败回执并送出。
     *
     * ## 绝不递归
     * 这条失败回执只有几十字节，不会再次触发分片；且它自己的结果**只计数 + WARN**，
     * 不再生成"失败回执的失败回执"。若这里也走 [handleOutcome]，一次通道饱和会变成无限循环。
     *
     * @param reason 回写给 JM 的失败原因（同时决定日志口径）。
     */
    private fun rewriteAsFailure(requestId: String, reason: String) {
        if (requestId.isEmpty()) {
            // 纯事件路径：没有 requestId 可回写，只计数（由发送器并入既有计数）+ WARN 一次（去重）。
            if (missingRequestIdWarned.compareAndSet(false, true)) {
                ProbeLogger.warn("桥上行帧超大或通道饱和,但该帧没有 requestId,无法回写失败回执(后续同类不再重复告警)")
            }
            return
        }
        val frame = buildReceiptFrame(requestId, BridgeCommandResult.fail(reason)) ?: return
        val retryOutcome = sender.offerReceipt(frame)
        if (retryOutcome == SubmitOutcome.REJECTED || retryOutcome == SubmitOutcome.TOO_LARGE) {
            if (rewriteRejectedWarned.compareAndSet(false, true)) {
                ProbeLogger.warn("桥失败回执再次被拒($retryOutcome),已放弃且不再重试(requestId=$requestId),由 JM 侧超时兜底")
            }
        }
    }

    /**
     * 编码一帧回执；编码失败只计数并返回 null。
     *
     * @return 可提交的帧；编码失败返回 null。
     */
    private fun buildReceiptFrame(requestId: String, result: BridgeCommandResult): OutboundFrame? {
        val payload = runCatching { encodeReceipt(requestId, result) }.getOrNull()
        if (payload == null) {
            receiptEncodeFailures.incrementAndGet()
            ProbeLogger.debug("桥回执编码失败,已放弃该条回执(requestId=$requestId)")
            return null
        }
        return OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = OutboundFrame.TYPE_COMMAND_RESULT,
            payload = payload,
            priority = OutboundFrame.Priority.COMMAND_RESULT,
            requestId = requestId,
        )
    }

    private companion object {

        /** 下行命令帧类型。 */
        const val TYPE_COMMAND = "command"

        /** 下行帧内承载操作者上下文的字段名。 */
        const val FIELD_PRINCIPAL = "principal"

        /** 回执超大（无法分片传输）时回写给 JM 的失败原因。 */
        const val REASON_TOO_LARGE = "回执过大无法分片传输"

        /** 回执通道饱和时回写给 JM 的失败原因。 */
        const val REASON_SATURATED = "回执通道饱和"

        /**
         * 默认帧解析器：只接受 `type=command` 的帧，其余返回 null（由 [dispatch] 计为解析错误）。
         *
         * 依赖运行期 nightconfig；单测通过构造参数替换为纯逻辑解析器。
         */
        fun parseCommand(raw: String): BridgeCommand? {
            val frame = Json.parse(raw)
            if (frame.getString("type") != TYPE_COMMAND) return null
            return BridgeCommand(
                action = frame.getString("action"),
                target = frame.getString("target"),
                reason = frame.getString("reason"),
                requestId = frame.getString("requestId"),
                domain = frame.getString("domain"),
                payload = frame.getString("payloadJson"),
                principal = parsePrincipal(frame.getObject(FIELD_PRINCIPAL)),
            )
        }

        /**
         * 解析操作者上下文。
         *
         * 帧上没有 `principal` 段时返回 [Principal.unknown]——治理命令与旧 JM 就是这样，
         * 不是错误。字段缺失或类型不符时逐字段回退到默认值，**绝不抛**：
         * 解析失败会让整条命令被判为坏帧，代价远大于"这次审计少一个字段"。
         */
        private fun parsePrincipal(node: JsonObject?): Principal {
            if (node == null) return Principal.unknown()
            return Principal(
                node.getString(Principal.FIELD_OPERATOR),
                node.getLong(Principal.FIELD_OPERATOR_ID),
                node.getString(Principal.FIELD_NODE_ID),
                node.getString(Principal.FIELD_REASON),
            )
        }

        /**
         * 默认回执编码器。
         *
         * 形态与 Worker 侧约定一致：`type=event` + `event=command_result`，
         * 结构化字段放在 `data` 内（`requestId`/`success`/`output`/`error`）。
         */
        fun encodeCommandResult(requestId: String, result: BridgeCommandResult): String = Json.encode(
            linkedMapOf(
                "type" to OutboundFrame.TYPE_EVENT,
                "event" to OutboundFrame.TYPE_COMMAND_RESULT,
                "data" to linkedMapOf(
                    "requestId" to requestId,
                    "success" to result.success,
                    "output" to result.output,
                    "error" to result.error,
                ),
            ),
        )
    }
}

/**
 * 内核状态快照。
 *
 * @property inboundFrames 累计处理的入站帧数。
 * @property parseErrors 解析失败数。
 * @property tooLarge 超限丢帧数。
 * @property saturated 池饱和导致的就地降级次数。
 * @property receiptEncodeFailures 回执编码失败次数（运行期 JSON 库异常等）。
 * @property routeNames 已注册的路由类名(诊断用)。
 */
data class KernelStatus(
    val inboundFrames: Long,
    val parseErrors: Long,
    val tooLarge: Long,
    val saturated: Long,
    val receiptEncodeFailures: Long,
    val routeNames: List<String>,
)
