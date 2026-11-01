package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.core.bridge.ServerStateSupport
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneModeService
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerGate
import top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerLauncher
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.Named
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import taboolib.common.platform.function.getDataFolder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 默认关闭的 MCP 控制面生命周期；仅启用后才创建监听 socket。
 *
 * ## 托管模式下零监听（协议规格 §3.6）
 * 托管模式下本控制面**一律不起服**，运行期开启命令同样被拒——判断依据是运行模式，不是 `mcp.enabled`：
 * 该键虽属 FORBIDDEN 档，但本地 `config.yml` 里遗留的 `true` 不会被迁移清掉，故必须由
 * [LocalListenerGate] 按模式强制关断。独立模式行为与引入托管能力前完全一致。
 *
 * ## 启动期异步、运行期同步
 * - `@PostEnable` 的 [start] 只起后台线程**等托管模式判定**（主线程零阻塞），判定后才决定是否 bind；
 * - 控制台命令触发的 [enable] 发生在判定完成之后，故直接同步过闸门即可。
 */
@Service
class McpControlPlane {

    @Inject
    lateinit var readApi: ProbeReadApi

    @Inject
    @Named("platformControlRegistry")
    lateinit var platformControl: PlatformControl

    @Inject
    lateinit var arthasControl: ArthasControl

    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    @Inject
    lateinit var mcpArtifactWorkspaceRegistry: McpArtifactWorkspaceRegistry

    /**
     * 托管模式判定服务：启动期必须先等它给出结论，再决定是否监听（托管模式零监听的承诺）。
     */
    @Inject
    lateinit var modeService: ControlPlaneModeService

    /** 可选注入的世界明细提供者；非 Bukkit 平台缺失时降级为既有输出。 */
    @Inject(required = false)
    var worldDetailProvider: WorldDetailProvider? = null

    private var httpServer: McpHttpServer? = null
    private var auditTrail: McpAuditTrail? = null
    private var artifactCleanup: ScheduledExecutorService? = null
    private var artifacts: McpArtifactWorkspace? = null

    @PostEnable
    fun start() {
        // 配置读取与“是否监听”的判定一律放到后台线程：
        // 1) 主线程零阻塞（项目红线）；
        // 2) `mcp.enabled` 属禁止远程档，值只能来自本地 config.yml，而 IOC 装配线程可能跑在
        //    TabooLib 注入配置之前——在主线程直接读会偶发拿到默认 false，把端点误判为“未开启”。
        LocalListenerLauncher(LocalListenerGate.Surface.MCP, awaitDecision = { modeService.awaitReady(it) })
            .launch { startIfEnabled() }
    }

    /** 后台判定：等配置就绪 → `mcp.enabled` 为真才真正尝试起服。 */
    private fun startIfEnabled() {
        if (!ProbeConfig.awaitLoaded()) {
            ProbeLogger.warn("MCP 控制面等待配置就绪超时，将按当前可见值判断 mcp.enabled")
        }
        if (!runCatching { settings().enabled }.getOrDefault(false)) {
            ProbeLogger.info("MCP 控制面未开启(mcp.enabled=false)，已跳过")
            return
        }
        enable()
    }

    @PreDestroy
    @Synchronized
    fun stop() {
        if (disable()) {
            ProbeLogger.info("MCP 控制面已停止")
        }
    }

    /**
     * 运行期开启 MCP 控制面（控制台命令触发）。
     *
     * 与启动期 [start] 共用同一实现，区别仅在绕过 `mcp.enabled`配置门；危险配置（空密钥、非回环）
     * 的中文告警照常输出。启动失败（端口占用等）在此收敛为返回值并回滚残留，不向调用方抛异常。
     *
     * 幂等判定在此完成（而非由调用方先查 [running] 再调用）：并发命令若都在检查时看到"未运行"，
     * 后到的一条会走到 [startServer] 首行的停止逻辑，把已起来的端点在无人使用的情况下重启一次。
     * 此处作为唯一决策点，已运行时直接返回说明。
     *
     * @return 启动结果说明；失败时 [McpToggleOutcome.success] 为 false。
     */
    @Synchronized
    fun enable(): McpToggleOutcome {
        // 托管模式下零监听：判断依据是运行模式而非配置值，本地遗留的 mcp.enabled=true 不作数。
        // 这是全类唯一的 bind 入口，故启动期与运行期命令两条路径都被这一道闸门覆盖。
        if (!LocalListenerGate.allows(LocalListenerGate.Surface.MCP)) {
            return McpToggleOutcome(false, "托管模式下不启动本地监听端口（零监听端口承诺），MCP 控制面已跳过。")
        }
        val settings = settings(enabled = true)
        if (running) return McpToggleOutcome(true, "MCP 控制面已在运行中，无需重复开启。")
        McpSafetyWarnings.messages(settings).forEach(ProbeLogger::warn)
        // 失败时必须清理：startServer 可能已创建并注册工件工作区，留下幽灵注册会让扩展工具误判为已启用。
        return runCatching { startServer(settings) }.fold(
            onSuccess = { McpToggleOutcome(true, "MCP 控制面已启动，监听 ${settings.host}:${settings.port}/mcp") },
            onFailure = { error ->
                stopServer()
                ProbeLogger.warn("MCP 控制面启动失败(${settings.host}:${settings.port})：${error.javaClass.simpleName}")
                McpToggleOutcome(false, "MCP 控制面启动失败(${settings.host}:${settings.port})：${error.javaClass.simpleName}")
            },
        )
    }

    /**
     * 运行期关闭 MCP 控制面（控制台命令触发）。
     *
     * 无条件走 [stopServer]（其内部全空安全）：启动中途失败可能留下已注册但未监听的工作区，
     * 旧实现的 `httpServer != null`守卫会跳过这类残留，故不再按句柄判断。
     *
     * @return 本次是否确实执行了停止（此前未运行返回 false）。
     */
    @Synchronized
    fun disable(): Boolean {
        // 先注销回撤登记：本方法就是回撤动作本身，避免完场后还留着悬空引用
        LocalListenerGate.unregisterBound(LocalListenerGate.Surface.MCP)
        if (httpServer == null && artifacts == null) return false
        stopServer()
        return true
    }

    /** MCP 控制面当前是否正在监听。 */
    val running: Boolean
        get() = httpServer != null

    private fun startServer(settings: McpSettings) {
        stopServer()
        ensureRuntime()
        McpHttpServer(
            settings,
            McpJsonRpcDispatcher(providers(), audit = auditTrail, encodeResult = McpJsonWriter::encode),
        ).also { server ->
            server.start()
            httpServer = server
            // 登记回撤动作：若托管模式在 bind 之后才落定，由闸门在 publish 时把它关掉
            LocalListenerGate.registerBound(LocalListenerGate.Surface.MCP) { disable() }
            artifactCleanup = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ServerProbe-McpArtifactCleanup").apply { isDaemon = true }
            }.also { executor ->
                executor.scheduleWithFixedDelay({
                    runCatching { artifacts?.cleanup() }.onFailure { error ->
                        ProbeLogger.warn("MCP 工件定期清理失败：${error.javaClass.simpleName}")
                    }
                }, CLEANUP_INTERVAL_HOURS, CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS)
            }
        }
        ProbeLogger.info("MCP 控制面已启动，监听 ${settings.host}:${settings.port}/mcp")
    }

    /**
     * 按需创建工件工作区与审计轨迹（幂等）。
     *
     * ## 为什么与 "起监听" 拆开
     * 托管模式下 MCP 不再监听本地端口（零监听端口承诺），但仍需完整的工具面——工件工作区（堆 dump、
     * GC JFR 等大产物落盘）与审计轨迹与端口无关。不拆开就只能二选一：要么开端口（违背承诺），
     * 要么退化成“只能调无状态工具”。
     *
     * @return 工件工作区；数据目录不可用时为 null（工具面仍可用，只是大产物类工具降级）。
     */
    @Synchronized
    internal fun ensureRuntime(): McpArtifactWorkspace? {
        artifacts?.let { return it }
        val workspace = runCatching { getDataFolder().toPath().resolve("mcp-workspace") }.getOrNull() ?: return null
        val created = McpArtifactWorkspace(workspace.resolve("artifacts"), artifactSettings())
        runCatching { created.cleanup() }
        artifacts = created
        auditTrail = McpAuditTrail(
            workspace,
            maxFileBytes = ProbeConfig.mcpAuditMaxFileMb() * 1024L * 1024L,
            retentionDays = ProbeConfig.mcpAuditRetentionDays(),
        )
        mcpArtifactWorkspaceRegistry.register(created)
        return created
    }

    /**
     * 供桥（WS 传输）复用的 JSON-RPC 分发器：**不绑定任何端口**。
     *
     * 与本地 HTTP 端点共用同一份工具面、工件工作区与审计口径，因此两条通道的行为一致；
     * 由调用方持有生命周期，停机时调 [disable] 释放工作区与审计。
     */
    @Synchronized
    internal fun bridgeDispatcher(): McpJsonRpcDispatcher {
        ensureRuntime()
        return McpJsonRpcDispatcher(providers(), audit = auditTrail, encodeResult = McpJsonWriter::encode)
    }

    private fun stopServer() {
        httpServer?.stop()
        httpServer = null
        artifacts?.let(mcpArtifactWorkspaceRegistry::unregister)
        artifacts = null
        artifactCleanup?.shutdownNow()
        artifactCleanup = null
        auditTrail?.close()
        auditTrail = null
    }

    private fun artifactSettings(): ArtifactWorkspaceSettings = ArtifactWorkspaceSettings(
        ProbeConfig.mcpArtifactMaxGib().coerceAtMost(MAX_ARTIFACT_GIB) * BYTES_PER_GIB,
        TimeUnit.HOURS.toMillis(ProbeConfig.mcpArtifactRetentionHours().coerceAtMost(MAX_ARTIFACT_RETENTION_HOURS)),
    )

    /** 组装控制面设置；[enabled] 供运行期开关显式覆盖 `mcp.enabled`（该字段仅语义用途，不参与监听决策）。 */
    private fun settings(enabled: Boolean = ProbeConfig.mcpEnabled()): McpSettings = McpSettings(
        enabled, ProbeConfig.mcpHost(), ProbeConfig.mcpPort(), ProbeConfig.mcpSecret(),
    )

    /** 供 NativeMcpToolProvider 组合 server_status 与 diagnostic_bundle；测试经内部可见性直测合并逻辑。 */
    internal fun serverStatus(): Map<String, Any?> = linkedMapOf(
        "jvm" to ServerStateSupport.jvmSnapshot(),
        "classLoading" to ServerStateSupport.classLoadingCounts(),
        "latestMetricSnapshot" to readApi.latestSnapshot(),
    ).let { output ->
        //：provider 存在时把 worlds 输出增强（entityTypeCounts/regionStats），
        // 缺失时保持既有快照输出（向后兼容）。增强仅作用于输出层，不改动 WorldMetrics 模型。
        val detailProvider = worldDetailProvider
        if (detailProvider != null) enhanceWorlds(output, detailProvider) else output
    }

    /**
     * 把世界明细合并进 server_status 输出的 latestMetricSnapshot.server.worlds（输出层增强）。
     *
     * 快照模型字段类型固定（worlds 为 [WorldMetrics] 列表），新字段无法回填模型，故命中明细的
     * 世界以同名字段 Map 输出（字段名与 bean 序列化一致，向后兼容）；未命中明细的世界保持原对象。
     */
    private fun enhanceWorlds(output: Map<String, Any?>, detailProvider: WorldDetailProvider): Map<String, Any?> {
        val snapshot = output["latestMetricSnapshot"] as? MetricSnapshot ?: return output
        val server = snapshot.server ?: return output
        val worlds = server.worlds ?: return output
        val detailsByName = detailProvider.details().associateBy { it.name }
        // 无可用明细时保持既有输出结构（与 provider 缺失同语义的降级）。
        if (detailsByName.isEmpty()) return output
        val enhancedWorlds = worlds.map { world ->
            val detail = detailsByName[world.name]
            if (detail == null) world else linkedMapOf<String, Any?>(
                "name" to world.name,
                "loadedChunks" to world.loadedChunks,
                "entityCount" to world.entityCount,
                "tileEntityCount" to world.tileEntityCount,
                "entitiesByType" to world.entitiesByType,
                "entityTypeCounts" to detail.entityTypeCounts,
                "regionStats" to detail.regionStats,
            )
        }
        val enhancedServer = linkedMapOf<String, Any?>(
            "tick" to server.tick,
            "onlinePlayers" to server.onlinePlayers,
            "maxPlayers" to server.maxPlayers,
            "uptimeMs" to server.uptimeMs,
            "worlds" to enhancedWorlds,
            "pingDistribution" to server.pingDistribution,
            "observedRegions" to server.observedRegions,
            "observedRegionWorlds" to server.observedRegionWorlds,
        )
        val enhancedSnapshot = linkedMapOf<String, Any?>(
            "schemaVersion" to snapshot.schemaVersion,
            "timestampMs" to snapshot.timestampMs,
            "serverId" to snapshot.serverId,
            "platform" to snapshot.platform,
            "jvm" to snapshot.jvm,
            "server" to enhancedServer,
            "proxy" to snapshot.proxy,
        )
        // McpJsonWriter 对 Map/数组按 MAX_ITEMS(256) 静默截断；worlds 超限时给出提示，避免 agent 误读为完整列表
        return output + ("latestMetricSnapshot" to enhancedSnapshot) +
            ("worldsTruncated" to (enhancedWorlds.size >= McpJsonWriter.MAX_ITEMS))
    }

    /**
     * 组合全部 MCP 工具提供者：原生工具 + 扩展 provider（~22 交付时在 McpToolProviderRegistry 注册）。
     *
     * [arthasTimeoutMillis] 默认取配置值（生产路径）；测试可显式传入，
     * 从而在没有 `config.yml` 的纯 JVM 里也能组合出真实的工具集。
     */
    internal fun providers(
        arthasTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(ProbeConfig.mcpTaskTimeoutMinutes()),
    ): List<McpToolProvider> = buildList {
        add(NativeMcpToolProvider(
            ::serverStatus,
            platformControl = platformControl,
            arthasControl = arthasControl,
            defaultArthasTimeoutMillis = arthasTimeoutMillis,
            artifacts = artifacts,
        ))
        addAll(mcpToolProviderRegistry.providers())
    }

    private companion object {
        const val BYTES_PER_GIB = 1024L * 1024 * 1024
        const val MAX_ARTIFACT_GIB = 1024L
        const val MAX_ARTIFACT_RETENTION_HOURS = 24L * 365
        const val CLEANUP_INTERVAL_HOURS = 1L
    }
}
