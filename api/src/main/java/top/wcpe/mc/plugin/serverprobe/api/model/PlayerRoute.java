package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 代理端视角下单个玩家的路由(FR2.5)。
 *
 * 描述某玩家当前被路由到哪个后端子服(代理本地视角)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class PlayerRoute {
    /** 玩家名(代理本地视角)。 */
    String name;
    /** 该玩家当前所在的子服名(代理配置中的 server 名)。 */
    String server;
}
