package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.BridgeEventPublisher
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.BusinessEventPublisher
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ConfigReloadCoordinator
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ConfigReloadRoute
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ReloadListenerSource
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ReloadRuntime
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicFilter
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicSubscriptionRegistry
import top.wcpe.mc.plugin.serverprobe.core.config.BridgeConfigSource
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfigBridgeConfigSource
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.store.InstanceId
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.io.EOFException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 插件桥反向 WS 客户端（线路治理）。
 *
 * 探针启用后主动以实例级 token 反向连入**本机 Worker** 的 `/ws/plugin-bridge`。
 * 本类只负责**连接生命周期**：
 * - 建连 / 握手 / hello / 周期性心跳 / 指数退避重连 / 停机；
 * - 把读循环收到的帧交给 [BridgeKernel]（解析与分发）；
 * - 把上行帧交给 [FrameSender]（队列、优先级、落盘）。
 *
 * 业务执行、路由匹配、事件分级都**不在本类**——这是本次重构的核心：
 * 读线程只做"读 + 转交"，任何执行都在池上发生。
 *
 * ## 线程模型
 * - `ServerProbe-Bridge`：读循环（唯一），绝不阻塞；
 * - `ServerProbe-Heartbeat`：周期 ping（不依赖读超时，忙连接也要发）；
 * - `ServerProbe-Sender`：发送循环，按优先级取帧；
 * - `ServerProbe-Spill`：落盘（仅可靠事件队列满时用）；
 * - `serverprobe-cmd/event/reload`：业务执行池，见 [BridgePools]。
 * - `serverprobe-reload-worker`：配置重载的监听器子任务池，与 `serverprobe-reload`
 *   **必须是两个池**（共用会池内自锁，见 [ReloadWorkerPool]）。
 * - `serverprobe-health` / `serverprobe-health-probe`：Provider 健康刷新，
 *   由 [ProviderHealthRegistry] 持有；桥启动时一并起、停机时与 reload 池同批 `shutdownNow`。
 *
 * ## 安全
 * token 只用于握手 query 参数，**绝不写入日志**；日志只记 host/port 与实例标识。
 */
// 函数数超阈值是**刻意的收敛结果**：本类只做"连接管理"，
// 把编解码/队列/分片/路由都推给了 FrameSender、BridgeKernel、BridgeRoutes。
// 剩下的多是小而单一的私有助手（encode/helloJson/classify/...），
// 再拆成 2~3 个类会把同一份连接状态切片共享，反而更难读，故只抑制。
@Suppress("TooManyFunctions")
@Service
class BridgeClient {

    /** 运行标志：start 置 true，stop 置 false。 */
    private val running = AtomicBoolean(false)

    /** 读线程。 */
    @Volatile
    private var readThread: Thread? = null

    /** 心跳线程。 */
    @Volatile
    private var heartbeatThread: Thread? = null

    /** 发送线程。 */
    @Volatile
    private var senderThread: Thread? = null

    /** 落盘线程。 */
    @Volatile
    private var spillThread: Thread? = null

    /** 当前连接。 */
    @Volatile
    private var client: MinimalWebSocketClient? = null

    /** 运行期组件（start 时创建，stop 时销毁）。 */
    @Volatile
    private var sender: FrameSender? = null

    @Volatile
    private var kernel: BridgeKernel? = null

    @Volatile
    private var pools: BridgePools? = null

    @Volatile
    private var spill: EventSpillStore? = null

    /**
     * 配置重载运行时：worker 池 + 在途表 + 熔断表，与 [pools] 的 `reload` 协调池**分离**。
     *
     * 由本类持有生命周期：桥启动时创建、停机时 `shutdownNow`（与 reload 协调池同批）。
     */
    @Volatile
    private var reloadRuntime: ReloadRuntime? = null

    /** 连接状态（批次 0 先记最小字段；批次 1 补完整重连语义）。 */
    private val link = BridgeLinkState()

    /** 上次打印的连接失败原因,用于日志限频。 */
    @Volatile
    private var lastLoggedReason: String = ""

    /** 自上次打印以来被抑制的同类日志条数。 */
    private val suppressedFailures = AtomicLong(0)

    /** 治理指令处理器注册中心。 */
    @Inject
    lateinit var commandRegistry: BridgeCommandRegistry

    /** 业务对接装配 / 派发中心。 */
    @Inject
    lateinit var businessHost: BusinessHost

    /**
     * Provider 健康状态缓存。
     *
     * 注入类型 [ProviderHealthRegistry] **未实现任何接口**，故 IoC 以自身类型暴露，按具体类型注入是
     * 正确写法（IoC 静态分析的坑在"实现了接口的类只以接口暴露"，`BusinessHost` 那类才必须按接口注入）。
     * 单测直接构造 [BridgeClient] 时该字段未注入，故所有访问点先判 `isInitialized`。
     */
    @Inject
    lateinit var healthRegistry: ProviderHealthRegistry

    /**
     * 配置重载监听器来源。
     *
     * 注入类型是 core 内部接口 [ReloadListenerSource]，实现类 [top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ConfigReloadRegistryImpl]
     * 同时被 IoC 暴露为对外契约 `ConfigReloadRegistry` 与本接口——**按接口注入**，
     * 注入实现类会报 `missing-bean`。
     *
     * 单测直接构造 [BridgeClient] 时该字段未注入，故访问点先判 `isInitialized`。
     */
    @Inject
    lateinit var reloadListeners: ReloadListenerSource

    /**
     * 业务事件发布器（懒建；与连接解耦，故可安全地在桥未启动时创建）。
     *
     * `BridgeEventPublisher` 每次调用现取 [sender]/[resolveInstance]，因此重连后无需重建。
     * 桥未启动时发送器为 null，发布器按"未受理"返回 false。
     */
    private val publisher: BridgeEventPublisher by lazy {
        BridgeEventPublisher(sender = { sender }, instance = { resolveInstance() })
    }

    /**
     * 业务事件发布器（供 [BusinessEventSinkImpl] 取用）。
     *
     * 返回的是稳定实例，可安全缓存。
     */
    fun eventPublisher(): BusinessEventPublisher = publisher

    /**
     * 可靠事件序号分配器。
     *
     * **生命周期长于桥连接**：`stop()` 不重置它——序号必须跨"桥内部重启"（配置重载、
     * 连接自愈触发的 stop/start）续接，否则一次重连就会让所有域的序号回到 1。
     * 落盘目录在 [doStart] 才知道，故在那里 [EventSeqAllocator.bind]。
     */
    val seqAllocator: EventSeqAllocator = EventSeqAllocator()

    /**
     * 主题订阅注册表（进程内**唯一**实例）。
     *
     * 生命周期刻意长于桥连接：订阅只存内存、**跨重连保留**（探针重启才清空），
     * 因此在字段初始化处创建一次，`start()`/`stop()` 都不重建。
     * 上限从 [BridgeConfigSource.subscribe] 现取，故配置重载后无需重建注册表。
     */
    private val subscriptions = TopicSubscriptionRegistry { configSource.subscribe() }

    /** 配置读取接缝：默认读 [ProbeConfigBridgeConfigSource]，单测可替换。 */
    @Volatile
    var configSource: BridgeConfigSource = ProbeConfigBridgeConfigSource

    /**
     * 启动插件桥客户端。
     *
     * 开关关闭或 url/token 未配置则跳过；否则建组件、起线程。
     *
     * **整体绝不抛**：本方法由 IoC 的 `@PostEnable` 反射调用，那里只会记一行
     * `bridgeClient.start - null` 就继续往下走——异常消息会丢失、桥静默不起、
     * 而服务器照常启动，排障成本极高（E2E 曾因此整场失败而日志无有效线索）。
     * 因此这里自己兜住并打印类名与堆栈前几帧，把"静默失败"变成"可诊断的降级"。
     */
    @PostEnable
    fun start() {
        runCatching { doStart() }.onFailure { e ->
            // doStart 失败时已就地回滚（running 复位 + 池/线程清理），因此这里只需把原因打出来；
            // 也正因为复位了，之后的 start()（重连/配置重载/人工重试）还能真的再试一次。
            ProbeLogger.warn("桥客户端启动失败(${e.javaClass.name}:${e.message}),已回滚本次启动,本次不建立连接")
            e.stackTrace.take(START_FAILURE_TRACE_FRAMES).forEach { ProbeLogger.warn("  at $it") }
        }
    }

    /**
     * 建立组件与线程。
     *
     * **失败时一律就地回滚**（见 [rollbackStartFailure]）后再把异常抛出去。
     * 回滚之所以不能留给外层的 `runCatching`，是因为那里只能打一行日志：
     * `running` 早已被置为 true，于是之后任何 `start()` 都直接 return，桥**永远不会自愈**
     * （只能重启服务器）；而且失败前已建好的落盘/发送/业务池无人 shutdown，线程与池一起泄漏。
     */
    // 必须广捕：失败点可能在任意一行（落盘目录不可写、线程创建失败、路由装配抛……），
    // 而回滚要对**所有**失败一视同仁地执行——漏掉一类失败就少清一类资源。
    @Suppress("TooGenericExceptionCaught")
    private fun doStart() {
        // 订阅接线与桥开关无关：`hello.subscriptions` 的唯一取值口必须先指向本进程的注册表，
        // 否则对端永远看到空数组，而实际仍有订阅（可观测性失效）。
        wireSubscriptions()
        val settings = configSource
        if (!settings.enabled()) {
            ProbeLogger.info("插件桥未开启(bridge.enabled=false),已跳过反向 WS 连接")
            return
        }
        if (settings.url().isEmpty() || settings.token().isEmpty()) {
            ProbeLogger.warn("插件桥已开启但 bridge.url 或 bridge.token 为空,已跳过(请检查 JianManager 下发的探针配置)")
            return
        }
        // 幂等 + 不重复启动：已有的桥不受理第二次 start（否则会两套线程抢同一条连接）。
        if (running.getAndSet(true)) return

        // 下面每个资源都先存到局部变量：回滚时需要拿到**已创建**的那几个，
        // 而字段赋值故意放在最后（一次性对外可见），中途失败时字段里不能出现半成品。
        var store: EventSpillStore? = null
        var frameSender: FrameSender? = null
        var bridgePools: BridgePools? = null
        var reloadPool: ReloadRuntime? = null
        try {
            val line = settings.line()
            store = if (line.spillEnabled) EventSpillStore(line).also { it.load() } else null
            //：序号状态与事件缓冲同目录、同开关（见 EventSpillStore.seqState 的说明）。
            // 绑定后立刻装载：必须在任何事件上报之前完成，否则首个事件会从兜底起点起号。
            seqAllocator.bind(store?.seqState)
            seqAllocator.load()
            // 分片需求 = 阈值与单组上限（`bridge.chunk.*`）；信封里的 instance/timestamp 不属于
            // OutboundFrame 的权威字段集，只能由装配处提供，故以供应器注入。
            val chunker = FrameChunker(settings.chunk()) { chunkEnvelope() }
            frameSender = FrameSender(line, store, chunker)
            // 主题滤镜：必须在容量判定之前（FrameSender.submit 的固定顺序，见事件主题订阅规格 §3.5）。
            frameSender.addFilter(TopicFilter(subscriptions))
            bridgePools = BridgePools(line)
            val bridgeKernel = BridgeKernel(frameSender, bridgePools)
            // 重载 worker 池与上面 BridgePools 里的 reload 协调池是**两个池**（规格 §3.1 的硬要求）。
            // 停机时与 reload 池同批 shutdownNow（在 [stop] 里）。
            reloadPool = ReloadRuntime.of(settings.reload())
            reloadRuntime = reloadPool
            registerRoutes(bridgeKernel, frameSender)
            // 按**实际接线情况**声明能力：只声明真的能处理的，避免面板下发后才发现不支持。
            // 分片能力（分片规格 §3.6）：对端据此决定是否下发需要分片的大数据请求。
            BridgeCapabilities.addCapability(BridgeCapabilities.CAP_CHUNK)
            // 主题订阅能力：对端据此判断可否下发 `core.subscribe`。
            BridgeCapabilities.addCapability(BridgeCapabilities.CAP_TOPIC)
            // 配置重载能力、健康状态查询能力：只在对应路由真的挂上时声明
            // （二者都依赖可选的注入项，见 registerRoutes 的 let 分支）。
            if (reloadRuntime != null) {
                BridgeCapabilities.addCapability(BridgeCapabilities.CAP_CONFIG_RELOAD)
            }
            if (healthRegistryOrNull() != null) {
                BridgeCapabilities.addCapability(BridgeCapabilities.CAP_BUSINESS_STATUS)
            }

            spill = store
            sender = frameSender
            pools = bridgePools
            kernel = bridgeKernel
            BridgeStatusRoute.CURRENT_POOLS = bridgePools

            startThreads(line, store, frameSender, bridgeKernel)
            // Provider 健康刷新线程与桥同生命周期：桥不开时没有任何消费者，多起两条线程只是白烧 CPU
            // （且会进第三方 health()）。start/stop 均幂等。
            healthRegistryOrNull()?.start()
        } catch (failure: Throwable) {
            // 回滚后重抛：异常照旧交给 [start] 打印（那里才有“堆栈前几帧”的排障约定），
            // 但现场已经被收拾干净，不存在“跑着一半就是好不了”的状态。
            rollbackStartFailure(store, frameSender, bridgePools, reloadPool)
            throw failure
        }
        ProbeLogger.info("插件桥反向 WS 客户端已启动,目标 ${safeAuthority(settings.url())}")
    }

    /**
     * 启动中途失败后的回滚：复位 `running`、解绑全局指针、停已起线程、关已建池。
     *
     * ## 为什么不能交给 [stop]
     * [stop] 是**运维停机协议**：它按“停读 → 业务池优雅停并等终止 → drain 队列 → flush 落盘”
     * 的顺序执行，每步都有秒级预算。启动失败时这些队列里根本没有在途流量（线程才刚起、连接还没建），
     * 走那套协议只会把一次启动失败拖成十几秒的主线程停顿，而主线程正是 `@PostEnable` 的调用线程。
     * 故这里一律 `shutdownNow`：唯一目的就是**不留线程、不留池、不留全局指针**。
     *
     * ## 为什么不回滚 `BridgeCapabilities`
     * 那是**进程级**、只增不删的能力声明（没有移除 API），且必须在起线程前声明：
     * `hello` 一连上就读它。声明为已失败的那次启动服务的是“下一次成功启动”，语义上不会错。
     */
    private fun rollbackStartFailure(
        store: EventSpillStore?,
        frameSender: FrameSender?,
        bridgePools: BridgePools?,
        reloadPool: ReloadRuntime?,
    ) {
        // ① **先**复位运行标志：各循环（runLoop/heartbeatLoop/readLoop）的条件都是它，
        //    置 false 后它们做完本轮就退出；晚置一步就是“回滚完还有线程在往外发帧”。
        running.set(false)
        // ② 解绑全局指针：`bridge_status` 读 `CURRENT_POOLS`，残留会让它对着已关的池报数；
        //    字段置空则让 publisher/submit 路径自然降级为“未受理”，而不是对着半成品发。
        BridgeStatusRoute.CURRENT_POOLS = null
        spill = null
        sender = null
        pools = null
        kernel = null
        reloadRuntime = null
        // ③ health 是最后一步启动的，最可能已起来；stop() 幂等且顺带把它自己的 running 复位
        //    （否则它也会卡在“已启动”而永不刷新）。
        runCatching { healthRegistryOrNull()?.stop() }
        // ④ 停线程：interrupt + 有界 join。它们都是 daemon，join 超时也不会阻止 JVM 退出，
        //    但必须留痕——“桥没起来却仍有后台线程在跑”是最难查的一类残留。
        listOf(readThread, senderThread, heartbeatThread, spillThread).forEach { worker ->
            if (worker == null) return@forEach
            runCatching { worker.interrupt() }
            runCatching { worker.join(START_ROLLBACK_JOIN_MS) }
            if (worker.isAlive) {
                ProbeLogger.warn(
                    "桥启动回滚后线程未退出:${worker.name}(daemon,会在其阻塞调用超时后自行结束)",
                )
            }
        }
        readThread = null
        senderThread = null
        heartbeatThread = null
        spillThread = null
        // ⑤ 关已创建的池与队列（反序：最新建的先关）。
        runCatching { reloadPool?.shutdownNow() }
        runCatching { bridgePools?.shutdownNow() }
        runCatching { frameSender?.shutdown() }
        runCatching { store?.shutdown() }
        // seqAllocator 刻意**不** shutdown：它长于桥连接，重启后续接靠的就是它的内存状态；
        // 它也不持有线程，留着不会泄漏。
        ProbeLogger.info("桥启动失败已回滚：运行标志复位、已建池与线程均已停止，下次 start() 可重新尝试")
    }

    /**
     * 停止插件桥客户端（停机协议，见线路治理规格 §6.1）。
     *
     * ## 顺序（不可交换）
     * 1. **停读** —— 关连接，不再有新命令进来；
     * 2. **停业务池并等终止** —— 在途命令跑完，它们还要发回执；
     * 3. **drain 发送队列** —— 把已入队的回执/事件真正写出去；
     * 4. **flush 落盘** —— 停机前一刻被受理的可靠事件必须先落盘；
     * 5. **停落盘线程**；
     * 6. **最后写序号状态** —— 它必须看到全部已分配的号（包括第 2 步那批在途任务刚分配的）。
     *
     * ## 为什么不能先把 sender/spill 停了
     * 之前实际执行的是 `sender.shutdown()` → `spill.shutdown()` → `seqAllocator.shutdown()`
     * → `pools.shutdown()`，与本注释写的顺序**完全相反**，造成三处丢数据：
     * - 在途命令的回执无处可发（`sendReceipt` 阻塞等容量必超时后丢弃）；
     * - 落盘待写队列不 drain、不 fsync，已受理的可靠事件静默消失；
     * - 序号状态早于业务静默落盘，之后分配的号不再持久化 → 重启后**重号**。
     *
     * 重载池与 health 仍用 `shutdownNow`：它们的任务会回调第三方插件，
     * 而那些插件可能已在自身 `onDisable` 里关掉资源，继续跑只会读到坏状态。
     */
    @PreDestroy
    fun stop() {
        // 解绑全局接线：停机后不应再对外声明"当前订阅"（也避免全局状态残留到下一次装配）。
        BridgeCapabilities.setSubscriptionSource(null)
        if (!running.getAndSet(false)) return
        // ① 停读：不再有新命令进来。
        runCatching { client?.close() }
        client = null
        runCatching { readThread?.join(JOIN_TIMEOUT_MS) }
        // ② 业务池优雅停 + 等终止：在途命令还需要发回执。
        //    重载/health 用 shutdownNow（理由见 KDoc）。
        reloadRuntime?.shutdownNow()
        reloadRuntime = null
        healthRegistryOrNull()?.stop()
        pools?.shutdown()
        val poolsDrained = pools?.awaitTermination(POOL_DRAIN_BUDGET_MS) ?: true
        if (!poolsDrained) {
            ProbeLogger.warn("桥业务池未在 ${POOL_DRAIN_BUDGET_MS}ms 内结束,停机继续(其回执将无法送达)")
        }
        // ③ drain 发送队列：把已入队的回执/事件真正写出去。
        sender?.let { s ->
            if (!s.shutdownAfterDrain(SENDER_DRAIN_BUDGET_MS)) {
                ProbeLogger.warn("桥发送队列未完全排空,剩余帧随停机丢弃")
            }
        }
        runCatching { senderThread?.join(JOIN_TIMEOUT_MS) }
        // ④ flush 落盘：停机前一刻被受理的可靠事件必须先落盘（shutdown 不会 drain）。
        spill?.flush()
        // ⑤ 停落盘线程。
        spill?.shutdown()
        runCatching { spillThread?.join(JOIN_TIMEOUT_MS) }
        // ⑥ 最后写序号状态：它必须看到全部已分配的号。
        //    （正常停机把"未落盘的号段"刷进 seq.state，抹平崩溃才会有的续接缺口。）
        seqAllocator.shutdown()
        runCatching { heartbeatThread?.interrupt() }
        runCatching { heartbeatThread?.join(JOIN_TIMEOUT_MS) }
        readThread = null
        senderThread = null
        spillThread = null
        heartbeatThread = null
        kernel = null
        sender = null
        spill = null
        pools = null
        BridgeStatusRoute.CURRENT_POOLS = null
        ProbeLogger.info("插件桥反向 WS 客户端已停止")
    }

    /**
     * 上报一个玩家事件（可丢）。
     *
     * 未连接或队列满时**静默丢弃**（玩家事件是实时增量，漏一两条由后续事件与名册重置自愈）。
     * 绝不抛、可在任意线程调用（含主线程）。
     */
    fun emitPlayerEvent(eventType: String, fields: Map<String, String>) {
        val frame = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = eventType,
            payload = playerEventJson(eventType, fields),
            priority = OutboundFrame.Priority.BEST_EFFORT_EVENT,
        )
        sender?.submit(frame)
    }

    /**
     * 上报一个业务域事件（**不可丢**）。
     *
     * 与玩家事件同走 `event` 帧，但带顶层 `domain` + `dedupKey`，Worker 按
     * `(domain, dedupKey)` 幂等落库。队列满时落盘兜底，重连后回放（至少一次）。
     *
     * 保留本方法是为了**调用方兼容**（平台模块与 E2E harness 仍按三参调用）；
     * 它等价于"未声明主题的可靠事件"。需要带主题或需要拿到受理结果时，走
     * `ServerProbeAPI.eventSink()`（`emit` / `emitReliable`）。
     *
     * @param domain 业务域。
     * @param dedupKey 去重键；空串会被可靠通道拒绝（见 `BusinessEventSink.emitReliable`）。
     * @param fields 结构化字段。
     */
    fun emitBusinessEvent(domain: String, dedupKey: String, fields: Map<String, String>) {
        if (domain.isEmpty()) return
        // 主题一次算出：payload 与帧字段共用同一个值（未声明主题即 null）。
        val topic: String? = null
        val frame = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "${domain}_change",
            domain = domain,
            payload = businessEventJson(domain, dedupKey, topic, fields),
            priority = OutboundFrame.Priority.RELIABLE_EVENT,
            topic = topic,
        )
        sender?.submit(frame)
    }

    /**
     * 注册全部下行路由（序位见线路治理规格的 order 表）。
     *
     * @param frameSender 本次启动新建的发送器。**必须显式传入**：`sender` 字段要到
     *   [doStart] 后段才赋值，此处若走 `requireSender()` 会抛"发送器尚未初始化"，
     *   而该异常会被 IoC 的 `@PostEnable` 兜住并只记一行 WARN —— 表现为桥整个没起来、
     *   日志里却只有一句 `bridgeClient.start - null`（曾因此让 E2E 静默失败）。
     */
    private fun registerRoutes(bridgeKernel: BridgeKernel, frameSender: FrameSender) {
        bridgeKernel.addRoute(ManifestRoute { businessHost })
        // 序位 100：配置重载，跑在 reload **协调池**上（子任务另走 reload-worker 池）。
        // 监听器来源未注入（单测直接构造本类）时跳过，其余路由照常工作。
        reloadListenersOrNull()?.let { bridgeKernel.addRoute(ConfigReloadRoute(newCoordinator(it))) }
        bridgeKernel.addRoute(
            BridgeStatusRoute(frameSender, bridgeKernel, topics = ::currentTopics) { link.snapshot() },
        )
        // 序位 110：`config_reload`(100) 之后、`subscribe`(120) 与 `bridge_status`(130) 之前。
        // 只读健康缓存，绝不在命令线程上调 Provider。注册表未注入（单测直接构造本类）时跳过。
        healthRegistryOrNull()?.let { bridgeKernel.addRoute(BusinessStatusRoute(it)) }
        // 序位 120：早于 `bridge_status`(130) 与业务域兜底(500)，且只认领 core.subscribe/unsubscribe。
        bridgeKernel.addRoute(SubscribeRoute(subscriptions))
        bridgeKernel.addRoute(BusinessDomainRoute { businessHost })
        bridgeKernel.addRoute(GovernanceRoute { commandRegistry.handler })
    }

    /** 取健康注册表；未注入（单测直接构造本类）时返回 null。 */
    private fun healthRegistryOrNull(): ProviderHealthRegistry? =
        if (::healthRegistry.isInitialized) healthRegistry else null

    /** 取重载监听器来源；未注入（单测直接构造本类）时返回 null。 */
    private fun reloadListenersOrNull(): ReloadListenerSource? =
        if (::reloadListeners.isInitialized) reloadListeners else null

    /**
     * 组装重载编排器。
     *
     * 阈值取 [configSource] 的 `bridge.reload.*`（**不直读 `ProbeConfig`**）；其中 `timeout-ms`
     * 与 `max-paths-per-request` 每次请求现读，而 `stuck-after-ms` 与熔断五项在 [ReloadRuntime.of]
     * 里**启动时固化**——它们是在途表与熔断表的构造参数，运行期变更需重连或重启才生效。
     */
    private fun newCoordinator(source: ReloadListenerSource): ConfigReloadCoordinator = ConfigReloadCoordinator(
        runtime = requireReloadRuntime(),
        listeners = source,
        settings = { configSource.reload() },
    )

    /** 取已创建的重载运行时（仅在 start 内部使用）。 */
    private fun requireReloadRuntime(): ReloadRuntime = reloadRuntime ?: error("重载运行时尚未初始化")

    /**
     * 当前生效订阅表达式（`bridge_status.topics.expressions` 的唯一取值口）。
     *
     * 与 `hello.subscriptions` 同源：两者都调本方法，因而**必然同值**——
     * 各自拼装会漂移，而这正是 JM 用来判断"探针是否仍在用旧订阅"的依据。
     */
    private fun currentTopics(): List<String> = subscriptions.snapshot().toList()

    /**
     * 接线订阅来源：`hello.subscriptions` 读它，`bridge_status` 也读同一份注册表。
     *
     * 见事件主题订阅规格 §3.8 的接线义务；漏接的表现是 `hello.subscriptions` 恒为空数组
     * 而实际仍有订阅，故装配单测断言"两者相等"。
     */
    private fun wireSubscriptions() {
        BridgeCapabilities.setSubscriptionSource(::currentTopics)
    }

    /** 起全部后台线程。 */
    private fun startThreads(
        line: top.wcpe.mc.plugin.serverprobe.core.config.LineSettings,
        store: EventSpillStore?,
        frameSender: FrameSender,
        bridgeKernel: BridgeKernel,
    ) {
        readThread = daemon("ServerProbe-Bridge") { runLoop(line, bridgeKernel) }
        senderThread = daemon("ServerProbe-Sender") { frameSender.runLoop() }
        heartbeatThread = daemon("ServerProbe-Heartbeat") { heartbeatLoop() }
        if (store != null) {
            spillThread = daemon("ServerProbe-Spill") { store.drainLoop() }
        }
    }

    /** 创建并启动一个守护线程。 */
    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply {
            isDaemon = true
            start()
        }

    /**
     * 连接-读-重连主循环。
     *
     * 每轮：重读配置 → 建连 → 握手 → 发 hello → 读循环 → 断开后退避重连。
     * 整体不抛：任何异常都只降级重连（探针不成事故源）。
     */
    // 连接/握手/读全过程必须广捕兜底:任何异常都只降级重连、绝不让后台线程挂掉(桥的韧性设计)。
    // 循环里的多处 continue/break 是"配置缺失→退避重连 / 链路超时→退出本轮"这两个早退路径，
    // 抽成状态机只会让线性流程变绕，故只抑制跳转数检查。
    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private fun runLoop(line: top.wcpe.mc.plugin.serverprobe.core.config.LineSettings, bridgeKernel: BridgeKernel) {
        var backoff = configSource.reconnect().initialBackoffMs
        while (running.get()) {
            // 重连前从磁盘重读:token 轮换后无需重启服务器。
            ProbeConfigBridgeConfigSource.refreshFromDisk()
            val url = configSource.url()
            val token = configSource.token()
            if (url.isEmpty() || token.isEmpty()) {
                link.markDisconnected(BridgeLinkState.REASON_CONFIG_EMPTY, "bridge.url 或 bridge.token 未配置", backoff)
                backoff = sleepBackoff(backoff)
                continue
            }
            val instance = resolveInstance()
            try {
                link.markConnecting()
                val target = buildTargetUri(url, token, instance)
                val ws = MinimalWebSocketClient(
                    uri = target,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = line.readTimeoutMs.toInt(),
                    writeDeadlineMs = line.writeDeadlineMs,
                    maxInboundBytes = line.maxInboundBytes,
                )
                ws.connect()
                client = ws
                onConnected(ws, frameSenderOrNull())
                readLoop(ws, bridgeKernel)
            } catch (e: EOFException) {
                link.markDisconnected(BridgeLinkState.REASON_PEER_CLOSED, e.message ?: "", backoff)
                logConnectFailure(BridgeLinkState.REASON_PEER_CLOSED, e.message ?: "对端关闭")
            } catch (e: Exception) {
                val reason = classify(e)
                link.markDisconnected(reason, e.message ?: "", backoff)
                logConnectFailure(reason, e.message ?: e.javaClass.simpleName)
            } finally {
                frameSenderOrNull()?.detach()
                runCatching { client?.close() }
                client = null
            }
            if (!running.get()) break
            backoff = sleepBackoff(backoff)
        }
    }

    /** 连接建立后的初始上行：hello + connected 事件。 */
    private fun onConnected(ws: MinimalWebSocketClient, sender: FrameSender?) {
        val instance = resolveInstance()
        ws.sendText(helloJson(instance))
        link.markConnected()
        sender?.attach(ws)
        // 重连后把落盘未确认的可靠事件回放出去。
        spill?.resetReplay()
        sender?.replaySpill()
    }

    /**
     * 读循环：阻塞读 → 交给内核 → **每次迭代**检查写侧 watchdog 与心跳。
     *
     * 写侧检查必须每次都做（读到帧后 + 读超时后），否则"对端持续下行但停止读取"
     * 这个最真实的拥塞形态永远检测不到。
     */
    @Suppress("SwallowedException")
    private fun readLoop(
        ws: MinimalWebSocketClient,
        bridgeKernel: BridgeKernel,
    ) {
        // 每次进读循环重读，重载 pongTimeout 后下一轮即生效（不缓存到字段，避免重载后不更新）。
        val pongTimeout = configSource.reconnect().pongTimeoutMs
        while (running.get()) {
            try {
                val msg = ws.readMessage()
                if (checkLinkHealth(ws, pongTimeout)) return
                bridgeKernel.onFrame(msg)
            } catch (e: FrameTooLargeException) {
                bridgeKernel.onInboundTooLarge(e.payloadSkipped)
                if (!e.payloadSkipped) throw e
                if (checkLinkHealth(ws, pongTimeout)) return
            } catch (_: java.net.SocketTimeoutException) {
                // 读超时是预期的心跳节拍，不是错误：只借它驱动一次链路健康检查。
                if (checkLinkHealth(ws, pongTimeout)) return
            }
        }
    }

    /**
     * 检查写侧 watchdog 与心跳；返回 true 表示连接已不可用、应退出读循环。
     *
     * **每次读循环迭代都要调用**（读到帧后 + 读超时后）：
     * - 写侧：只在一处检查会漏掉"对端持续下行但停止读取"；
     * - 心跳：只在一处检查会漏掉"持续有下行流量、但 pong 从不来"。
     */
    private fun checkLinkHealth(ws: MinimalWebSocketClient, pongTimeoutMs: Long): Boolean {
        if (ws.checkWriteDeadline()) return true
        val now = System.currentTimeMillis()
        if (ws.isHeartbeatTimedOut(now, pongTimeoutMs)) {
            throw HeartbeatTimeout("已发出 ping 但 ${pongTimeoutMs}ms 内未收到 pong")
        }
        return false
    }

    /**
     * 周期心跳：**不依赖读超时**，直接向连接发 ping 控制帧。
     *
     * 必须走 `ws.sendPing()` 而不是经发送器提交文本帧：只有前者会记录 `lastPingSentAtMs`，
     * 而心跳判定依赖该时间戳做门控。旧实现只在读超时时发 ping，忙连接（持续有下行流量）
     * 永远不读超时 → 从不发 ping → 对端没机会回 pong → 被误判心跳超时、周期性假重连。
     */
    private fun heartbeatLoop() {
        val interval = configSource.reconnect().pingIntervalMs.coerceAtLeast(MIN_PING_INTERVAL_MS)
        while (running.get()) {
            runCatching { Thread.sleep(interval) }
                .onFailure {
                    Thread.currentThread().interrupt()
                    return
                }
            if (!running.get()) return
            val ws = client ?: continue
            runCatching { ws.sendPing() }
                .onFailure { ProbeLogger.debug("桥心跳 ping 发送失败:${it.javaClass.simpleName}") }
        }
    }

    /**
     * 退避休眠；返回下一轮退避值。
     *
     * 三条策略：
     * - 常规：指数翻倍，封顶 `maxBackoffMs`；
     * - **认证失败**：封顶 `authFailedMaxBackoffMs`（比常规小），因为 token 轮换后应尽快恢复；
     * - **连续认证失败超过阈值**：降频到 `authFailedDegradedBackoffMs`，避免对一个明确无效的
     *   凭据持续冲击（同时保留恢复可能，不是彻底放弃）。
     *
     * 成功连着时由 [BridgeLinkState.markConnected] 清零 `consecutiveFailures`，退避自动恢复常规节奏。
     */
    private fun sleepBackoff(current: Long): Long {
        val reconnect = configSource.reconnect()
        runCatching { Thread.sleep(current) }

        val snapshot = link.snapshot()
        val authFailed = snapshot.lastDisconnectReason == BridgeLinkState.REASON_AUTH_FAILED
        if (!authFailed) {
            return (current * 2).coerceAtMost(reconnect.maxBackoffMs)
        }
        if (snapshot.consecutiveFailures >= reconnect.authFailedDegradeThreshold) {
            return reconnect.authFailedDegradedBackoffMs
        }
        return (current * 2).coerceAtMost(reconnect.authFailedMaxBackoffMs)
    }

    /**
     * 记录连接类日志，并对同一原因做限频。
     *
     * Worker 长期不可达时，每次重连都打 WARN 会把日志刷爆；同一原因连续出现时只在首条 WARN，
     * 其后降为 DEBUG；原因变化或连接成功时重置。恢复为 WARN 时带上"期间已抑制 N 条"。
     */
    private fun logConnectFailure(reason: String, detail: String) {
        val suppressed = if (reason == lastLoggedReason) {
            suppressedFailures.incrementAndGet()
            return
        } else {
            val previous = suppressedFailures.getAndSet(0)
            lastLoggedReason = reason
            previous
        }
        val suffix = if (suppressed > 0) "（此前已抑制 $suppressed 条同类日志）" else ""
        ProbeLogger.warn("插件桥连接异常（$reason）,将重连:$detail$suffix")
    }

    /** 断开原因归类（据握手状态码精确区分认证失败与服务不可用）。 */
    private fun classify(e: Throwable): String = when (e) {
        is BridgeHandshakeException -> if (e.statusCode in AUTH_FAILED_CODES) {
            BridgeLinkState.REASON_AUTH_FAILED
        } else {
            BridgeLinkState.REASON_UNREACHABLE
        }
        is HeartbeatTimeout -> BridgeLinkState.REASON_HEARTBEAT_TIMEOUT
        is java.net.ConnectException, is java.net.UnknownHostException -> BridgeLinkState.REASON_UNREACHABLE
        is java.net.SocketTimeoutException -> BridgeLinkState.REASON_UNREACHABLE
        is EOFException -> BridgeLinkState.REASON_PEER_CLOSED
        else -> BridgeLinkState.REASON_UNKNOWN
    }

    /**
     * 构造握手目标 URI，**对 query 参数做编码**。
     *
     * 安全要点：token 是凭据，含 `&`/`#`/空格时若不编码，会污染 query 并在
     * `URISyntaxException` 的消息里泄漏。这里用 7 参构造 + 显式编码规避。
     *
     * 【为什么不直接用 URI.base.host】`java.net.URI` 只对 RFC 2396 的「服务器型」scheme
     * 白名单（http/https/ftp 等）填充 host；`ws` / `wss` 不在其列，
     * `URI("ws://127.0.0.1:9102/x").host` 返回 **null**。若照抄 base.host，
     * 构造出的握手 URI 就是 `ws:null:9102/...`（真机复现：日志打印 `目标 null:80`）。
     * 这里改为手工切出 authority 再拼装，绕开该白名单。
     *
     * @throws java.net.URISyntaxException 地址非法（上层会兜住并归为不可达）。
     */
    internal fun buildTargetUri(url: String, token: String, instance: String): URI {
        val trimmed = url.trim()
        val scheme = when {
            trimmed.contains("://") -> trimmed.substringBefore("://")
            else -> ""  // 无 scheme 时交给 URI 解析报错，保持原有失败语义
        }
        if (scheme.isEmpty()) return URI(url)  // 保持原有行为（非法地址抛 URISyntaxException）

        val afterScheme = trimmed.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val pathRaw = afterScheme.substring(authority.length).substringBefore('?').substringBefore('#')
        val path = if (pathRaw.isEmpty()) "" else pathRaw

        // authority 拆 host / port（兼容 IPv6 字面量）
        val (host, port) = splitAuthority(authority)
        val query = "token=${encode(token)}&instance=${encode(instance)}"
        return URI(scheme, null, host, port, path, query, null)
    }

    /** 把 authority 拆成 host + port；无端口返回 -1。IPv6 字面量形如 `[::1]:9102`。 */
    private fun splitAuthority(authority: String): Pair<String, Int> {
        if (authority.isEmpty()) return "" to -1
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return authority to -1
            val host = authority.substring(0, close + 1)
            val portPart = authority.substring(close + 1).removePrefix(":")
            return host to (portPart.toIntOrNull() ?: -1)
        }
        val colons = authority.count { it == ':' }
        return when {
            colons == 1 -> {
                val host = authority.substringBefore(':')
                val port = authority.substringAfter(':').toIntOrNull() ?: -1
                host to port
            }
            colons == 0 -> authority to -1
            else -> authority to -1  // 多冒号但不是 IPv6 字面量：原样当 host，不猜端口
        }
    }

    /** URL 编码（RFC 3986 的 query 安全子集）。 */
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun frameSenderOrNull(): FrameSender? = sender

    /** 解析实例标识：优先配置，为空回退探针自身实例 ID。 */
    private fun resolveInstance(): String {
        val configured = configSource.instance()
        return if (configured.isNotEmpty()) configured else InstanceId.resolve(null)
    }

    /**
     * 构造 hello 帧。
     *
     * `subscriptions` 取自 [BridgeCapabilities]（与 `bridge_status.topics.expressions` 同源）：
     * 让 JM 在连上瞬间就能发现"探针仍在用旧订阅"并重下发，不必先发一条 `bridge_status`。
     */
    private fun helloJson(instance: String): String = Json.encode(
        linkedMapOf(
            "type" to OutboundFrame.TYPE_HELLO,
            "instance" to instance,
            "platform" to platformName(),
            "version" to BridgeCapabilities.VERSION,
            "capabilities" to BridgeCapabilities.snapshot(),
            "subscriptions" to BridgeCapabilities.subscriptions(),
        ),
    )

    /** 构造玩家事件帧。 */
    private fun playerEventJson(event: String, fields: Map<String, String>): String = Json.encode(
        linkedMapOf(
            "type" to OutboundFrame.TYPE_EVENT,
            "event" to event,
            "instance" to resolveInstance(),
            "timestamp" to System.currentTimeMillis(),
            "data" to fields.filterValues { it.isNotEmpty() },
        ),
    )

    /**
     * 构造业务事件帧（带 domain/dedupKey/可选 topic）。
     *
     * @param topic 与帧上的 `OutboundFrame.topic` **必须同源**——由调用方一次算出后两处写入，
     *   不允许本方法自行推导（滤镜读帧字段、对端读 payload，不一致时过滤行为无法解释）。
     */
    private fun businessEventJson(
        domain: String,
        dedupKey: String,
        topic: String?,
        fields: Map<String, String>,
    ): String {
        val payload = linkedMapOf<String, Any?>(
            "type" to OutboundFrame.TYPE_EVENT,
            "event" to "${domain}_change",
            "instance" to resolveInstance(),
            "timestamp" to System.currentTimeMillis(),
            "domain" to domain,
            "dedupKey" to dedupKey,
        )
        // 缺失 topic = 非主题事件（与规格 §3.1 一致）。
        if (topic != null) payload["topic"] = topic
        payload["data"] = fields.filterValues { it.isNotEmpty() }
        return Json.encode(payload)
    }

    /** 推断平台名（不把平台 API 引到 core）。 */
    private fun platformName(): String = when {
        canLoad("org.bukkit.Bukkit") -> "bukkit"
        canLoad("net.md_5.bungee.api.ProxyServer") -> "bungee"
        else -> "unknown"
    }

    /**
     * 分片信封的公共字段（实例标识与时间戳）。
     *
     * `OutboundFrame` 的字段集是唯一权威且**不允许**为分片加字段，故这两项由装配处按片注入；
     * 分片器会把它计入信封开销测量，预算因此始终精确。
     */
    private fun chunkEnvelope(): Map<String, Any?> = linkedMapOf(
        "instance" to resolveInstance(),
        "timestamp" to System.currentTimeMillis(),
    )

    /** 类是否可加载。 */
    private fun canLoad(name: String): Boolean = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

    /**
     * 从 ws URL 提取仅 host:port 的可记日志部分（去掉 query，避免 token 入日志）。
     *
     * 【为什么不直接用 URI.host】`java.net.URI` 只对 RFC 2396 的「服务器型」scheme
     * （http/https/ftp 等白名单）填充 host；`ws` / `wss` 不在其列，`URI("ws://127.0.0.1:9102/x").host`
     * 返回 null，导致日志打印成 `null:80` —— 真机排障时极易被误读成「配置没生效」。
     * 这里手工切 authority，不依赖 URI 的 scheme 白名单。
     *
     * 安全约束：只输出 host:port，**query 与 path 一律丢弃**（query 里带 token）。
     */
    internal fun safeAuthority(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "(未配置)"
        // 切掉 scheme:// 或 // 前缀
        val afterScheme = when {
            trimmed.contains("://") -> trimmed.substringAfter("://")
            trimmed.startsWith("//") -> trimmed.removePrefix("//")
            else -> trimmed
        }
        // authority 到第一个 / ? # 为止；query 与 path 由此丢弃
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return "(地址解析失败)"
        // 兼容 IPv6 字面量 [::1]:port（冒号多于一个且以 ] 结尾的 host 段）
        return when {
            authority.startsWith("[") -> {
                val close = authority.indexOf(']')
                if (close < 0) "(地址解析失败)"
                else {
                    val hostPart = authority.substring(0, close + 1)
                    val portPart = authority.substring(close + 1).removePrefix(":")
                    if (portPart.isEmpty()) "$hostPart:80" else "$hostPart:$portPart"
                }
            }
            authority.count { it == ':' } == 1 -> {
                val host = authority.substringBefore(':')
                val port = authority.substringAfter(':')
                if (port.isEmpty()) "$host:80" else "$host:$port"
            }
            authority.contains(':') -> authority  // 多冒号但非 IPv6 字面量：原样输出，不猜
            else -> "$authority:80"
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val JOIN_TIMEOUT_MS = 3_000L

        /** 停机时等待业务池跑完在途任务的预算（毫秒）。超时则继续停机并告警。 */
        private const val POOL_DRAIN_BUDGET_MS = 5_000L

        /** 停机时等待发送队列排空的预算（毫秒）。对端已死时不无限拖住停机。 */
        private const val SENDER_DRAIN_BUDGET_MS = 3_000L

        /** 心跳最小间隔:避免配置成 0/负数导致忙等。 */
        const val MIN_PING_INTERVAL_MS = 1_000L

        /** 启动失败时打印的堆栈帧数：足够定位到具体调用点，又不至于刷屏。 */
        const val START_FAILURE_TRACE_FRAMES = 8

        /** 启动回滚时逐个线程 join 的上限（毫秒）：回滚跑在主线程，不能在这里耗秒级预算。 */
        private const val START_ROLLBACK_JOIN_MS = 1_000L

        /** 视为认证失败的握手状态码。 */
        val AUTH_FAILED_CODES = setOf(401, 403)

    }
}

/**
 * 连接状态持有者（批次 0 最小实现）。
 *
 * 用**整体替换的不可变快照**而非多个 `@Volatile` 字段：后者在 `bridge_status` 序列化时
 * 会读到撕裂组合（`state=CONNECTED` 但原因来自上一次断开）。
 */
class BridgeLinkState {

    @Volatile
    private var current: BridgeLinkSnapshot = BridgeLinkSnapshot()

    /** 当前快照。 */
    fun snapshot(): BridgeLinkSnapshot = current

    fun markConnecting() {
        current = current.copy(state = STATE_CONNECTING, currentBackoffMs = 0)
    }

    fun markConnected() {
        current = current.copy(
            connected = true,
            state = STATE_CONNECTED,
            lastConnectedAtMs = System.currentTimeMillis(),
            consecutiveFailures = 0,
            currentBackoffMs = 0,
            lastDisconnectReason = "",
            lastDisconnectDetail = "",
        )
    }

    fun markDisconnected(reason: String, detail: String, backoffMs: Long) {
        current = current.copy(
            connected = false,
            state = STATE_BACKOFF,
            reconnects = current.reconnects + 1,
            lastDisconnectReason = reason,
            lastDisconnectDetail = detail.take(MAX_DETAIL_CHARS),
            lastDisconnectAtMs = System.currentTimeMillis(),
            currentBackoffMs = backoffMs,
            consecutiveFailures = current.consecutiveFailures + 1,
        )
    }

    companion object {
        const val STATE_STOPPED = "STOPPED"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_BACKOFF = "BACKOFF"

        const val REASON_CONFIG_EMPTY = "CONFIG_EMPTY"
        const val REASON_UNREACHABLE = "UNREACHABLE"
        const val REASON_PEER_CLOSED = "PEER_CLOSED"
        const val REASON_AUTH_FAILED = "AUTH_FAILED"
        const val REASON_HEARTBEAT_TIMEOUT = "HEARTBEAT_TIMEOUT"
        const val REASON_UNKNOWN = "UNKNOWN"

        /** 断开说明长度上限（会进回执并展示）。 */
        private const val MAX_DETAIL_CHARS = 512
    }
}
