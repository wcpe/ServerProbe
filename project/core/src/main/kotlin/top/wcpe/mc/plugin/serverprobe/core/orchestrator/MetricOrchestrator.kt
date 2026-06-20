package top.wcpe.mc.plugin.serverprobe.core.orchestrator

import taboolib.common.platform.Platform
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertEngine
import top.wcpe.mc.plugin.serverprobe.core.buffer.MetricSnapshotBuffer
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry
import top.wcpe.mc.plugin.serverprobe.core.store.InstanceId
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 指标采集编排核心。
 *
 * 以固定周期驱动一次采集:从 [ProbeRegistry] 取得已注册的采集器,聚合为一份 [MetricSnapshot]
 * 并缓存为"最新快照",供只读 API 与呈现层读取。
 *
 * 本阶段(P3)为编排骨架:[ProbeRegistry] 尚无任何采集器注册时,[collect] 直接跳过、不产出快照;
 * 待 P4 注册 JVM 采集器后,编排才开始稳定产出快照。采集任务运行于异步线程,单次失败被捕获记录,
 * 不影响后续周期。
 */
@Service
class MetricOrchestrator {

    /** 组件注册中心,用于发现运行期注册的采集器。 */
    @Inject
    lateinit var registry: ProbeRegistry

    /** 近期历史缓冲;每次采集产出的快照同步写入,供只读 API 回看。 */
    @Inject
    lateinit var snapshotBuffer: MetricSnapshotBuffer

    /** 存储后端;每次采集产出的快照追加落盘为历史指标(FR3.2)。 */
    @Inject
    lateinit var metricStore: MetricStore

    /** 告警引擎(FR5);每次采集末尾据最新快照判定并广播告警事件。 */
    @Inject
    lateinit var alertEngine: AlertEngine

    /**
     * 最新一份指标快照;尚未产出时为 null。
     *
     * 由异步采集线程写、由其它线程(API/呈现)读,故用 `@Volatile` 保证可见性。
     */
    @Volatile
    private var latest: MetricSnapshot? = null

    /** 定时采集任务句柄;未启动或已停止时为 null。 */
    private var task: PlatformExecutor.PlatformTask? = null

    /**
     * 获取最新一份指标快照。
     *
     * @return 最新快照;尚无任何采样时为 null。
     */
    fun latestSnapshot(): MetricSnapshot? = latest

    /**
     * 启动定时采集。
     *
     * 在所有 Bean 就绪后由容器回调。采集周期取自 [ProbeConfig.collectPeriodTicks];
     * 任务异步执行,单次采集异常被捕获并记录,保证调度不被中断。
     */
    @PostEnable
    fun start() {
        // 幂等保护:若被重复回调,先取消旧任务避免句柄泄漏
        task?.cancel()
        task = submit(period = ProbeConfig.collectPeriodTicks(), async = true) {
            runCatching { collect() }.onFailure { ProbeLogger.error("指标采集失败", it) }
        }
        ProbeLogger.info("已启动定时采集")
    }

    /**
     * 停止定时采集。
     *
     * 在容器关闭时由回调触发,取消采集任务。
     */
    @PreDestroy
    fun stop() {
        task?.cancel()
        ProbeLogger.info("已停止定时采集")
    }

    /**
     * 执行一次采集并刷新最新快照。
     *
     * 流程:无 JVM 采集器(P4 前)则整体跳过;有则采集 JVM 指标,再尝试采集服务器指标
     * (代理端或未注册时为 null)与代理端指标(服务端或未注册时为 null),聚合后写入 [latest]、
     * 追加到近期历史缓冲,追加落盘为历史指标(FR3.2),最后交告警引擎判定(FR5)。
     * 服务端快照含 server 不含 proxy,代理端反之,JVM 两端皆有。
     * 服务端额外并入世界指标(FR2.3):server 非空时经 copy 注入 worlds(未注册采集器时为 null)。
     *
     * 注:JVM/服务器/代理采集器均取首个([firstOrNull]),注册多个时仅用第一个;[ProbeRegistry]
     * 的去重注册仅作防重保护,不做多采集器聚合(M1 各类仅一个实现)。
     */
    private fun collect() {
        val jvm = registry.jvmCollectors.firstOrNull()?.collect() ?: return
        var server = registry.serverCollectors.firstOrNull()?.collect()
        val proxy = registry.proxyCollectors.firstOrNull()?.collect()
        // 仅服务端有世界:server 非空时并入世界指标(FR2.3);代理端 server=null,不含世界。
        // ServerMetrics 经 toBuilder 注入 worlds(尚无采集器/缓存时为 null)。
        if (server != null) {
            val worlds = registry.worldCollectors.firstOrNull()?.collect()
            server = server.toBuilder().worlds(worlds).build()
        }
        val snapshot = MetricSnapshot.builder()
            .schemaVersion(SCHEMA_VERSION)
            .timestampMs(System.currentTimeMillis())
            // 实例标识:配置覆盖优先,否则取持久化的稳定实例 ID
            .serverId(InstanceId.resolve(ProbeConfig.configuredServerName()))
            .platform(currentProbePlatform())
            .jvm(jvm)
            .server(server)
            .proxy(proxy)
            .build()
        latest = snapshot
        // 追加到近期历史,供 ProbeReadApi.recentSnapshots 回看
        snapshotBuffer.record(snapshot)
        // 追加落盘为历史指标(FR3.2);本任务已在异步线程,落盘不阻塞主线程(R7)
        metricStore.appendHistory(snapshot)
        // 告警判定(FR5):在本异步采集线程串行调用,引擎据规则集判定越线并广播触发/恢复事件
        alertEngine.evaluate(snapshot)
    }

    /**
     * 将 TabooLib 当前运行平台映射为对外的 [ProbePlatform]。
     *
     * 仅服务端/代理三种平台对探针有意义,其余运行环境统一兜底为 [ProbePlatform.BUKKIT]。
     *
     * @return 当前运行平台对应的 [ProbePlatform]。
     */
    private fun currentProbePlatform(): ProbePlatform = when (Platform.CURRENT) {
        Platform.BUKKIT -> ProbePlatform.BUKKIT
        Platform.BUNGEE -> ProbePlatform.BUNGEE
        Platform.VELOCITY -> ProbePlatform.VELOCITY
        else -> {
            ProbeLogger.warn("未识别的运行平台 ${Platform.CURRENT},暂按 BUKKIT 处理")
            ProbePlatform.BUKKIT
        }
    }

    private companion object {

        /** 落盘/快照格式版本号,M1 固定为 1。 */
        private const val SCHEMA_VERSION = 1
    }
}
