package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 服务器运行时指标快照(FR2.2,Bukkit 端)。
 *
 * 聚合 tick 采样与基础在线/运行时长信息。仅 Bukkit 系服务端具备完整语义;
 * 代理端无此概念(详见 [MetricSnapshot.server])。
 *
 * @property tick tick 采样数据(TPS/MSPT)。
 * @property onlinePlayers 当前在线人数。
 * @property maxPlayers 最大可容纳人数。
 * @property uptimeMs 服务器运行时长(毫秒)。
 */
data class ServerMetrics(
    val tick: TickSample,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val uptimeMs: Long
)
