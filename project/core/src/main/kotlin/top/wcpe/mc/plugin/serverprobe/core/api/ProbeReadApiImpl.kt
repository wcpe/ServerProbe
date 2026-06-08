package top.wcpe.mc.plugin.serverprobe.core.api

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
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
 * - 最近启动画像:优先取 [StartupProfileHolder] 内存中的最近一份,内存缺失时回退 [MetricStore] 落盘读取(P7)。
 *
 * ## FR8.1 对外暴露(第三方获取方式)
 * 本类以 `top.wcpe.taboolib.ioc` 的 [Service] 纳入 IOC 容器,**容器内即可按接口类型取用**:
 * ```kotlin
 * val api = top.wcpe.taboolib.ioc.bean.BeanContainer.getBean(ProbeReadApi::class.java)
 * ```
 * 容器在 TabooLib `ACTIVE` 生命周期完成初始化;调用方应在容器就绪后(如自身 `ENABLE`/`ACTIVE` 之后)获取。
 * 本插件内部的 `/probe` 命令即经此机制注入同一实例使用(见 `ProbeCommand`)。
 *
 * 注:M1 保证"容器内可取";跨插件(独立 jar)的稳定 API 暴露与版本兼容策略留待 M2 完善,
 * 届时为纯增能力,不破坏本只读契约。
 */
@Service
class ProbeReadApiImpl : ProbeReadApi {

    /** 采集编排核心,提供最新快照。 */
    @Inject
    lateinit var orchestrator: MetricOrchestrator

    /** 近期历史缓冲,提供最近若干份快照。 */
    @Inject
    lateinit var snapshotBuffer: MetricSnapshotBuffer

    /** 启动画像持有者,提供内存中最近一次启动画像。 */
    @Inject
    lateinit var startupProfileHolder: StartupProfileHolder

    /** 存储后端,内存无启动画像时回退读取落盘的最近一份。 */
    @Inject
    lateinit var store: MetricStore

    override fun latestSnapshot(): MetricSnapshot? = orchestrator.latestSnapshot()

    override fun recentSnapshots(limit: Int): List<MetricSnapshot> = snapshotBuffer.recent(limit)

    override fun lastStartupProfile(): StartupProfile? = startupProfileHolder.get() ?: store.lastStartupProfile()

    override fun lastStartupComparisonSummary(): String? = startupProfileHolder.comparisonSummary
}
