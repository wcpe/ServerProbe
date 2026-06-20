package top.wcpe.mc.plugin.serverprobe.api.enums;

/**
 * 探针运行平台。
 *
 * 用于标识一份指标快照或启动画像来自哪种平台,以便呈现层与外部消费方区分语义
 * (例如代理端无世界/TPS 概念)。
 *
 * - {@link #BUKKIT}:Bukkit 系服务端(CraftBukkit/Spigot/Paper/Folia 等);Folia 视为 Bukkit 的运行期变体,非独立平台。
 * - {@link #BUNGEE}:BungeeCord 代理端;仅采集网络拓扑类数据,无世界/TPS/MSPT。
 * - {@link #VELOCITY}:Velocity 代理端;**当前阶段仅预留**,架构已抽象,后续低成本接入。
 */
public enum ProbePlatform {
    /** Bukkit 系服务端(含 Folia 变体)。 */
    BUKKIT,

    /** BungeeCord 代理端。 */
    BUNGEE,

    /** Velocity 代理端(预留,尚未启用)。 */
    VELOCITY
}
