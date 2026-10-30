package top.wcpe.mc.plugin.serverprobe.core.config

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertLevel
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertRule
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertType
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 探针配置门面(配套)。
 *
 * 经 TabooLib 的 [Config] 机制在加载期把 `config.yml`注入到 [conf](不存在则由 TabooLib 从插件资源释放默认副本)。
 * 以 `object`实现并对外暴露**带默认值兜底的取值方法**:配置缺项或类型不符时回退默认值,确保即使配置异常也不致空指针。
 * 各取值方法对应一个配置键,集中收口默认值与键名,避免散落的魔法值(规范第 6 条)。
 *
 * 注:本类不纳入 IOC 容器——[Config] 字段注入由 TabooLib 反射扫描完成,与 IOC 互不干扰;
 * IOC 组件直接以 Kotlin `object`方式读取本类即可,无需注入。
 */
object ProbeConfig {

    /**
     * 主配置实例(`config.yml`)。
     *
     * 由 TabooLib 在加载期经 [Config] 注入,故声明为 `lateinit`;在 [LifeCycle.ENABLE] 及之后访问均已就绪。
     */
    @Config("config.yml")
    lateinit var conf: Configuration

    /**
     * 采集周期(单位 ticks),默认 100(约 5 秒)。
     *
     * @return 采集周期 tick 数。
     */
    fun collectPeriodTicks(): Long = conf.getLong(KEY_COLLECT_PERIOD_TICKS, DEFAULT_COLLECT_PERIOD_TICKS)

    /**
     * 近期历史容量(份),默认 360(约覆盖 30 分钟)。
     *
     * @return 历史缓冲容量。
     */
    fun historyCapacity(): Int = conf.getInt(KEY_HISTORY_CAPACITY, DEFAULT_HISTORY_CAPACITY)

    /**
     * 慢插件榜展示条数,默认 5。
     *
     * @return Top-N 条数。
     */
    fun startupTopN(): Int = conf.getInt(KEY_STARTUP_TOP_N, DEFAULT_STARTUP_TOP_N)

    /**
     * 指标聚合窗口大小(份),默认 12(约 1 分钟 @ 5 秒采集周期)。
     *
     * 用于 `/probe tps`的聚合补充行:对最近这么多份快照做跨快照统计。
     *
     * @return 聚合窗口份数。
     */
    fun aggregationWindow(): Int = conf.getInt(KEY_AGGREGATION_WINDOW, DEFAULT_AGGREGATION_WINDOW)

    /**
     * 配置覆盖的实例名;为空白则返回 null(交由 [top.wcpe.mc.plugin.serverprobe.core.store.InstanceId] 自动生成)。
     *
     * @return 去空白后的实例名;未配置或空白时为 null。
     */
    fun configuredServerName(): String? = conf.getString(KEY_SERVER_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 是否开启 DEBUG 日志,默认 false。
     *
     * @return 是否开启调试日志。
     */
    fun debug(): Boolean = conf.getBoolean(KEY_DEBUG, DEFAULT_DEBUG)

    /**
     * 启动画像归档保留份数,默认 30(超出后清理最旧的)。
     *
     * @return 保留份数。
     */
    fun historyRetention(): Int = conf.getInt(KEY_HISTORY_RETENTION, DEFAULT_HISTORY_RETENTION)

    /**
     * 是否开启历史指标落盘,默认 true。
     *
     * 关闭后不再把采集快照按日追加到 `data/metrics/`下的 JSONL 文件(与内存缓冲、启动画像归档相互独立)。
     *
     * @return 是否落盘历史指标。
     */
    fun historyFileEnabled(): Boolean = conf.getBoolean(KEY_HISTORY_FILE_ENABLED, DEFAULT_HISTORY_FILE_ENABLED)

    /**
     * 历史指标文件保留天数,默认 7;按自然日,含当天在内保留最近 N 天,更早的文件清理。
     *
     * @return 保留天数。
     */
    fun historyFileRetentionDays(): Int =
        conf.getInt(KEY_HISTORY_FILE_RETENTION_DAYS, DEFAULT_HISTORY_FILE_RETENTION_DAYS)

    /** 过期指标 jsonl 的 gzip 归档保留天数(0=过期即删不归档)。 */
    fun historyFileArchiveDays(): Int =
        conf.getInt(KEY_HISTORY_FILE_ARCHIVE_DAYS, DEFAULT_HISTORY_FILE_ARCHIVE_DAYS)

    /** 启动画像栈样本保留条数(均匀抽稀;0=不限,默认 10)。 */
    fun agentStackMaxSamples(): Int =
        conf.getInt(KEY_AGENT_STACK_MAX_SAMPLES, DEFAULT_AGENT_STACK_MAX_SAMPLES)

    /**
     * 单实例历史指标文件总体积上限(MB),默认 200;超出后从最旧文件起清理至达标(绝不删当天)。
     *
     * @return 体积上限(MB)。
     */
    fun historyFileMaxTotalMb(): Int =
        conf.getInt(KEY_HISTORY_FILE_MAX_TOTAL_MB, DEFAULT_HISTORY_FILE_MAX_TOTAL_MB)

    /**
     * 世界指标采样周期(单位 ticks),默认 600(约 30 秒)。
     *
     * 世界/实体采集需遍历所有世界与区块,开销高于轻量指标,故独立限频、采样后缓存,
     * 避免每个 collect 周期都重扫。
     *
     * @return 世界采样周期 tick 数。
     */
    fun worldSamplePeriodTicks(): Long = conf.getLong(KEY_WORLD_SAMPLE_PERIOD_TICKS, DEFAULT_WORLD_SAMPLE_PERIOD_TICKS)

    /**
     * 是否统计各世界按类型的实体分布,默认 true。
     *
     * 关闭后仅给出实体总数,不再对实体按类型分组(可降低开销)。
     *
     * @return 是否开启按类型实体统计。
     */
    fun worldEntityTypes(): Boolean = conf.getBoolean(KEY_WORLD_ENTITY_TYPES, DEFAULT_WORLD_ENTITY_TYPES)

    /**
     * Folia 已观测 region 无新样本后的过期秒数，默认 60，至少为 1。
     *
     * @return region 指标过期秒数。
     */
    fun foliaObservedRegionExpireSeconds(): Long =
        conf.getLong(KEY_FOLIA_OBSERVED_REGION_EXPIRE_SECONDS, DEFAULT_FOLIA_OBSERVED_REGION_EXPIRE_SECONDS)
            .coerceAtLeast(MIN_FOLIA_OBSERVED_REGION_EXPIRE_SECONDS)

    /** 是否开启 SQLite 网络包取证，默认开启。 */
    fun networkForensicsEnabled(): Boolean =
        conf.getBoolean(KEY_NETWORK_FORENSICS_ENABLED, DEFAULT_NETWORK_FORENSICS_ENABLED)

    /** 允许保存载荷的包类型白名单。 */
    fun networkForensicsPayloadPacketTypes(): Set<String> =
        conf.getStringList(KEY_NETWORK_FORENSICS_PAYLOAD_PACKET_TYPES).map(String::trim).filter(String::isNotEmpty).toSet()

    /** Plugin Message 允许保存载荷的频道白名单。 */
    fun networkForensicsPayloadChannels(): Set<String> =
        conf.getStringList(KEY_NETWORK_FORENSICS_PAYLOAD_CHANNELS).map(String::trim).filter(String::isNotEmpty).toSet()

    /** 单包保留载荷的最大字节数，最小为 1。 */
    fun networkForensicsMaxPayloadBytes(): Int =
        conf.getInt(KEY_NETWORK_FORENSICS_MAX_PAYLOAD_BYTES, DEFAULT_NETWORK_FORENSICS_MAX_PAYLOAD_BYTES)
            .coerceAtLeast(MIN_NETWORK_FORENSICS_MAX_PAYLOAD_BYTES)

    /** SQLite 取证保留天数，最小为 0。 */
    fun networkForensicsRetentionDays(): Int =
        conf.getInt(KEY_NETWORK_FORENSICS_RETENTION_DAYS, DEFAULT_NETWORK_FORENSICS_RETENTION_DAYS).coerceAtLeast(0)

    /** SQLite 与 WAL 文件合计上限，单位为字节，最小为 1 GiB。 */
    fun networkForensicsMaxTotalBytes(): Long =
        conf.getLong(KEY_NETWORK_FORENSICS_MAX_TOTAL_GIB, DEFAULT_NETWORK_FORENSICS_MAX_TOTAL_GIB)
            .coerceAtLeast(MIN_NETWORK_FORENSICS_MAX_TOTAL_GIB) * BYTES_PER_GIB

    /**
     * 是否开启 HTTP/TCP 外呼监控(M5,需挂载 agent),默认 true。
     *
     * 关闭后 agent 侧外呼 hook 立即返回不记录(开销可忽略),不再产出外呼日志/明细。
     *
     * @return 是否开启外呼监控。
     */
    fun httpMonitorEnabled(): Boolean = conf.getBoolean(KEY_HTTP_MONITOR_ENABLED, DEFAULT_HTTP_MONITOR_ENABLED)

    /**
     * 外呼是否实时打印到控制台/插件日志,默认 true。关闭后仍写文件/进 `/probe http`,只是不刷控制台。
     *
     * @return 是否打印到控制台。
     */
    fun httpMonitorLogToConsole(): Boolean =
        conf.getBoolean(KEY_HTTP_MONITOR_LOG_CONSOLE, DEFAULT_HTTP_MONITOR_LOG_CONSOLE)

    /**
     * 外呼日志是否附带(已脱敏的)请求头,默认 false(默认仅 URL/方法/响应码/耗时/调用方,避免刷屏)。
     *
     * @return 是否记录请求头。
     */
    fun httpMonitorLogHeaders(): Boolean =
        conf.getBoolean(KEY_HTTP_MONITOR_LOG_HEADERS, DEFAULT_HTTP_MONITOR_LOG_HEADERS)

    /**
     * 是否把外呼明细落盘到 `data/http/`,默认 true。
     *
     * @return 是否落盘外呼明细。
     */
    fun httpMonitorFileEnabled(): Boolean =
        conf.getBoolean(KEY_HTTP_MONITOR_FILE_ENABLED, DEFAULT_HTTP_MONITOR_FILE_ENABLED)

    /**
     * `/probe http`可回看的近期外呼条数(内存缓冲),默认 500。
     *
     * @return 近期缓冲容量。
     */
    fun httpMonitorRecentCapacity(): Int =
        conf.getInt(KEY_HTTP_MONITOR_RECENT_CAPACITY, DEFAULT_HTTP_MONITOR_RECENT_CAPACITY)

    /**
     * 外呼记录拉取周期(ticks),默认 20(约 1 秒)。监控线程在任意线程产生记录,本周期从 agent 增量拉取并呈现。
     *
     * @return 拉取周期 tick 数。
     */
    fun httpMonitorDrainPeriodTicks(): Long =
        conf.getLong(KEY_HTTP_MONITOR_DRAIN_PERIOD_TICKS, DEFAULT_HTTP_MONITOR_DRAIN_PERIOD_TICKS)

    /**
     * 是否开启 Prometheus `/metrics`端点,默认 false(关闭)。
     *
     * 出于安全默认:端点关闭、仅本地可访问,需显式开启。关闭时 [top.wcpe.mc.plugin.serverprobe.core.prometheus.PrometheusExporter] 不起服。
     *
     * @return 是否开启导出端点。
     */
    fun metricsEnabled(): Boolean = conf.getBoolean(KEY_METRICS_ENABLED, DEFAULT_METRICS_ENABLED)

    /**
     * Prometheus 端点绑定地址,默认 `127.0.0.1`(仅本机回环,不对外暴露)。
     *
     * 如需被同网段 Prometheus 抓取,可改为 `0.0.0.0`或具体网卡地址,但务必同时配置 [metricsToken] 或 [metricsAllowedIps]。
     *
     * @return 绑定主机地址。
     */
    fun metricsHost(): String = conf.getString(KEY_METRICS_HOST)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_METRICS_HOST

    /**
     * Prometheus 端点监听端口,默认 9940。
     *
     * Bukkit 与代理端各为独立进程、各起一个端点,同机部署时两端需配置不同端口避免冲突。
     *
     * @return 监听端口。
     */
    fun metricsPort(): Int = conf.getInt(KEY_METRICS_PORT, DEFAULT_METRICS_PORT)

    /**
     * Prometheus 端点鉴权 token,默认空串(不启用 token 鉴权)。
     *
     * 非空时要求请求头 `Authorization: Bearer <token>`匹配方可访问;为空时仅靠 [metricsAllowedIps] IP 白名单限制。
     *
     * @return 鉴权 token;未配置时为空串。
     */
    fun metricsToken(): String = conf.getString(KEY_METRICS_TOKEN)?.trim() ?: DEFAULT_METRICS_TOKEN

    /**
     * Prometheus 端点 IP 白名单,默认 `["127.0.0.1"]`(仅本机)。
     *
     * 仅白名单内的来源 IP 可访问端点(与 [metricsToken] 叠加生效)。配置缺失或读取异常时兜底为默认本机白名单,
     * 杜绝因配置异常而"裸奔"对外。
     *
     * @return 允许访问的 IP 列表(恒非空,至少含本机回环)。
     */
    fun metricsAllowedIps(): List<String> {
        // getStringList 在键缺失时返回空列表;再以 takeIf 兜底为默认白名单,确保恒非空(安全默认)
        val configured = runCatching { conf.getStringList(KEY_METRICS_ALLOWED_IPS) }.getOrNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        return configured?.takeIf { it.isNotEmpty() } ?: DEFAULT_METRICS_ALLOWED_IPS
    }

    /** 是否开启事故诊断 MCP 控制面，默认关闭。 */
    fun mcpEnabled(): Boolean = conf.getBoolean(KEY_MCP_ENABLED, DEFAULT_MCP_ENABLED)

    /** MCP 监听地址，默认仅本机回环。 */
    fun mcpHost(): String = conf.getString(KEY_MCP_HOST)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MCP_HOST

    /** MCP 监听端口，默认 9942。 */
    fun mcpPort(): Int = conf.getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT)

    /** MCP Bearer 密钥；空串表示显式允许无认证。 */
    fun mcpSecret(): String = conf.getString(KEY_MCP_SECRET)?.trim() ?: DEFAULT_MCP_SECRET

    /** MCP Arthas 同时运行的任务数。 */
    fun mcpTaskMaxConcurrent(): Int = conf.getInt(KEY_MCP_TASK_MAX_CONCURRENT, DEFAULT_MCP_TASK_MAX_CONCURRENT).coerceAtLeast(1)

    /** MCP Arthas 单任务最长分钟数；零表示不限时。 */
    fun mcpTaskTimeoutMinutes(): Long = conf.getLong(KEY_MCP_TASK_TIMEOUT_MINUTES, DEFAULT_MCP_TASK_TIMEOUT_MINUTES).coerceAtLeast(0)

    /** MCP Arthas 单任务输出上限，单位 MiB。 */
    fun mcpTaskOutputMebibytes(): Int = conf.getInt(KEY_MCP_TASK_OUTPUT_MEBIBYTES, DEFAULT_MCP_TASK_OUTPUT_MEBIBYTES).coerceAtLeast(1)

    /** MCP Arthas 完成任务的保留分钟数。 */
    fun mcpTaskRetentionMinutes(): Long = conf.getLong(KEY_MCP_TASK_RETENTION_MINUTES, DEFAULT_MCP_TASK_RETENTION_MINUTES).coerceAtLeast(1)

    /** MCP 工件保留小时数。 */
    fun mcpArtifactRetentionHours(): Long =
    conf.getLong(KEY_MCP_ARTIFACT_RETENTION_HOURS, DEFAULT_MCP_ARTIFACT_RETENTION_HOURS).coerceAtLeast(1)

    /** 外呼 .log 原始保留天数(过期 gzip 归档)。 */
    fun httpMonitorFileRetentionDays(): Int =
        conf.getInt(KEY_HTTP_MONITOR_FILE_RETENTION_DAYS, DEFAULT_HTTP_MONITOR_FILE_RETENTION_DAYS)

    /** 外呼 gzip 归档保留天数(0=过期直接删除)。 */
    fun httpMonitorFileArchiveDays(): Int =
        conf.getInt(KEY_HTTP_MONITOR_FILE_ARCHIVE_DAYS, DEFAULT_HTTP_MONITOR_FILE_ARCHIVE_DAYS)

    /** MCP 审计记录轮转阈值(活跃文件超过该体积 gzip 轮转),单位 MB。 */
    fun mcpAuditMaxFileMb(): Int = conf.getInt(KEY_MCP_AUDIT_MAX_FILE_MB, DEFAULT_MCP_AUDIT_MAX_FILE_MB)

    /** MCP 审计 gzip 归档保留天数(0=不过期)。 */
    fun mcpAuditRetentionDays(): Int = conf.getInt(KEY_MCP_AUDIT_RETENTION_DAYS, DEFAULT_MCP_AUDIT_RETENTION_DAYS)

    /** MCP 工件工作区总容量上限，单位 GiB。 */
    fun mcpArtifactMaxGib(): Long = conf.getLong(KEY_MCP_ARTIFACT_MAX_GIB, DEFAULT_MCP_ARTIFACT_MAX_GIB).coerceAtLeast(1)

    /**
     * 是否开启插件桥反向 WS(见 JianManager ADR-016),默认 false(关闭)。
     *
     * 由 JianManager 建服时写入 `bridge.enabled=true`并下发 url/instance/token;独立使用探针(非 JianManager
     * 托管)时保持关闭,探针不连任何外部 Worker。关闭时 [top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient]
     * 不起连接。
     *
     * @return 是否开启插件桥。
     */
    fun bridgeEnabled(): Boolean = conf.getBoolean(KEY_BRIDGE_ENABLED, DEFAULT_BRIDGE_ENABLED)

    /**
     * 插件桥目标 Worker 的 WS 地址(如 `ws://127.0.0.1:9102/ws/plugin-bridge`),默认空串(未配置)。
     *
     * 探针据此主动反向连入本机 Worker;token/instance 作为查询参数另行附加。空串时即使开关开启也不连。
     *
     * @return 去空白后的 WS 地址;未配置或空白时为空串。
     */
    fun bridgeUrl(): String = conf.getString(KEY_BRIDGE_URL)?.trim() ?: DEFAULT_BRIDGE_URL

    /**
     * 插件桥实例标识(JianManager 实例 UUID),默认空串。
     *
     * 作为握手 `?instance=`参数,须与 token 内 instanceId 一致(Worker 校验)。
     *
     * @return 去空白后的实例标识;未配置时为空串。
     */
    fun bridgeInstance(): String = conf.getString(KEY_BRIDGE_INSTANCE)?.trim() ?: DEFAULT_BRIDGE_INSTANCE

    /**
     * 插件桥连接 token(实例级 HS256 JWT,scope=plugin-bridge),默认空串。
     *
     * 作为握手 `?token=`参数;**安全:绝不写入日志**。空串时不连。
     *
     * @return token;未配置时为空串。
     */
    fun bridgeToken(): String = conf.getString(KEY_BRIDGE_TOKEN)?.trim() ?: DEFAULT_BRIDGE_TOKEN

    // ---------------------------------------------------------------------------
    // 插件桥运行参数(bridge.line/dispatch/reload/chunk/subscribe/health/reconnect.*)
    //
    // 这些方法只由 [ProbeConfigBridgeConfigSource] 组装成各 Settings,业务组件经
    // [BridgeConfigSource] 接缝取值,不直接读本类(单测 classpath 无 basic-configuration,
    // 且 [conf] 为 lateinit)。默认值须与 BridgeConfigSource.kt 中各 Settings 数据类的
    // 默认参数保持一致,缺失时回退到此处的常量。
    // ---------------------------------------------------------------------------

    // ----- 线路治理参数(bridge.line.*)-----

    /** 读超时兼心跳节拍(毫秒),默认 5000;须不大于 [bridgeLineWriteDeadlineMs]。 */
    fun bridgeLineReadTimeoutMs(): Long = conf.getLong(KEY_BRIDGE_LINE_READ_TIMEOUT_MS, DEFAULT_BRIDGE_LINE_READ_TIMEOUT_MS)

    /** 写侧 watchdog 上限(毫秒),默认 10000;超时强关 socket 解阻塞。 */
    fun bridgeLineWriteDeadlineMs(): Long = conf.getLong(KEY_BRIDGE_LINE_WRITE_DEADLINE_MS, DEFAULT_BRIDGE_LINE_WRITE_DEADLINE_MS)

    /** 入站单帧硬上限(字节),默认 1 MiB;超限丢帧但保持连接。 */
    fun bridgeLineMaxInboundBytes(): Int = conf.getInt(KEY_BRIDGE_LINE_MAX_INBOUND_BYTES, DEFAULT_BRIDGE_LINE_MAX_INBOUND_BYTES)

    /** 命令池并发位,默认 4。 */
    fun bridgeLineCmdPoolSize(): Int = conf.getInt(KEY_BRIDGE_LINE_CMD_POOL_SIZE, DEFAULT_BRIDGE_LINE_CMD_POOL_SIZE)

    /** 命令池队列容量,默认 64。 */
    fun bridgeLineCmdQueueCapacity(): Int = conf.getInt(KEY_BRIDGE_LINE_CMD_QUEUE_CAPACITY, DEFAULT_BRIDGE_LINE_CMD_QUEUE_CAPACITY)

    /** 事件池并发位,默认 2。 */
    fun bridgeLineEventPoolSize(): Int = conf.getInt(KEY_BRIDGE_LINE_EVENT_POOL_SIZE, DEFAULT_BRIDGE_LINE_EVENT_POOL_SIZE)

    /** 事件池队列容量,默认 512。 */
    fun bridgeLineEventQueueCapacity(): Int = conf.getInt(KEY_BRIDGE_LINE_EVENT_QUEUE_CAPACITY, DEFAULT_BRIDGE_LINE_EVENT_QUEUE_CAPACITY)

    /** 重载协调池并发位(与 reload worker 池分离),默认 4。 */
    fun bridgeLineReloadPoolSize(): Int = conf.getInt(KEY_BRIDGE_LINE_RELOAD_POOL_SIZE, DEFAULT_BRIDGE_LINE_RELOAD_POOL_SIZE)

    /** 重载协调池队列容量,默认 32。 */
    fun bridgeLineReloadQueueCapacity(): Int =
        conf.getInt(KEY_BRIDGE_LINE_RELOAD_QUEUE_CAPACITY, DEFAULT_BRIDGE_LINE_RELOAD_QUEUE_CAPACITY)

    /** 可丢事件队列容量,默认 2048。 */
    fun bridgeLineBestEffortCapacity(): Int =
        conf.getInt(KEY_BRIDGE_LINE_BEST_EFFORT_CAPACITY, DEFAULT_BRIDGE_LINE_BEST_EFFORT_CAPACITY)

    /** 不可丢事件队列容量,默认 1024。 */
    fun bridgeLineReliableCapacity(): Int =
        conf.getInt(KEY_BRIDGE_LINE_RELIABLE_CAPACITY, DEFAULT_BRIDGE_LINE_RELIABLE_CAPACITY)

    /** 命令回执队列容量,默认 256。 */
    fun bridgeLineReceiptCapacity(): Int = conf.getInt(KEY_BRIDGE_LINE_RECEIPT_CAPACITY, DEFAULT_BRIDGE_LINE_RECEIPT_CAPACITY)

    /** 池线程提交回执时的有界阻塞预算(毫秒),默认 500。 */
    fun bridgeLineReceiptOfferBudgetMs(): Long =
        conf.getLong(KEY_BRIDGE_LINE_RECEIPT_OFFER_BUDGET_MS, DEFAULT_BRIDGE_LINE_RECEIPT_OFFER_BUDGET_MS)

    /** 是否启用不可丢事件落盘兜底,默认 true。 */
    fun bridgeLineSpillEnabled(): Boolean = conf.getBoolean(KEY_BRIDGE_LINE_SPILL_ENABLED, DEFAULT_BRIDGE_LINE_SPILL_ENABLED)

    /** 落盘目录,默认 `data/bridge-spill`;空白时回退默认值(避免写进进程工作目录)。 */
    fun bridgeLineSpillDir(): String =
        conf.getString(KEY_BRIDGE_LINE_SPILL_DIR)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BRIDGE_LINE_SPILL_DIR

    /** 落盘文件数上限,默认 8。 */
    fun bridgeLineSpillMaxFiles(): Int = conf.getInt(KEY_BRIDGE_LINE_SPILL_MAX_FILES, DEFAULT_BRIDGE_LINE_SPILL_MAX_FILES)

    /** 落盘总字节上限,默认 8 MiB。 */
    fun bridgeLineSpillMaxBytes(): Long = conf.getLong(KEY_BRIDGE_LINE_SPILL_MAX_BYTES, DEFAULT_BRIDGE_LINE_SPILL_MAX_BYTES)

    // ----- 业务命令派发参数(bridge.dispatch.*)-----

    /** 单次派发有界等待上限(毫秒),默认 5000;超时后动作可能仍生效。 */
    fun bridgeDispatchTimeoutMs(): Long = conf.getLong(KEY_BRIDGE_DISPATCH_TIMEOUT_MS, DEFAULT_BRIDGE_DISPATCH_TIMEOUT_MS)

    /** 业务线程池并发位,默认 8。 */
    fun bridgeDispatchPoolSize(): Int = conf.getInt(KEY_BRIDGE_DISPATCH_POOL_SIZE, DEFAULT_BRIDGE_DISPATCH_POOL_SIZE)

    // ----- 配置重载参数(bridge.reload.*)-----

    /** 单次重载总预算(毫秒),默认 5000。 */
    fun bridgeReloadTimeoutMs(): Long = conf.getLong(KEY_BRIDGE_RELOAD_TIMEOUT_MS, DEFAULT_BRIDGE_RELOAD_TIMEOUT_MS)

    /** 单次请求路径数上限,默认 32;超限整请求拒绝(不静默截断)。 */
    fun bridgeReloadMaxPathsPerRequest(): Int =
        conf.getInt(KEY_BRIDGE_RELOAD_MAX_PATHS_PER_REQUEST, DEFAULT_BRIDGE_RELOAD_MAX_PATHS_PER_REQUEST)

    /** 监听器子任务 worker 池并发位(与协调池分离,防池内自锁),默认 8。 */
    fun bridgeReloadWorkerPoolSize(): Int =
        conf.getInt(KEY_BRIDGE_RELOAD_WORKER_POOL_SIZE, DEFAULT_BRIDGE_RELOAD_WORKER_POOL_SIZE)

    /** 在途超过此时长(毫秒)判定为卡死,默认 30000。 */
    fun bridgeReloadStuckAfterMs(): Long = conf.getLong(KEY_BRIDGE_RELOAD_STUCK_AFTER_MS, DEFAULT_BRIDGE_RELOAD_STUCK_AFTER_MS)

    /** 是否启用重载熔断,默认 true。 */
    fun bridgeReloadBreakerEnabled(): Boolean =
        conf.getBoolean(KEY_BRIDGE_RELOAD_BREAKER_ENABLED, DEFAULT_BRIDGE_RELOAD_BREAKER_ENABLED)

    /** 熔断统计窗口(毫秒),默认 60000。 */
    fun bridgeReloadBreakerFailureWindowMs(): Long =
        conf.getLong(KEY_BRIDGE_RELOAD_BREAKER_FAILURE_WINDOW_MS, DEFAULT_BRIDGE_RELOAD_BREAKER_FAILURE_WINDOW_MS)

    /** 首次熔断打开时长(毫秒,指数退避基数),默认 60000。 */
    fun bridgeReloadBreakerOpenDurationMs(): Long =
        conf.getLong(KEY_BRIDGE_RELOAD_BREAKER_OPEN_DURATION_MS, DEFAULT_BRIDGE_RELOAD_BREAKER_OPEN_DURATION_MS)

    /** 熔断打开时长上限(毫秒),默认 600000。 */
    fun bridgeReloadBreakerMaxOpenDurationMs(): Long =
        conf.getLong(KEY_BRIDGE_RELOAD_BREAKER_MAX_OPEN_DURATION_MS, DEFAULT_BRIDGE_RELOAD_BREAKER_MAX_OPEN_DURATION_MS)

    /** 窗口内超时次数达到即熔断,默认 1。 */
    fun bridgeReloadBreakerFailureThreshold(): Int =
        conf.getInt(KEY_BRIDGE_RELOAD_BREAKER_FAILURE_THRESHOLD, DEFAULT_BRIDGE_RELOAD_BREAKER_FAILURE_THRESHOLD)

    /** 熔断表条目上限,默认 256;满则拒绝新条目,不淘汰已有。 */
    fun bridgeReloadBreakerMaxEntries(): Int =
        conf.getInt(KEY_BRIDGE_RELOAD_BREAKER_MAX_ENTRIES, DEFAULT_BRIDGE_RELOAD_BREAKER_MAX_ENTRIES)

    // ----- 帧分片参数(bridge.chunk.*)-----

    /** 是否启用帧分片,默认 true。 */
    fun bridgeChunkEnabled(): Boolean = conf.getBoolean(KEY_BRIDGE_CHUNK_ENABLED, DEFAULT_BRIDGE_CHUNK_ENABLED)

    /** 单帧整帧序列化后的字节上限,默认 262144;超限才切分。 */
    fun bridgeChunkMaxFrameBytes(): Int = conf.getInt(KEY_BRIDGE_CHUNK_MAX_FRAME_BYTES, DEFAULT_BRIDGE_CHUNK_MAX_FRAME_BYTES)

    /** 单组片数上限,默认 64;超限整组拒绝。 */
    fun bridgeChunkMaxChunksPerGroup(): Int =
        conf.getInt(KEY_BRIDGE_CHUNK_MAX_CHUNKS_PER_GROUP, DEFAULT_BRIDGE_CHUNK_MAX_CHUNKS_PER_GROUP)

    // ----- 主题订阅参数(bridge.subscribe.*)-----

    /** 订阅表达式数量上限,默认 256;超限整批拒绝。 */
    fun bridgeSubscribeMaxTopics(): Int = conf.getInt(KEY_BRIDGE_SUBSCRIBE_MAX_TOPICS, DEFAULT_BRIDGE_SUBSCRIBE_MAX_TOPICS)

    /** 单个订阅表达式长度上限,默认 256。 */
    fun bridgeSubscribeMaxExpressionChars(): Int =
        conf.getInt(KEY_BRIDGE_SUBSCRIBE_MAX_EXPRESSION_CHARS, DEFAULT_BRIDGE_SUBSCRIBE_MAX_EXPRESSION_CHARS)

    // ----- Provider 健康参数(bridge.health.*)-----

    /** 健康快照刷新周期(毫秒),默认 10000。 */
    fun bridgeHealthRefreshIntervalMs(): Long =
        conf.getLong(KEY_BRIDGE_HEALTH_REFRESH_INTERVAL_MS, DEFAULT_BRIDGE_HEALTH_REFRESH_INTERVAL_MS)

    /** 单次 `health()` 调用的有界等待上限(毫秒),默认 1000。 */
    fun bridgeHealthTimeoutMs(): Long = conf.getLong(KEY_BRIDGE_HEALTH_TIMEOUT_MS, DEFAULT_BRIDGE_HEALTH_TIMEOUT_MS)

    /** 超时/异常后该域的隔离窗口(毫秒),默认 60000;窗口内不再调用其 `health()`。 */
    fun bridgeHealthQuarantineMs(): Long =
        conf.getLong(KEY_BRIDGE_HEALTH_QUARANTINE_MS, DEFAULT_BRIDGE_HEALTH_QUARANTINE_MS)

    /** 注销后健康状态保留时长(毫秒),默认 300000。 */
    fun bridgeHealthRetainAfterUnregisterMs(): Long =
        conf.getLong(KEY_BRIDGE_HEALTH_RETAIN_AFTER_UNREGISTER_MS, DEFAULT_BRIDGE_HEALTH_RETAIN_AFTER_UNREGISTER_MS)

    /** 健康状态表条目上限,默认 256。 */
    fun bridgeHealthMaxEntries(): Int = conf.getInt(KEY_BRIDGE_HEALTH_MAX_ENTRIES, DEFAULT_BRIDGE_HEALTH_MAX_ENTRIES)

    /** 每域 `reason` 的截断长度(字符),默认 512。 */
    fun bridgeHealthReasonMaxChars(): Int =
        conf.getInt(KEY_BRIDGE_HEALTH_REASON_MAX_CHARS, DEFAULT_BRIDGE_HEALTH_REASON_MAX_CHARS)

    /** 每域 `detailJson` 的截断长度(字符),默认 2048。 */
    fun bridgeHealthDetailMaxChars(): Int =
        conf.getInt(KEY_BRIDGE_HEALTH_DETAIL_MAX_CHARS, DEFAULT_BRIDGE_HEALTH_DETAIL_MAX_CHARS)

    // ----- 连接自愈与心跳参数(bridge.reconnect.*)-----

    /** 周期 ping 间隔(毫秒),默认 20000;不依赖读超时,忙连接也要发。 */
    fun bridgeReconnectPingIntervalMs(): Long =
        conf.getLong(KEY_BRIDGE_RECONNECT_PING_INTERVAL_MS, DEFAULT_BRIDGE_RECONNECT_PING_INTERVAL_MS)

    /** pong 等待上限(毫秒),默认 60000;配置为 0 表示关闭该判定。 */
    fun bridgeReconnectPongTimeoutMs(): Long =
        conf.getLong(KEY_BRIDGE_RECONNECT_PONG_TIMEOUT_MS, DEFAULT_BRIDGE_RECONNECT_PONG_TIMEOUT_MS)

    /** 首次重连退避(毫秒),默认 1000。 */
    fun bridgeReconnectInitialBackoffMs(): Long =
        conf.getLong(KEY_BRIDGE_RECONNECT_INITIAL_BACKOFF_MS, DEFAULT_BRIDGE_RECONNECT_INITIAL_BACKOFF_MS)

    /** 常规退避上限(毫秒),默认 30000。 */
    fun bridgeReconnectMaxBackoffMs(): Long =
        conf.getLong(KEY_BRIDGE_RECONNECT_MAX_BACKOFF_MS, DEFAULT_BRIDGE_RECONNECT_MAX_BACKOFF_MS)

    /** 认证失败时的退避上限(毫秒),默认 10000;比常规小,便于 token 轮换后快速恢复。 */
    fun bridgeReconnectAuthFailedMaxBackoffMs(): Long =
        conf.getLong(KEY_BRIDGE_RECONNECT_AUTH_FAILED_MAX_BACKOFF_MS, DEFAULT_BRIDGE_RECONNECT_AUTH_FAILED_MAX_BACKOFF_MS)

    /** 连续认证失败达到此次数后降频,默认 10。 */
    fun bridgeReconnectAuthFailedDegradeThreshold(): Int =
        conf.getInt(KEY_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADE_THRESHOLD, DEFAULT_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADE_THRESHOLD)

    /** 降频后的退避(毫秒),默认 60000。 */
    fun bridgeReconnectAuthFailedDegradedBackoffMs(): Long =
        conf.getLong(
            KEY_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADED_BACKOFF_MS,
            DEFAULT_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADED_BACKOFF_MS
        )

    /**
     * 是否开启告警引擎,默认 false(关闭)。
     *
     * 关闭时 [top.wcpe.mc.plugin.serverprobe.core.alert.AlertEngine] 不构建任何规则,采集末尾的判定空转。
     *
     * @return 是否开启告警。
     */
    fun alertEnabled(): Boolean = conf.getBoolean(KEY_ALERT_ENABLED, DEFAULT_ALERT_ENABLED)

    /**
     * 是否开启日志告警通道,默认 true。
     *
     * @return 是否启用日志通道。
     */
    fun alertChannelLog(): Boolean = conf.getBoolean(KEY_ALERT_CHANNEL_LOG, DEFAULT_ALERT_CHANNEL_LOG)

    /**
     * 是否开启游戏内告警通道,默认 true(仅 Bukkit 端生效)。
     *
     * @return 是否启用游戏内通道。
     */
    fun alertChannelInGame(): Boolean = conf.getBoolean(KEY_ALERT_CHANNEL_IN_GAME, DEFAULT_ALERT_CHANNEL_IN_GAME)

    /**
     * 是否开启 Webhook 告警通道,默认 false。
     *
     * @return 是否启用 Webhook 通道。
     */
    fun alertWebhookEnabled(): Boolean = conf.getBoolean(KEY_ALERT_WEBHOOK_ENABLED, DEFAULT_ALERT_WEBHOOK_ENABLED)

    /**
     * Webhook 回调地址,默认空串(未配置)。
     *
     * 出于安全,本值仅在通道内部使用、绝不写入日志(可能含 token)。
     *
     * @return 去空白后的 URL;未配置或空白时为空串。
     */
    fun alertWebhookUrl(): String = conf.getString(KEY_ALERT_WEBHOOK_URL)?.trim() ?: DEFAULT_ALERT_WEBHOOK_URL

    /**
     * Webhook 连接/读取超时(毫秒),默认 3000;低于下限时兜底为下限,避免 0/负值导致无限等待。
     *
     * @return 超时毫秒数(不小于 [MIN_ALERT_WEBHOOK_TIMEOUT_MS])。
     */
    fun alertWebhookTimeoutMs(): Int =
        conf.getInt(KEY_ALERT_WEBHOOK_TIMEOUT_MS, DEFAULT_ALERT_WEBHOOK_TIMEOUT_MS)
            .coerceAtLeast(MIN_ALERT_WEBHOOK_TIMEOUT_MS)

    /**
     * 是否开启运行期 CPU 采样归因(M3,P2),默认 false(关闭)。
     *
     * P2 增强,采样有一定开销;默认关闭,需显式开启。关闭时
     * [top.wcpe.mc.plugin.serverprobe.core.cpu.CpuAttributionSampler] 不启动采样任务。
     *
     * @return 是否开启运行期 CPU 归因。
     */
    fun cpuEnabled(): Boolean = conf.getBoolean(KEY_CPU_ENABLED, DEFAULT_CPU_ENABLED)

    /**
     * 是否开启 Incision 方法级精确归因,默认 false。
     *
     * 关闭时 advice 直接返回且不记录精确耗时;未命中有效切点时平台侧降级为普通启动画像。
     *
     * @return 是否请求启用 Incision。
     */
    fun incisionEnabled(): Boolean = conf.getBoolean(KEY_INCISION_ENABLED, DEFAULT_INCISION_ENABLED)

    /**
     * CPU 采样周期(单位 ticks),默认 20(约 1 秒)。
     *
     * @return 采样周期 tick 数。
     */
    fun cpuSamplePeriodTicks(): Long = conf.getLong(KEY_CPU_SAMPLE_PERIOD_TICKS, DEFAULT_CPU_SAMPLE_PERIOD_TICKS)

    /**
     * CPU 归因聚合窗口(轮),默认 60(约 1 分钟 @ 1 秒采样周期)。
     *
     * 窗口内各插件累计样本数/占比即
     * [top.wcpe.mc.plugin.serverprobe.core.cpu.CpuAttributionSampler.snapshot] 输出。
     *
     * @return 窗口轮数。
     */
    fun cpuWindowRounds(): Int = conf.getInt(KEY_CPU_WINDOW_ROUNDS, DEFAULT_CPU_WINDOW_ROUNDS)

    /**
     * 是否开启 Web 面板(M3,P2),默认 false(关闭)。
     *
     * 出于安全默认:端点关闭、仅本地可访问,需显式开启。关闭时
     * [top.wcpe.mc.plugin.serverprobe.core.web.WebPanelServer] 不起服。
     *
     * @return 是否开启 Web 面板。
     */
    fun webEnabled(): Boolean = conf.getBoolean(KEY_WEB_ENABLED, DEFAULT_WEB_ENABLED)

    /**
     * Web 面板绑定地址,默认 `127.0.0.1`(仅本机回环,不对外暴露)。
     *
     * @return 绑定主机地址。
     */
    fun webHost(): String = conf.getString(KEY_WEB_HOST)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_WEB_HOST

    /**
     * Web 面板监听端口,默认 9941(与 Prometheus 9940 区分)。
     *
     * @return 监听端口。
     */
    fun webPort(): Int = conf.getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT)

    /**
     * Web 面板鉴权 token,默认空串(不启用 token 鉴权)。
     *
     * 非空时要求请求头 `Authorization: Bearer <token>`匹配方可访问。
     *
     * @return 鉴权 token;未配置时为空串。
     */
    fun webToken(): String = conf.getString(KEY_WEB_TOKEN)?.trim() ?: DEFAULT_WEB_TOKEN

    /**
     * Web 面板 IP 白名单,默认 `["127.0.0.1"]`(仅本机)。
     *
     * @return 允许访问的 IP 列表(恒非空,至少含本机回环)。
     */
    fun webAllowedIps(): List<String> {
        val configured = runCatching { conf.getStringList(KEY_WEB_ALLOWED_IPS) }.getOrNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        return configured?.takeIf { it.isNotEmpty() } ?: DEFAULT_WEB_ALLOWED_IPS
    }

    /**
     * 解析告警规则集:把 `alert.rules`下各命名规则解析为 [AlertRule] 列表。
     *
     * 集中收口规则解析:命名规则 → 固定 [AlertType] 的映射、阈值/持续周期默认值、level 字符串转枚举
     * 与缺项兜底全在此完成,避免散落(规范第 6 条)。每条规则缺省值见 [RuleSpec];level 文本无法识别时
     * 兜底为 [AlertLevel.WARN],sustain-cycles 至少为 1。
     *
     * @return 规则列表(顺序固定,与 [RuleSpec] 一致);引擎再据 [AlertRule.enabled] 过滤。
     */
    fun alertRules(): List<AlertRule> = RuleSpec.values().map { spec ->
        val path = "$KEY_ALERT_RULES.${spec.key}"
        AlertRule(
            type = spec.type,
            threshold = conf.getDouble("$path.$SUB_THRESHOLD", spec.defaultThreshold),
            sustainCycles = conf.getInt("$path.$SUB_SUSTAIN_CYCLES", spec.defaultSustainCycles)
                .coerceAtLeast(MIN_SUSTAIN_CYCLES),
            level = parseLevel(conf.getString("$path.$SUB_LEVEL"), spec.defaultLevel),
            enabled = conf.getBoolean("$path.$SUB_ENABLED", spec.defaultEnabled)
        )
    }

    /**
     * 把 level 文本解析为 [AlertLevel](大小写不敏感),无法识别时兜底为 [fallback]。
     *
     * @param raw 配置中的 level 文本(可能为 null/空/非法)。
     * @param fallback 兜底级别。
     * @return 解析得到的级别或兜底值。
     */
    private fun parseLevel(raw: String?, fallback: AlertLevel): AlertLevel {
        val text = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return fallback
        return AlertLevel.values().firstOrNull { it.name == text } ?: run {
            ProbeLogger.warn("未识别的告警级别 \"$raw\",已回退为 $fallback")
            fallback
        }
    }

    /**
     * 内置告警规则规格:命名规则键 → 告警类型 + 各项默认值。
     *
     * 规则种类有限且已知,以枚举集中描述每条内置规则的键名/类型/默认阈值/默认持续周期/默认级别/默认开关,
     * 既驱动 [alertRules] 解析,又作为 `config.yml`默认值的单一事实来源(规范第 6 条)。
     *
     * @property key `alert.rules`下的子键名。
     * @property type 对应的告警类型。
     * @property defaultThreshold 默认阈值。
     * @property defaultSustainCycles 默认持续周期。
     * @property defaultLevel 默认级别。
     * @property defaultEnabled 默认是否启用。
     */
    private enum class RuleSpec(
        val key: String,
        val type: AlertType,
        val defaultThreshold: Double,
        val defaultSustainCycles: Int,
        val defaultLevel: AlertLevel,
        val defaultEnabled: Boolean
    ) {
        /** TPS 偏低(警告):tps1m < 18 持续 3 周期。 */
        TPS_WARN("tps-warn", AlertType.TPS_LOW, 18.0, 3, AlertLevel.WARN, true),

        /** TPS 过低(严重):tps1m < 15 持续 3 周期。 */
        TPS_CRITICAL("tps-critical", AlertType.TPS_LOW, 15.0, 3, AlertLevel.CRITICAL, true),

        /** MSPT p95 过高(警告):msptP95 > 50ms 持续 3 周期。 */
        MSPT_P95("mspt-p95", AlertType.MSPT_HIGH, 50.0, 3, AlertLevel.WARN, true),

        /** 堆占用率过高(警告):heap 使用率 > 90% 持续 3 周期。 */
        HEAP_USAGE("heap-usage", AlertType.HEAP_USAGE_HIGH, 90.0, 3, AlertLevel.WARN, true),

        /** 死锁(严重,事件型):死锁线程数 > 0 即触发(持续 1 周期)。 */
        DEADLOCK("deadlock", AlertType.DEADLOCK, 0.0, 1, AlertLevel.CRITICAL, true),

        /** Old GC 频繁(警告,FR-29):老年代 GC 次数速率 > 0.05 次/秒(约每 20 秒一次)持续 6 周期(约 30 秒)。 */
        GC_OLD_HIGH("gc-old-high", AlertType.GC_OLD_HIGH, 0.05, 6, AlertLevel.WARN, true),

        /** 启动超基线(警告,FR-29):最近一次启动总时长 > 180 秒持续 1 周期(启动后一次性判定,事件型)。 */
        STARTUP_SLOW("startup-slow", AlertType.STARTUP_SLOW, 180.0, 1, AlertLevel.WARN, true)
    }

    /**
     * 在 [LifeCycle.ENABLE] 时把 DEBUG 开关同步到日志门面。
     *
     * 此时 [conf] 已注入完成,读取 [debug] 安全;之后 [ProbeLogger.debug] 才据此决定是否输出。
     */
    @Awake(LifeCycle.ENABLE)
    fun applyLogLevel() {
        ProbeLogger.debugEnabled = debug()
        ProbeLogger.info("配置加载完成,调试日志=${ProbeLogger.debugEnabled}")
    }

    /** 配置键名:采集周期(ticks)。 */
    private const val KEY_COLLECT_PERIOD_TICKS = "collect-period-ticks"

    /** 配置键名:近期历史容量(份)。 */
    private const val KEY_HISTORY_CAPACITY = "history-capacity"

    /** 配置键名:慢插件榜条数。 */
    private const val KEY_STARTUP_TOP_N = "startup-top-n"

    /** 配置键名:指标聚合窗口(份)。 */
    private const val KEY_AGGREGATION_WINDOW = "aggregation.window"

    /** 配置键名:实例名覆盖。 */
    private const val KEY_SERVER_NAME = "server-name"

    /** 配置键名:调试日志开关。 */
    private const val KEY_DEBUG = "debug"

    /** 配置键名:启动画像归档保留份数。 */
    private const val KEY_HISTORY_RETENTION = "history-retention"

    /** 配置键名:历史指标落盘开关。 */
    private const val KEY_HISTORY_FILE_ENABLED = "history-file.enabled"

    /** 配置键名:历史指标文件保留天数。 */
    private const val KEY_HISTORY_FILE_RETENTION_DAYS = "history-file.retention-days"

    /** 配置键名:历史指标文件总体积上限(MB)。 */
    private const val KEY_HISTORY_FILE_MAX_TOTAL_MB = "history-file.max-total-mb"
    private const val KEY_HISTORY_FILE_ARCHIVE_DAYS = "history-file.archive-days"
    private const val KEY_AGENT_STACK_MAX_SAMPLES = "agent-stack-max-samples"

    /** 配置键名:世界采样周期(ticks)。 */
    private const val KEY_WORLD_SAMPLE_PERIOD_TICKS = "world.sample-period-ticks"

    /** 配置键名:世界按类型实体统计开关。 */
    private const val KEY_WORLD_ENTITY_TYPES = "world.entity-types"

    /** 配置键名：Folia 已观测 region 过期秒数。 */
    private const val KEY_FOLIA_OBSERVED_REGION_EXPIRE_SECONDS = "folia.observed-region-expire-seconds"

    /** 配置键名：网络取证总开关。 */
    private const val KEY_NETWORK_FORENSICS_ENABLED = "network-forensics.enabled"

    /** 配置键名：网络取证载荷包类型白名单。 */
    private const val KEY_NETWORK_FORENSICS_PAYLOAD_PACKET_TYPES = "network-forensics.payload-packet-types"

    /** 配置键名：网络取证插件消息频道白名单。 */
    private const val KEY_NETWORK_FORENSICS_PAYLOAD_CHANNELS = "network-forensics.payload-channels"

    /** 配置键名：网络取证单包载荷上限。 */
    private const val KEY_NETWORK_FORENSICS_MAX_PAYLOAD_BYTES = "network-forensics.max-payload-bytes"

    /** 配置键名：网络取证保留天数。 */
    private const val KEY_NETWORK_FORENSICS_RETENTION_DAYS = "network-forensics.retention-days"

    /** 配置键名：网络取证 SQLite 体积上限。 */
    private const val KEY_NETWORK_FORENSICS_MAX_TOTAL_GIB = "network-forensics.max-total-gib"

    /** 配置键名:外呼监控总开关。 */
    private const val KEY_HTTP_MONITOR_ENABLED = "http-monitor.enabled"

    /** 配置键名:外呼控制台日志开关。 */
    private const val KEY_HTTP_MONITOR_LOG_CONSOLE = "http-monitor.log-to-console"

    /** 配置键名:外呼日志是否含请求头。 */
    private const val KEY_HTTP_MONITOR_LOG_HEADERS = "http-monitor.log-headers"

    /** 配置键名:外呼明细落盘开关。 */
    private const val KEY_HTTP_MONITOR_FILE_ENABLED = "http-monitor.file-enabled"
    private const val KEY_HTTP_MONITOR_FILE_RETENTION_DAYS = "http-monitor.file-retention-days"
    private const val DEFAULT_HTTP_MONITOR_FILE_RETENTION_DAYS = 7
    private const val KEY_HTTP_MONITOR_FILE_ARCHIVE_DAYS = "http-monitor.file-archive-days"
    private const val DEFAULT_HTTP_MONITOR_FILE_ARCHIVE_DAYS = 30

    /** 配置键名:外呼近期缓冲容量。 */
    private const val KEY_HTTP_MONITOR_RECENT_CAPACITY = "http-monitor.recent-capacity"

    /** 配置键名:外呼记录拉取周期(ticks)。 */
    private const val KEY_HTTP_MONITOR_DRAIN_PERIOD_TICKS = "http-monitor.drain-period-ticks"

    /** 配置键名:Prometheus 端点开关。 */
    private const val KEY_METRICS_ENABLED = "metrics.enabled"

    /** 配置键名:Prometheus 端点绑定地址。 */
    private const val KEY_METRICS_HOST = "metrics.host"

    /** 配置键名:Prometheus 端点监听端口。 */
    private const val KEY_METRICS_PORT = "metrics.port"

    /** 配置键名:Prometheus 端点鉴权 token。 */
    private const val KEY_METRICS_TOKEN = "metrics.token"

    /** 配置键名:Prometheus 端点 IP 白名单。 */
    private const val KEY_METRICS_ALLOWED_IPS = "metrics.allowed-ips"

    /** 配置键名:MCP 控制面总开关。 */
    private const val KEY_MCP_ENABLED = "mcp.enabled"

    /** 配置键名:MCP 控制面监听地址。 */
    private const val KEY_MCP_HOST = "mcp.host"

    /** 配置键名:MCP 控制面监听端口。 */
    private const val KEY_MCP_PORT = "mcp.port"

    /** 配置键名:MCP 控制面 Bearer 密钥。 */
    private const val KEY_MCP_SECRET = "mcp.secret"

    /** 配置键名:MCP Arthas 任务并发上限。 */
    private const val KEY_MCP_TASK_MAX_CONCURRENT = "mcp.task-max-concurrent"

    /** 配置键名:MCP Arthas 单任务超时分钟数。 */
    private const val KEY_MCP_TASK_TIMEOUT_MINUTES = "mcp.task-timeout-minutes"

    /** 配置键名:MCP Arthas 单任务输出 MiB 上限。 */
    private const val KEY_MCP_TASK_OUTPUT_MEBIBYTES = "mcp.task-output-mebibytes"

    /** 配置键名:MCP Arthas 完成任务保留分钟数。 */
    private const val KEY_MCP_TASK_RETENTION_MINUTES = "mcp.task-retention-minutes"

    /** 配置键名:MCP 工件保留小时数。 */
    private const val KEY_MCP_AUDIT_MAX_FILE_MB = "mcp.audit-max-file-mb"
    private const val DEFAULT_MCP_AUDIT_MAX_FILE_MB = 20
    private const val KEY_MCP_AUDIT_RETENTION_DAYS = "mcp.audit-retention-days"
    private const val DEFAULT_MCP_AUDIT_RETENTION_DAYS = 30
    private const val KEY_MCP_ARTIFACT_RETENTION_HOURS = "mcp.artifact-retention-hours"

    /** 配置键名:MCP 工件容量上限 GiB。 */
    private const val KEY_MCP_ARTIFACT_MAX_GIB = "mcp.artifact-max-gib"

    /** 配置键名:插件桥总开关。 */
    private const val KEY_BRIDGE_ENABLED = "bridge.enabled"

    /** 配置键名:插件桥 Worker WS 地址。 */
    private const val KEY_BRIDGE_URL = "bridge.url"

    /** 配置键名:插件桥实例标识。 */
    private const val KEY_BRIDGE_INSTANCE = "bridge.instance"

    /** 配置键名:插件桥连接 token。 */
    private const val KEY_BRIDGE_TOKEN = "bridge.token"

    // ----- 插件桥线路治理键名(bridge.line.*)-----

    /** 配置键名:桥读超时兼心跳节拍(毫秒)。 */
    private const val KEY_BRIDGE_LINE_READ_TIMEOUT_MS = "bridge.line.read-timeout-ms"

    /** 配置键名:桥写侧 watchdog 上限(毫秒)。 */
    private const val KEY_BRIDGE_LINE_WRITE_DEADLINE_MS = "bridge.line.write-deadline-ms"

    /** 配置键名:入站单帧硬上限(字节)。 */
    private const val KEY_BRIDGE_LINE_MAX_INBOUND_BYTES = "bridge.line.max-inbound-bytes"

    /** 配置键名:命令池并发位。 */
    private const val KEY_BRIDGE_LINE_CMD_POOL_SIZE = "bridge.line.cmd-pool-size"

    /** 配置键名:命令池队列容量。 */
    private const val KEY_BRIDGE_LINE_CMD_QUEUE_CAPACITY = "bridge.line.cmd-queue-capacity"

    /** 配置键名:事件池并发位。 */
    private const val KEY_BRIDGE_LINE_EVENT_POOL_SIZE = "bridge.line.event-pool-size"

    /** 配置键名:事件池队列容量。 */
    private const val KEY_BRIDGE_LINE_EVENT_QUEUE_CAPACITY = "bridge.line.event-queue-capacity"

    /** 配置键名:重载协调池并发位。 */
    private const val KEY_BRIDGE_LINE_RELOAD_POOL_SIZE = "bridge.line.reload-pool-size"

    /** 配置键名:重载协调池队列容量。 */
    private const val KEY_BRIDGE_LINE_RELOAD_QUEUE_CAPACITY = "bridge.line.reload-queue-capacity"

    /** 配置键名:可丢事件队列容量。 */
    private const val KEY_BRIDGE_LINE_BEST_EFFORT_CAPACITY = "bridge.line.best-effort-capacity"

    /** 配置键名:不可丢事件队列容量。 */
    private const val KEY_BRIDGE_LINE_RELIABLE_CAPACITY = "bridge.line.reliable-capacity"

    /** 配置键名:命令回执队列容量。 */
    private const val KEY_BRIDGE_LINE_RECEIPT_CAPACITY = "bridge.line.receipt-capacity"

    /** 配置键名:回执有界阻塞预算(毫秒)。 */
    private const val KEY_BRIDGE_LINE_RECEIPT_OFFER_BUDGET_MS = "bridge.line.receipt-offer-budget-ms"

    /** 配置键名:不可丢事件落盘兜底开关。 */
    private const val KEY_BRIDGE_LINE_SPILL_ENABLED = "bridge.line.spill-enabled"

    /** 配置键名:落盘目录。 */
    private const val KEY_BRIDGE_LINE_SPILL_DIR = "bridge.line.spill-dir"

    /** 配置键名:落盘文件数上限。 */
    private const val KEY_BRIDGE_LINE_SPILL_MAX_FILES = "bridge.line.spill-max-files"

    /** 配置键名:落盘总字节上限。 */
    private const val KEY_BRIDGE_LINE_SPILL_MAX_BYTES = "bridge.line.spill-max-bytes"

    // ----- 业务命令派发键名(bridge.dispatch.*)-----

    /** 配置键名:业务命令派发超时(毫秒)。 */
    private const val KEY_BRIDGE_DISPATCH_TIMEOUT_MS = "bridge.dispatch.timeout-ms"

    /** 配置键名:业务线程池并发位。 */
    private const val KEY_BRIDGE_DISPATCH_POOL_SIZE = "bridge.dispatch.pool-size"

    // ----- 配置重载键名(bridge.reload.*)-----

    /** 配置键名:单次重载总预算(毫秒)。 */
    private const val KEY_BRIDGE_RELOAD_TIMEOUT_MS = "bridge.reload.timeout-ms"

    /** 配置键名:单次请求路径数上限。 */
    private const val KEY_BRIDGE_RELOAD_MAX_PATHS_PER_REQUEST = "bridge.reload.max-paths-per-request"

    /** 配置键名:重载监听器 worker 池并发位。 */
    private const val KEY_BRIDGE_RELOAD_WORKER_POOL_SIZE = "bridge.reload.worker-pool-size"

    /** 配置键名:在途判定卡死时长(毫秒)。 */
    private const val KEY_BRIDGE_RELOAD_STUCK_AFTER_MS = "bridge.reload.stuck-after-ms"

    /** 配置键名:重载熔断开关。 */
    private const val KEY_BRIDGE_RELOAD_BREAKER_ENABLED = "bridge.reload.circuit-breaker.enabled"

    /** 配置键名:重载熔断统计窗口(毫秒)。 */
    private const val KEY_BRIDGE_RELOAD_BREAKER_FAILURE_WINDOW_MS = "bridge.reload.circuit-breaker.failure-window-ms"

    /** 配置键名:重载熔断首次打开时长(毫秒)。 */
    private const val KEY_BRIDGE_RELOAD_BREAKER_OPEN_DURATION_MS = "bridge.reload.circuit-breaker.open-duration-ms"

    /** 配置键名:重载熔断打开时长上限(毫秒)。 */
    private const val KEY_BRIDGE_RELOAD_BREAKER_MAX_OPEN_DURATION_MS = "bridge.reload.circuit-breaker.max-open-duration-ms"

    /** 配置键名:重载熔断触发阈值(窗口内超时次数)。 */
    private const val KEY_BRIDGE_RELOAD_BREAKER_FAILURE_THRESHOLD = "bridge.reload.circuit-breaker.failure-threshold"

    /** 配置键名:重载熔断表条目上限。 */
    private const val KEY_BRIDGE_RELOAD_BREAKER_MAX_ENTRIES = "bridge.reload.circuit-breaker.max-entries"

    // ----- 帧分片键名(bridge.chunk.*)-----

    /** 配置键名:帧分片开关。 */
    private const val KEY_BRIDGE_CHUNK_ENABLED = "bridge.chunk.enabled"

    /** 配置键名:单帧整帧字节上限。 */
    private const val KEY_BRIDGE_CHUNK_MAX_FRAME_BYTES = "bridge.chunk.max-frame-bytes"

    /** 配置键名:单组片数上限。 */
    private const val KEY_BRIDGE_CHUNK_MAX_CHUNKS_PER_GROUP = "bridge.chunk.max-chunks-per-group"

    // ----- 主题订阅键名(bridge.subscribe.*)-----

    /** 配置键名:订阅表达式数量上限。 */
    private const val KEY_BRIDGE_SUBSCRIBE_MAX_TOPICS = "bridge.subscribe.max-topics"

    /** 配置键名:单个订阅表达式长度上限。 */
    private const val KEY_BRIDGE_SUBSCRIBE_MAX_EXPRESSION_CHARS = "bridge.subscribe.max-expression-chars"

    // ----- Provider 健康键名(bridge.health.*)-----

    /** 配置键名:健康快照刷新周期(毫秒)。 */
    private const val KEY_BRIDGE_HEALTH_REFRESH_INTERVAL_MS = "bridge.health.refresh-interval-ms"

    /** 配置键名:单次 health() 有界等待上限(毫秒)。 */
    private const val KEY_BRIDGE_HEALTH_TIMEOUT_MS = "bridge.health.timeout-ms"

    /** 配置键名:超时/异常后该域的隔离窗口(毫秒)。 */
    private const val KEY_BRIDGE_HEALTH_QUARANTINE_MS = "bridge.health.quarantine-ms"

    /** 配置键名:注销后健康状态保留时长(毫秒)。 */
    private const val KEY_BRIDGE_HEALTH_RETAIN_AFTER_UNREGISTER_MS = "bridge.health.retain-after-unregister-ms"

    /** 配置键名:健康状态表条目上限。 */
    private const val KEY_BRIDGE_HEALTH_MAX_ENTRIES = "bridge.health.max-entries"

    /** 配置键名:每域 reason 的截断长度(字符)。 */
    private const val KEY_BRIDGE_HEALTH_REASON_MAX_CHARS = "bridge.health.reason-max-chars"

    /** 配置键名:每域 detailJson 的截断长度(字符)。 */
    private const val KEY_BRIDGE_HEALTH_DETAIL_MAX_CHARS = "bridge.health.detail-max-chars"

    // ----- 连接自愈与心跳键名(bridge.reconnect.*)-----

    /** 配置键名:周期 ping 间隔(毫秒)。 */
    private const val KEY_BRIDGE_RECONNECT_PING_INTERVAL_MS = "bridge.reconnect.ping-interval-ms"

    /** 配置键名:pong 等待上限(毫秒)。 */
    private const val KEY_BRIDGE_RECONNECT_PONG_TIMEOUT_MS = "bridge.reconnect.pong-timeout-ms"

    /** 配置键名:首次重连退避(毫秒)。 */
    private const val KEY_BRIDGE_RECONNECT_INITIAL_BACKOFF_MS = "bridge.reconnect.initial-backoff-ms"

    /** 配置键名:常规退避上限(毫秒)。 */
    private const val KEY_BRIDGE_RECONNECT_MAX_BACKOFF_MS = "bridge.reconnect.max-backoff-ms"

    /** 配置键名:认证失败退避上限(毫秒)。 */
    private const val KEY_BRIDGE_RECONNECT_AUTH_FAILED_MAX_BACKOFF_MS = "bridge.reconnect.auth-failed-max-backoff-ms"

    /** 配置键名:认证失败降频阈值(连续失败次数)。 */
    private const val KEY_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADE_THRESHOLD = "bridge.reconnect.auth-failed-degrade-threshold"

    /** 配置键名:认证失败降频后的退避(毫秒)。 */
    private const val KEY_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADED_BACKOFF_MS = "bridge.reconnect.auth-failed-degraded-backoff-ms"

    /** 配置键名:告警总开关。 */
    private const val KEY_ALERT_ENABLED = "alert.enabled"

    /** 配置键名:日志告警通道开关。 */
    private const val KEY_ALERT_CHANNEL_LOG = "alert.channels.log"

    /** 配置键名:游戏内告警通道开关。 */
    private const val KEY_ALERT_CHANNEL_IN_GAME = "alert.channels.in-game"

    /** 配置键名:Webhook 告警通道开关。 */
    private const val KEY_ALERT_WEBHOOK_ENABLED = "alert.channels.webhook.enabled"

    /** 配置键名:Webhook 回调地址。 */
    private const val KEY_ALERT_WEBHOOK_URL = "alert.channels.webhook.url"

    /** 配置键名:Webhook 超时(毫秒)。 */
    private const val KEY_ALERT_WEBHOOK_TIMEOUT_MS = "alert.channels.webhook.timeout-ms"

    /** 配置键名:告警规则段根。 */
    private const val KEY_ALERT_RULES = "alert.rules"

    /** 配置键名:CPU 归因总开关。 */
    private const val KEY_CPU_ENABLED = "cpu.enabled"

    /** 配置键名:Incision 方法级精确归因总开关。 */
    private const val KEY_INCISION_ENABLED = "incision.enabled"

    /** 配置键名:CPU 采样周期(ticks)。 */
    private const val KEY_CPU_SAMPLE_PERIOD_TICKS = "cpu.sample-period-ticks"

    /** 配置键名:CPU 归因聚合窗口(轮)。 */
    private const val KEY_CPU_WINDOW_ROUNDS = "cpu.window-rounds"

    /** 配置键名:Web 面板总开关。 */
    private const val KEY_WEB_ENABLED = "web.enabled"

    /** 配置键名:Web 面板绑定地址。 */
    private const val KEY_WEB_HOST = "web.host"

    /** 配置键名:Web 面板监听端口。 */
    private const val KEY_WEB_PORT = "web.port"

    /** 配置键名:Web 面板鉴权 token。 */
    private const val KEY_WEB_TOKEN = "web.token"

    /** 配置键名:Web 面板 IP 白名单。 */
    private const val KEY_WEB_ALLOWED_IPS = "web.allowed-ips"

    /** 规则子键名:阈值。 */
    private const val SUB_THRESHOLD = "threshold"

    /** 规则子键名:持续周期。 */
    private const val SUB_SUSTAIN_CYCLES = "sustain-cycles"

    /** 规则子键名:级别。 */
    private const val SUB_LEVEL = "level"

    /** 规则子键名:开关。 */
    private const val SUB_ENABLED = "enabled"

    /** 默认采集周期(ticks)。 */
    private const val DEFAULT_COLLECT_PERIOD_TICKS = 100L

    /** 默认近期历史容量(份)。 */
    private const val DEFAULT_HISTORY_CAPACITY = 360

    /** 默认慢插件榜条数。 */
    private const val DEFAULT_STARTUP_TOP_N = 5

    /** 默认指标聚合窗口(份),约 1 分钟 @ 5 秒采集周期。 */
    private const val DEFAULT_AGGREGATION_WINDOW = 12

    /** 默认调试日志开关。 */
    private const val DEFAULT_DEBUG = false

    /** 默认启动画像归档保留份数。 */
    private const val DEFAULT_HISTORY_RETENTION = 30

    /** 默认历史指标落盘开关。 */
    private const val DEFAULT_HISTORY_FILE_ENABLED = true

    /** 默认历史指标文件保留天数。 */
    private const val DEFAULT_HISTORY_FILE_RETENTION_DAYS = 7

    /** 默认历史指标文件总体积上限(MB)。 */
    private const val DEFAULT_HISTORY_FILE_MAX_TOTAL_MB = 200
    private const val DEFAULT_HISTORY_FILE_ARCHIVE_DAYS = 30
    private const val DEFAULT_AGENT_STACK_MAX_SAMPLES = 10

    /** 默认世界采样周期(ticks),约 30 秒。 */
    private const val DEFAULT_WORLD_SAMPLE_PERIOD_TICKS = 600L

    /** 默认世界按类型实体统计开关。 */
    private const val DEFAULT_WORLD_ENTITY_TYPES = true

    /** 默认 Folia 已观测 region 过期秒数。 */
    private const val DEFAULT_FOLIA_OBSERVED_REGION_EXPIRE_SECONDS = 60L

    /** Folia 已观测 region 过期秒数下限。 */
    private const val MIN_FOLIA_OBSERVED_REGION_EXPIRE_SECONDS = 1L

    /** 默认网络取证开关：开启。 */
    private const val DEFAULT_NETWORK_FORENSICS_ENABLED = true

    /** 默认网络取证单包载荷上限：64 KiB。 */
    private const val DEFAULT_NETWORK_FORENSICS_MAX_PAYLOAD_BYTES = 64 * 1_024

    /** 网络取证单包载荷下限。 */
    private const val MIN_NETWORK_FORENSICS_MAX_PAYLOAD_BYTES = 1

    /** 默认网络取证保留天数。 */
    private const val DEFAULT_NETWORK_FORENSICS_RETENTION_DAYS = 60

    /** 默认网络取证数据库上限，单位 GiB。 */
    private const val DEFAULT_NETWORK_FORENSICS_MAX_TOTAL_GIB = 4L

    /** 网络取证数据库上限下限，单位 GiB。 */
    private const val MIN_NETWORK_FORENSICS_MAX_TOTAL_GIB = 1L

    /** 每 GiB 的字节数。 */
    private const val BYTES_PER_GIB = 1_024L * 1_024L * 1_024L

    /** 默认外呼监控开关:开启。 */
    private const val DEFAULT_HTTP_MONITOR_ENABLED = true

    /** 默认外呼控制台日志开关:开启。 */
    private const val DEFAULT_HTTP_MONITOR_LOG_CONSOLE = true

    /** 默认外呼日志含请求头:关闭(避免刷屏)。 */
    private const val DEFAULT_HTTP_MONITOR_LOG_HEADERS = false

    /** 默认外呼明细落盘开关:开启。 */
    private const val DEFAULT_HTTP_MONITOR_FILE_ENABLED = true

    /** 默认外呼近期缓冲容量。 */
    private const val DEFAULT_HTTP_MONITOR_RECENT_CAPACITY = 500

    /** 默认外呼记录拉取周期(ticks),约 1 秒。 */
    private const val DEFAULT_HTTP_MONITOR_DRAIN_PERIOD_TICKS = 20L

    /** 默认 Prometheus 端点开关:关闭(安全默认,需显式开启)。 */
    private const val DEFAULT_METRICS_ENABLED = false

    /** 默认 Prometheus 端点绑定地址:仅本机回环。 */
    private const val DEFAULT_METRICS_HOST = "127.0.0.1"

    /** 默认 Prometheus 端点监听端口。 */
    private const val DEFAULT_METRICS_PORT = 9940

    /** 默认 Prometheus 端点鉴权 token:空串(不启用 token 鉴权)。 */
    private const val DEFAULT_METRICS_TOKEN = ""

    /** 默认 Prometheus 端点 IP 白名单:仅本机回环(配置缺失/异常时兜底,杜绝裸奔)。 */
    private val DEFAULT_METRICS_ALLOWED_IPS = listOf("127.0.0.1")

    /** 默认 MCP 控制面关闭。 */
    private const val DEFAULT_MCP_ENABLED = false

    /** 默认 MCP 控制面仅监听本机回环。 */
    private const val DEFAULT_MCP_HOST = "127.0.0.1"

    /** 默认 MCP 控制面监听端口。 */
    private const val DEFAULT_MCP_PORT = 9942

    /** 默认 MCP 控制面不预置密钥。 */
    private const val DEFAULT_MCP_SECRET = ""

    /** 默认 MCP Arthas 同时运行四个任务。 */
    private const val DEFAULT_MCP_TASK_MAX_CONCURRENT = 4

    /** 默认 MCP Arthas 单任务最多运行三十分钟。 */
    private const val DEFAULT_MCP_TASK_TIMEOUT_MINUTES = 30L

    /** 默认 MCP Arthas 单任务最多保留六十四 MiB 输出。 */
    private const val DEFAULT_MCP_TASK_OUTPUT_MEBIBYTES = 64

    /** 默认 MCP Arthas 完成任务保留六十分钟。 */
    private const val DEFAULT_MCP_TASK_RETENTION_MINUTES = 60L

    private const val DEFAULT_MCP_ARTIFACT_RETENTION_HOURS = 24L

    private const val DEFAULT_MCP_ARTIFACT_MAX_GIB = 10L

    /** 默认插件桥总开关:关闭(独立使用探针时不连任何 Worker;由 JianManager 下发开启)。 */
    private const val DEFAULT_BRIDGE_ENABLED = false

    /** 默认插件桥 Worker WS 地址:空串(未配置)。 */
    private const val DEFAULT_BRIDGE_URL = ""

    /** 默认插件桥实例标识:空串(未配置)。 */
    private const val DEFAULT_BRIDGE_INSTANCE = ""

    /** 默认插件桥连接 token:空串(未配置)。 */
    private const val DEFAULT_BRIDGE_TOKEN = ""

    // ----- 插件桥线路治理默认值(bridge.line.*)-----

    /** 默认桥读超时兼心跳节拍(毫秒)。 */
    private const val DEFAULT_BRIDGE_LINE_READ_TIMEOUT_MS = 5_000L

    /** 默认桥写侧 watchdog 上限(毫秒)。 */
    private const val DEFAULT_BRIDGE_LINE_WRITE_DEADLINE_MS = 10_000L

    /** 默认入站单帧硬上限(字节),1 MiB。 */
    private const val DEFAULT_BRIDGE_LINE_MAX_INBOUND_BYTES = 1_048_576

    /** 默认命令池并发位。 */
    private const val DEFAULT_BRIDGE_LINE_CMD_POOL_SIZE = 4

    /** 默认命令池队列容量。 */
    private const val DEFAULT_BRIDGE_LINE_CMD_QUEUE_CAPACITY = 64

    /** 默认事件池并发位。 */
    private const val DEFAULT_BRIDGE_LINE_EVENT_POOL_SIZE = 2

    /** 默认事件池队列容量。 */
    private const val DEFAULT_BRIDGE_LINE_EVENT_QUEUE_CAPACITY = 512

    /** 默认重载协调池并发位。 */
    private const val DEFAULT_BRIDGE_LINE_RELOAD_POOL_SIZE = 4

    /** 默认重载协调池队列容量。 */
    private const val DEFAULT_BRIDGE_LINE_RELOAD_QUEUE_CAPACITY = 32

    /** 默认可丢事件队列容量。 */
    private const val DEFAULT_BRIDGE_LINE_BEST_EFFORT_CAPACITY = 2_048

    /** 默认不可丢事件队列容量。 */
    private const val DEFAULT_BRIDGE_LINE_RELIABLE_CAPACITY = 1_024

    /** 默认命令回执队列容量。 */
    private const val DEFAULT_BRIDGE_LINE_RECEIPT_CAPACITY = 256

    /** 默认回执有界阻塞预算(毫秒)。 */
    private const val DEFAULT_BRIDGE_LINE_RECEIPT_OFFER_BUDGET_MS = 500L

    /** 默认启用不可丢事件落盘兜底。 */
    private const val DEFAULT_BRIDGE_LINE_SPILL_ENABLED = true

    /** 默认落盘目录。 */
    private const val DEFAULT_BRIDGE_LINE_SPILL_DIR = "data/bridge-spill"

    /** 默认落盘文件数上限。 */
    private const val DEFAULT_BRIDGE_LINE_SPILL_MAX_FILES = 8

    /** 默认落盘总字节上限(8 MiB)。 */
    private const val DEFAULT_BRIDGE_LINE_SPILL_MAX_BYTES = 8_388_608L

    // ----- 业务命令派发默认值(bridge.dispatch.*)-----

    /** 默认派发有界等待上限(毫秒)。 */
    private const val DEFAULT_BRIDGE_DISPATCH_TIMEOUT_MS = 5_000L

    /** 默认业务线程池并发位。 */
    private const val DEFAULT_BRIDGE_DISPATCH_POOL_SIZE = 8

    // ----- 配置重载默认值(bridge.reload.*)-----

    /** 默认单次重载总预算(毫秒)。 */
    private const val DEFAULT_BRIDGE_RELOAD_TIMEOUT_MS = 5_000L

    /** 默认单次请求路径数上限。 */
    private const val DEFAULT_BRIDGE_RELOAD_MAX_PATHS_PER_REQUEST = 32

    /** 默认重载监听器 worker 池并发位。 */
    private const val DEFAULT_BRIDGE_RELOAD_WORKER_POOL_SIZE = 8

    /** 默认在途判定卡死时长(毫秒)。 */
    private const val DEFAULT_BRIDGE_RELOAD_STUCK_AFTER_MS = 30_000L

    /** 默认启用重载熔断。 */
    private const val DEFAULT_BRIDGE_RELOAD_BREAKER_ENABLED = true

    /** 默认熔断统计窗口(毫秒)。 */
    private const val DEFAULT_BRIDGE_RELOAD_BREAKER_FAILURE_WINDOW_MS = 60_000L

    /** 默认熔断首次打开时长(毫秒)。 */
    private const val DEFAULT_BRIDGE_RELOAD_BREAKER_OPEN_DURATION_MS = 60_000L

    /** 默认熔断打开时长上限(毫秒)。 */
    private const val DEFAULT_BRIDGE_RELOAD_BREAKER_MAX_OPEN_DURATION_MS = 600_000L

    /** 默认熔断触发阈值(窗口内超时次数)。 */
    private const val DEFAULT_BRIDGE_RELOAD_BREAKER_FAILURE_THRESHOLD = 1

    /** 默认熔断表条目上限。 */
    private const val DEFAULT_BRIDGE_RELOAD_BREAKER_MAX_ENTRIES = 256

    // ----- 帧分片默认值(bridge.chunk.*)-----

    /** 默认启用帧分片。 */
    private const val DEFAULT_BRIDGE_CHUNK_ENABLED = true

    /** 默认单帧整帧字节上限(256 KiB)。 */
    private const val DEFAULT_BRIDGE_CHUNK_MAX_FRAME_BYTES = 262_144

    /** 默认单组片数上限。 */
    private const val DEFAULT_BRIDGE_CHUNK_MAX_CHUNKS_PER_GROUP = 64

    // ----- 主题订阅默认值(bridge.subscribe.*)-----

    /** 默认订阅表达式数量上限。 */
    private const val DEFAULT_BRIDGE_SUBSCRIBE_MAX_TOPICS = 256

    /** 默认单个订阅表达式长度上限。 */
    private const val DEFAULT_BRIDGE_SUBSCRIBE_MAX_EXPRESSION_CHARS = 256

    // ----- Provider 健康默认值(bridge.health.*)-----

    /** 默认健康快照刷新周期(毫秒)。 */
    private const val DEFAULT_BRIDGE_HEALTH_REFRESH_INTERVAL_MS = 10_000L

    /** 默认单次 health() 有界等待上限(毫秒)。 */
    private const val DEFAULT_BRIDGE_HEALTH_TIMEOUT_MS = 1_000L

    /** 默认隔离窗口(毫秒)。 */
    private const val DEFAULT_BRIDGE_HEALTH_QUARANTINE_MS = 60_000L

    /** 默认注销后健康状态保留时长(毫秒)。 */
    private const val DEFAULT_BRIDGE_HEALTH_RETAIN_AFTER_UNREGISTER_MS = 300_000L

    /** 默认健康状态表条目上限。 */
    private const val DEFAULT_BRIDGE_HEALTH_MAX_ENTRIES = 256

    /** 默认 reason 截断长度(字符)。 */
    private const val DEFAULT_BRIDGE_HEALTH_REASON_MAX_CHARS = 512

    /** 默认 detailJson 截断长度(字符)。 */
    private const val DEFAULT_BRIDGE_HEALTH_DETAIL_MAX_CHARS = 2_048

    // ----- 连接自愈与心跳默认值(bridge.reconnect.*)-----

    /** 默认周期 ping 间隔(毫秒)。 */
    private const val DEFAULT_BRIDGE_RECONNECT_PING_INTERVAL_MS = 20_000L

    /** 默认 pong 等待上限(毫秒)。 */
    private const val DEFAULT_BRIDGE_RECONNECT_PONG_TIMEOUT_MS = 60_000L

    /** 默认首次重连退避(毫秒)。 */
    private const val DEFAULT_BRIDGE_RECONNECT_INITIAL_BACKOFF_MS = 1_000L

    /** 默认常规退避上限(毫秒)。 */
    private const val DEFAULT_BRIDGE_RECONNECT_MAX_BACKOFF_MS = 30_000L

    /** 默认认证失败退避上限(毫秒)。 */
    private const val DEFAULT_BRIDGE_RECONNECT_AUTH_FAILED_MAX_BACKOFF_MS = 10_000L

    /** 默认认证失败降频阈值(连续失败次数)。 */
    private const val DEFAULT_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADE_THRESHOLD = 10

    /** 默认认证失败降频后的退避(毫秒)。 */
    private const val DEFAULT_BRIDGE_RECONNECT_AUTH_FAILED_DEGRADED_BACKOFF_MS = 60_000L

    /** 默认告警总开关:关闭(需显式开启)。 */
    private const val DEFAULT_ALERT_ENABLED = false

    /** 默认 CPU 归因总开关:关闭(P2 增强,需显式开启)。 */
    private const val DEFAULT_CPU_ENABLED = false

    /** 默认 Incision 方法级精确归因开关:关闭(需显式开启)。 */
    private const val DEFAULT_INCISION_ENABLED = false

    /** 默认 CPU 采样周期(ticks),约 1 秒。 */
    private const val DEFAULT_CPU_SAMPLE_PERIOD_TICKS = 20L

    /** 默认 CPU 归因聚合窗口(轮),约 1 分钟 @ 1 秒采样周期。 */
    private const val DEFAULT_CPU_WINDOW_ROUNDS = 60

    /** 默认 Web 面板总开关:关闭(需显式开启)。 */
    private const val DEFAULT_WEB_ENABLED = false

    /** 默认 Web 面板绑定地址:仅本机回环。 */
    private const val DEFAULT_WEB_HOST = "127.0.0.1"

    /** 默认 Web 面板监听端口(与 Prometheus 9940 区分)。 */
    private const val DEFAULT_WEB_PORT = 9941

    /** 默认 Web 面板鉴权 token:空串(不启用 token 鉴权)。 */
    private const val DEFAULT_WEB_TOKEN = ""

    /** 默认 Web 面板 IP 白名单:仅本机回环(配置缺失/异常时兜底,杜绝裸奔)。 */
    private val DEFAULT_WEB_ALLOWED_IPS = listOf("127.0.0.1")

    /** 默认日志告警通道开关:开启。 */
    private const val DEFAULT_ALERT_CHANNEL_LOG = true

    /** 默认游戏内告警通道开关:开启。 */
    private const val DEFAULT_ALERT_CHANNEL_IN_GAME = true

    /** 默认 Webhook 告警通道开关:关闭。 */
    private const val DEFAULT_ALERT_WEBHOOK_ENABLED = false

    /** 默认 Webhook 回调地址:空串(未配置)。 */
    private const val DEFAULT_ALERT_WEBHOOK_URL = ""

    /** 默认 Webhook 超时(毫秒)。 */
    private const val DEFAULT_ALERT_WEBHOOK_TIMEOUT_MS = 3000

    /** Webhook 超时下限(毫秒):兜底,避免 0/负值导致无限等待。 */
    private const val MIN_ALERT_WEBHOOK_TIMEOUT_MS = 500

    /** 持续周期下限:至少 1 个采集周期。 */
    private const val MIN_SUSTAIN_CYCLES = 1
}
