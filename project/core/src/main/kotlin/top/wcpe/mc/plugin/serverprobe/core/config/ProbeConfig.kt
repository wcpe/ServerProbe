package top.wcpe.mc.plugin.serverprobe.core.config

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
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

    /** 配置键名:实例名覆盖。 */
    private const val KEY_SERVER_NAME = "server-name"

    /** 配置键名:调试日志开关。 */
    private const val KEY_DEBUG = "debug"

    /** 配置键名:启动画像归档保留份数。 */
    private const val KEY_HISTORY_RETENTION = "history-retention"

    /** 默认采集周期(ticks)。 */
    private const val DEFAULT_COLLECT_PERIOD_TICKS = 100L

    /** 默认近期历史容量(份)。 */
    private const val DEFAULT_HISTORY_CAPACITY = 360

    /** 默认慢插件榜条数。 */
    private const val DEFAULT_STARTUP_TOP_N = 5

    /** 默认调试日志开关。 */
    private const val DEFAULT_DEBUG = false

    /** 默认启动画像归档保留份数。 */
    private const val DEFAULT_HISTORY_RETENTION = 30
}
