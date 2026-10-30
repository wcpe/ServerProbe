package top.wcpe.mc.plugin.serverprobe.core.config

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 生产实现：读 [ProbeConfig] 组装各 Settings。
 *
 * 除组装外还负责一件事：把磁盘上的 config.yml 重读进内存快照。
 * 起因是 TabooLib 的 Configuration 是内存视图，外部改写文件不会自动生效，
 * 而桥需要「重连前重读 token」以支持轮换自愈。
 *
 * **命名务必区分**：[BridgeConfigSource.reload] 返回的是「配置重载功能」的设置对象
 * （[ReloadSettings]，桥处理 `config_reload` 指令时用），与「从磁盘重读配置」无关；
 * 后者是本类的 [refreshFromDisk]，它不是接口方法，只在重连前被调用。
 *
 * 本对象无状态（每次都现读 [ProbeConfig]），故以 `object` 实现，不纳入 IOC 容器。
 */
object ProbeConfigBridgeConfigSource : BridgeConfigSource {

    /** 桥总开关；仅启动期语义，运行期改配置不会停连。 */
    override fun enabled(): Boolean = ProbeConfig.bridgeEnabled()

    /** Worker 反向 WS 地址；每轮重连前会经 [refreshFromDisk] 刷新。 */
    override fun url(): String = ProbeConfig.bridgeUrl()

    /** 实例级 token；**绝不写入日志**。 */
    override fun token(): String = ProbeConfig.bridgeToken()

    /** 实例标识；为空时由探针自行生成。 */
    override fun instance(): String = ProbeConfig.bridgeInstance()

    /** 线路治理参数（`bridge.line.*`）。 */
    override fun line(): LineSettings = LineSettings(
        readTimeoutMs = ProbeConfig.bridgeLineReadTimeoutMs(),
        writeDeadlineMs = ProbeConfig.bridgeLineWriteDeadlineMs(),
        maxInboundBytes = ProbeConfig.bridgeLineMaxInboundBytes(),
        cmdPoolSize = ProbeConfig.bridgeLineCmdPoolSize(),
        cmdQueueCapacity = ProbeConfig.bridgeLineCmdQueueCapacity(),
        eventPoolSize = ProbeConfig.bridgeLineEventPoolSize(),
        eventQueueCapacity = ProbeConfig.bridgeLineEventQueueCapacity(),
        reloadPoolSize = ProbeConfig.bridgeLineReloadPoolSize(),
        reloadQueueCapacity = ProbeConfig.bridgeLineReloadQueueCapacity(),
        bestEffortCapacity = ProbeConfig.bridgeLineBestEffortCapacity(),
        reliableCapacity = ProbeConfig.bridgeLineReliableCapacity(),
        receiptCapacity = ProbeConfig.bridgeLineReceiptCapacity(),
        receiptOfferBudgetMs = ProbeConfig.bridgeLineReceiptOfferBudgetMs(),
        spillEnabled = ProbeConfig.bridgeLineSpillEnabled(),
        spillDir = ProbeConfig.bridgeLineSpillDir(),
        spillMaxFiles = ProbeConfig.bridgeLineSpillMaxFiles(),
        spillMaxBytes = ProbeConfig.bridgeLineSpillMaxBytes(),
    )

    /** 业务命令派发参数（`bridge.dispatch.*`）。 */
    override fun dispatch(): DispatchSettings = DispatchSettings(
        timeoutMs = ProbeConfig.bridgeDispatchTimeoutMs(),
        poolSize = ProbeConfig.bridgeDispatchPoolSize(),
    )

    /** 配置重载参数（`bridge.reload.*`，含熔断段 `circuit-breaker.*`）。 */
    override fun reload(): ReloadSettings = ReloadSettings(
        timeoutMs = ProbeConfig.bridgeReloadTimeoutMs(),
        maxPathsPerRequest = ProbeConfig.bridgeReloadMaxPathsPerRequest(),
        workerPoolSize = ProbeConfig.bridgeReloadWorkerPoolSize(),
        stuckAfterMs = ProbeConfig.bridgeReloadStuckAfterMs(),
        breakerEnabled = ProbeConfig.bridgeReloadBreakerEnabled(),
        breakerFailureWindowMs = ProbeConfig.bridgeReloadBreakerFailureWindowMs(),
        breakerOpenDurationMs = ProbeConfig.bridgeReloadBreakerOpenDurationMs(),
        breakerMaxOpenDurationMs = ProbeConfig.bridgeReloadBreakerMaxOpenDurationMs(),
        breakerFailureThreshold = ProbeConfig.bridgeReloadBreakerFailureThreshold(),
        breakerMaxEntries = ProbeConfig.bridgeReloadBreakerMaxEntries(),
    )

    /** 帧分片参数（`bridge.chunk.*`）。 */
    override fun chunk(): ChunkSettings = ChunkSettings(
        enabled = ProbeConfig.bridgeChunkEnabled(),
        maxFrameBytes = ProbeConfig.bridgeChunkMaxFrameBytes(),
        maxChunksPerGroup = ProbeConfig.bridgeChunkMaxChunksPerGroup(),
    )

    /** 主题订阅参数（`bridge.subscribe.*`）。 */
    override fun subscribe(): SubscribeSettings = SubscribeSettings(
        maxTopics = ProbeConfig.bridgeSubscribeMaxTopics(),
        maxExpressionChars = ProbeConfig.bridgeSubscribeMaxExpressionChars(),
    )

    /** Provider 健康参数（`bridge.health.*`）。 */
    override fun health(): HealthSettings = HealthSettings(
        refreshIntervalMs = ProbeConfig.bridgeHealthRefreshIntervalMs(),
        healthTimeoutMs = ProbeConfig.bridgeHealthTimeoutMs(),
        quarantineMs = ProbeConfig.bridgeHealthQuarantineMs(),
        retainAfterUnregisterMs = ProbeConfig.bridgeHealthRetainAfterUnregisterMs(),
        maxEntries = ProbeConfig.bridgeHealthMaxEntries(),
        reasonMaxChars = ProbeConfig.bridgeHealthReasonMaxChars(),
        detailMaxChars = ProbeConfig.bridgeHealthDetailMaxChars(),
    )

    /** 连接自愈与心跳参数（`bridge.reconnect.*`）。 */
    override fun reconnect(): ReconnectSettings = ReconnectSettings(
        pingIntervalMs = ProbeConfig.bridgeReconnectPingIntervalMs(),
        pongTimeoutMs = ProbeConfig.bridgeReconnectPongTimeoutMs(),
        initialBackoffMs = ProbeConfig.bridgeReconnectInitialBackoffMs(),
        maxBackoffMs = ProbeConfig.bridgeReconnectMaxBackoffMs(),
        authFailedMaxBackoffMs = ProbeConfig.bridgeReconnectAuthFailedMaxBackoffMs(),
        authFailedDegradeThreshold = ProbeConfig.bridgeReconnectAuthFailedDegradeThreshold(),
        authFailedDegradedBackoffMs = ProbeConfig.bridgeReconnectAuthFailedDegradedBackoffMs(),
    )

    /**
     * 从磁盘重读 config.yml，供桥在重连前刷新 url/token。
     *
     * 整体不抛：文件缺失/损坏/解析失败时保留内存旧值并返回 false（桥继续用旧配置重连）。
     * [ProbeConfig.conf] 是 `lateinit`，未注入时访问会抛 `UninitializedPropertyAccessException`，
     * 同样由 runCatching 兜住，不会冒到重连循环。
     *
     * 日志只记异常类型、不记异常内容，避免把文件路径或配置值（可能含 token）带进日志。
     *
     * @return 重读是否成功；false 表示沿用内存旧快照。
     */
    fun refreshFromDisk(): Boolean =
        runCatching { ProbeConfig.conf.reload() }
            .onFailure { ProbeLogger.warn("config.yml 重读失败，沿用内存旧配置：${it.javaClass.simpleName}") }
            .isSuccess
}
