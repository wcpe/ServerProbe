package top.wcpe.mc.plugin.serverprobe.e2e

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/** ServerProbe 的独立第三方 E2E 消费者，负责 FR8 与 FR9 的真实判定。 */
class ServerProbeE2eHarnessPlugin : JavaPlugin(), Listener {

    private val scenario = E2eScenario.from(System.getenv(SCENARIO_ENV))
    private val completed = AtomicBoolean(false)
    private var resultWriter: ScenarioResultWriter? = null
    private var fixture: ProtocolWorkerFixture? = null
    private var storageSession: Any? = null
    private val foliaPlayers = ConcurrentHashMap.newKeySet<String>()
    private val foliaPlayersSeparated = AtomicBoolean(false)
    private val foliaLoadEnabled = AtomicBoolean(false)
    private val foliaLoadRuns = AtomicInteger()
    private val foliaTargetRegionId = AtomicLong(UNKNOWN_REGION_ID)
    private val foliaControlRegionId = AtomicLong(UNKNOWN_REGION_ID)
    // Folia 调度任务匣：io.papermc 类型只允许出现在嵌套类中，主类被旧版本服务器反射时不得触发类缺失。
    private var foliaTaskBox: Any? = null
    private var foliaBaseline: Fr8Consumer.FoliaRegionPair? = null
    @Volatile private var foliaLoadSink = 0L

    override fun onLoad() {
        when (scenario) {
            E2eScenario.BRIDGE_FIXTURE -> {
                fixture = ProtocolWorkerFixture.startFromProbeConfiguration(dataFolder.parentFile)
            }
            E2eScenario.INTEGRATIONS_BOTH,
            E2eScenario.INTEGRATIONS_MCE_ONLY,
            E2eScenario.INTEGRATIONS_AIS_ONLY,
            E2eScenario.INTEGRATIONS_NONE,
            -> fixture = ProtocolWorkerFixture.startFromProbeConfiguration(dataFolder.parentFile)
            E2eScenario.READ_API,
            E2eScenario.STORAGE_SPI,
            E2eScenario.NETWORK_FORENSICS_BUNGEE,
            E2eScenario.FOLIA_OBSERVED_REGIONS,
            E2eScenario.MATRIX_SMOKE,
            -> writeFastCollectionConfiguration()
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
            E2eScenario.INTEGRATIONS_BOTH,
            E2eScenario.INTEGRATIONS_MCE_ONLY,
            E2eScenario.INTEGRATIONS_AIS_ONLY,
            E2eScenario.INTEGRATIONS_NONE,
            -> startFr10IntegrationScenario()
            E2eScenario.READ_API,
            E2eScenario.NETWORK_FORENSICS_BUNGEE,
            E2eScenario.FOLIA_OBSERVED_REGIONS,
            E2eScenario.MCP_DIAGNOSTICS_PAPER,
            E2eScenario.MATRIX_SMOKE,
            -> Unit
        }
    }

    /** 仅在依赖 ServerLoadEvent 的场景注册桥监听；事件类缺失（1.11-）时静默跳过，保证旧版本可加载。 */
    private fun registerStartupListenerIfNeeded() {
        val needsStartupEvent = scenario == E2eScenario.STORAGE_SPI ||
            scenario == E2eScenario.BRIDGE_FIXTURE ||
            scenario == E2eScenario.INTEGRATIONS_BOTH ||
            scenario == E2eScenario.INTEGRATIONS_MCE_ONLY ||
            scenario == E2eScenario.INTEGRATIONS_AIS_ONLY ||
            scenario == E2eScenario.INTEGRATIONS_NONE
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
        stopFoliaRegionTasks()
        fixture?.close()
    }

    /** 轮询只读门面，直到真实采样快照与启动画像都已准备完成。 */
    private fun checkReadApi(remaining: Int) {
        // 早期快照可能尚未携带 Tick 指标(readEvidence 内的严格校验会抛异常):
        // 按"未就绪"容错重试,异常终止轮询会错失后续就绪窗口。
        val evidence = runCatching { Fr8Consumer.readEvidence() }.getOrNull()
        if (evidence != null) {
            pass("第三方插件已经公开门面读到运行快照与启动画像", evidence)
            return
        }
        if (remaining == 0) {
            fail("读取 API 未在限定时间内提供快照和启动画像")
            return
        }
        runLater { checkReadApi(remaining - 1) }
    }

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

    /** 验收 Region 明细、真实样本与每世界加权汇总均通过 FR8 公共读取接口可见。 */
    private fun checkObservedRegions(remaining: Int) {
        if (Fr8Consumer.foliaGlobalTickUnavailable() == false) {
            fail("Folia 全局 TPS/MSPT 未保持 N/A")
            return
        }
        val targetId = foliaTargetRegionId.get()
        val controlId = foliaControlRegionId.get()
        val evidence = Fr8Consumer.readObservedRegionEvidence()
        val pair = Fr8Consumer.observedRegionPair(targetId, controlId)
        if (evidence != null && pair != null) {
            foliaBaseline = pair
            smokeFoliaTpsCommand()
            foliaLoadEnabled.set(true)
            runLater(FOLIA_LOAD_SETTLE_TICKS) { checkFoliaRegionIsolation(FOLIA_LOAD_OBSERVE_ATTEMPTS) }
            return
        }
        if (remaining == 0) {
            fail("Folia 已观测 region 未在限定时间内形成两个真实区域样本:${Fr8Consumer.observedRegionDiagnostic(targetId, controlId)}")
            return
        }
        runLater { checkObservedRegions(remaining - 1) }
    }

    /** 只在目标 region 的真实 tick 上运行有界 CPU 负载，以验证分区指标隔离。 */
    private fun checkFoliaRegionIsolation(remaining: Int) {
        val baseline = foliaBaseline ?: error("Folia 负载基线尚未形成")
        val pair = Fr8Consumer.observedRegionPair(foliaTargetRegionId.get(), foliaControlRegionId.get())
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
        baseline: Fr8Consumer.FoliaRegionPair,
        current: Fr8Consumer.FoliaRegionPair,
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
        foliaPlayers.forEach { name -> Bukkit.dispatchCommand(server.consoleSender, "minecraft:kick $name FR12验收结束") }
    }

    private fun checkObservedRegionsExpired(evidence: Map<String, String>, remaining: Int) {
        if (Fr8Consumer.observedRegionsExpired()) {
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
            storageSession = Fr8Consumer.installStorage()
            checkStorageWrites(STORAGE_ATTEMPTS)
        }.onFailure {
            fail("安装第三方存储失败:${it.message}")
        }
    }

    /** 等待启动画像与定时指标各至少一次真实写入后卸载替换。 */
    private fun checkStorageWrites(remaining: Int) {
        val session = requireStorageSession()
        if (Fr8Consumer.startupWrites(session) > 0 && Fr8Consumer.historyWrites(session) > 0) {
            Fr8Consumer.closeStorage(session)
            val countAtClose = Fr8Consumer.historyWrites(session)
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
        if (Fr8Consumer.historyWrites(session) != historyWritesAtClose) {
            fail("关闭存储注册后仍写入第三方存储")
            return
        }
        pass(
            "第三方存储已收到真实写入并在关闭注册后回退默认实现",
            mapOf(
                "startupWrites" to Fr8Consumer.startupWrites(session).toString(),
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

    /** FR10 在后台经真实 BusinessHost 执行外部插件读写，Bukkit 生命周期操作切回服务器线程。 */
    private fun startFr10IntegrationScenario() {
        val activeFixture = checkNotNull(fixture) { "FR10 协议 fixture 未启动" }
        Thread({
            runCatching {
                IntegrationsConsumer(this, activeFixture, object : ServerThreadInvoker {
                    override fun <T> call(action: () -> T): T = callOnServerThread(action)
                }).verify(scenario.id)
            }.onSuccess { evidence ->
                runSync { pass("真实 MCE/AIS 集成已完成读写、失败恢复与卸载验收", evidence) }
            }.onFailure { error ->
                runSync { fail("FR10 真实集成验收失败:${integrationsFailureDetail(error)}") }
            }
        }, "serverprobe-e2e-integrations").apply {
            isDaemon = true
            start()
        }
    }

    /** 保留反射调用的根因与调用点，避免 InvocationTargetException 的空消息掩盖真实失败。 */
    private fun integrationsFailureDetail(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        val trace = cause.stackTrace.take(INTEGRATIONS_FAILURE_TRACE_LINES).joinToString(" <- ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        return "类型=${cause.javaClass.name},消息=${cause.message ?: "(无)"},调用点=$trace"
    }

    /** 将 Bukkit 对象访问和插件禁用切回服务器线程，后台验收线程只等待有限时结果。 */
    private fun <T> callOnServerThread(action: () -> T): T {
        val completion = CompletableFuture<T>()
        runSync {
            runCatching(action).onSuccess(completion::complete).onFailure(completion::completeExceptionally)
        }
        return completion.get(SERVER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** 注册仅测试的 e2e Provider，再由回环 fixture 走真实桥协议下发命令。 */
    private fun startBridgeScenario() {
        runCatching {
            val activeFixture = checkNotNull(fixture) { "协议 fixture 未启动" }
            registerBusinessProvider()
            activeFixture.openCommands()
            awaitFixture(activeFixture)
        }.onFailure {
            fail("启动业务桥验收失败:${it.message}")
        }
    }

    /** fixture 等待可能跨越 Provider 超时，必须放后台线程，绝不占服务器主线程。 */
    private fun awaitFixture(activeFixture: ProtocolWorkerFixture) {
        Thread({
            runCatching { activeFixture.awaitEvidence() }
                .onSuccess { evidence -> runSync { pass("协议 fixture 完成 FR9 业务链路验收", evidence) } }
                .onFailure { error -> runSync { fail("协议 fixture 验收失败:${error.message}") } }
        }, "serverprobe-e2e-fixture-await").apply {
            isDaemon = true
            start()
        }
    }

    /** 通过 ServerProbe 自身运行期容器反射取得业务宿主，避免将测试 Provider 放入生产产物。 */
    private fun registerBusinessProvider() {
        val probe = checkNotNull(server.pluginManager.getPlugin(SERVER_PROBE_PLUGIN)) { "未找到 ServerProbe 插件" }
        val loader = probe.javaClass.classLoader
        val hostClass = loader.loadClass(BUSINESS_HOST_CLASS)
        val providerClass = loader.loadClass(BUSINESS_PROVIDER_CLASS)
        val resultClass = loader.loadClass(BUSINESS_RESULT_CLASS)
        val bridgeClass = loader.loadClass(BRIDGE_CLIENT_CLASS)
        val container = loader.loadClass(CONTAINER_CLASS).getField("INSTANCE").get(null)
        val getBean = container.javaClass.getMethod("getBean", Class::class.java, String::class.java)
        val host = checkNotNull(getBean.invoke(container, hostClass, null)) { "业务宿主尚未就绪" }
        val bridge = checkNotNull(getBean.invoke(container, bridgeClass, null)) { "桥客户端尚未就绪" }
        val provider = Proxy.newProxyInstance(loader, arrayOf(providerClass)) { _, method, args ->
            handleProviderMethod(method.name, args.orEmpty(), bridge, bridgeClass, resultClass)
        }
        hostClass.getMethod("register", providerClass).invoke(host, provider)
    }

    /** e2e Provider 的成功、失败、阻塞与事件均在真实 BusinessHost 线程池内执行。 */
    private fun handleProviderMethod(
        name: String,
        args: Array<out Any?>,
        bridge: Any,
        bridgeClass: Class<*>,
        resultClass: Class<*>,
    ): Any? = when (name) {
        "getDomain" -> E2E_DOMAIN
        "manifest" -> mapOf("actions" to E2E_ACTIONS)
        "dispatch" -> dispatchE2e(args[0] as String, args[1] as String, bridge, bridgeClass, resultClass)
        "toString" -> "ServerProbeE2eProvider"
        else -> null
    }

    /** 业务结果仅由 Provider 产生，fixture 只验证真实回执，不伪造成功。 */
    private fun dispatchE2e(
        action: String,
        payload: String,
        bridge: Any,
        bridgeClass: Class<*>,
        resultClass: Class<*>,
    ): Any {
        if (action == ACTION_EMIT) {
            bridgeClass.getMethod("emitBusinessEvent", String::class.java, String::class.java, Map::class.java)
                .invoke(bridge, E2E_DOMAIN, EVENT_DEDUP_KEY, mapOf("kind" to "emitted"))
        }
        if (action == ACTION_SLOW) {
            Thread.sleep(PROVIDER_DELAY_MS)
        }
        return when (action) {
            ACTION_FAIL -> businessResult(resultClass, false, "", "fixture failure")
            ACTION_EMIT -> businessResult(resultClass, true, "emitted", "")
            else -> businessResult(resultClass, true, payload, "")
        }
    }

    /** 构造与 ServerProbe 同一 ClassLoader 的业务结果对象。 */
    private fun businessResult(resultClass: Class<*>, success: Boolean, output: String, error: String): Any =
        resultClass.getConstructor(Boolean::class.javaPrimitiveType, String::class.java, String::class.java)
            .newInstance(success, output, error)

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
        STORAGE_SPI("storage-spi"),
        BRIDGE_FIXTURE("bridge-fixture"),
        INTEGRATIONS_BOTH("integrations-both"),
        INTEGRATIONS_MCE_ONLY("integrations-mce-only"),
        INTEGRATIONS_AIS_ONLY("integrations-ais-only"),
        INTEGRATIONS_NONE("integrations-none"),
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
        private const val CONTAINER_CLASS = "top.wcpe.mc.plugin.serverprobe.ioc.bean.BeanContainer"
        private const val BUSINESS_HOST_CLASS = "top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHost"
        private const val BUSINESS_PROVIDER_CLASS = "top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessProvider"
        private const val BUSINESS_RESULT_CLASS = "top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult"
        private const val BRIDGE_CLIENT_CLASS = "top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient"
        private const val E2E_DOMAIN = "e2e"
        private const val ACTION_EMIT = "emit"
        private const val ACTION_FAIL = "fail"
        private const val ACTION_SLOW = "slow"
        private val E2E_ACTIONS = listOf("echo", ACTION_EMIT, ACTION_FAIL, ACTION_SLOW)
        private const val EVENT_DEDUP_KEY = "fixture-event"
        private const val PROVIDER_DELAY_MS = 6_000L
        private const val SERVER_THREAD_TIMEOUT_SECONDS = 15L
        private const val INTEGRATIONS_FAILURE_TRACE_LINES = 6
        private const val CHECK_DELAY_TICKS = 20L
        private const val READ_API_ATTEMPTS = 20
        private const val MATRIX_READ_API_ATTEMPTS = 90
        private const val STORAGE_ATTEMPTS = 20
        private const val FOLIA_PLAYER_ATTEMPTS = 30
        private const val FOLIA_OBSERVE_ATTEMPTS = 40
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
