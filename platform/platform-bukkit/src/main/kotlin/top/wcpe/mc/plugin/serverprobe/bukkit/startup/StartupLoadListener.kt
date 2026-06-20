package top.wcpe.mc.plugin.serverprobe.bukkit.startup

import org.bukkit.Bukkit
import org.bukkit.event.server.ServerLoadEvent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupItemTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.core.agent.AgentDataReader
import top.wcpe.mc.plugin.serverprobe.core.agent.AgentStartupData
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.startup.PhaseTimingRecorder
import top.wcpe.mc.plugin.serverprobe.core.startup.StartupComparator
import top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileBuilder
import top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileHolder
import top.wcpe.mc.plugin.serverprobe.core.store.InstanceId
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Bukkit 启动就绪监听器(FR1,启动画像装配触发点)。
 *
 * 监听 [ServerLoadEvent]:仅在 [ServerLoadEvent.LoadType.STARTUP](正常启动,排除 `/reload`)时触发一次,
 * 此刻视为"服务器对外就绪",据此计算端到端启动总时长并装配启动画像([StartupProfile])。
 *
 * 端到端总时长 = `事件触发时刻(currentTimeMillis)` − `RuntimeMXBean.startTime`(JVM 启动时刻),覆盖
 * 「JVM 启动 → 服务就绪」整条串行链(FR1.1)。
 *
 * **时序要点(关键)**:[PhaseTimingRecorder] 的 [taboolib.common.LifeCycle.ACTIVE] 打点由 TabooLib 经
 * `runTask` 安排在**首 tick**执行,而 [ServerLoadEvent] 的派发**早于**该首 tick(经 TabooLib 6.3.0 源码核实)。
 * 若在事件回调内立即组装画像,[PhaseTimingRecorder.phaseTimings] 将**缺失 ACTIVE 段**。为此本监听器把组装
 * 推迟到 ACTIVE 打点之后:在事件回调中仅于**主线程立即**测得 `totalMs` 与世界名(保证口径不漂移),随后
 * 用 `submit(delay = 2, async = true)` 延迟 ≥1 tick(确保首 tick 的 ACTIVE 已记录)且异步执行(读日志不阻塞主线程),
 * 在该任务内完成「读分段 + 读并解析日志 + 装配 + 落库 + 打印摘要」。
 *
 * 平台隔离:仅在 Bukkit 平台生效([PlatformSide]);所需的 core bean([StartupProfileBuilder]/
 * [StartupProfileHolder])由 IOC 的 ObjectInjector 注入。读取 `logs/latest.log`(磁盘 IO)在延迟异步任务中进行,
 * **主线程零阻塞**(规范 R7);装配完成后写入持有者并输出启动画像摘要。
 *
 * **形态(关键)**:本类为 `object` 而非 `@Service class`——TabooLib `@SubscribeEvent` 要求事件方法宿主为
 * object(静态 INSTANCE),否则运行期抛 `not a static method`;`@Inject` 字段照常由 IOC ObjectInjector 注入。
 */
@PlatformSide(Platform.BUKKIT)
object StartupLoadListener {

    /** 启动画像装配器(core)。 */
    @Inject
    lateinit var profileBuilder: StartupProfileBuilder

    /** 启动画像持有者(core)。 */
    @Inject
    lateinit var profileHolder: StartupProfileHolder

    /** 存储后端(core),用于读取上次画像做对比、并持久化本次画像。 */
    @Inject
    lateinit var store: MetricStore

    /**
     * 服务器就绪回调:主线程即时测量,延迟异步装配启动画像。
     *
     * 仅处理 [ServerLoadEvent.LoadType.STARTUP];`/reload` 触发的 RELOAD 类型直接忽略
     * (避免把热重载误计为一次启动)。
     *
     * 在事件线程(主线程)**立即**测得 `totalMs` 与世界名快照(保证口径不漂移、Bukkit API 在主线程访问),
     * 随后将"读分段 + 读日志 + 装配 + 落库 + 打印"整体推迟到 `submit(delay = 2, async = true)`:
     * 延迟 ≥1 tick 等 [PhaseTimingRecorder] 首 tick 的 ACTIVE 打点落地(见类 KDoc 时序要点),
     * 异步执行使主线程不被磁盘 IO 阻塞。
     *
     * @param event 服务器加载事件。
     */
    @SubscribeEvent
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type != ServerLoadEvent.LoadType.STARTUP) {
            return
        }
        // 总时长须在就绪时刻立即测得(延迟任务稍后才执行,届时 currentTimeMillis 已漂移)。
        // 口径:仅覆盖「JVM 进程拉起(RuntimeMXBean.startTime)→ 服务就绪」串行链,
        // 不含 JVM 进程拉起前的外部(启动脚本/容器)耗时——那部分发生在 JVM 计时起点之前,无从测量。
        val totalMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime
        // 世界名快照亦在主线程取(Bukkit API 应在主线程访问),耗时来源 M1 暂缺,见 worldTimings 说明
        val worldNames = Bukkit.getWorlds().map { it.name }
        val mcVersion = resolveMcVersion()

        // 延迟 2 tick 且异步:确保 ACTIVE 已打点(首 tick),且读日志不阻塞主线程
        submit(delay = ASSEMBLE_DELAY_TICKS, async = true) {
            runCatching { buildAndStore(totalMs, worldNames, mcVersion) }
                .onFailure { ProbeLogger.error("装配启动画像失败", it) }
        }
    }

    /**
     * 在延迟异步任务中装配并落库启动画像,最后输出摘要与对比。
     *
     * 此时已过 ACTIVE 打点,[StartupProfileBuilder.build] 经 [PhaseTimingRecorder] 取到的分段含 ACTIVE 段。
     *
     * **启动 agent 早期数据(A3)**:经 [AgentDataReader] 跨 ClassLoader 反射读出
     * (premain 时基、逐插件 load/enable、库下载、主线程栈热点)——读取**先于**定格画像;
     * 若 agent 已挂载,在读毕后立即 [AgentDataReader.stopStackSampler] 停采样(画像就此定格,
     * 避免栈采样持续到运行期空耗)。读取与停采样均尽力而为:未挂载/失败时降级为
     * [AgentStartupData.notAttached],不影响其余画像数据。随后并入 [StartupProfileBuilder.build]。
     *
     * 落库前**先读取**上次画像([MetricStore.lastStartupProfile])用于对比(读在写前,避免被本次覆盖),
     * 随后写入持有者并落盘([MetricStore.saveStartupProfile])——本方法运行于延迟异步任务,落盘不阻塞主线程。
     * serverId 与编排层一致,经 [InstanceId] 解析(配置覆盖优先,否则稳定实例 ID)。
     *
     * @param totalMs 端到端启动总时长(毫秒,事件时刻已测得)。
     * @param worldNames 世界名列表(主线程预取)。
     * @param mcVersion Minecraft 版本。
     */
    private fun buildAndStore(totalMs: Long, worldNames: List<String>, mcVersion: String) {
        val pluginTimings = parsePluginTimings()
        // 启动 agent 早期数据:先读出(热点取配置 Top-N),再停采样定格;未挂载/失败时降级为 notAttached
        val agentData = AgentDataReader.read(ProbeConfig.startupTopN())
        if (agentData.attached) {
            AgentDataReader.stopStackSampler()
        }
        // 世界耗时:agent 挂载且测得 createWorld 耗时时择优用实测值,否则回退占位 0
        val worldTimings = resolveWorldTimings(worldNames, agentData)
        val profile = profileBuilder.build(
            mcVersion = mcVersion,
            platform = ProbePlatform.BUKKIT,
            serverId = InstanceId.resolve(ProbeConfig.configuredServerName()),
            totalMs = totalMs,
            pluginTimings = pluginTimings,
            worldTimings = worldTimings,
            agentData = agentData
        )
        // 先读上次画像再落盘:读取须先于保存,否则会读到刚被覆盖的本次画像
        val previous = store.lastStartupProfile()
        // 对比摘要在此算一次,既用于日志输出,又写入持有者供 /probe startup 读取(零重复计算)
        val comparisonSummary = StartupComparator.summary(profile, previous)
        logSummary(profile, pluginTimings, comparisonSummary)
        profileHolder.set(profile)
        profileHolder.comparisonSummary = comparisonSummary
        store.saveStartupProfile(profile)
    }

    /**
     * 解析本次画像的世界耗时:agent 挂载且测得 `createWorld` 耗时时择优用实测值,否则回退占位 0。
     *
     * 以**实际加载的世界名**([worldNames])为准逐个回填 agent 实测耗时(精度远高于占位):agent 未测到的世界
     * (理论上罕见)用 [WORLD_LOAD_MS_UNKNOWN] 占位。未挂载或无实测时整体回退为"世界名 + 占位 0"。
     *
     * @param worldNames 实际加载的世界名(主线程预取)。
     * @param agentData 启动 agent 早期数据。
     * @return 逐世界耗时列表。
     */
    private fun resolveWorldTimings(worldNames: List<String>, agentData: AgentStartupData): List<WorldTiming> {
        if (!agentData.attached || agentData.worldTimings.isEmpty()) {
            return worldNames.map { WorldTiming.builder().name(it).loadMs(WORLD_LOAD_MS_UNKNOWN).build() }
        }
        val measured = agentData.worldTimings.associate { it.name to it.loadMs }
        return worldNames.map { WorldTiming.builder().name(it).loadMs(measured[it] ?: WORLD_LOAD_MS_UNKNOWN).build() }
    }

    /**
     * 读取并解析 `logs/latest.log` 得到逐插件 onEnable 耗时。
     *
     * 日志文件不存在或读取失败时降级为空列表(容错,不影响其余画像数据)。
     * 路径相对服务端工作目录解析(标准布局)。
     *
     * @return 逐插件耗时列表;无法读取/解析时为空列表。
     */
    private fun parsePluginTimings(): List<PluginTiming> {
        val logFile = File(LATEST_LOG_PATH)
        if (!logFile.isFile) {
            ProbeLogger.warn("未找到启动日志 $LATEST_LOG_PATH,跳过逐插件耗时解析")
            return emptyList()
        }
        val lines = runCatching { logFile.readLines() }
            .getOrElse {
                ProbeLogger.warn("读取启动日志失败,跳过逐插件耗时解析:${it.message}")
                return emptyList()
            }
        return LatestLogPluginTimingParser.parse(lines)
    }

    /**
     * 解析当前 Minecraft 版本标识。
     *
     * 取 [Bukkit.getBukkitVersion](形如 "1.21.4-R0.1-SNAPSHOT"),截取首个 `-` 之前的纯版本号(如 "1.21.4");
     * 解析异常时降级为 [Bukkit.getVersion] 原始串。
     *
     * 注:本模块未引入 TabooLib `bukkit-nms`(env 未装),故不使用 `MinecraftVersion.runningVersion`,
     * 改以 Bukkit 原生 API 取版本,避免平台模块额外依赖。
     *
     * @return 版本标识字符串。
     */
    private fun resolveMcVersion(): String =
        runCatching { Bukkit.getBukkitVersion().substringBefore('-') }.getOrElse { Bukkit.getVersion() }

    /**
     * 输出启动画像摘要:总时长、与上次启动的对比、启用间隔近似 Top-N(按相邻 Enabling 时间差降序)。
     *
     * Top-N 条数取自 [ProbeConfig.startupTopN];对比文案由调用方经 [StartupComparator] 预先生成后传入
     * (首次记录给出"无基线"),此处仅做输出,避免重复计算。
     * 口径与 [LatestLogPluginTimingParser] 一致——耗时为"相邻 Enabling 行的时间差"近似,而非纯 onEnable 耗时,
     * 故摘要用词为"启用间隔近似",避免运维误读为单插件 onEnable 实测耗时。
     *
     * **agent 增强行(A3)**:仅当 [StartupProfile.agentAttached] 为 true 时追加输出"库下载 Top-N"
     * 与"主线程热点 Top-N"两行(N 同取 [ProbeConfig.startupTopN]),便于运维一眼看到首次启动的隐形大头
     * 与主线程热点;未挂载时不打这两行(避免无意义空行)。
     *
     * @param profile 本次启动画像。
     * @param pluginTimings 逐插件耗时。
     * @param comparisonSummary 与上一次启动的对比摘要(已由 [StartupComparator] 生成)。
     */
    private fun logSummary(profile: StartupProfile, pluginTimings: List<PluginTiming>, comparisonSummary: String) {
        ProbeLogger.info("启动画像已生成:端到端总时长 ${formatSeconds(profile.totalMs)}")
        ProbeLogger.info("启动对比:$comparisonSummary")
        val topN = ProbeConfig.startupTopN()
        val top = pluginTimings.sortedByDescending { it.enableMs }.take(topN)
        if (top.isEmpty()) {
            ProbeLogger.info("启用间隔近似 Top-$topN:无可用数据(未解析到插件启用耗时)")
        } else {
            val summary = top.joinToString(separator = " | ") { "${it.name} ${formatSeconds(it.enableMs)}" }
            ProbeLogger.info("启用间隔近似 Top-$topN:$summary")
        }
        logAgentSummary(profile, topN)
    }

    /**
     * 输出 agent 增强摘要行(库下载 Top-N、主线程热点 Top-N);agent 未挂载时直接返回不打印。
     *
     * 库下载耗时与栈热点均为 agent 独有数据(日志解析无从得知),故仅在挂载时呈现;
     * 列表为空(挂载但无库下载/未采到热点)时给出"无数据"提示行,与既有摘要风格一致。
     *
     * @param profile 本次启动画像(含 agent 字段)。
     * @param topN 取前若干(与慢插件榜同取 [ProbeConfig.startupTopN])。
     */
    private fun logAgentSummary(profile: StartupProfile, topN: Int) {
        if (!profile.agentAttached) {
            return
        }
        val libraries = profile.libraryTimings.orEmpty().sortedByDescending { it.loadMs }.take(topN)
        if (libraries.isEmpty()) {
            ProbeLogger.info("库下载 Top-$topN:无库下载记录")
        } else {
            val summary = libraries.joinToString(separator = " | ") { "${it.name} ${formatSeconds(it.loadMs)}" }
            ProbeLogger.info("库下载 Top-$topN:$summary")
        }
        val hotspots = profile.mainThreadHotspots.orEmpty().take(topN)
        if (hotspots.isEmpty()) {
            ProbeLogger.info("主线程热点 Top-$topN:无采样数据")
        } else {
            val summary = hotspots.joinToString(separator = " | ") { "${it.frame} ${it.sampleCount}" }
            ProbeLogger.info("主线程热点 Top-$topN:$summary")
        }
        // 配置加载 / 事件注册 / 命令注册 Top-N(agent 独有,无数据则不打印,避免空行刷屏)
        logItemTop("配置加载", profile.configTimings, topN)
        logItemTop("事件注册", profile.eventTimings, topN)
        logItemTop("命令注册", profile.commandTimings, topN)
    }

    /**
     * 输出一类"命名项耗时"的 Top-N 摘要行(配置加载/事件注册/命令注册);列表为空则不打印。
     *
     * @param label 维度中文标签(如"配置加载")。
     * @param timings 该维度耗时列表(可空)。
     * @param topN 取前若干。
     */
    private fun logItemTop(label: String, timings: List<StartupItemTiming>?, topN: Int) {
        val top = timings.orEmpty().sortedByDescending { it.costMs }.take(topN)
        if (top.isEmpty()) {
            return
        }
        val summary = top.joinToString(separator = " | ") { "${it.name} ${formatSeconds(it.costMs)}" }
        ProbeLogger.info("$label Top-$topN:$summary")
    }

    /**
     * 将毫秒格式化为保留一位小数的秒字符串(如 `12.3s`)。
     *
     * @param ms 毫秒值。
     * @return 形如 `x.xs` 的字符串。
     */
    private fun formatSeconds(ms: Long): String = String.format("%.1fs", ms / MILLIS_PER_SECOND)

    /**
     * 画像装配延迟(tick)。
     *
     * 取 2(≥1 tick)以确保 [PhaseTimingRecorder] 首 tick 的 [taboolib.common.LifeCycle.ACTIVE] 打点已落地,
     * 从而装配出的分段含 ACTIVE 段(见类 KDoc 时序要点)。
     */
    private const val ASSEMBLE_DELAY_TICKS = 2L

    /** 启动日志相对路径(相对服务端工作目录)。 */
    private const val LATEST_LOG_PATH = "logs/latest.log"

    /**
     * 世界加载耗时占位值(毫秒)。
     *
     * M1 在 [ServerLoadEvent] 时世界已全部加载完毕,缺乏精确的逐世界加载耗时来源,故置 0。
     * **M2 完善**:改为从日志 `Preparing spawn area` 行或世界加载事件估算各世界耗时(含 spawn-chunk 预加载)。
     */
    private const val WORLD_LOAD_MS_UNKNOWN = 0L

    /** 一秒的毫秒数(用于摘要格式化)。 */
    private const val MILLIS_PER_SECOND = 1000.0
}
