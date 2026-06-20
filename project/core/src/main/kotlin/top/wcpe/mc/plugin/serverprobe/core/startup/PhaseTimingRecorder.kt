package top.wcpe.mc.plugin.serverprobe.core.startup

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import top.wcpe.mc.plugin.serverprobe.api.enums.StartupPhase
import top.wcpe.mc.plugin.serverprobe.api.model.PhaseTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * 启动分段打点器(FR1.4,"本插件视角"的生命周期分段)。
 *
 * 借助 TabooLib 生命周期钩子([Awake]),在 CONST/INIT/LOAD/ENABLE/ACTIVE 各阶段进入时记录一次
 * [System.nanoTime];据此产出各阶段相对**最早记录点**的累计耗时,作为启动画像的分段数据。
 *
 * 取代原先散落在插件主类的 `phaseNanos`:打点逻辑下沉到 core,使平台/主类无需再维护时间戳,
 * 由 [StartupProfileBuilder] 统一消费。
 *
 * **时间轴口径(关键,呈现层须知)**:本打点器的最早记录点为 **TabooLib CONST 初始化时刻**,
 * 它**晚于** JVM 进程的绝对启动点(后者见 [StartupProfile.totalMs] / [StartupProfile.jvmStartTimeMs])。
 * 因此 `phaseTimings` 是一条"自 CONST 起算"的相对时间轴,与基于 JVM 绝对启动点的 `totalMs` **不在同一基准**;
 * 呈现层切勿把二者混在同一条绝对时间轴上比较或相加。
 *
 * 形态选择:以 `object` + `@Awake` 实现,而非 IOC [top.wcpe.taboolib.ioc.annotation.Service]——
 * `@Awake` 由 TabooLib 自身的生命周期扫描器驱动(独立于 IOC 容器),需在容器装配前的极早阶段
 * (CONST)即开始打点;且本打点器无需被注入,故不纳入 IOC。其产出经 [phaseTimings] 静态读取。
 *
 * 线程模型:在 Folia 上 [LifeCycle.ACTIVE] 可能由异步调度线程触发,与主线程的早期阶段非同一线程,
 * 读取方([StartupProfileBuilder])亦可能在第三线程,故以 [ConcurrentHashMap] 保证跨线程可见与安全。
 */
@Awake
object PhaseTimingRecorder {

    /**
     * 各启动阶段进入时的纳秒时间戳。
     *
     * 键为 [StartupPhase],值为该阶段的 [System.nanoTime];未到达的阶段不存在对应键。
     */
    private val phaseNanos = ConcurrentHashMap<StartupPhase, Long>()

    @Awake(LifeCycle.CONST)
    fun onConst() {
        phaseNanos[StartupPhase.CONST] = System.nanoTime()
    }

    @Awake(LifeCycle.INIT)
    fun onInit() {
        phaseNanos[StartupPhase.INIT] = System.nanoTime()
    }

    @Awake(LifeCycle.LOAD)
    fun onLoad() {
        phaseNanos[StartupPhase.LOAD] = System.nanoTime()
    }

    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        phaseNanos[StartupPhase.ENABLE] = System.nanoTime()
    }

    @Awake(LifeCycle.ACTIVE)
    fun onActive() {
        phaseNanos[StartupPhase.ACTIVE] = System.nanoTime()
    }

    /**
     * 产出各启动分段耗时,按 [StartupPhase.ordinal] 升序排列。
     *
     * 取一致性快照后委托纯函数 [computeTimings] 计算,避免读取期间并发写入导致的基准漂移。
     * 语义见 [computeTimings]。
     *
     * @return 分段耗时列表(按阶段顺序);无打点时为空列表。
     */
    fun phaseTimings(): List<PhaseTiming> = computeTimings(HashMap(phaseNanos))

    /**
     * 由"各阶段 nanoTime 映射"计算分段耗时(纯函数,无副作用,便于单测)。
     *
     * **语义说明**:每项 [PhaseTiming.durationMs] 为"该阶段相对**最早记录点**的累计耗时(毫秒)"——
     * 即 `(该阶段 nanoTime − 最早阶段 nanoTime) / 1e6`,而非相邻阶段的差值。
     * 因此最早阶段(通常为 CONST)恒为 0,后续阶段单调递增,直观反映"到达各阶段时已耗时多久"。
     *
     * 仅包含传入映射中存在(已打点)的阶段;空映射返回空列表。
     *
     * @param phaseNanos 各阶段进入时的 [System.nanoTime] 映射。
     * @return 分段耗时列表(按 [StartupPhase.ordinal] 升序);空映射时为空列表。
     */
    fun computeTimings(phaseNanos: Map<StartupPhase, Long>): List<PhaseTiming> {
        if (phaseNanos.isEmpty()) {
            return emptyList()
        }
        val baseline = phaseNanos.values.min()
        return phaseNanos.entries
            .sortedBy { it.key.ordinal }
            .map { (phase, nanos) -> PhaseTiming.builder().phase(phase).durationMs((nanos - baseline) / NANOS_PER_MILLI).build() }
    }

    /** 一毫秒的纳秒数。 */
    private const val NANOS_PER_MILLI = 1_000_000L
}
