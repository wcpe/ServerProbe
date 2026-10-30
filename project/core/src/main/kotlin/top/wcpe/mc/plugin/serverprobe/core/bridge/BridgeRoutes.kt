package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.SubscriptionApplyResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicSubscriptionRegistry
import top.wcpe.mc.plugin.serverprobe.core.json.Json

/**
 * `core.bridge_status` 路由（线路治理规格 §3.5 的权威 schema）。
 *
 * 只读内存快照，不做 IO、不调 Provider，故可在命令池上安全执行。
 * 连接相关字段由 [snapshot] 提供（批次 1 会补 `reconnects`/`lastDisconnectReason` 的真实语义）。
 *
 * @param sender 发送器状态来源。
 * @param kernel 内核状态来源。
 * @param topics 当前生效订阅表达式的来源（按字典序、已去重）。必须与 `hello.subscriptions`
 *   **同源**（同一个注册表快照），否则两处会各拼一套值。
 * @param encode JSON 序列化器；单测注入纯文本实现以脱离运行期 JSON 库。
 * @param snapshot 连接状态快照提供者。
 */
class BridgeStatusRoute(
    private val sender: FrameSender,
    private val kernel: BridgeKernel,
    private val topics: () -> List<String> = { emptyList() },
    private val encode: (Map<String, Any?>) -> String = Json::encode,
    private val snapshot: () -> BridgeLinkSnapshot,
) : InboundRoute {

    override val order: Int = ORDER

    override val executor: ExecutorKind = ExecutorKind.COMMAND

    override fun claims(command: BridgeCommand): Boolean =
        command.action == ACTION && (command.domain.isEmpty() || command.domain == DOMAIN_CORE)

    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val link = snapshot()
        val send = sender.status()
        val core = kernel.status()
        // 一次取快照：`count` 与 `expressions` 必须来自同一份值，不能各取一次（并发变更时会撕裂）。
        val expressions = topics()
        val payload = linkedMapOf<String, Any?>(
            "connected" to link.connected,
            "state" to link.state,
            "reconnects" to link.reconnects,
            "lastDisconnectReason" to link.lastDisconnectReason,
            "lastDisconnectDetail" to link.lastDisconnectDetail,
            "lastDisconnectAtMs" to link.lastDisconnectAtMs,
            "lastConnectedAtMs" to link.lastConnectedAtMs,
            "currentBackoffMs" to link.currentBackoffMs,
            "consecutiveFailures" to link.consecutiveFailures,
            "pools" to poolStatus(),
            "sender" to linkedMapOf(
                "commandResultQueue" to send.commandResultQueue,
                "commandResultRejected" to send.commandResultRejected,
                "reliableQueue" to send.reliableQueue,
                "reliableRejected" to send.reliableRejected,
                "bestEffortDropped" to send.bestEffortDropped,
                "filteredOut" to send.filteredOut,
                "spilled" to send.spilled,
                "spillPending" to send.spillPending,
                // 真实数据丢失（已受理但被落盘缓冲挤掉）；回灌场景必须监控这一项。
                "spillDropped" to send.spillDropped,
                "writeTimeouts" to send.writeTimeouts,
                "writeFailures" to send.writeFailures,
            ),
            "inbound" to linkedMapOf(
                "frames" to core.inboundFrames,
                "tooLarge" to core.tooLarge,
                "parseErrors" to core.parseErrors,
                "saturated" to core.saturated,
            ),
            "routes" to core.routeNames,
            // `count == 0` 表示**无订阅**（全量上报），不是"订阅了 0 个主题所以什么都不发"。
            "topics" to linkedMapOf(
                "count" to expressions.size,
                "expressions" to expressions,
            ),
            "version" to PROTOCOL_VERSION,
            "timestamp" to System.currentTimeMillis(),
        )
        return BridgeCommandResult.ok(encode(payload))
    }

    /** 三个池的深度/活跃/拒绝计数。 */
    private fun poolStatus(): Map<String, Any?> = linkedMapOf(
        "cmd" to poolOf(ExecutorKind.COMMAND),
        "event" to poolOf(ExecutorKind.EVENT),
        "reload" to poolOf(ExecutorKind.RELOAD),
    )

    private fun poolOf(kind: ExecutorKind): Map<String, Any?> = linkedMapOf(
        "queued" to (CURRENT_POOLS?.queuedOf(kind) ?: 0),
        "active" to (CURRENT_POOLS?.activeOf(kind) ?: 0),
    )

    companion object {

        /** 路由序:见线路治理规格的 order 分配表。 */
        const val ORDER = 130

        /** 动作名。 */
        const val ACTION = "bridge_status"

        /** 内建治理域。 */
        const val DOMAIN_CORE = "core"

        /** 协议版本(与 hello 一致)。 */
        const val PROTOCOL_VERSION = "2"

        /**
         * 当前池句柄(诊断用)。
         *
         * 用可变静态引用而非构造参数:本路由是纯查询,不想为了池计数把 `BridgePools`
         * 也塞进构造签名(会让测试装配更重)。桥启停时由 `BridgeClient` 置位/清空。
         */
        @Volatile
        var CURRENT_POOLS: BridgePools? = null
    }
}

/**
 * 连接状态快照（批次 0 只填最小字段，批次 1 补齐重连语义）。
 *
 * @property connected 是否已连上。
 * @property state 状态机名（`STOPPED`/`CONNECTING`/`CONNECTED`/`BACKOFF`）。
 * @property reconnects 累计重连次数。
 * @property lastDisconnectReason 最近断开原因码；从未断开为空串。
 * @property lastDisconnectDetail 最近断开的人可读说明。
 * @property lastDisconnectAtMs 最近断开时刻。
 * @property lastConnectedAtMs 最近连上时刻。
 * @property currentBackoffMs 当前退避时长。
 * @property consecutiveFailures 连续失败次数。
 */
data class BridgeLinkSnapshot(
    val connected: Boolean = false,
    val state: String = "STOPPED",
    val reconnects: Long = 0,
    val lastDisconnectReason: String = "",
    val lastDisconnectDetail: String = "",
    val lastDisconnectAtMs: Long = 0,
    val lastConnectedAtMs: Long = 0,
    val currentBackoffMs: Long = 0,
    val consecutiveFailures: Int = 0,
)

/**
 * `jbis/manifest` 元查询路由：汇总各业务域能力清单。
 *
 * 序位选 90:它必须早于业务域兜底(500),否则会被当成普通业务命令派发。
 *
 * ## 回执形态（spec §6）
 * `output` 是 `{"apiVersion":1,"domains":{...}}`。`apiVersion` 在**顶层**而不是逐域重复：
 * 它是信封自己的协议版本，逐域版本另由 Provider 声明并在聚合时判定。
 * 旧的 `{"domains":{...}}` 消费方（JM）仍能读到 `domains`，新增字段不影响它们。
 */
class ManifestRoute(private val businessHost: () -> BusinessHost?) : InboundRoute {

    override val order: Int = ORDER

    override fun claims(command: BridgeCommand): Boolean =
        command.domain == DOMAIN_JBIS && command.action == ACTION

    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val host = businessHost() ?: return BridgeCommandResult.fail("业务宿主不可用")
        // 宿主返回结构化 Map,回执是字符串:此处编码成 JSON。
        return runCatching {
            BridgeCommandResult.ok(Json.encode(host.manifest()))
        }.getOrElse { BridgeCommandResult.fail("业务能力清单获取失败:${it.javaClass.simpleName}") }
    }

    companion object {
        /** 路由序:早于业务域兜底。 */
        const val ORDER = 90
        const val DOMAIN_JBIS = "jbis"
        const val ACTION = "manifest"
    }
}

/**
 * 业务域派发路由（兜底,order=500）。
 *
 * 命中条件:domain 非空、且不是内建治理域/元域。真正的执行在业务线程池内由
 * [BusinessHost] 自行做事故域隔离与有界超时。
 *
 * ## 的上下文从哪里来
 * 下行帧里 `requestId` 是治理命令的既有字段，`principal` 段是新加的（JM 在写动作前注入操作者身份）。
 * 两者都**原样**塞进 [ActionRequest]：探针不理解业务语义，但必须把「谁、为什么」交给 Provider，
 * 否则插件侧审计只能记到一条无主的命令。
 */
class BusinessDomainRoute(private val businessHost: () -> BusinessHost?) : InboundRoute {

    override val order: Int = ORDER

    override fun claims(command: BridgeCommand): Boolean =
        command.domain.isNotEmpty() &&
            command.domain != DOMAIN_CORE &&
            command.domain != DOMAIN_JBIS

    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val host = businessHost() ?: return BridgeCommandResult.fail("业务宿主不可用")
        val request = ActionRequest(
            command.domain,
            command.action,
            command.payload,
            command.requestId,
            command.principal,
        )
        return runCatching {
            val result = host.dispatch(request)
            if (result.success) {
                BridgeCommandResult.ok(result.outputJson)
            } else {
                // 错误码对人无用，但必须留痕：否则面板只能看到一句中文，按码分流的信息全丢。
                // 这里把它拼进失败原因的前缀，格式固定为 `[CODE] message`，旧消费方不受影响。
                BridgeCommandResult.fail(annotate(result))
            }
        }.getOrElse { BridgeCommandResult.fail("业务执行异常:${it.javaClass.simpleName}") }
    }

    /** 失败原因拼上错误码前缀（无码时原样返回）。 */
    private fun annotate(result: ActionResult): String {
        val message = result.errorMessage
        if (result.errorCode.isEmpty()) {
            return message
        }
        return if (message.isEmpty()) "[${result.errorCode}]" else "[${result.errorCode}] $message"
    }

    companion object {
        /** 路由序:业务域兜底。 */
        const val ORDER = 500
        const val DOMAIN_CORE = "core"
        const val DOMAIN_JBIS = "jbis"
    }
}

/**
 * 平台治理路由（兜底,order=900）。
 *
 * 命中条件:domain 为空或 `core`,且未被更早的路由(如 `bridge_status`)认领。
 * 平台未注册执行器时优雅降级为失败回执。
 */
class GovernanceRoute(private val registry: () -> BridgeCommandHandler?) : InboundRoute {

    override val order: Int = ORDER

    override fun claims(command: BridgeCommand): Boolean =
        command.domain.isEmpty() || command.domain == DOMAIN_CORE

    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val handler = registry() ?: return BridgeCommandResult.fail("本平台未实现治理执行")
        return runCatching { handler.handle(command) }
            .getOrElse { BridgeCommandResult.fail("治理执行异常:${it.javaClass.simpleName}") }
    }

    companion object {
        /** 路由序:治理兜底,必须排在所有 core.* 专门路由之后。 */
        const val ORDER = 900
        const val DOMAIN_CORE = "core"
    }
}

/**
 * `core.subscribe` / `core.unsubscribe` 路由（事件主题订阅规格 §3.7）。
 *
 * ## 语义
 * - `topics` 为空数组 → 清空订阅，恢复全量上报（向后兼容）。
 * - 成功 → `ok(output = 当前生效集合 JSON)`，与 `bridge_status.topics.expressions` 同值。
 * - 失败 → `fail(原因 + 上限值 + 逐条被拒明细)`，且**订阅保持变更前的状态**（整批拒绝，不部分应用）。
 * - `unsubscribe` 移除不存在的表达式不报错（幂等），`output` 仍回显生效集合。
 *
 * ## 为什么"匹配"与"执行"分离
 * [claims] 只做纯同步字符串判定（跑在桥读线程上）；[handle] 在 COMMAND 池执行——
 * 订阅变更是治理操作，与业务命令同池，绝不占用读线程。
 *
 * ## 可替换的编解码
 * 默认走 `Json` 门面（运行期 nightconfig）。单测注入纯逻辑实现，
 * 从而在没有 JSON 库的裸类路径上验证整套拒绝语义。
 *
 * @param registry 订阅注册表（与 [TopicFilter]、`bridge_status.topics` 同一实例）。
 * @param decode payload → 表达式列表；返回 null 表示 payload 非法。
 * @param encode 生效集合 → JSON 数组文本。
 */
class SubscribeRoute(
    private val registry: TopicSubscriptionRegistry,
    private val decode: (String) -> List<String>? = ::parseTopics,
    private val encode: (List<String>) -> String = ::encodeExpressions,
) : InboundRoute {

    override val order: Int = ORDER

    override val executor: ExecutorKind = ExecutorKind.COMMAND

    override fun claims(command: BridgeCommand): Boolean =
        command.domain == DOMAIN_CORE && command.action in ACTIONS

    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val requested = decode(command.payload)
            ?: return BridgeCommandResult.fail("payload 缺少 topics 数组(订阅意图必须显式给出表达式列表)")
        return if (command.action == ACTION_UNSUBSCRIBE) unsubscribe(requested) else subscribe(requested)
    }

    /** 订阅（整批替换）；空数组等价于清空。 */
    private fun subscribe(requested: List<String>): BridgeCommandResult {
        if (requested.isEmpty()) {
            registry.clear()
            return BridgeCommandResult.ok(encode(emptyList()))
        }
        return report(registry.replace(requested.toSet()))
    }

    /**
     * 退订：按表达式移除，**移除不存在的表达式不报错**（幂等）。
     *
     * 通过 `replace(当前集合 - 待移除)` 实现，从而完全复用整批校验语义
     * （不必在注册表上再开一个会绕过上限校验的删除入口）。
     */
    private fun unsubscribe(requested: List<String>): BridgeCommandResult =
        report(registry.replace(registry.snapshot() - requested.toSet()))

    /** 结果 → 回执：成功回显生效集合；失败给出上限值与逐条明细。 */
    private fun report(result: SubscriptionApplyResult): BridgeCommandResult =
        if (result.applied) {
            BridgeCommandResult.ok(encode(result.effective.toList()))
        } else {
            BridgeCommandResult.fail(errorsOf(result))
        }

    /** 失败原因：明确"未部分生效"，并逐条列出被拒表达式与原因。 */
    private fun errorsOf(result: SubscriptionApplyResult): String = buildString {
        append("订阅被整批拒绝,当前生效集合保持不变")
        append(";被拒明细:")
        append(result.rejected.joinToString(";") { it.toString() })
    }

    companion object {

        /** 路由序：见线路治理规格的 order 分配表（`config_reload` 100 / `business_status` 110 / 本路由 120）。 */
        const val ORDER = 120

        /** 内建治理域。 */
        const val DOMAIN_CORE = "core"

        /** 动作名：订阅。 */
        const val ACTION_SUBSCRIBE = "subscribe"

        /** 动作名：退订。 */
        const val ACTION_UNSUBSCRIBE = "unsubscribe"

        /** 本路由认领的动作集合。 */
        private val ACTIONS = setOf(ACTION_SUBSCRIBE, ACTION_UNSUBSCRIBE)

        /**
         * 默认 payload 解析器：取 `topics` 数组。
         *
         * 缺失该键时返回 null（→ 失败回执），不把它当成"空数组"：
         * 前者是**协议错误**，后者是**显式清空订阅**，混为一谈会让一次拼错 payload 的下发
         * 悄悄清空 JM 的订阅。
         */
        fun parseTopics(payload: String): List<String>? = runCatching {
            val tree = Json.parse(payload)
            if (!tree.contains("topics")) null else tree.getStringList("topics")
        }.getOrNull()

        /** 默认生效集合编码器：JSON 字符串数组（与 `bridge_status.topics.expressions` 同形）。 */
        fun encodeExpressions(expressions: List<String>): String = Json.encode(expressions)
    }
}
