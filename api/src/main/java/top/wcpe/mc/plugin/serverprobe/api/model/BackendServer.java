package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 代理端视角下的单个后端子服。
 *
 * 描述代理本地已知的子服在线信息(M1,A 方案):名称 + 在线人数;
 * M2+ 补充子服 ping/可达性(RTT 延迟)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class BackendServer {
    /** 子服名称(代理配置中的 server 名)。 */
    String name;
    /** 该子服当前在线人数(代理本地视角)。 */
    int online;
    /**
     * 该子服最近一次 ping 的 RTT 延迟(毫秒,FR2.5);-1 表示尚未测到 / 不可用。
     * 由代理端采集器的后台周期 ping 任务维护(异步,不阻塞主线程)。
     */
    int pingMs;
    /**
     * 该子服是否可达(最近一次 ping 成功,FR2.5);尚未测到时为 false。
     */
    boolean reachable;
}
