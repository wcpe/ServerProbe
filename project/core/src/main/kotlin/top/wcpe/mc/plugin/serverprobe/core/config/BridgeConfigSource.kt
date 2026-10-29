package top.wcpe.mc.plugin.serverprobe.core.config

/**
 * 桥运行参数的读取接缝(线路治理规格 §6.2 的权威定义)。
 *
 * ## 为什么需要这层间接
 * `ProbeConfig.conf` 是 TabooLib 注入的内存快照,而 `core` 的单测 classpath **不含**
 * `basic-configuration`,且 `conf` 是 `lateinit` —— 单测里直接读 `ProbeConfig` 会
 * `UninitializedPropertyAccessException`。
 *
 * 因此 `BridgeClient` 及各组件只依赖本接口,生产注入 [ProbeConfigBridgeConfigSource],
 * 单测注入 fake —— 这样"调小阈值后行为变化"这类验收才写得出来。
 *
 * ## 边界
 * 本接口**不是对外契约**,留在 core;`api` 模块不得引用它。
 * 新增 Settings 或字段必须登记在本文件,各功能不得自行往接口加方法。
 */
interface BridgeConfigSource {

    /** 桥总开关;仅启动期语义(运行期修改不会停连)。 */
    fun enabled(): Boolean

    /** Worker 反向 WS 地址。 */
    fun url(): String

    /** 实例级 token;绝不可写入日志。 */
    fun token(): String

    /** 实例标识;为空时由探针自行生成。 */
    fun instance(): String

    /** 线路治理参数。 */
    fun line(): LineSettings

    /** 业务命令派发参数。 */
    fun dispatch(): DispatchSettings

    /** 配置重载参数。 */
    fun reload(): ReloadSettings

    /** 帧分片参数。 */
    fun chunk(): ChunkSettings

    /** 主题订阅参数。 */
    fun subscribe(): SubscribeSettings

    /** Provider 健康参数。 */
    fun health(): HealthSettings

    /** 连接自愈与心跳参数。 */
    fun reconnect(): ReconnectSettings
}

/**
 * 线路治理参数。
 *
 * @property readTimeoutMs 读超时兼心跳节拍;**须 ≤ [writeDeadlineMs]**,否则写侧检测下限被拉长。
 * @property writeDeadlineMs 写侧 watchdog 上限,超时强关 socket 解阻塞。
 * @property maxInboundBytes 入站单帧硬上限,超限丢帧但保持连接。
 * @property cmdPoolSize 命令池并发位。
 * @property cmdQueueCapacity 命令池队列容量。
 * @property eventPoolSize 事件池并发位。
 * @property eventQueueCapacity 事件池队列容量。
 * @property reloadPoolSize 重载协调池并发位(与 worker 池分离)。
 * @property reloadQueueCapacity 重载协调池队列容量。
 * @property bestEffortCapacity 可丢事件队列容量。
 * @property reliableCapacity 不可丢事件队列容量。
 * @property receiptCapacity 命令回执队列容量。
 * @property receiptOfferBudgetMs 池线程提交回执时的有界阻塞预算(读线程禁用)。
 * @property spillEnabled 是否启用不可丢事件落盘兜底。
 * @property spillDir 落盘目录。
 * @property spillMaxFiles 落盘文件数上限。
 * @property spillMaxBytes 落盘总字节上限。
 */
data class LineSettings(
    val readTimeoutMs: Long = 5_000,
    val writeDeadlineMs: Long = 10_000,
    val maxInboundBytes: Int = 1_048_576,
    val cmdPoolSize: Int = 4,
    val cmdQueueCapacity: Int = 64,
    val eventPoolSize: Int = 2,
    val eventQueueCapacity: Int = 512,
    val reloadPoolSize: Int = 4,
    val reloadQueueCapacity: Int = 32,
    val bestEffortCapacity: Int = 2_048,
    val reliableCapacity: Int = 1_024,
    val receiptCapacity: Int = 256,
    val receiptOfferBudgetMs: Long = 500,
    val spillEnabled: Boolean = true,
    val spillDir: String = "data/bridge-spill",
    val spillMaxFiles: Int = 8,
    val spillMaxBytes: Long = 8_388_608,
)

/**
 * 业务命令派发参数。
 *
 * @property timeoutMs 单次派发有界等待上限;超时后动作可能仍生效(调用方须自带幂等键)。
 * @property poolSize 业务线程池并发位。
 */
data class DispatchSettings(
    val timeoutMs: Long = 5_000,
    val poolSize: Int = 8,
)

/**
 * 配置重载参数。
 *
 * @property timeoutMs 单次重载总预算。
 * @property maxPathsPerRequest 单次请求路径数上限,超限整请求拒绝(不静默截断)。
 * @property workerPoolSize 监听器子任务 worker 池并发位(与协调池分离,防池内自锁)。
 * @property stuckAfterMs 在途超过此时长判定为卡死,回执改用 STUCK 而非 IN_FLIGHT。
 * @property breakerEnabled 是否启用熔断。
 * @property breakerFailureWindowMs 熔断统计窗口。
 * @property breakerOpenDurationMs 首次熔断打开时长(指数退避的基数)。
 * @property breakerMaxOpenDurationMs 熔断打开时长上限。
 * @property breakerFailureThreshold 窗口内超时次数达到即熔断。
 * @property breakerMaxEntries 熔断表条目上限(满则拒绝新条目,不淘汰已有)。
 */
data class ReloadSettings(
    val timeoutMs: Long = 5_000,
    val maxPathsPerRequest: Int = 32,
    val workerPoolSize: Int = 8,
    val stuckAfterMs: Long = 30_000,
    val breakerEnabled: Boolean = true,
    val breakerFailureWindowMs: Long = 60_000,
    val breakerOpenDurationMs: Long = 60_000,
    val breakerMaxOpenDurationMs: Long = 600_000,
    val breakerFailureThreshold: Int = 1,
    val breakerMaxEntries: Int = 256,
)

/**
 * 帧分片参数。
 *
 * @property enabled 是否启用分片。
 * @property maxFrameBytes 单帧整帧序列化后的字节上限;超限才切分。
 * @property maxChunksPerGroup 单组片数上限,超限整组拒绝。
 */
data class ChunkSettings(
    val enabled: Boolean = true,
    val maxFrameBytes: Int = 262_144,
    val maxChunksPerGroup: Int = 64,
)

/**
 * 主题订阅参数。
 *
 * @property maxTopics 订阅表达式数量上限,超限整批拒绝。
 * @property maxExpressionChars 单个表达式长度上限。
 */
data class SubscribeSettings(
    val maxTopics: Int = 256,
    val maxExpressionChars: Int = 256,
)

/**
 * Provider 健康参数。
 *
 * @property refreshIntervalMs 健康快照刷新周期。
 * @property healthTimeoutMs 单次 `health()` 调用的有界等待上限。
 * @property quarantineMs 超时/异常后该域的隔离窗口;窗口内不再调用其 `health()`,直接沿用上次结论。
 * @property retainAfterUnregisterMs 注销后状态保留时长。
 * @property maxEntries 健康状态表条目上限。
 * @property reasonMaxChars 每域 `reason` 的存储与回执截断长度。
 * @property detailMaxChars 每域 `detailJson` 的存储与回执截断长度。
 */
data class HealthSettings(
    val refreshIntervalMs: Long = 10_000,
    val healthTimeoutMs: Long = 1_000,
    val quarantineMs: Long = 60_000,
    val retainAfterUnregisterMs: Long = 300_000,
    val maxEntries: Int = 256,
    val reasonMaxChars: Int = 512,
    val detailMaxChars: Int = 2_048,
)

/**
 * 连接自愈与心跳参数。
 *
 * @property pingIntervalMs 周期 ping 间隔(不依赖读超时,忙连接也要发)。
 * @property pongTimeoutMs pong 等待上限;`0` 表示关闭该判定。
 * @property initialBackoffMs 首次重连退避。
 * @property maxBackoffMs 常规退避上限。
 * @property authFailedMaxBackoffMs 认证失败时的退避上限(比常规小,便于 token 轮换后快速恢复)。
 * @property authFailedDegradeThreshold 连续认证失败达到此次数后降频。
 * @property authFailedDegradedBackoffMs 降频后的退避。
 */
data class ReconnectSettings(
    val pingIntervalMs: Long = 20_000,
    val pongTimeoutMs: Long = 60_000,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 30_000,
    val authFailedMaxBackoffMs: Long = 10_000,
    val authFailedDegradeThreshold: Int = 10,
    val authFailedDegradedBackoffMs: Long = 60_000,
)
