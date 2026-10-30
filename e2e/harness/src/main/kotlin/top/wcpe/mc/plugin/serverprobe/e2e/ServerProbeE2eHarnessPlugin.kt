package top.wcpe.mc.plugin.serverprobe.e2e

import top.wcpe.mc.plugin.serverprobe.api.Registration
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.api.bridge.RiskLevel
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadErrorCode
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadEvent
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadResult
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/** ServerProbe 的独立第三方 E2E 消费者，负责与 的真实判定。 */
class ServerProbeE2eHarnessPlugin : JavaPlugin(), Listener {

    private val scenario = E2eScenario.from(System.getenv(SCENARIO_ENV))
    private val completed = AtomicBoolean(false)
    private var resultWriter: ScenarioResultWriter? = null
    /** 桥业务链路的回环 fixture；与的分片 fixture 互斥，同一进程只会有一个非 null。 */
    private var bridgeFixture: ProtocolWorkerFixture? = null
    /** 帧分片的回环 fixture（独立状态机，见 ChunkFrameFixture）。 */
    private var chunkFixture: ChunkFrameFixture? = null
    /** 事件主题订阅的回环 fixture（独立状态机，见 TopicFrameFixture）。 */
    private var topicFixture: TopicFrameFixture? = null
    /** Provider 健康状态的回环 fixture（独立状态机，见 HealthFrameFixture）。 */
    private var healthFixture: HealthFrameFixture? = null
    /** 配置重载通知的回环 fixture（独立状态机，见 ReloadFrameFixture）。 */
    private var reloadFixture: ReloadFrameFixture? = null
    /** 场景的监听器观测点：fixture 与三个监听器共享同一实例（计数即证据）。 */
    private val reloadState = ReloadFixtureState()
    /** 场景的三个监听器注册句柄：onDisable 一并关闭，避免留下未注销的监听器。 */
    private val reloadHandles = mutableListOf<Registration>()
    private var storageSession: Any? = null
    /** 业务域注册句柄：onDisable 关闭它，验证陈旧句柄保护。 */
    private var businessHandle: Registration? = null
    /** 旧契约域的注册句柄：证明旧签名插件仍能按句柄注销。 */
    private var legacyBusinessHandle: Registration? = null
    /** 场景的三个域注册句柄：onDisable 一并关闭，避免留下未注销的 Provider。 */
    private val healthHandles = mutableListOf<Registration>()
    /** 场景里三个 Provider 的 `dispatch` 调用计数：查询路径必须始终为 0。 */
    private val healthDispatches = AtomicInteger()
    /** 场景里被真正派发过的域；非空即证明查询路径违规派发。 */
    private val healthDispatchedDomains = ConcurrentHashMap.newKeySet<String>()
    private val foliaPlayers = ConcurrentHashMap.newKeySet<String>()
    private val foliaPlayersSeparated = AtomicBoolean(false)
    private val foliaLoadEnabled = AtomicBoolean(false)
    private val foliaLoadRuns = AtomicInteger()
    private val foliaTargetRegionId = AtomicLong(UNKNOWN_REGION_ID)
    private val foliaControlRegionId = AtomicLong(UNKNOWN_REGION_ID)
    // Folia 调度任务匣：io.papermc 类型只允许出现在嵌套类中，主类被旧版本服务器反射时不得触发类缺失。
    private var foliaTaskBox: Any? = null
    private var foliaBaseline: ReadApiConsumer.FoliaRegionPair? = null
    @Volatile private var foliaLoadSink = 0L

    override fun onLoad() {
        when (scenario) {
            E2eScenario.BRIDGE_FIXTURE -> {
                bridgeFixture = ProtocolWorkerFixture.startFromProbeConfiguration(dataFolder.parentFile)
            }
            // 分片场景同样要在服务端启动前开始监听：探针的反向连接一建立就会连上来。
            E2eScenario.CHUNK_FIXTURE -> {
                chunkFixture = ChunkFrameFixture.startFromProbeConfiguration(dataFolder.parentFile, CHUNK_MAX_FRAME_BYTES)
            }
            // 主题订阅场景同理：连接一建立探针就会先发 hello。
            E2eScenario.TOPIC_FIXTURE -> {
                topicFixture = TopicFrameFixture.startFromProbeConfiguration(dataFolder.parentFile)
            }
            // 健康状态场景同理：探针一连上就发 hello，fixture 必须先在监听。
            E2eScenario.HEALTH_FIXTURE -> {
                healthFixture = HealthFrameFixture.startFromProbeConfiguration(dataFolder.parentFile)
            }
            // 配置重载场景同理：fixture 与监听器共享同一份计数观测点。
            E2eScenario.RELOAD_FIXTURE -> {
                reloadFixture = ReloadFrameFixture.startFromProbeConfiguration(dataFolder.parentFile, reloadState)
            }
            E2eScenario.READ_API,
            E2eScenario.STORAGE_SPI,
            E2eScenario.NETWORK_FORENSICS_BUNGEE,
            E2eScenario.FOLIA_OBSERVED_REGIONS,
            E2eScenario.MATRIX_SMOKE,
            -> writeFastCollectionConfiguration()
            // 本场景的 config.yml 由 prepareIncisionStartupConfiguration 在服启动前写入模板，
            // 这里再写就太晚了：TabooLib 在 LOAD 阶段已读完并缓存了配置。
            E2eScenario.INCISION_STARTUP -> Unit
            E2eScenario.MCP_DIAGNOSTICS_PAPER -> Unit
        }
    }

    override fun onEnable() {
        resultWriter = ScenarioResultWriter(resolveResultFile())
        registerStartupListenerIfNeeded()
        server.pluginManager.registerEvents(this, this)
        if (scenario == E2eScenario.READ_API || scenario == E2eScenario.MATRIX_SMOKE) {
            // 矩阵覆盖慢启动旧版本(1.16.5 首启画像可达 40s+)，冒烟窗口按场景放宽。
            val attempts = if (scenario == E2eScenario.MATRIX_SMOKE) MATRIX_READ_API_ATTEMPTS else READ_API_ATTEMPTS
            runLater { checkReadApi(attempts) }
        }
        if (scenario == E2eScenario.INCISION_STARTUP) {
            // 织入在 CONST 阶段完成，但首个启动画像要等 ServerLoadEvent + 延迟装配；给足轮询窗口。
            runLater { checkIncisionInjected(READ_API_ATTEMPTS) }
        }
        if (scenario == E2eScenario.FOLIA_OBSERVED_REGIONS) {
            runLater { awaitFoliaPlayers(FOLIA_PLAYER_ATTEMPTS) }
        }
        if (scenario == E2eScenario.MCP_DIAGNOSTICS_PAPER) {
            Thread({ runCatching { verifyMcp() }.onSuccess { pass("MCP Paper 端到端验收通过", it) }.onFailure { fail("MCP 验收失败:${it.message}") } }, "serverprobe-e2e-mcp").apply { isDaemon = true; start() }
        }
    }

    /** ServerLoadEvent 桥回调：参数降级为布尔，保持主类签名不引用 1.12 才存在的事件类型。 */
    fun handleServerLoad(startup: Boolean) {
        if (!startup) {
            return
        }
        when (scenario) {
            E2eScenario.STORAGE_SPI -> installStorageScenario()
            E2eScenario.BRIDGE_FIXTURE -> startBridgeScenario()
            E2eScenario.CHUNK_FIXTURE -> startChunkScenario()
            E2eScenario.TOPIC_FIXTURE -> startTopicScenario()
            E2eScenario.HEALTH_FIXTURE -> startHealthScenario()
            E2eScenario.RELOAD_FIXTURE -> startReloadScenario()
            E2eScenario.READ_API,
            E2eScenario.INCISION_STARTUP,
            E2eScenario.NETWORK_FORENSICS_BUNGEE,
            E2eScenario.MCP_DIAGNOSTICS_PAPER,
            E2eScenario.MATRIX_SMOKE,
            -> Unit
            E2eScenario.FOLIA_OBSERVED_REGIONS -> prewarmEntityTypesForFolia()
        }
    }

    /**
     * 预热 CraftBukkit 的实体类型注册表。
     *
     * <h3>为什么必须预热</h3>
     * 首个玩家登录时 `PlayerList.canPlayerLogin` → `ServerPlayer.getBukkitEntity` →
     * `CraftEntityTypes.<clinit>`。该静态初始化要注册全部实体类型，首次在本机实测耗时
     * **5 个以上秒**；而它跑在 **global region 线程**上，会触发 Folia 的 watchdog
     * （`Global region has not responded in ...`），进而连带 level 初始化在 region 线程上抢跑、
     * 报 `Cannot asynchronously load chunks` —— 整个服务端启动失败。
     *
     * 预热把它移到“服务端已就绪、bot 尚未登录”的窗口：此时同样在主线程做一次初始化，
     * watchdog 的判定对象是**单个 tick** 的响应性，启动末尾的一次性预热不会让 tick 卡住。
     *
     * 用反射而非直接引用：`CraftEntityTypes` 是 CraftBukkit 内部类，
     * 编译期不应依赖 NMS 符号（本模块只面向 Bukkit API）。
     *
     * <h3>适用范围（重要）</h3>
     * 只覆盖**本 harness 接管的场景**（目前仅 `folia-observed-regions`）。`mcp-diagnostics-folia`
     * 与 `network-forensics-folia` 跑在各自独立的 harness（`harness-mcp-bukkit` /
     * `harness-network-bukkit`）上，不经过本类，因此**不受此预热保护**。
     * 它们目前实测通过，因为 bot 登录时机晚于该初始化；若将来它们开始报
     * `Global region has not responded`，需在各自的 harness 里做同样的预热。
     */
    private fun prewarmEntityTypesForFolia() {
        val startedAt = System.currentTimeMillis()
        // **同步**执行：必须赶在 bot 登录之前完成，否则预热毫无意义。
        // 此时服务端刚就绪、还没有玩家，这次阻塞发生在一个“尚无 tick 压力”的窗口。
        runCatching {
            Class.forName("org.bukkit.craftbukkit.entity.CraftEntityTypes", true, javaClass.classLoader)
        }.onSuccess {
            logger.info("Folia 实体类型表预热完成，耗时 ${System.currentTimeMillis() - startedAt}ms（首个玩家登录不再触发慢初始化）")
        }.onFailure {
            // 预热失败不影响验收：真实登录路径仍会自己初始化，只是可能慢。
            logger.warning("Folia 实体类型表预热失败（不影响验收）：${it.message}")
        }
    }

    /** 仅在依赖 ServerLoadEvent 的场景注册桥监听；事件类缺失（1.11-）时静默跳过，保证旧版本可加载。 */
    private fun registerStartupListenerIfNeeded() {
        val needsStartupEvent = scenario == E2eScenario.STORAGE_SPI ||
            scenario == E2eScenario.BRIDGE_FIXTURE ||
            scenario == E2eScenario.CHUNK_FIXTURE ||
            scenario == E2eScenario.TOPIC_FIXTURE ||
            scenario == E2eScenario.HEALTH_FIXTURE ||
            // Folia 场景需要该事件在“服务端刚就绪”这一刻预热实体类型表，
            // 否则首个 bot 登录会在 global region 线程上慢过 watchdog 阈值。
            scenario == E2eScenario.FOLIA_OBSERVED_REGIONS ||
            scenario == E2eScenario.RELOAD_FIXTURE
        if (!needsStartupEvent) return
        if (runCatching { Class.forName("org.bukkit.event.server.ServerLoadEvent", false, javaClass.classLoader) }.isFailure) {
            return
        }
        server.pluginManager.registerEvents(ServerLoadBridge(this), this)
    }

    /** 玩家加入事件只记录身份；世界读取与传送均转到 Folia 全局区域调度器。 */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (scenario == E2eScenario.FOLIA_OBSERVED_REGIONS) {
            foliaPlayers += event.player.name
        }
    }

    override fun onDisable() {
        // 先解注册再停 fixture：让探针在桥仍可用时收到域下线，避免日志里出现"桥已关仍推 manifest"的噪声。
        unregisterBusinessProvider()
        stopFoliaRegionTasks()
        bridgeFixture?.close()
        chunkFixture?.close()
        topicFixture?.close()
        healthFixture?.close()
        reloadFixture?.close()
    }

    /**
     * 轮询公开画像，直到 Incision 织入生效并产出逐插件耗时。
     *
     * <p>这条场景是“切点真的被织入”的唯一真机判据：单元测试只能证明切点字符串编进了常量池，
     * 证明不了 Incision 真的把 `SimplePluginManager#enablePlugin` 织上了。
     * `incisionActive=true` 由 `afterEnablePlugin` 实际执行过才会置位，因此它不可伪造。</p>
     */
    private fun checkIncisionInjected(remaining: Int) {
        val evidence = runCatching { ReadApiConsumer.readIncisionEvidence() }.getOrNull()
        if (evidence != null) {
            pass("Incision 已织入 SimplePluginManager#enablePlugin 并产出逐插件实测耗时", evidence)
            return
        }
        if (remaining == 0) {
            fail("Incision 未在限定时间内织入生效（incisionActive 未变 true 或未产出逐插件耗时）")
            return
        }
        runLater { checkIncisionInjected(remaining - 1) }
    }

    /** 轮询只读门面，直到真实采样快照与启动画像都已准备完成。 */
    private fun checkReadApi(remaining: Int) {
        // 早期快照可能尚未携带 Tick 指标(readEvidence 内的严格校验会抛异常):
        // 按"未就绪"容错重试,异常终止轮询会错失后续就绪窗口。
        val evidence = runCatching { ReadApiConsumer.readEvidence() }.getOrNull()
        if (evidence == null) {
            retryReadApi(remaining)
            return
        }
        if (scenario != E2eScenario.READ_API) {
            pass("第三方插件已经公开门面读到运行快照与启动画像", evidence)
            return
        }
        // FR-26:historySnapshots 明示"可能读盘、宜异步",主线程调用是该 API 的红线,故另起线程调用。
        Thread({
            val history = runCatching { ReadApiConsumer.readHistoryEvidence(historyMetricsRoot(), HISTORY_READ_LIMIT) }.getOrNull()
            if (history == null) {
                retryReadApi(remaining)
                return@Thread
            }
            pass("第三方插件已经公开门面读到运行快照、启动画像与落盘历史指标", evidence + history)
        }, HISTORY_THREAD_NAME).apply { isDaemon = true; start() }
    }

    /** 读 API 未就绪时的轮询收尾：用尽次数则判失败，否则退回主线程继续下一轮。 */
    private fun retryReadApi(remaining: Int) {
        if (remaining == 0) {
            fail("读取 API 未在限定时间内提供快照、启动画像与落盘历史指标")
            return
        }
        runLater { checkReadApi(remaining - 1) }
    }

    /** 探针指标历史根目录：harness 数据目录与插件数据目录同级（plugins/）。 */
    private fun historyMetricsRoot(): File = File(dataFolder.parentFile, "ServerProbe/data/metrics")

    /** 等待两个真实协议 bot 入服，分散到不同 region 后验收公开快照。 */
    private fun awaitFoliaPlayers(remaining: Int) {
        val players = server.onlinePlayers.map { it.name }.filter(foliaPlayers::contains).sorted()
        if (players.size < REQUIRED_FOLIA_PLAYERS) {
            retryFoliaPlayers(remaining)
            return
        }
        if (foliaPlayersSeparated.compareAndSet(false, true)) {
            prepareFoliaRegions(players.take(REQUIRED_FOLIA_PLAYERS))
        }
    }

    /** 分别在主世界与下界建立已加载 region，避免同世界空区域被 Folia 合并。 */
    private fun prepareFoliaRegions(players: List<String>) {
        forceLoad(OVERWORLD_DIMENSION, FOLIA_TARGET_BLOCK, FOLIA_TARGET_BLOCK)
        forceLoad(NETHER_DIMENSION, FOLIA_TARGET_BLOCK, FOLIA_TARGET_BLOCK)
        runLater(FOLIA_CHUNK_LOAD_TICKS) {
            placeAndTeleport(players[0], OVERWORLD_DIMENSION, FOLIA_TARGET_BLOCK, FOLIA_TARGET_BLOCK)
            placeAndTeleport(players[1], NETHER_DIMENSION, FOLIA_TARGET_BLOCK, FOLIA_TARGET_BLOCK)
            startFoliaRegionTasks()
            runLater(FOLIA_SETTLE_TICKS) { checkObservedRegions(FOLIA_OBSERVE_ATTEMPTS) }
        }
    }

    /** 远端区块未加载时不得直接传送，否则 bot 会在尚未生成的区域坠落。 */
    private fun forceLoad(dimension: String, x: Int, z: Int) {
        check(inDimension(dimension, "forceload add $x $z")) {
            "无法强制加载 Folia 验收区块:$dimension:$x,$z"
        }
    }

    /** 在目标位置铺设落脚点，避免协议 bot 从高空坠落后污染 region 稳定样本。 */
    private fun placeAndTeleport(name: String, dimension: String, x: Int, z: Int) {
        check(inDimension(dimension, "setblock $x ${FOLIA_TELEPORT_Y - 1} $z minecraft:stone")) {
            "无法放置 Folia 验收落脚点:$name"
        }
        teleport(name, dimension, x, z)
    }

    private fun teleport(name: String, dimension: String, x: Int, z: Int) {
        check(inDimension(dimension, "tp $name $x $FOLIA_TELEPORT_Y $z")) {
            "无法传送 Folia 验收 bot:$name"
        }
    }

    /** 控制台命令仅在全局区域调度器调用；维度切换不读取任何 region 所有权数据。 */
    private fun inDimension(dimension: String, command: String): Boolean =
        Bukkit.dispatchCommand(server.consoleSender, "minecraft:execute in $dimension run $command")

    /** 在两个已含玩家的 region 运行轻量身份采样；仅目标 region 在后续启用 CPU 负载。 */
    private fun startFoliaRegionTasks() {
        foliaTaskBox = FoliaTaskBox()
        runCatching {
            foliaRegionIdReader
            val overworld = worldFor(org.bukkit.World.Environment.NORMAL)
            val nether = worldFor(org.bukkit.World.Environment.NETHER)
            scheduleFoliaRegionTask(overworld, FOLIA_TARGET_BLOCK, FOLIA_TARGET_BLOCK, foliaTargetRegionId, true)
            scheduleFoliaRegionTask(
                nether,
                FOLIA_TARGET_BLOCK,
                FOLIA_TARGET_BLOCK,
                foliaControlRegionId,
                false,
            )
        }.onFailure { error ->
            stopFoliaRegionTasks()
            fail("启动 Folia region 受控负载失败:${error.message}")
        }
    }

    /** 通过环境类型选择真实世界，避免把服务器可配置的 Bukkit 世界名写死。 */
    private fun worldFor(environment: org.bukkit.World.Environment): org.bukkit.World =
        requireNotNull(server.worlds.firstOrNull { it.environment == environment }) { "未找到 Folia 验收世界:$environment" }

    /** 官方 RegionScheduler 保证任务在指定 chunk 当前所属的真实 region tick 内执行。 */
    private fun scheduleFoliaRegionTask(
        world: org.bukkit.World,
        x: Int,
        z: Int,
        regionId: AtomicLong,
        isTarget: Boolean,
    ) {
        val task = server.regionScheduler.runAtFixedRate(
            this,
            world,
            x shr CHUNK_COORDINATE_BITS,
            z shr CHUNK_COORDINATE_BITS,
            Consumer {
                regionId.set(foliaRegionIdReader.currentId())
                if (isTarget && foliaLoadEnabled.get()) {
                    consumeFoliaRegionCpu()
                    foliaLoadRuns.incrementAndGet()
                }
            },
            FIRST_REGION_TASK_DELAY_TICKS,
            REGION_TASK_PERIOD_TICKS,
        )
        val box = foliaTaskBox as? FoliaTaskBox ?: return
        if (isTarget) box.target = task else box.control = task
    }

    /** 固定时长的纯 CPU 自旋只发生在目标 region tick 线程，绝不等待、IO 或触及全局线程。 */
    private fun consumeFoliaRegionCpu() {
        val deadline = System.nanoTime() + FOLIA_LOAD_BUDGET_NANOS
        var value = foliaLoadSink
        while (System.nanoTime() < deadline) {
            value = value * LOAD_MULTIPLIER + LOAD_INCREMENT
            Thread.onSpinWait()
        }
        foliaLoadSink = value
    }

    /** 停止两个 region 调度任务，确保踢人和关闭后不再保留受控负载。 */
    private fun stopFoliaRegionTasks() {
        (foliaTaskBox as? FoliaTaskBox)?.stop(foliaLoadEnabled)
    }

    /** 验收 Region 明细、真实样本与每世界加权汇总均通过公共读取接口可见。 */
    private fun checkObservedRegions(remaining: Int) {
        if (ReadApiConsumer.foliaGlobalTickUnavailable() == false) {
            fail("Folia 全局 TPS/MSPT 未保持 N/A")
            return
        }
        val targetId = foliaTargetRegionId.get()
        val controlId = foliaControlRegionId.get()
        val evidence = ReadApiConsumer.readObservedRegionEvidence()
        val pair = ReadApiConsumer.observedRegionPair(targetId, controlId)
        if (evidence != null && pair != null) {
            foliaBaseline = pair
            smokeFoliaTpsCommand()
            foliaLoadEnabled.set(true)
            runLater(FOLIA_LOAD_SETTLE_TICKS) { checkFoliaRegionIsolation(FOLIA_LOAD_OBSERVE_ATTEMPTS) }
            return
        }
        if (remaining == 0) {
            fail("Folia 已观测 region 未在限定时间内形成两个真实区域样本:${ReadApiConsumer.observedRegionDiagnostic(targetId, controlId)}")
            return
        }
        runLater { checkObservedRegions(remaining - 1) }
    }

    /** 只在目标 region 的真实 tick 上运行有界 CPU 负载，以验证分区指标隔离。 */
    private fun checkFoliaRegionIsolation(remaining: Int) {
        val baseline = foliaBaseline ?: error("Folia 负载基线尚未形成")
        val pair = ReadApiConsumer.observedRegionPair(foliaTargetRegionId.get(), foliaControlRegionId.get())
        val evidence = pair?.let { regionIsolationEvidence(baseline, it) }
        if (evidence != null && foliaLoadRuns.get() >= MIN_FOLIA_LOAD_RUNS) {
            kickFoliaPlayers()
            runLater(FOLIA_EXPIRE_SETTLE_TICKS) { checkObservedRegionsExpired(evidence, FOLIA_EXPIRE_ATTEMPTS) }
            return
        }
        if (remaining == 0) {
            fail("Folia 受控 region 负载未形成隔离的 MSPT 证据")
            return
        }
        runLater { checkFoliaRegionIsolation(remaining - 1) }
    }

    /** 控制台命令仅作真实运行烟测，详细数值仍以公开 API 的强断言为准。 */
    private fun smokeFoliaTpsCommand() {
        check(Bukkit.dispatchCommand(server.consoleSender, "probe tps")) { "Folia /probe tps 命令执行失败" }
    }

    /** 目标 p95 必须显著抬升，而控制 region 的变化不得达到目标变化的一半。 */
    private fun regionIsolationEvidence(
        baseline: ReadApiConsumer.FoliaRegionPair,
        current: ReadApiConsumer.FoliaRegionPair,
    ): Map<String, String>? {
        val targetDelta = current.target.msptP95!! - baseline.target.msptP95!!
        val controlDelta = current.control.msptP95!! - baseline.control.msptP95!!
        if (targetDelta < MIN_TARGET_MSPT_DELTA || controlDelta > targetDelta / CONTROL_DELTA_DIVISOR) {
            return null
        }
        return mapOf(
            "targetRegionId" to current.target.foliaRegionId.toString(),
            "controlRegionId" to current.control.foliaRegionId.toString(),
            "targetP95Delta" to targetDelta.toString(),
            "controlP95Delta" to controlDelta.toString(),
            "loadRuns" to foliaLoadRuns.get().toString(),
        )
    }

    /** 踢出两个协议 bot，验证配置的已观测 region 保留窗口实际生效。 */
    private fun kickFoliaPlayers() {
        stopFoliaRegionTasks()
        foliaPlayers.forEach { name -> Bukkit.dispatchCommand(server.consoleSender, "minecraft:kick $name 验收结束") }
    }

    private fun checkObservedRegionsExpired(evidence: Map<String, String>, remaining: Int) {
        if (ReadApiConsumer.observedRegionsExpired()) {
            pass("Folia 真实 region tick 已形成明细、汇总并在离开后过期", evidence + ("expired" to "true"))
            return
        }
        if (remaining == 0) {
            fail("Folia 玩家离开后已观测 region 未按配置过期")
            return
        }
        runLater { checkObservedRegionsExpired(evidence, remaining - 1) }
    }

    private fun retryFoliaPlayers(remaining: Int) {
        if (remaining == 0) {
            fail("Folia 验收未等到两个真实协议 bot 入服")
            return
        }
        runLater { awaitFoliaPlayers(remaining - 1) }
    }

    /** 在启动画像异步落盘前安装记录型存储，使两类真实写入都经过 SPI。 */
    private fun installStorageScenario() {
        runCatching {
            storageSession = ReadApiConsumer.installStorage()
            checkStorageWrites(STORAGE_ATTEMPTS)
        }.onFailure {
            fail("安装第三方存储失败:${it.message}")
        }
    }

    /** 等待启动画像与定时指标各至少一次真实写入后卸载替换。 */
    private fun checkStorageWrites(remaining: Int) {
        val session = requireStorageSession()
        if (ReadApiConsumer.startupWrites(session) > 0 && ReadApiConsumer.historyWrites(session) > 0) {
            ReadApiConsumer.closeStorage(session)
            val countAtClose = ReadApiConsumer.historyWrites(session)
            runLater { verifyStorageRestored(countAtClose) }
            return
        }
        if (remaining == 0) {
            fail("第三方存储未收到启动画像和指标历史写入")
            return
        }
        runLater { checkStorageWrites(remaining - 1) }
    }

    /** 卸载后等一个采集周期，记录型存储不再接收写入即证明已回退默认实现。 */
    private fun verifyStorageRestored(historyWritesAtClose: Int) {
        val session = requireStorageSession()
        if (ReadApiConsumer.historyWrites(session) != historyWritesAtClose) {
            fail("关闭存储注册后仍写入第三方存储")
            return
        }
        pass(
            "第三方存储已收到真实写入并在关闭注册后回退默认实现",
            mapOf(
                "startupWrites" to ReadApiConsumer.startupWrites(session).toString(),
                "historyWrites" to historyWritesAtClose.toString(),
            ),
        )
    }

    /** ServerProbe 启用后才解析包含公开 API 类型的消费者类，保留 fixture 的加载先后顺序。 */
    private fun requireStorageSession(): Any = storageSession ?: error("第三方存储会话尚未创建")

    /** 非桥场景统一缩短采集周期，保证回退断言至少跨越一次真实采集。 */
    private fun writeFastCollectionConfiguration() {
        val configuration = java.io.File(dataFolder.parentFile, "$SERVER_PROBE_PLUGIN/config.yml")
        configuration.parentFile.mkdirs()
        configuration.writeText(
            """
            collect-period-ticks: 20
            world:
              sample-period-ticks: 20
            folia:
              observed-region-expire-seconds: 2
            """.trimIndent() + "\n"
        )
    }

    /** 通过真实 HTTP JSON-RPC 验证鉴权、原生工具、平台命令与 Arthas sc/watch。 */
    private fun verifyMcp(): Map<String, String> {
        Thread.sleep(3_000)
        // 用 HttpURLConnection 而非 java.net.http.HttpClient:后者在 Java 8 不存在,
        // 会被事件注册的成员解析触发 NoClassDefFoundError,使 harness 在旧版本服务器上注册失败。
        fun call(name: String, arguments: String = "{}"): String {
            val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$arguments}}"
            val connection = java.net.URL("http://127.0.0.1:19876/mcp").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer e2e-secret")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            return response
        }
        check(call("server_status").contains("result")) { "server_status 未返回结果" }
        check(call("thread_top").contains("result")) { "thread_top 未返回结果" }
        check(call("server_command", "{\"command\":\"say mcp-e2e\"}").contains("success")) { "server_command 未返回" }
        val scTaskId = submitArthasTask(::call, "sc top.wcpe.mc.plugin.serverprobe.e2e.*")
        awaitArthasSuccess(::call, scTaskId, "sc")
        val watchTaskId = submitArthasTask(::call, "watch top.wcpe.mc.plugin.serverprobe.e2e.ServerProbeE2eHarnessPlugin arthasWatchTarget '{params,returnObj}' -n 1")
        triggerWatchTarget(::call, watchTaskId)
        val watchOutput = awaitArthasSuccess(::call, watchTaskId, "watch")
        check(watchOutput.contains(WATCH_TARGET_VALUE)) {
            "Arthas watch 未记录测试调用:${watchOutput.take(MAX_MCP_ERROR_RESPONSE_CHARS)}"
        }
        return mapOf("scTaskId" to scTaskId, "watchTaskId" to watchTaskId, "arthas" to "sc,watch")
    }

    /** 仅作为 Arthas 一次性 watch 的确定性目标；不访问 Bukkit 状态，也不改变服务器数据。 */
    fun arthasWatchTarget(value: String): String = value

    private fun submitArthasTask(call: (String, String) -> String, command: String): String {
        val task = call("arthas_execute", "{\"command\":${jsonString(command)},\"timeoutMillis\":5000}")
        return Regex("\\\"taskId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(task.replace("\\\"", "\""))?.groupValues?.get(1)
            ?: error("arthas_execute 未返回任务:${task.take(MAX_MCP_ERROR_RESPONSE_CHARS)}")
    }

    /** -n 1 的 watch 仅等待一次目标调用；未完成则取消，防止测试残留增强任务。 */
    private fun triggerWatchTarget(call: (String, String) -> String, taskId: String) {
        repeat(WATCH_TRIGGER_ATTEMPTS) {
            Thread.sleep(WATCH_TRIGGER_INTERVAL_MILLIS)
            arthasWatchTarget(WATCH_TARGET_VALUE)
            val status = call("arthas_task_status", "{\"taskId\":\"$taskId\"}")
            if (status.contains("SUCCEEDED")) return
            if (status.contains("FAILED") || status.contains("TIMED_OUT")) error("Arthas watch 启动失败:${status.take(MAX_MCP_ERROR_RESPONSE_CHARS)}")
        }
    }

    private fun awaitArthasSuccess(call: (String, String) -> String, taskId: String, operation: String): String {
        var status = ""
        repeat(ARTHAS_STATUS_ATTEMPTS) {
            Thread.sleep(ARTHAS_STATUS_INTERVAL_MILLIS)
            status = call("arthas_task_status", "{\"taskId\":\"$taskId\"}")
            if (status.contains("SUCCEEDED")) return call("arthas_task_output", "{\"taskId\":\"$taskId\"}")
            if (status.contains("FAILED") || status.contains("CANCELLED") || status.contains("TIMED_OUT")) {
                error("Arthas $operation 执行失败:${status.take(MAX_MCP_ERROR_RESPONSE_CHARS)}")
            }
        }
        call("arthas_task_cancel", "{\"taskId\":\"$taskId\"}")
        error("Arthas $operation 未在限定时间完成:${status.take(MAX_MCP_ERROR_RESPONSE_CHARS)}")
    }

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 注册仅测试的 e2e Provider，再由回环 fixture 走真实桥协议下发命令。 */
    private fun startBridgeScenario() {
        runCatching {
            val activeFixture = checkNotNull(bridgeFixture) { "协议 fixture 未启动" }
            registerBusinessProvider()
            activeFixture.openCommands()
            awaitFixture(
                activeFixture,
                BRIDGE_FIXTURE_TIMEOUT_SECONDS,
                "协议 fixture 完成 业务链路验收",
                "协议 fixture 验收失败",
            )
        }.onFailure {
            fail("启动业务桥验收失败:${it.message}")
        }
    }

    /**
     * 帧分片场景：注册同一个 Provider（多一个 `big` 动作），由 fixture 下发大回执命令。
     *
     * 判定全部发生在 fixture 侧（按 groupId 重组 + 逐项断言），这里只负责把结论落成结果文件。
     */
    private fun startChunkScenario() {
        runCatching {
            val activeFixture = checkNotNull(chunkFixture) { "分片 fixture 未启动" }
            registerBusinessProvider()
            activeFixture.openCommands()
            awaitFixture(
                activeFixture,
                CHUNK_FIXTURE_TIMEOUT_SECONDS,
                "协议 fixture 完成 帧分片验收",
                "协议 fixture 分片验收失败",
            )
        }.onFailure {
            fail("启动帧分片验收失败:${it.message}")
        }
    }

    /**
     * 事件主题订阅场景：注册 Provider 后由 fixture 下发 `core.subscribe`，再触发三条可靠业务事件。
     *
     * 判定全部发生在 fixture 侧（命中/未命中/未声明三类 + `filteredOut` 计数），
     * 这里只负责把结论落成结果文件。
     */
    private fun startTopicScenario() {
        runCatching {
            val activeFixture = checkNotNull(topicFixture) { "主题订阅 fixture 未启动" }
            registerBusinessProvider()
            activeFixture.openCommands()
            awaitFixture(
                activeFixture,
                TOPIC_FIXTURE_TIMEOUT_SECONDS,
                "协议 fixture 完成 事件主题订阅验收",
                "协议 fixture 主题订阅验收失败",
            )
        }.onFailure {
            fail("启动事件主题订阅验收失败:${it.message}")
        }
    }

    /** fixture 等待可能跨越 Provider 超时，必须放后台线程，绝不占服务器主线程。 */
    private fun awaitFixture(
        activeFixture: WebSocketFixtureServer,
        timeoutSeconds: Long,
        successMessage: String,
        failureMessage: String,
        /** 证据落地前的附加校验：可增补证据，抛异常即判定失败。默认原样通过。 */
        validate: (Map<String, String>) -> Map<String, String> = { it },
        /** 该场景断言在证据表里的键前缀（各场景 payload 对象自持，默认沿用健康状态场景的那份）。 */
        assertPrefix: String = HealthFixturePayload.ASSERT_PREFIX,
    ) {
        Thread({
            runCatching { validate(activeFixture.awaitEvidence(timeoutSeconds)) }
                .onSuccess { evidence -> runSync { passScenario(successMessage, evidence, assertPrefix) } }
                .onFailure { error -> runSync { fail("$failureMessage:${error.message}") } }
        }, "serverprobe-e2e-fixture-await").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 把 fixture 的逐项断言转登进结果文件，再按汇总结果落 PASS。
     *
     * fixture 无法直接持有 [ScenarioResultWriter]（它在探针那一侧，两者只通过桥通信），
     * 因此约定：证据表里以 `<场景>FixturePayload.ASSERT_PREFIX` 开头的键是断言结果。
     * 这样一来 `assertions.total/passed` 是**逐项断言汇总**出来的，而"跑完即 PASS"不再可能——
     * 任何一条断言没被登记，计数就会露出来。
     */
    private fun passScenario(
        message: String,
        evidence: Map<String, String>,
        prefix: String = HealthFixturePayload.ASSERT_PREFIX,
    ) {
        val (assertions, details) = evidence.entries.partition { it.key.startsWith(prefix) }
        assertions.forEach { (key, value) ->
            val name = key.removePrefix(prefix)
            resultWriter?.assertTrue(name, value == ASSERT_PASSED_VALUE, value)
        }
        pass(message, details.associate { it.key to it.value })
    }

    /**
     * 通过 ServerProbe 对外门面自注册业务域——这正是/要验证的第三方路径。
     *
     * 不再钻探针运行期容器：门面是 static volatile + register，探针 @PostConstruct 装配后即可用，
     * 无需反射，也无需把测试 Provider 放进生产产物。
     */
    /**
     * Provider 健康状态场景：注册正常 / 抛异常 / 卡死三个域，再由 fixture 下发 `core.business_status`。
     *
     * 判定全部发生在 fixture 侧（三域区分呈现 + 整段 success + 查询不派发），
     * harness 只额外核对**Provider 侧**的派发计数：回执里的 `dispatch.total` 是探针的统计，
     * 而这里数的是"我们的 `dispatch()` 到底有没有被调用过"，两者互为独立证据。
     */
    private fun startHealthScenario() {
        runCatching {
            val activeFixture = checkNotNull(healthFixture) { "健康状态 fixture 未启动" }
            registerHealthProviders()
            activeFixture.openCommands()
            awaitFixture(
                activeFixture,
                HEALTH_FIXTURE_TIMEOUT_SECONDS,
                "协议 fixture 完成 Provider 健康状态验收",
                "协议 fixture 健康状态验收失败",
                ::verifyNoProviderDispatch,
            )
        }.onFailure {
            fail("启动健康状态验收失败:${it.message}")
        }
    }

    /**
     * 证据落地前的最后一道校验：三个 Provider 的 `dispatch()` 一次都没被调用过。
     *
     * 为什么不只依赖回执里的 `dispatch.total`：那是探针自己的计数，理论上可能因实现缺陷而漏记；
     * 在 Provider 内计数则是**从被调用方看**的独立事实。两者都为 0 才真正说明查询路径不派发。
     */
    private fun verifyNoProviderDispatch(evidence: Map<String, String>): Map<String, String> {
        val dispatched = healthDispatchedDomains.toList().sorted()
        check(dispatched.isEmpty()) { "查询路径触发了业务派发:$dispatched" }
        return evidence + mapOf("providerDispatchCalls" to healthDispatches.get().toString())
    }

    /**
     * 配置重载通知场景：注册精确 / 目录前缀 / 拒绝三个监听器，再由 fixture 下发 `core.config_reload`。
     *
     * 判定全部发生在 fixture 侧（回执结构 + `costMs` + 监听器真实调用计数），这里只负责把结论
     * 落成结果文件，并额外核对监听器的**累计**调用次数——它与 fixture 读到的计数同源，
     * 但作为落地前的最后一道门，仍值得独立断一次（多一次调用就说明有路径被重复通知）。
     */
    private fun startReloadScenario() {
        runCatching {
            val activeFixture = checkNotNull(reloadFixture) { "配置重载 fixture 未启动" }
            registerReloadListeners()
            activeFixture.openCommands()
            awaitFixture(
                activeFixture,
                RELOAD_FIXTURE_TIMEOUT_SECONDS,
                "协议 fixture 完成 配置重载验收",
                "协议 fixture 配置重载验收失败",
                ::verifyReloadListenerTotals,
                ReloadFixturePayload.ASSERT_PREFIX,
            )
        }.onFailure {
            fail("启动配置重载验收失败:${it.message}")
        }
    }

    /**
     * 累计调用次数必须与逐条命令推导出的期望值一致（推导见 [ReloadFixtureState.EXPECTED_CALLS]）。
     *
     * 这条断言的分量在于"**恰好**"：少了说明有通知没送达，多了说明同一路径被重复通知或
     * 一次事件被拆成多次回调（§3.1 要求一次回调覆盖该监听器的全部命中路径）。
     */
    private fun verifyReloadListenerTotals(evidence: Map<String, String>): Map<String, String> {
        val totals = reloadState.snapshot()
        check(totals == ReloadFixtureState.EXPECTED_CALLS) {
            "监听器累计调用次数不符:期望 ${ReloadFixtureState.EXPECTED_CALLS},实际 $totals"
        }
        return evidence + mapOf(
            "listenerCallsExact" to totals.exact.toString(),
            "listenerCallsPrefix" to totals.prefix.toString(),
            "listenerCallsReject" to totals.reject.toString(),
        )
    }

    /** 注册三个监听器；顺序即派发顺序，与 `EXPECTED_CALLS` 的推导无关（各自独立计数）。 */
    private fun registerReloadListeners() {
        check(server.pluginManager.getPlugin(SERVER_PROBE_PLUGIN) != null) { "未找到 ServerProbe 插件" }
        check(ServerProbeAPI.awaitAvailable(FACADE_AWAIT_MS)) {
            "ServerProbe 对外门面未在 ${FACADE_AWAIT_MS}ms 内就绪（探针装配失败？）"
        }
        reloadHandles += registerReloadListener(ExactPathReloadListener())
        reloadHandles += registerReloadListener(PrefixReloadListener())
        reloadHandles += registerReloadListener(RejectingReloadListener())
    }

    /** 注册单个监听器，并校验句柄真的生效（注册被拒必须当场暴露，否则场景只是在等超时）。 */
    private fun registerReloadListener(listener: ConfigReloadListener): Registration {
        val handle = ServerProbeAPI.configReloads().register(listener)
        check(handle.getActive()) {
            "配置重载监听器 ${listener.javaClass.name} 注册失败：${handle.getInactiveReason()} ${handle.getInactiveDetail()}"
        }
        return handle
    }

    /**
     * 精确路径监听器：只关心 `plugins/ReloadFixture/ok.yml`，返回不带任何自报字段的 `ok()`。
     *
     * 不自报字段是刻意的：同一路径还会被 [PrefixReloadListener] 命中，由后者上报三个自报字段。
     * 两个监听器都不报的路径不会出现在 `applied` 里（§3.7），因此这里不能"顺手也报一个"。
     */
    private inner class ExactPathReloadListener : ConfigReloadListener {

        override fun watchedPaths(): Collection<String> = listOf(ReloadFixturePayload.EXACT_PATH)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            reloadState.exactCalls.incrementAndGet()
            reloadState.exactLastMatched = event.matchedPaths
            return ConfigReloadResult.ok()
        }

        override fun toString(): String = "ServerProbeE2eReloadListener(exact)"
    }

    /**
     * 目录前缀监听器：覆盖 `plugins/ReloadFixture/` 下任意层级，返回带自定义 detail 与三个
     * 自报字段的 `ok()`。
     *
     * ## 为什么对 `partial.yml` 只上报 `count`
     * §3.7 规定 `applied` 里"缺省该键表示未上报，不要写 0"，而 `count=0` 又是合法事实。
     * 要同时验证这两点，必须有一个"只报一部分字段"的路径：它在独立的一次请求里被命中，
     * 于是该路径的 `applied` 只有 `count: 0`，`md5`/`version` 两个键整个不出现。
     */
    private inner class PrefixReloadListener : ConfigReloadListener {

        override fun watchedPaths(): Collection<String> = listOf(ReloadFixturePayload.PREFIX)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            reloadState.prefixCalls.incrementAndGet()
            reloadState.prefixLastMatched = event.matchedPaths
            reloadState.prefixLastRequested = event.requestedPaths
            reloadState.prefixLastFullReload = event.fullReload
            val result = ConfigReloadResult.ok(ReloadFixturePayload.DETAIL_JSON)
            if (event.matchedPaths.contains(ReloadFixturePayload.PARTIAL_PATH)) {
                return result.withAppliedCount(ReloadFixturePayload.PARTIAL_COUNT)
            }
            return result
                .withAppliedMd5(ReloadFixturePayload.MD5)
                .withAppliedVersion(ReloadFixturePayload.VERSION)
                .withAppliedCount(ReloadFixturePayload.COUNT)
        }

        override fun toString(): String = "ServerProbeE2eReloadListener(prefix)"
    }

    /**
     * 拒绝监听器：只关心 `plugins/ReloadFixture/reject.yml`，明确返回 `PLUGIN_REJECTED`。
     *
     * 只声明这一条精确路径是必须的：一次回调的结果作用于它的**全部** `matchedPaths`，
     * 若它也声明前缀，拒绝就会把同一次请求里的成功路径一并判失败。
     */
    private inner class RejectingReloadListener : ConfigReloadListener {

        override fun watchedPaths(): Collection<String> = listOf(ReloadFixturePayload.REJECT_PATH)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            reloadState.rejectCalls.incrementAndGet()
            return ConfigReloadResult.failed(
                ConfigReloadErrorCode.PLUGIN_REJECTED,
                ReloadFixturePayload.REJECT_MESSAGE,
            )
        }

        override fun toString(): String = "ServerProbeE2eReloadListener(reject)"
    }

    /**
     * 按**字典序**注册三个域：正常 → 抛异常 → 卡死。
     *
     * 顺序不能随意：刷新线程按域名字典序逐个 probe，而 probe 池是单线程且有界队列。
     * 卡死域一旦排在最前，唯一 probe 线程会被它占住，另外两个域本轮直接跳过，
     * "三域区分呈现"就永远等不出来。域名前缀 `a`/`b`/`c` 已把这条约束固化（见 HealthFixturePayload）。
     */
    private fun registerHealthProviders() {
        check(server.pluginManager.getPlugin(SERVER_PROBE_PLUGIN) != null) { "未找到 ServerProbe 插件" }
        check(ServerProbeAPI.awaitAvailable(FACADE_AWAIT_MS)) {
            "ServerProbe 对外门面未在 ${FACADE_AWAIT_MS}ms 内就绪（探针装配失败？）"
        }
        healthHandles += registerHealthProvider(HealthyProvider())
        healthHandles += registerHealthProvider(ThrowingProvider())
        healthHandles += registerHealthProvider(StuckProvider())
    }

    /** 注册单个健康场景 Provider，并校验句柄真的生效（注册失败必须当场暴露）。 */
    private fun registerHealthProvider(provider: BusinessProvider): Registration {
        val handle = ServerProbeAPI.businessHost().register(provider)
        check(handle.getActive()) {
            "业务域 ${provider.domain()} 注册失败：${handle.getInactiveReason()} ${handle.getInactiveDetail()}"
        }
        return handle
    }

    /** 健康场景 Provider 的公共实现：manifest 与 dispatch 行为一致，只有 `health()` 各自覆写。 */
    private abstract inner class HealthStubProvider : BusinessProvider {

        override fun manifest(): DomainManifest = manifestOf(domain(), HEALTH_ACTIONS)

        /**
         * 三个域都不该被派发：这里既计数又留痕，任何一次调用都会让场景失败。
         *
         * 返回成功而不是失败，避免"派发被记为失败"这类副作用掩盖真实违约——
         * 本场景要证明的是"一次都没派发"，不是"派发了但失败了"。
         */
        override fun dispatch(request: ActionRequest): ActionResult {
            healthDispatches.incrementAndGet()
            healthDispatchedDomains += domain()
            return ActionResult.ok(request.payloadJson)
        }

        override fun toString(): String = "ServerProbeE2eHealthProvider(${domain()})"
    }

    /** 正常域：`health()` 恒报可用。 */
    private inner class HealthyProvider : HealthStubProvider() {
        override fun domain(): String = HealthFixturePayload.OK_DOMAIN

        override fun health(): ProviderHealth = ProviderHealth.available()
    }

    /** 抛异常域：`health()` 直接抛，探针必须兜住并转成该域的 DEGRADED，绝不影响其它域。 */
    private inner class ThrowingProvider : HealthStubProvider() {
        override fun domain(): String = HealthFixturePayload.THROW_DOMAIN

        override fun health(): ProviderHealth = throw IllegalStateException(HealthFixturePayload.THROW_MESSAGE)
    }

    /**
     * 卡死域：`health()` **真阻塞**远超 `bridge.health.timeout-ms`，且刻意不响应中断。
     *
     * ## 为什么不能用 `Thread.sleep` 一把睡完
     * 超时后探针会 `future.cancel(true)` 打断这次调用。若睡眠被中断后立刻返回，
     * 这个域就变成"答得慢但最终答了"，掩盖了"不可中断地卡住"这一真实形态
     * （规格 §6 风险 2 登记的上限正是它）。因此中断被显式吞掉，继续睡到自己的截止时刻。
     *
     * ## 为什么用睡眠而不是自旋
     * 自旋会白烧一个 CPU 核，在真机 E2E 上会污染其它场景的时序观测；睡眠同样"不可中断"，
     * 但不产生额外负载。
     */
    private inner class StuckProvider : HealthStubProvider() {
        override fun domain(): String = HealthFixturePayload.STUCK_DOMAIN

        override fun health(): ProviderHealth {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HealthFixturePayload.STUCK_BLOCK_MS)
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(HealthFixturePayload.STUCK_POLL_MS)
                } catch (_: InterruptedException) {
                    // 刻意忽略：模拟不可中断的第三方 health()，这正是规格登记过的卡死形态。
                }
            }
            return ProviderHealth.available()
        }
    }

    private fun registerBusinessProvider() {
        check(server.pluginManager.getPlugin(SERVER_PROBE_PLUGIN) != null) { "未找到 ServerProbe 插件" }
        check(ServerProbeAPI.awaitAvailable(FACADE_AWAIT_MS)) {
            "ServerProbe 对外门面未在 ${FACADE_AWAIT_MS}ms 内就绪（探针装配失败？）"
        }
        val handle = ServerProbeAPI.businessHost().register(E2eBusinessProvider())
        check(handle.getActive()) {
            "业务域 $E2E_DOMAIN 注册失败：${handle.getInactiveReason()} ${handle.getInactiveDetail()}"
        }
        businessHandle = handle
        registerLegacyProvider()
    }

    /**
     * 同时注册一个**旧契约**域（向后兼容的真机验证）。
     *
     * 单元测试只能证明 BusinessHostImpl 的分类逻辑，prove 不了「旧插件换成新 jar 后仍能被注册」——
     * 那正是这次改造最可能踩坏的地方（第三方插件不会同步升级）。注册成功后由 fixture 断言
     * 聚合 manifest 里该域存在且 `specDeclared` 为 false。
     */
    private fun registerLegacyProvider() {
        val handle = ServerProbeAPI.businessHost().registerLegacy(LegacyE2eBusinessProvider())
        check(handle.getActive()) {
            "旧契约业务域 $LEGACY_DOMAIN 注册失败：${handle.getInactiveReason()} ${handle.getInactiveDetail()}"
        }
        legacyBusinessHandle = handle
    }

    /** 解注册走句柄 close，验证「陈旧句柄不得误删后来注册的同域 Provider」这条保护。 */
    private fun unregisterBusinessProvider() {
        businessHandle?.close()
        businessHandle = null
        legacyBusinessHandle?.close()
        legacyBusinessHandle = null
        healthHandles.forEach { it.close() }
        healthHandles.clear()
        reloadHandles.forEach { it.close() }
        reloadHandles.clear()
    }

    /** e2e 业务域实现：真实实现 api 契约，因此同时验证契约本身能否被第三方落地。 */
    private inner class E2eBusinessProvider : BusinessProvider {
        override fun domain(): String = E2E_DOMAIN

        override fun manifest(): DomainManifest = manifestOf(E2E_DOMAIN, E2E_ACTIONS)

        /** 成功、失败、阻塞与事件均在真实 BusinessHost 线程池内执行，不切主线程。 */
        override fun dispatch(request: ActionRequest): ActionResult =
            dispatchE2e(request.action, request.payloadJson)

        override fun toString(): String = "ServerProbeE2eProvider"
    }

    /**
     * 旧契约（形态）域：`implements LegacyBusinessProvider`，裸 Map manifest + 双字符串 dispatch。
     *
     * 它是「第三方插件只把 `implements BusinessProvider` 改了一个词」的真实形态，
     * 因此能证明向后兼容不是文档承诺而是可运行的事实。
     */
    private inner class LegacyE2eBusinessProvider : LegacyBusinessProvider {
        override fun domain(): String = LEGACY_DOMAIN

        override fun manifest(): Map<String, Any> =
            mapOf(LegacyBusinessProvider.ACTIONS_KEY to listOf("echo"))

        override fun dispatch(action: String, payloadJson: String): BridgeCommandResult =
            if (action == "echo") {
                BridgeCommandResult.ok(payloadJson)
            } else {
                BridgeCommandResult.fail("旧契约 fixture 不支持该动作：$action")
            }

        override fun toString(): String = "ServerProbeE2eLegacyProvider"
    }

    /**
     * 构造本 fixture 的清单（强类型）。
     *
     * 动作清单来自既有常量，风险统一声明为 `WRITE`：E2E 关注的是"命令能否完整往返"，
     * 而不是"这个动作有多危险"；声明 READ 会让发布方误以为这些 fixture 动作是只读的。
     */
    private fun manifestOf(domain: String, actions: List<String>): DomainManifest = DomainManifest.of(
        domain,
        domain,
        "ServerProbe E2E fixture 的业务域",
        actions.map { ActionSpec.of(it, "", RiskLevel.WRITE, false, null, null, null) },
        mapOf("fixture" to "true"),
    )

    /** 业务结果仅由 Provider 产生，fixture 只验证真实回执，不伪造成功。 */
    private fun dispatchE2e(action: String, payload: String): ActionResult = when (action) {
        // 主题订阅场景：一次命令发出三条可靠事件，回执 output 携带三者的受理结果。
        TopicFixturePayload.ACTION_EMIT_TOPIC -> emitTopicEvents()
        ACTION_FAIL -> ActionResult.fail("FIXTURE_FAILURE", "fixture failure")
        // 约 1 MiB 的确定性载荷：用来触发真实分片（阈值远小于它）。
        ACTION_BIG -> ActionResult.ok(ChunkFixturePayload.text)
        ACTION_SLOW -> {
            Thread.sleep(PROVIDER_DELAY_MS)
            ActionResult.ok(payload)
        }
        ACTION_EMIT -> {
            emitBusinessEventViaCore()
            ActionResult.ok("emitted")
        }
        else -> ActionResult.ok(payload)
    }

    /**
     * 一次性发出三条**可靠**业务事件，作为主题过滤的唯一触发点。
     *
     * ## 为什么三条都走 `emitReliable`
     * 规格 §3.5 规定"被主题滤镜丢弃"仍返回 `true`。若"未命中"那条走 `emit`（可丢），
     * 返回 `true` 也可能来自 best-effort 的宽松语义，就无法证明"没上行"是滤镜判定而非通道丢弃。
     * 三条都不可丢 + 都带非空 `dedupKey`（规格 §3.3 的硬性要求），回执里的三个 `true` 只能解释为已受理。
     *
     * ## 为什么用对外门面而不是 core 内部反射
     * 这正是第三方 Provider 的真实路径（把 `BusinessEventSink` 定为唯一对外类型）：
     * E2E 顺带验证门面可用，且门面代理在探针未就绪时返回 `false`，这里会立刻暴露成断言失败。
     */
    private fun emitTopicEvents(): ActionResult {
        val sink = ServerProbeAPI.eventSink()
        val hit = sink.emitReliable(
            TopicFixturePayload.DOMAIN,
            TopicFixturePayload.HIT_DEDUP_KEY,
            TopicFixturePayload.HIT_TOPIC,
            topicFields(TopicFixturePayload.PURPOSE_HIT, TopicFixturePayload.HIT_TOPIC),
        )
        val miss = sink.emitReliable(
            TopicFixturePayload.DOMAIN,
            TopicFixturePayload.MISS_DEDUP_KEY,
            TopicFixturePayload.MISS_TOPIC,
            topicFields(TopicFixturePayload.PURPOSE_MISS, TopicFixturePayload.MISS_TOPIC),
        )
        // 未声明主题：topic 传 null（= 帧上没有 topic 字段），有订阅时仍必须放行。
        val untopiced = sink.emitReliable(
            TopicFixturePayload.DOMAIN,
            TopicFixturePayload.UNTOPICED_DEDUP_KEY,
            null,
            mapOf(TopicFixturePayload.PURPOSE_FIELD to TopicFixturePayload.PURPOSE_UNTOPICED),
        )
        return ActionResult.ok("hit=$hit;miss=$miss;untopiced=$untopiced")
    }

    /** 事件结构化字段：`purpose` 标记用途，`declared` 自行声明主题（供 fixture 核对同源同值）。 */
    private fun topicFields(purpose: String, topic: String): Map<String, String> = mapOf(
        TopicFixturePayload.PURPOSE_FIELD to purpose,
        TopicFixturePayload.DECLARED_FIELD to topic,
    )

    /**
     * 业务事件上行。
     *
     * 事件上报的对外门面（eventSink）属于尚未落地；在此之前只能走 core 内部的
     * BridgeClient.emitBusinessEvent。这段反射是临时通道，待落地后改为门面调用并删除
     * 本方法与 BRIDGE_CLIENT_CLASS / CONTAINER_CLASS 两个常量。
     */
    private fun emitBusinessEventViaCore() {
        val probe = checkNotNull(server.pluginManager.getPlugin(SERVER_PROBE_PLUGIN)) { "未找到 ServerProbe 插件" }
        val loader = probe.javaClass.classLoader
        val container = loader.loadClass(CONTAINER_CLASS).getField("INSTANCE").get(null)
        val bridge = checkNotNull(
            container.javaClass.getMethod("getBean", Class::class.java, String::class.java)
                .invoke(container, loader.loadClass(BRIDGE_CLIENT_CLASS), null),
        ) { "桥客户端尚未就绪" }
        loader.loadClass(BRIDGE_CLIENT_CLASS)
            .getMethod("emitBusinessEvent", String::class.java, String::class.java, Map::class.java)
            .invoke(bridge, E2E_DOMAIN, EVENT_DEDUP_KEY, mapOf("kind" to "emitted"))
    }

    private fun resolveResultFile() = java.io.File(System.getenv(RESULT_FILE_ENV) ?: error("缺少 mc-testkit 结果文件路径"))

    /** Folia 走全局区域调度器，Paper 保持 Bukkit 调度器，避免桩触发不支持的全局调度。 */
    private fun runLater(task: () -> Unit) = runLater(CHECK_DELAY_TICKS, task)

    private fun runLater(delayTicks: Long, task: () -> Unit) {
        val scheduler = foliaGlobalScheduler
        if (scheduler == null) {
            server.scheduler.runTaskLater(this, Runnable { task() }, delayTicks)
            return
        }
        scheduler.javaClass.getMethod("runDelayed", org.bukkit.plugin.Plugin::class.java, Consumer::class.java, Long::class.javaPrimitiveType)
            .invoke(scheduler, this, Consumer<Any?> { task() }, maxOf(1L, delayTicks))
    }

    private fun runSync(task: () -> Unit) {
        val scheduler = foliaGlobalScheduler
        if (scheduler == null) {
            server.scheduler.runTask(this, Runnable { task() })
            return
        }
        scheduler.javaClass.getMethod("run", org.bukkit.plugin.Plugin::class.java, Consumer::class.java)
            .invoke(scheduler, this, Consumer<Any?> { task() })
    }

    /** Folia 专有类存在时取得全局区域调度器；Paper 运行时保持 null。 */
    private val foliaGlobalScheduler: Any? by lazy {
        runCatching {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            Bukkit.getServer().javaClass.getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer())
        }.getOrNull()
    }

    /** E2E 仅在指定 region 线程读取 Folia 真实内部 id，用来与公开快照严格对应。 */
    private val foliaRegionIdReader: FoliaRegionIdReader by lazy { FoliaRegionIdReader.create() }

    private fun pass(message: String, details: Map<String, String>) {
        if (completed.compareAndSet(false, true)) {
            stopFoliaRegionTasks()
            resultWriter?.pass(message, details)
            runLater { server.shutdown() }
        }
    }

    private fun fail(message: String) {
        if (completed.compareAndSet(false, true)) {
            stopFoliaRegionTasks()
            logger.severe(message)
            resultWriter?.fail(message)
            runLater { server.shutdown() }
        }
    }

    private enum class E2eScenario(val id: String) {
        READ_API("read-api"),
        INCISION_STARTUP("incision-startup"),
        STORAGE_SPI("storage-spi"),
        BRIDGE_FIXTURE("bridge-fixture"),
        CHUNK_FIXTURE("chunk-fixture"),
        TOPIC_FIXTURE("topic-fixture"),
        HEALTH_FIXTURE("health-fixture"),
        RELOAD_FIXTURE("reload-fixture"),
        NETWORK_FORENSICS_BUNGEE("network-forensics-bungee"),
        FOLIA_OBSERVED_REGIONS("folia-observed-regions"),
        MCP_DIAGNOSTICS_PAPER("mcp-diagnostics-paper"),
        MATRIX_SMOKE("matrix-smoke"),
        ;

        companion object {
            fun from(id: String?): E2eScenario = entries.firstOrNull { it.id == id }
                ?: entries.firstOrNull { it == MATRIX_SMOKE && id != null && id.startsWith("matrix-") }
                ?: error("未知 E2E 场景:$id")
        }
    }

    /** 1.12 起才有 ServerLoadEvent；旧版本主类不得在签名中引用它，故桥接为按场景注册的嵌套监听器。 */
    private class ServerLoadBridge(private val owner: ServerProbeE2eHarnessPlugin) : Listener {
        @EventHandler
        fun onServerLoad(event: ServerLoadEvent) {
            owner.handleServerLoad(event.type == ServerLoadEvent.LoadType.STARTUP)
        }
    }

    /** Folia 专属调度任务匣：io.papermc 类型只出现在此嵌套类，旧版本反射主类时不会解析到缺失类型。 */
    private class FoliaTaskBox {
        var target: ScheduledTask? = null
        var control: ScheduledTask? = null

        fun stop(loadEnabled: AtomicBoolean) {
            loadEnabled.set(false)
            target?.cancel()
            control?.cancel()
            target = null
            control = null
        }
    }

    private companion object {
        private const val SCENARIO_ENV = "MC_TESTKIT_E2E_SCENARIO"
        private const val RESULT_FILE_ENV = "MC_TESTKIT_E2E_RESULT_FILE"
        private const val SERVER_PROBE_PLUGIN = "ServerProbe"
        /** 门面就绪等待上限：探针 @PostConstruct 装配很快，但 E2E 环境可能排到较后。 */
        private const val FACADE_AWAIT_MS = 15_000L
        // 仅事件通路仍需要：的 eventSink 落地后，两个常量与方法 emitBusinessEventViaCore 一并删除。
        private const val CONTAINER_CLASS = "top.wcpe.mc.plugin.serverprobe.ioc.bean.BeanContainer"
        private const val BRIDGE_CLIENT_CLASS = "top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient"
        private const val E2E_DOMAIN = "e2e"
        /** 旧契约域的域名：用于真机验证向后兼容（必须以 `specDeclared=false` 出现在 manifest 里）。 */
        private const val LEGACY_DOMAIN = "e2e-legacy"
        private const val ACTION_EMIT = "emit"
        private const val ACTION_FAIL = "fail"
        private const val ACTION_SLOW = "slow"
        /** 分片场景的大回执动作：返回约 1 MiB 的确定性载荷。 */
        private const val ACTION_BIG = "big"
        /** 主题订阅场景的动作：一次性发出命中/未命中/未声明三条可靠事件。 */
        private const val ACTION_EMIT_TOPIC = TopicFixturePayload.ACTION_EMIT_TOPIC
        private val E2E_ACTIONS = listOf("echo", ACTION_EMIT, ACTION_FAIL, ACTION_SLOW, ACTION_BIG, ACTION_EMIT_TOPIC)
        /** 三个健康域的能力清单：动作只有 `echo`，本场景从不下发业务命令。 */
        private val HEALTH_ACTIONS = listOf(HealthFixturePayload.ACTION_ECHO)
        private const val EVENT_DEDUP_KEY = "fixture-event"
        private const val PROVIDER_DELAY_MS = 6_000L
        private const val CHECK_DELAY_TICKS = 20L
        /** FR-26 历史回读线程名:该 API 明示可能读盘、宜异步,主线程调用不进 E2E。 */
        private const val HISTORY_THREAD_NAME = "serverprobe-e2e-history"

        /** FR-26 历史回读条数上限:只验契约与"确实来自落盘",不追求全量。 */
        private const val HISTORY_READ_LIMIT = 5

        private const val READ_API_ATTEMPTS = 20
        private const val MATRIX_READ_API_ATTEMPTS = 90
        private const val STORAGE_ATTEMPTS = 20
        private const val FOLIA_PLAYER_ATTEMPTS = 30
        private const val FOLIA_OBSERVE_ATTEMPTS = 40
        /**
         * 分片场景配置里的 `bridge.chunk.max-frame-bytes`。
         *
         * 必须与 `e2e/templates/configs/chunk-fixture/plugins/ServerProbe/config.yml` 一致：
         * fixture 用它逐片核对"每片整帧字节不超过阈值"，两处失配会让断言失去意义。
         */
        private const val CHUNK_MAX_FRAME_BYTES = 65_536
        /** 桥 fixture 证据等待上限（沿用既有 25s 口径）。 */
        private const val BRIDGE_FIXTURE_TIMEOUT_SECONDS = 25L
        /** 分片 fixture 需要传输约 1.4 MiB base64，给足观察窗口（失败时才等这么久）。 */
        private const val CHUNK_FIXTURE_TIMEOUT_SECONDS = 60L
        /** 主题订阅 fixture 含 3s 静默窗口，失败路径也给足余量。 */
        private const val TOPIC_FIXTURE_TIMEOUT_SECONDS = 60L
        /**
         * 健康状态 fixture 的证据等待上限。
         *
         * 含 300ms 超时判定 + 逐次查询的往返，正常路径 2s 内完成；给足余量只为让失败路径
         * 仍能拿到"哪条断言先破"的完整消息，而不是提前超时。
         */
        private const val HEALTH_FIXTURE_TIMEOUT_SECONDS = 60L
        /**
         * 配置重载 fixture 的证据等待上限。
         *
         * 五条命令都是"监听器立刻返回"的形态，正常路径 1s 内完成；
         * 给足余量只为让失败路径仍能拿到"哪条断言先破"的完整消息，而不是提前超时。
         */
        private const val RELOAD_FIXTURE_TIMEOUT_SECONDS = 60L
        /** 断言成立时 fixture 写入的值；此处按同一口径判定该条断言通过。 */
        private const val ASSERT_PASSED_VALUE = "true"
        private const val REQUIRED_FOLIA_PLAYERS = 2
        private const val FOLIA_CHUNK_LOAD_TICKS = 40L
        private const val FOLIA_SETTLE_TICKS = 100L
        private const val FOLIA_LOAD_SETTLE_TICKS = 120L
        private const val FOLIA_EXPIRE_SETTLE_TICKS = 60L
        private const val FOLIA_TARGET_BLOCK = 0
        private const val FOLIA_TELEPORT_Y = 100
        private const val FOLIA_EXPIRE_ATTEMPTS = 20
        private const val FOLIA_LOAD_OBSERVE_ATTEMPTS = 30
        private const val MIN_FOLIA_LOAD_RUNS = 40
        private const val MIN_TARGET_MSPT_DELTA = 6.0
        private const val CONTROL_DELTA_DIVISOR = 2.0
        private const val FOLIA_LOAD_BUDGET_NANOS = 15_000_000L
        private const val LOAD_MULTIPLIER = 1_103_515_245L
        private const val LOAD_INCREMENT = 12_345L
        private const val CHUNK_COORDINATE_BITS = 4
        private const val FIRST_REGION_TASK_DELAY_TICKS = 1L
        private const val REGION_TASK_PERIOD_TICKS = 1L
        private const val UNKNOWN_REGION_ID = -1L
        private const val MAX_MCP_ERROR_RESPONSE_CHARS = 512
        private const val ARTHAS_STATUS_ATTEMPTS = 50
        private const val ARTHAS_STATUS_INTERVAL_MILLIS = 100L
        private const val WATCH_TRIGGER_ATTEMPTS = 10
        private const val WATCH_TRIGGER_INTERVAL_MILLIS = 100L
        private const val WATCH_TARGET_VALUE = "mcp-watch-once"
        private const val OVERWORLD_DIMENSION = "minecraft:overworld"
        private const val NETHER_DIMENSION = "minecraft:the_nether"
    }
}

/** Folia 内部 id 的最小 E2E 读取器；生产指标不依赖此测试辅助类。 */
private class FoliaRegionIdReader private constructor(
    private val currentRegion: java.lang.reflect.Method,
    private val regionId: java.lang.reflect.Field,
) {

    fun currentId(): Long {
        val region = requireNotNull(currentRegion.invoke(null)) { "当前 region 线程未提供内部 region" }
        return regionId.getLong(region)
    }

    companion object {

        fun create(): FoliaRegionIdReader {
            val scheduler = Class.forName(TICK_REGION_SCHEDULER)
            val region = Class.forName(THREADED_REGION)
            return FoliaRegionIdReader(scheduler.getMethod("getCurrentRegion"), region.getField("id"))
        }

        private const val TICK_REGION_SCHEDULER = "io.papermc.paper.threadedregions.TickRegionScheduler"
        private const val THREADED_REGION = "io.papermc.paper.threadedregions.ThreadedRegionizer\$ThreadedRegion"
    }
}
