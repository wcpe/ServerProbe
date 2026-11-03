package top.wcpe.mc.plugin.serverprobe.core.config

/**
 * 托管配置的**运行期版本状态**(FR-44 / FR-45)。
 *
 * 与 [ManagedConfigStore](只存值)分工:本类记"这些值是第几版、什么时候生效、上次为什么没生效"。
 * 三份数据必须一起被读到,故收在同一个对象里,而不是各自一个散落的全局变量。
 *
 * ## 为什么是独立单例而不是 IOC 组件
 * 写入方是桥的后台线程(`config_push` 应用链路),读取方是 `config_state` 上报线程与启动期加载链路;
 * 三者都不经过 IOC 容器获取对方,用 `@Volatile` + `@Synchronized` 的进程级视图最省事,
 * 也与 [ManagedConfigStore] / [ControlPlaneRuntime] 的既有形态一致。
 *
 * ## 版本只增不减
 * [configVersion] 只在**应用成功**或**启动加载缓存**时推进。被拒/自检失败一律不推进——
 * 否则"陈旧的推送"会把版本号往前挪,控制面对账时会以为探针接受了那一版。
 */
object ManagedConfigState {

    /** 当前生效版本;从未下发且无缓存时为 0。 */
    @Volatile
    private var version = 0L

    /** 上次成功应用的时刻(epoch 秒);未知时为 null(缓存时间戳不可解析,或从未成功应用)。 */
    @Volatile
    private var appliedAtSeconds: Long? = null

    /** 上次失败/被拒的原因(中文);最近一次成功应用后清空。 */
    @Volatile
    private var lastError: String? = null

    /** 当前生效版本(单调不减)。 */
    fun configVersion(): Long = version

    /** 上次成功应用的时刻(epoch 秒);未知时为 null。 */
    fun lastAppliedAtSeconds(): Long? = appliedAtSeconds

    /** 上次失败/被拒的原因;最近一次成功应用后为 null。 */
    fun lastError(): String? = lastError

    /**
     * 记录一次成功应用(或启动时加载缓存):推进版本、刷新应用时刻、清空错误。
     *
     * @param configVersion 生效版本。
     * @param appliedAtEpochSeconds 生效时刻(epoch 秒);缓存时间戳不可解析时传 null(如实留空,不伪造)。
     */
    @Synchronized
    fun markApplied(configVersion: Long, appliedAtEpochSeconds: Long?) {
        // 单调不减：并发/乱序到达时，已生效的版本绝不能被写回去
        version = maxOf(version, configVersion)
        appliedAtSeconds = appliedAtEpochSeconds
        lastError = null
    }

    /**
     * 记录一次失败/被拒。
     *
     * **不推进版本**:被拒的载荷没有生效,版本号必须停在原地,否则控制面对账会误判。
     *
     * @param error 中文原因(会原样进入 `config_state` 的 `lastError`)。
     */
    @Synchronized
    fun recordError(error: String) {
        lastError = error
    }

    /** 清空为"从未下发"的初始态(仅测试用:隔离用例间的全局状态)。 */
    internal fun reset() {
        synchronized(this) {
            version = 0L
            appliedAtSeconds = null
            lastError = null
        }
    }
}
