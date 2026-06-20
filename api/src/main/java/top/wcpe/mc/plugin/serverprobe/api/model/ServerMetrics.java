package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 服务器运行时指标快照(FR2.2,Bukkit 端)。
 *
 * 聚合 tick 采样与基础在线/运行时长信息。仅 Bukkit 系服务端具备完整语义;
 * 代理端无此概念(详见 MetricSnapshot.server)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class ServerMetrics {
    /** tick 采样数据(TPS/MSPT)。 */
    TickSample tick;
    /** 当前在线人数。 */
    int onlinePlayers;
    /** 最大可容纳人数。 */
    int maxPlayers;
    /** 服务器运行时长(毫秒)。 */
    long uptimeMs;
    /**
     * 各世界运行时指标(FR2.3);默认 null 以保持 gson/Configuration 序列化向后兼容
     * (历史快照不含该字段),尚未采集到时亦为 null。
     */
    java.util.List<WorldMetrics> worlds;
}
