package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.config.ConfigPushOutcome

/**
 * 探针桥**上行帧构造**（协议规格 §3.4；形态与 Worker 侧 `bridgeMessage` 约定一致）。
 *
 * 从 [BridgeClient] 抽出的原因：连接生命周期（连/断/重连/读循环）与"帧长什么样"是两件独立的事，
 * 混在一个类里既让类膨胀，也让帧结构难以单独断言。本对象只做"数据 → 帧树"的确定性映射：
 * 无状态、不碰平台 API、可被单测直接逐字段断言。
 *
 * 帧树由调用方经项目统一 JSON 适配器（`Json.encode`，ADR-14）编码，本对象**不拼字符串**。
 *
 * ## 实例标识由调用方传入
 * `instance` 的来源（托管模式取身份文件、独立模式取配置/自动生成）属于连接装配，故不在本对象内解析。
 */
internal object BridgeFrames {

    /** 桥协议版本（随 hello 上报，供 Worker/CP 展示）。 */
    const val PROBE_BRIDGE_VERSION = "1"

    /**
     * 能力位：指标经 WS 推送（协议规格 §3.6）。
     *
     * Worker 见到该位即按"推"处理 `metric_snapshot`，否则回退 HTTP 拉取（`/metrics`）。
     */
    const val CAPABILITY_METRIC_PUSH = "metric_push"

    /** 能力位：接受托管面下发配置（只在托管模式声明）。 */
    const val CAPABILITY_CONFIG_PUSH = "config_push"

    /** 能力位：周期上报托管状态（只在托管模式声明）。 */
    const val CAPABILITY_CONFIG_STATE = "config_state"

    /** 事件名：配置应用回执（协议规格 §3.4.2）。 */
    const val EVENT_CONFIG_APPLIED = "config_applied"

    /** 事件名：托管状态周期上报（协议规格 §3.4.3）。 */
    const val EVENT_CONFIG_STATE = "config_state"

    /** 独立模式的能力位：只有指标推送（与引入托管能力前一致）。 */
    private val STANDALONE_CAPABILITIES: List<String> = listOf(CAPABILITY_METRIC_PUSH)

    /** 托管模式的能力位：指标推送 + 配置下发 + 状态上报。 */
    private val MANAGED_CAPABILITIES: List<String> =
        listOf(CAPABILITY_METRIC_PUSH, CAPABILITY_CONFIG_PUSH, CAPABILITY_CONFIG_STATE)

    /**
     * hello 帧（带 `capabilities`；托管模式再追加配置类两项）。
     *
     * 既有字段语义不变，旧对端忽略不认识的字段即可（向后兼容）；新对端不认该字段时仍按老路径拉取，
     * 故独立模式与老 Worker 的行为都不受影响。
     *
     * **能力位必须与真实能力一致**：`config_push` / `config_state` 只在托管模式下真正启用
     * （独立模式既不注册该动作也不上报状态），故此时不声明——否则控制面会误以为可以对一台
     * 独立探针下发配置。
     *
     * @param instance 实例标识。
     * @param platform 平台名（bukkit/bungee/unknown）。
     * @param managed 当前是否托管模式（决定是否声明配置类能力位）。
     * @return 帧树。
     */
    fun hello(instance: String, platform: String, managed: Boolean = false): Map<String, Any?> = linkedMapOf(
        "type" to "hello",
        "instance" to instance,
        "platform" to platform,
        "version" to PROBE_BRIDGE_VERSION,
        "capabilities" to capabilities(managed),
    )

    /** 当前模式下本机声明的能力位（供 hello 与 `config_state` 复用同一份，避免两处漂移）。 */
    fun capabilities(managed: Boolean): List<String> = if (managed) MANAGED_CAPABILITIES else STANDALONE_CAPABILITIES

    /**
     * 连接事件帧（`connected` 等仅带时间戳的事件）。
     *
     * @param event 事件子类型。
     * @param instance 实例标识。
     * @param timestampMs 事件时刻（epoch 毫秒）。
     * @return 帧树。
     */
    fun event(event: String, instance: String, timestampMs: Long): Map<String, Any?> = linkedMapOf(
        "type" to "event",
        "event" to event,
        "instance" to instance,
        "timestamp" to timestampMs,
    )

    /**
     * 带结构化载荷的玩家事件帧（FR-066）。
     *
     * 结构化字段统一放在嵌套 `data` 对象内（Worker 仅解析 `data` 填充 workerpb.PluginEvent）；
     * 空值字段不写入，减小帧体并避免下游误判。
     *
     * @param event 事件子类型。
     * @param instance 实例标识。
     * @param timestampMs 事件时刻（epoch 毫秒）。
     * @param fields 结构化字段（playerName/playerUuid/message/server/fromServer/toServer 等）。
     * @return 帧树。
     */
    fun playerEvent(event: String, instance: String, timestampMs: Long, fields: Map<String, String>): Map<String, Any?> =
        linkedMapOf(
            "type" to "event",
            "event" to event,
            "instance" to instance,
            "timestamp" to timestampMs,
            "data" to fields.filterValues { it.isNotEmpty() }, // 空值不发
        )

    /**
     * JBIS 业务域事件帧（FR-122）：在玩家事件形态上加顶层 `domain` + `dedupKey`。
     *
     * Worker 透传 domain/dedupKey、并把整帧原文带给 CP（raw），CP 据此按 (domain, dedupKey) 去重落库。
     *
     * @param domain 业务域（economy…）。
     * @param dedupKey 去重键（经济域为 ledgerId 字符串）。
     * @param instance 实例标识。
     * @param timestampMs 事件时刻（epoch 毫秒）。
     * @param fields 业务信封结构化字段。
     * @return 帧树。
     */
    fun businessEvent(
        domain: String,
        dedupKey: String,
        instance: String,
        timestampMs: Long,
        fields: Map<String, String>,
    ): Map<String, Any?> = linkedMapOf(
        "type" to "event",
        "event" to "${domain}_change",
        "instance" to instance,
        "timestamp" to timestampMs,
        "domain" to domain,
        "dedupKey" to dedupKey,
        "data" to fields.filterValues { it.isNotEmpty() }, // 空值不发
    )

    /**
     * 治理/查询指令回执帧（FR-067）。
     *
     * 顶层 `type=event` + `event=command_result`，结构化字段放在嵌套 `data` 内
     * （requestId/success/output/error）。requestId 须与下发指令一致，Worker 据此把回执路由给
     * 同步等待的调用方——FR-46 的 `metrics_now` 回执同样依赖本约定。
     *
     * @param instance 实例标识。
     * @param timestampMs 回执时刻（epoch 毫秒）。
     * @param requestId 关联回执标识（来自下发的 command 帧）。
     * @param result 执行结果（平台治理、业务域或 core 内置动作）。
     * @return 帧树。
     */
    fun commandResult(
        instance: String,
        timestampMs: Long,
        requestId: String,
        result: BridgeCommandResult,
    ): Map<String, Any?> = linkedMapOf(
        "type" to "event",
        "event" to "command_result",
        "instance" to instance,
        "timestamp" to timestampMs,
        "data" to linkedMapOf(
            "requestId" to requestId,
            "success" to result.success,
            "output" to result.output,
            "error" to result.error,
        ),
    )

    /**
     * 配置应用回执帧（协议规格 §3.4.2）。
     *
     * 拓扑与既有事件帧略有不同：规格把结构化字段直接放在 `payload` 下（而非 `data`），
     * 本方法照规格输出；`instance` / `timestamp` 沿用既有信封字段，便于 Worker 统一路由与排序。
     *
     * `dedupKey` 形如 `cfg:<configVersion>:applied`：同一版本重复投递时控制面据此幂等落库。
     *
     * @param instance 实例标识。
     * @param timestampMs 回执时刻（epoch 毫秒）。
     * @param outcome 应用结果（版本 / 结果码 / 生效值 / 自检 / 错误）。
     * @return 帧树。
     */
    fun configApplied(instance: String, timestampMs: Long, outcome: ConfigPushOutcome): Map<String, Any?> = linkedMapOf(
        "type" to "event",
        "event" to EVENT_CONFIG_APPLIED,
        "instance" to instance,
        "timestamp" to timestampMs,
        "dedupKey" to "cfg:${outcome.configVersion}:applied",
        "payload" to linkedMapOf(
            "configVersion" to outcome.configVersion,
            "result" to outcome.result,
            "effective" to outcome.effective,
            "selfCheck" to outcome.selfCheck?.toMap(),
            "error" to outcome.error,
            "appliedAt" to outcome.appliedAt,
        ),
    )

    /**
     * 托管状态上报帧（协议规格 §3.4.3）。
     *
     * 字段语义全部由调用方（[ConfigStateReporter]）决定并原样放进 `payload`：本对象只负责
     * 信封结构与字段命名，不解释 `connection` / `cacheAgeSeconds` 这类运行态。
     *
     * @param instance 实例标识。
     * @param timestampMs 上报时刻（epoch 毫秒）。
     * @param payload 规约字段（configVersion / effective / cacheAgeSeconds / mode / connection / capabilities / lastError）。
     * @return 帧树。
     */
    fun configState(instance: String, timestampMs: Long, payload: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "type" to "event",
        "event" to EVENT_CONFIG_STATE,
        "instance" to instance,
        "timestamp" to timestampMs,
        "payload" to payload,
    )

    /**
     * 推断平台名（bukkit/bungee/unknown）：探针单 jar 多端，以类是否可加载粗判，
     * 不引平台 API 依赖到 core（core 平台无关）。
     *
     * @return 平台名。
     */
    fun platformName(): String = when {
        canLoad("org.bukkit.Bukkit") -> "bukkit"
        canLoad("net.md_5.bungee.api.ProxyServer") -> "bungee"
        else -> "unknown"
    }

    /** 类是否可加载（用于平台粗判），异常即视为不可加载。 */
    private fun canLoad(name: String): Boolean =
        runCatching { Class.forName(name, false, BridgeFrames::class.java.classLoader) }.isSuccess
}
