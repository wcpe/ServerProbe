package top.wcpe.mc.plugin.serverprobe.core.api

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.model.AggregatedMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.core.aggregator.MetricAggregator
import top.wcpe.mc.plugin.serverprobe.core.buffer.MetricSnapshotBuffer
import top.wcpe.mc.plugin.serverprobe.core.orchestrator.MetricOrchestrator
import top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileHolder
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 只读开放接口实现(FR8.1)。
 *
 * 面向第三方插件的只读数据出口:将查询请求委派给内部组件,仅暴露查询、不暴露任何写入/控制能力。
 * - 最新快照:经 [MetricOrchestrator] 取得;
 * - 近期历史:经 [MetricSnapshotBuffer] 回看(P6 环形缓冲);
 * - 指标聚合:经 [MetricAggregator] 对近期历史做跨快照统计(M2 FR3.3);
 * - 最近启动画像:优先取 [StartupProfileHolder] 内存中的最近一份,内存缺失时回退 [MetricStore] 落盘读取(P7);
 * - 近期快照按时间下界筛(M2 FR8):经 [MetricSnapshotBuffer] 内存过滤,不读盘;
 * - 历史启动画像(M2 FR8):委派 [MetricStore] 读归档,**可能读盘**(故接口 KDoc 约定调用方宜在异步上下文)。
 *
 * ## FR8.1 对外暴露(第三方获取方式)
 * 本类以 `top.wcpe.taboolib.ioc` 的 [Service] 纳入 IOC 容器,**容器内即可按接口类型取用**:
 * ```kotlin
 * val api = top.wcpe.taboolib.ioc.bean.BeanContainer.getBean(ProbeReadApi::class.java)
 * ```
 * 容器在 TabooLib `ACTIVE` 生命周期完成初始化;调用方应在容器就绪后(如自身 `ENABLE`/`ACTIVE` 之后)获取。
 * 本插件内部的 `/probe` 命令即经此机制注入同一实例使用(见 `ProbeCommand`)。
 *
 * 跨插件(独立 jar)无需依赖 IOC 内部细节即可获取本只读 API:见运行期静态门面
 * `top.wcpe.mc.plugin.serverprobe.ServerProbeApi`(对上述 `BeanContainer` 取 bean 的容错封装)。
 */
@Service
class ProbeReadApiImpl : ProbeReadApi {

    /** 采集编排核心,提供最新快照。 */
    @Inject
    lateinit var orchestrator: MetricOrchestrator

    /** 近期历史缓冲,提供最近若干份快照。 */
    @Inject
    lateinit var snapshotBuffer: MetricSnapshotBuffer

    /** 指标聚合器,对近期历史做跨快照统计(FR3.3)。 */
    @Inject
    lateinit var aggregator: MetricAggregator

    /** 启动画像持有者,提供内存中最近一次启动画像。 */
    @Inject
    lateinit var startupProfileHolder: StartupProfileHolder

    /** 存储后端,内存无启动画像时回退读取落盘的最近一份。 */
    @Inject
    lateinit var store: MetricStore

    override fun latestSnapshot(): MetricSnapshot? = orchestrator.latestSnapshot()

    override fun recentSnapshots(limit: Int): List<MetricSnapshot> = snapshotBuffer.recent(limit)

    override fun recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot> = snapshotBuffer.recentSince(sinceMs)

    override fun aggregated(windowSize: Int): AggregatedMetrics = aggregator.aggregate(windowSize)

    override fun lastStartupProfile(): StartupProfile? = startupProfileHolder.get() ?: store.lastStartupProfile()

    override fun historyStartupProfiles(limit: Int): List<StartupProfile> = store.readStartupProfiles(limit)

    override fun lastStartupComparisonSummary(): String? = startupProfileHolder.comparisonSummary
}
