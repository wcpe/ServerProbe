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

    /**
     * 是否存在「托管意图」（身份文件存在）。
     *
     * 与 [currentMode] 分开是因为这两件事的时间点不同：身份文件是否存在在判定链路极早期就能确认，
     * 而模式结论要等迁移/解析跑完。监听面在**判定超时**时拿不到结论，只能靠这个信号决定
     * fail-open（确定是独立部署）还是 fail-close（有托管意图，宁可不开端口）。
     *
     * 粘性语义：一旦为 true 就不会被 `publish(STANDALONE)` 抹掉——那是“判定结论”，不是“没有身份”；
     * 只有 [reset] 这种“回到初始态”才会清空。
     */
    @Volatile
    private var identityIntent: Boolean = false

    /** 当前运行模式。 */
    fun mode(): ProbeMode = currentMode

    /** 当前托管身份;独立模式下为 null。 */
    fun identity(): ControlPlaneIdentity? = currentIdentity

    /** 存在托管意图（身份文件存在），即使判定尚未完成/已降级。 */
    fun identityPresent(): Boolean = identityIntent

    /** 当前是否托管模式。 */
    fun isManaged(): Boolean = currentMode == ProbeMode.MANAGED

    /**
     * 发布运行态(仅由 [ControlPlaneModeService] 的判定链路调用)。
     *
     * @param mode 判定结论。
     * @param identity 托管身份;独立模式必须传 null。
     */
    fun publish(mode: ProbeMode, identity: ControlPlaneIdentity?) {
        if (identity != null) {
            identityIntent = true
        }
        currentIdentity = identity
        currentMode = mode
        // 托管模式落定时补一次回撤：若某个监听面在“判定未完成”的窗口里已经 bind（历史行为或外部干扰），
        // 必须在这里关掉，否则零监听承诺就会在后面整场会话里失效。幂等、无线程亲和性。
        if (mode == ProbeMode.MANAGED) {
            LocalListenerGate.reconcileManaged()
        }
    }

    /**
     * 判定链路早期登记“身份文件是否存在”。
     *
     * 只用于把“托管意图”提前告诉监听面，不参与模式结论；[present] 为 false 时不做任何事
     * （避免把粘性标志误清掉）。
     */
    internal fun markIdentityPresent(present: Boolean) {
        if (present) {
            identityIntent = true
        }
    }

    /** 回落到独立模式(判定异常或无数据目录时的保守兜底)。同时清空托管意图，回到初始态。 */
    fun reset() {
        identityIntent = false
        publish(ProbeMode.STANDALONE, null)
    }
}
