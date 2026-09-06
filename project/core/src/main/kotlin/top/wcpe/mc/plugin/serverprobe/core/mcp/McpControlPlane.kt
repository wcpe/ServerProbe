package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.core.bridge.ServerStateSupport
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
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

/** 默认关闭的 MCP 控制面生命周期；仅启用后才创建监听 socket。 */
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

    /** FR-17：可选注入的世界明细提供者；非 Bukkit 平台缺失时降级为既有输出。 */
    @Inject(required = false)
    var worldDetailProvider: WorldDetailProvider? = null

    private var httpServer: McpHttpServer? = null
    private var auditTrail: McpAuditTrail? = null
    private var artifactCleanup: ScheduledExecutorService? = null
    private var artifacts: McpArtifactWorkspace? = null

    @PostEnable
    fun start() {
        val settings = settings()
        if (!settings.enabled) {
            ProbeLogger.info("MCP 控制面未开启(mcp.enabled=false)，已跳过")
            return
        }
        McpSafetyWarnings.messages(settings).forEach(ProbeLogger::warn)
        runCatching { startServer(settings) }.onFailure {
            ProbeLogger.warn("MCP 控制面启动失败(${settings.host}:${settings.port})，已降级跳过：${it.javaClass.simpleName}")
        }
    }

    @PreDestroy
    fun stop() {
        if (httpServer != null) {
            stopServer()
            ProbeLogger.info("MCP 控制面已停止")
        }
    }

    private fun startServer(settings: McpSettings) {
        stopServer()
        val workspace = getDataFolder().toPath().resolve("mcp-workspace")
        val artifactWorkspace = McpArtifactWorkspace(workspace.resolve("artifacts"), artifactSettings())
        artifactWorkspace.cleanup()
        artifacts = artifactWorkspace
        // 注册进工作区注册表，供 Binary/PrePatch/Flamegraph/GcJfr 等扩展 provider 委托访问
        mcpArtifactWorkspaceRegistry.register(artifactWorkspace)
        val audit = McpAuditTrail(
            workspace,
            maxFileBytes = ProbeConfig.mcpAuditMaxFileMb() * 1024L * 1024L,
            retentionDays = ProbeConfig.mcpAuditRetentionDays(),
        )
        McpHttpServer(settings, McpJsonRpcDispatcher(
            providers(),
            audit = audit,
            encodeResult = McpJsonWriter::encode,
        )).also {
            it.start()
            httpServer = it
            auditTrail = audit
            artifactCleanup = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ServerProbe-McpArtifactCleanup").apply { isDaemon = true }
            }.also { executor ->
                executor.scheduleWithFixedDelay({
                    runCatching(artifactWorkspace::cleanup).onFailure { error ->
                        ProbeLogger.warn("MCP 工件定期清理失败：${error.javaClass.simpleName}")
                    }
                }, CLEANUP_INTERVAL_HOURS, CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS)
            }
        }
        ProbeLogger.info("MCP 控制面已启动，监听 ${settings.host}:${settings.port}/mcp")
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

    private fun settings(): McpSettings = McpSettings(
        ProbeConfig.mcpEnabled(), ProbeConfig.mcpHost(), ProbeConfig.mcpPort(), ProbeConfig.mcpSecret(),
    )

    /** 供 NativeMcpToolProvider 组合 server_status 与 diagnostic_bundle；测试经内部可见性直测合并逻辑。 */
    internal fun serverStatus(): Map<String, Any?> = linkedMapOf(
        "jvm" to ServerStateSupport.jvmSnapshot(),
        "classLoading" to ServerStateSupport.classLoadingCounts(),
        "latestMetricSnapshot" to readApi.latestSnapshot(),
    ).let { output ->
        // FR-17：provider 存在时把 worlds 输出增强（entityTypeCounts/regionStats），
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
        return output + ("latestMetricSnapshot" to enhancedSnapshot)
    }

    /** 组合全部 MCP 工具提供者：原生工具 + 扩展 provider（FR-15~22 交付时在 McpToolProviderRegistry 注册）。 */
    private fun providers(): List<McpToolProvider> = buildList {
        add(NativeMcpToolProvider(
            ::serverStatus,
            platformControl = platformControl,
            arthasControl = arthasControl,
            defaultArthasTimeoutMillis = TimeUnit.MINUTES.toMillis(ProbeConfig.mcpTaskTimeoutMinutes()),
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
