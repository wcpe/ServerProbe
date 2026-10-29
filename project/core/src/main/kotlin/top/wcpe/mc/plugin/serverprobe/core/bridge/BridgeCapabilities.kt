package top.wcpe.mc.plugin.serverprobe.core.bridge

/**
 * hello 帧的能力声明（**version / capabilities 的唯一真源**）。
 *
 * ## 为什么集中一处
 * `hello` 的 `version` 与 `capabilities` 会被后续多个功能追加（分片、主题订阅、配置重载、
 * 健康状态）。若各功能各自改 `helloJson`，多处拼接必然漂移；集中到本类后，
 * 新增能力只需调用 [addCapability]，`hello` 结构只在 [BridgeClient.helloJson] 一处拼装。
 *
 * ## 版本约定
 * `version` 是**协议版本**，只在有破坏性变更时提升；新增能力只加 `capabilities`。
 * 当前 `"2"`：线路治理落地（`bridge_status` 可查）。
 *
 * ## 兼容性
 * 对端（JM Worker）应忽略不认识的 capability；本类不做协商，只如实声明。
 * **注意**：`version` 从 1 升到 2 时，若对端做严格相等校验会拒绝连接——
 * 这一点需与 JM 侧确认，本仓无法单方面保证。
 */
object BridgeCapabilities {

    /** 当前协议版本。 */
    const val VERSION = "2"

    /** 能力：`core.bridge_status` 运行状态查询。 */
    const val CAP_BRIDGE_STATUS = "bridge_status"

    /** 能力：事件主题订阅（`core.subscribe` / 帧上 `topic`）。 */
    const val CAP_TOPIC = "topic"

    /** 能力：大数据帧分片（`event=chunk` 多片 + 对端按 `groupId`/`seq` 重组）。 */
    const val CAP_CHUNK = "chunk"

    /** 能力：配置重载通知（`core.config_reload`）。 */
    const val CAP_CONFIG_RELOAD = "config_reload"

    /** 能力：Provider 健康状态查询（`core.business_status`）。 */
    const val CAP_BUSINESS_STATUS = "business_status"

    /** 已注册能力（保序，便于断言与日志）。 */
    private val capabilities = LinkedHashSet<String>()

    /**
     * 当前订阅表达式来源；未接线时为 null。
     *
     * 由事件主题订阅功能在装配时接上（`{ registry.snapshot().toList() }`）。
     * 用 `@Volatile` 持有函数引用：装配在启动期、读取在每次 `hello` 发送前，跨线程可见性是必需的。
     */
    @Volatile
    private var subscriptionSource: (() -> List<String>)? = null

    init {
        capabilities.add(CAP_BRIDGE_STATUS)
    }

    /**
     * 声明一项能力（幂等）。
     *
     * 供后续功能在装配时调用；重复声明不产生重复项。
     */
    fun addCapability(capability: String) {
        if (capability.isNotEmpty()) capabilities.add(capability)
    }

    /** 当前能力列表（不可变快照）。 */
    fun snapshot(): List<String> = capabilities.toList()

    /**
     * 接上当前订阅表达式来源（事件主题订阅规格 §3.8 的接线义务）。
     *
     * 传 `null` 表示解绑：停机与单测需要把全局状态复位，否则"未接线时为空数组"
     * 这个可观测性断言无法在同一个 JVM 里重复验证。
     *
     * @param source 返回当前生效表达式列表的供应器；须自身不抛（[subscriptions] 仍会兜底）。
     */
    fun setSubscriptionSource(source: (() -> List<String>)?) {
        subscriptionSource = source
    }

    /**
     * 当前订阅表达式（`hello.subscriptions` 的唯一取值口）。
     *
     * 未接线时返回**空数组且不抛**——这是刻意的：装配漏了
     * [setSubscriptionSource] 时，`hello.subscriptions` 恒为空而实际仍有订阅，
     * 至少可观测，比静默沿用旧订阅好。装配单测断言"接线后与注册表快照相等"来挡住这种漏配。
     */
    fun subscriptions(): List<String> {
        val source = subscriptionSource ?: return emptyList()
        return runCatching { source() }.getOrNull() ?: emptyList()
    }
}
