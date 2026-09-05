package top.wcpe.mc.plugin.serverprobe.velocity.collector

import com.velocitypowered.api.proxy.ProxyServer
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.server
import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.api.collector.ProxyMetricsCollector
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.registry.ProbeRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap

/** Velocity 代理端的在线、后端 RTT、路由与玩家延迟采集器。 */
@Service
@PlatformSide(Platform.VELOCITY)
class VelocityProxyCollector : ProxyMetricsCollector {

    /** 通用采集器注册中心。 */
    @Inject
    lateinit var registry: ProbeRegistry

    /** 后端名到最近一次探测结果的并发缓存。 */
    private val pingCache = ConcurrentHashMap<String, VelocityBackendPing>()

    /** 周期探测任务与缓存的统一释放边界。 */
    private val lifecycle = VelocityCollectorLifecycle(pingCache)

    /** Velocity 端注册采集器并启动异步后端探测。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.VELOCITY) return
        registry.register(this)
        startPingTask()
        ProbeLogger.info("Velocity 代理端采集器已注册(含子服 ping/路由/每玩家 ping)")
    }

    /** 释放周期探测任务。 */
    @PreDestroy
    fun shutdown() {
        lifecycle.close()
        registry.unregister(this)
    }

    /** 启动受总时限保护的异步后端探测任务。 */
    private fun startPingTask() {
        lifecycle.start(submit(period = ProbeConfig.collectPeriodTicks(), async = true) {
            runCatching { pingAllBackends() }
                .onFailure { ProbeLogger.error("Velocity 代理端子服 ping 任务异常", it) }
        })
    }

    /** 读取缓存和 Velocity 当前连接，构造通用代理指标。 */
    override fun collect(): ProxyMetrics {
        val proxy = server<ProxyServer>()
        val players = proxy.allPlayers.map { player ->
            VelocityPlayerObservation(
                name = player.username,
                server = player.currentServer.orElse(null)?.serverInfo?.name,
                pingMs = player.ping.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        val backends = proxy.allServers.map { registered ->
            val name = registered.serverInfo.name
            val cached = pingCache[name]
            VelocityBackendObservation(
                name = name,
                online = registered.playersConnected.size,
                pingMs = cached?.pingMs ?: UNAVAILABLE_PING_MS,
                reachable = cached?.reachable ?: false,
            )
        }
        return VelocityProxyMetricMapper.map(proxy.playerCount, backends, players)
    }

    /** 在异步调度线程并发启动探测，等待仅发生在该后台任务。 */
    private fun pingAllBackends() {
        val proxy = server<ProxyServer>()
        val pings = proxy.allServers.associate { registered ->
            registered.serverInfo.name to VelocityPendingPing(System.nanoTime(), registered.ping())
        }
        val reachable = VelocityPingBatch(PING_BATCH_TIMEOUT_NANOS)
            .await(pings.mapValues { (_, pending) -> pending.future })
        for ((name, pending) in pings) {
            val completed = reachable.getValue(name)
            val ping = if (completed) elapsedMillis(pending.startedNanos) else UNAVAILABLE_PING_MS
            pingCache[name] = VelocityBackendPing(ping, completed)
        }
    }

    /** 把单调时钟耗时收敛为 API 的毫秒整数。 */
    private fun elapsedMillis(startedNanos: Long): Int =
        ((System.nanoTime() - startedNanos) / NANOS_PER_MILLI).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** 后端 ping 缓存记录。 */
    private data class VelocityBackendPing(val pingMs: Int, val reachable: Boolean)

    /** 已发起但尚未取回的后端 ping。 */
    private data class VelocityPendingPing(
        val startedNanos: Long,
        val future: java.util.concurrent.CompletableFuture<*>,
    )

    private companion object {
        private const val PING_BATCH_TIMEOUT_NANOS = 3_000_000_000L
        private const val UNAVAILABLE_PING_MS = -1
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
