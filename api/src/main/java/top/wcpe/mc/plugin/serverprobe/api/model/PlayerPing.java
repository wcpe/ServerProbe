package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 代理端视角下单个玩家的网络延迟(FR2.5)。
 *
 * 描述代理本地记录的某玩家到其所在子服的回程延迟(RTT,毫秒)。
 * pingMs 为 -1 表示当前不可用(代理端未提供该值)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class PlayerPing {
    /** 玩家名(代理本地视角)。 */
    String name;
    /** 该玩家最近一次 ping 的 RTT(毫秒);-1 = 不可用/N/A。 */
    int pingMs;
}
