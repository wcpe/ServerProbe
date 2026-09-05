package top.wcpe.mc.plugin.serverprobe.velocity.collector

import top.wcpe.mc.plugin.serverprobe.api.model.BackendServer
import top.wcpe.mc.plugin.serverprobe.api.model.PlayerPing
import top.wcpe.mc.plugin.serverprobe.api.model.PlayerRoute
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics

/** Velocity 平台原始观测到通用代理指标模型的纯映射。 */
object VelocityProxyMetricMapper {

    /** 将平台无关的原始观测映射为既有对外 ProxyMetrics 契约。 */
    fun map(
        totalOnline: Int,
        backends: List<VelocityBackendObservation>,
        players: List<VelocityPlayerObservation>,
    ): ProxyMetrics = ProxyMetrics.builder()
        .totalOnline(totalOnline)
        .backends(backends.map { it.toApi() })
        .playerPings(players.map { PlayerPing.builder().name(it.name).pingMs(it.pingMs).build() })
        .playerRoutes(players.mapNotNull { it.toRoute() })
        .build()

    private fun VelocityBackendObservation.toApi(): BackendServer = BackendServer.builder()
        .name(name)
        .online(online)
        .pingMs(pingMs)
        .reachable(reachable)
        .build()

    private fun VelocityPlayerObservation.toRoute(): PlayerRoute? = server?.let {
        PlayerRoute.builder().name(name).server(it).build()
    }
}

/** 一台 Velocity 后端的原始观测。 */
data class VelocityBackendObservation(
    val name: String,
    val online: Int,
    val pingMs: Int,
    val reachable: Boolean,
)

/** 一名 Velocity 玩家当前的路由与客户端延迟观测。 */
data class VelocityPlayerObservation(
    val name: String,
    val server: String?,
    val pingMs: Int,
)
