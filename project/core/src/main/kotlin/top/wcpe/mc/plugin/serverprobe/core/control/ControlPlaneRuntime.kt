package top.wcpe.mc.plugin.serverprobe.core.control

/**
 * 当前托管运行态(运行模式 + 身份)的**进程级只读视图**。
 *
 * ## 为什么需要这个视图
 * [top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig] 是纯 Kotlin `object`(由 TabooLib 的 `@Config`
 * 反射注入),**没有 IOC 注入点**,无法直接拿到 [ControlPlaneModeService]。而配置取值必须能判断
 * "当前是托管还是独立"。故把运行态收敛到本视图:[ControlPlaneModeService] 是唯一写入方,配置侧只读。
 *
 * ## 单一真源
 * 运行模式与身份**只**保存在这里([ControlPlaneModeService] 不再自留副本),避免"服务字段与全局视图各说一套"。
 *
 * ## 并发与可见性
 * 字段均 `@Volatile`:判定在 `ServerProbe-ManagedMode` 后台线程写,配置读取在主线程/采集线程读。
 * 判定完成前恒为 [ProbeMode.STANDALONE]——这是保守值,与引入托管能力前的行为一致。
 */
object ControlPlaneRuntime {

    /** 当前运行模式;判定完成前为独立模式。 */
    @Volatile
    private var currentMode: ProbeMode = ProbeMode.STANDALONE

    /** 当前身份;仅托管模式非 null。 */
    @Volatile
    private var currentIdentity: ControlPlaneIdentity? = null

    /** 当前运行模式。 */
    fun mode(): ProbeMode = currentMode

    /** 当前托管身份;独立模式下为 null。 */
    fun identity(): ControlPlaneIdentity? = currentIdentity

    /** 当前是否托管模式。 */
    fun isManaged(): Boolean = currentMode == ProbeMode.MANAGED

    /**
     * 发布运行态(仅由 [ControlPlaneModeService] 的判定链路调用)。
     *
     * @param mode 判定结论。
     * @param identity 托管身份;独立模式必须传 null。
     */
    fun publish(mode: ProbeMode, identity: ControlPlaneIdentity?) {
        currentIdentity = identity
        currentMode = mode
    }

    /** 回落到独立模式(判定异常或无数据目录时的保守兜底)。 */
    fun reset() = publish(ProbeMode.STANDALONE, null)
}
