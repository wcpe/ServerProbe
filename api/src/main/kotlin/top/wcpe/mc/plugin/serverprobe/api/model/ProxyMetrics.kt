package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 代理端运行时指标快照(M1,A 方案)。
 *
 * 聚合代理总在线与各子服在线明细。仅代理端(BUNGEE 等)具备此语义;
 * 服务端无此概念(详见 [MetricSnapshot.proxy])。
 *
 * 注:M2 在此基础上补充子服 ping/可达性、玩家路由(玩家分布到各子服)、每玩家延迟等。
 *
 * @property totalOnline 代理当前总在线人数。
 * @property backends 各后端子服在线明细。
 */
data class ProxyMetrics(
    val totalOnline: Int,
    val backends: List<BackendServer>
)
