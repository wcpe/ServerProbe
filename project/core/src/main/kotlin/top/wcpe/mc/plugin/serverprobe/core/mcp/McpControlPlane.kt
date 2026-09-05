package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
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

    private var httpServer: McpHttpServer? = null
    private var auditTrail: McpAuditTrail? = null
    private var artifactCleanup: ScheduledExecutorService? = null

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
        val artifacts = McpArtifactWorkspace(workspace.resolve("artifacts"), artifactSettings())
        artifacts.cleanup()
        val audit = McpAuditTrail(
            workspace,
            maxFileBytes = ProbeConfig.mcpAuditMaxFileMb() * 1024L * 1024L,
            retentionDays = ProbeConfig.mcpAuditRetentionDays(),
        )
        McpHttpServer(settings, McpJsonRpcDispatcher(
            NativeMcpToolProvider(
                ::serverStatus,
                platformControl = platformControl,
                arthasControl = arthasControl,
                defaultArthasTimeoutMillis = TimeUnit.MINUTES.toMillis(ProbeConfig.mcpTaskTimeoutMinutes()),
                artifacts = artifacts,
            ),
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
                    runCatching(artifacts::cleanup).onFailure { error ->
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

    private fun serverStatus(): Map<String, Any?> = linkedMapOf(
        "jvm" to ServerStateSupport.jvmSnapshot(),
        "classLoading" to ServerStateSupport.classLoadingCounts(),
        "latestMetricSnapshot" to readApi.latestSnapshot(),
    )

    private companion object {
        const val BYTES_PER_GIB = 1024L * 1024 * 1024
        const val MAX_ARTIFACT_GIB = 1024L
        const val MAX_ARTIFACT_RETENTION_HOURS = 24L * 365
        const val CLEANUP_INTERVAL_HOURS = 1L
    }
}
