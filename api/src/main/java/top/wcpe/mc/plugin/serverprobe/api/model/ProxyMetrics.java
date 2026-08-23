package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 代理端运行时指标快照(M1,A 方案)。
 *
 * 聚合代理总在线与各子服在线明细;M2+ 补充子服 ping/可达性、玩家路由、每玩家延迟(FR2.5)。
 * 仅代理端(BUNGEE 等)具备此语义;服务端无此概念(详见 MetricSnapshot.proxy)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class ProxyMetrics {
    /** 代理当前总在线人数。 */
    int totalOnline;
    /** 各后端子服在线明细。 */
    java.util.List<BackendServer> backends;
    /**
     * 各玩家到其所在子服的延迟明细(FR2.5);尚未采集 / 代理端不提供时为 null。
     */
    java.util.List<PlayerPing> playerPings;
    /**
     * 各玩家当前路由(所在子服)明细(FR2.5);尚未采集时为 null。
     */
    java.util.List<PlayerRoute> playerRoutes;
}
