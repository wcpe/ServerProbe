package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 代理端视角下的单个后端子服。
 *
 * 仅描述代理本地已知的子服在线信息(M1,A 方案):名称 + 在线人数。
 *
 * 注:子服 ping/可达性(在线状态、RTT 延迟)留 M2 补齐。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class BackendServer {
    /** 子服名称(代理配置中的 server 名)。 */
    String name;
    /** 该子服当前在线人数(代理本地视角)。 */
    int online;
}
