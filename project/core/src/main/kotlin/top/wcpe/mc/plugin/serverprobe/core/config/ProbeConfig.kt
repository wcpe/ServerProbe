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
 * 探针配置门面(FR1.5 配套)。
 *
 * 经 TabooLib 的 [Config] 机制在加载期把 `config.yml` 注入到 [conf](不存在则由 TabooLib 从插件资源释放默认副本)。
 * 以 `object` 实现并对外暴露**带默认值兜底的取值方法**:配置缺项或类型不符时回退默认值,确保即使配置异常也不致空指针。
 * 各取值方法对应一个配置键,集中收口默认值与键名,避免散落的魔法值(规范第 6 条)。
 *
 * 注:本类不纳入 IOC 容器——[Config] 字段注入由 TabooLib 反射扫描完成,与 IOC 互不干扰;
 * IOC 组件直接以 Kotlin `object` 方式读取本类即可,无需注入。
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
     * 用于 `/probe tps` 的聚合补充行:对最近这么多份快照做跨快照统计(FR3.3)。
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
     * 是否开启历史指标落盘(FR3.2),默认 true。
     *
     * 关闭后不再把采集快照按日追加到 `data/metrics/` 下的 JSONL 文件(与内存缓冲、启动画像归档相互独立)。
     *
     * @return 是否落盘历史指标。
     */
    fun historyFileEnabled(): Boolean = conf.getBoolean(KEY_HISTORY_FILE_ENABLED, DEFAULT_HISTORY_FILE_ENABLED)

    /**
     * 历史指标文件保留天数(FR3.2),默认 7;按自然日,含当天在内保留最近 N 天,更早的文件清理。
     *
     * @return 保留天数。
     */
    fun historyFileRetentionDays(): Int =
        conf.getInt(KEY_HISTORY_FILE_RETENTION_DAYS, DEFAULT_HISTORY_FILE_RETENTION_DAYS)

    /**
     * 单实例历史指标文件总体积上限(MB,FR3.2),默认 200;超出后从最旧文件起清理至达标(绝不删当天)。
     *
     * @return 体积上限(MB)。
     */
    fun historyFileMaxTotalMb(): Int =
        conf.getInt(KEY_HISTORY_FILE_MAX_TOTAL_MB, DEFAULT_HISTORY_FILE_MAX_TOTAL_MB)

    /**
     * 世界指标采样周期(单位 ticks),默认 600(约 30 秒)。
     *
     * 世界/实体采集需遍历所有世界与区块,开销高于轻量指标,故独立限频、采样后缓存,
     * 避免每个 collect 周期都重扫(FR2.3)。
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
     * 是否开启 Prometheus `/metrics` 端点(FR4.2),默认 false(关闭)。
     *
     * 出于安全默认:端点关闭、仅本地可访问,需显式开启。关闭时 [top.wcpe.mc.plugin.serverprobe.core.prometheus.PrometheusExporter] 不起服。
     *
     * @return 是否开启导出端点。
     */
    fun metricsEnabled(): Boolean = conf.getBoolean(KEY_METRICS_ENABLED, DEFAULT_METRICS_ENABLED)

    /**
     * Prometheus 端点绑定地址,默认 `127.0.0.1`(仅本机回环,不对外暴露)。
     *
     * 如需被同网段 Prometheus 抓取,可改为 `0.0.0.0` 或具体网卡地址,但务必同时配置 [metricsToken] 或 [metricsAllowedIps]。
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
     * 非空时要求请求头 `Authorization: Bearer <token>` 匹配方可访问;为空时仅靠 [metricsAllowedIps] IP 白名单限制。
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

    /**
     * 是否开启告警引擎(FR5),默认 false(关闭)。
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
     * 解析告警规则集(FR5):把 `alert.rules` 下各命名规则解析为 [AlertRule] 列表。
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
     * 既驱动 [alertRules] 解析,又作为 `config.yml` 默认值的单一事实来源(规范第 6 条)。
     *
     * @property key `alert.rules` 下的子键名。
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
        DEADLOCK("deadlock", AlertType.DEADLOCK, 0.0, 1, AlertLevel.CRITICAL, true)
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

    /** 配置键名:世界采样周期(ticks)。 */
    private const val KEY_WORLD_SAMPLE_PERIOD_TICKS = "world.sample-period-ticks"

    /** 配置键名:世界按类型实体统计开关。 */
    private const val KEY_WORLD_ENTITY_TYPES = "world.entity-types"

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

    /** 默认世界采样周期(ticks),约 30 秒。 */
    private const val DEFAULT_WORLD_SAMPLE_PERIOD_TICKS = 600L

    /** 默认世界按类型实体统计开关。 */
    private const val DEFAULT_WORLD_ENTITY_TYPES = true

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

    /** 默认告警总开关:关闭(需显式开启)。 */
    private const val DEFAULT_ALERT_ENABLED = false

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
