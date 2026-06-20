package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 一次对外网络外呼记录(M5,启动 agent 增强数据)。
 *
 * 由启动 agent 在 {@code HttpURLConnection.getInputStream}(完整 HTTP/HTTPS 语义)与 {@code java.net.Socket.connect}
 * (原始 TCP,{@code method} = "TCP")处插桩捕获;仅当挂载启动 agent({@code -javaagent:plugins/ServerProbe.jar})且
 * 外呼监控开启时才有此数据。用于回答"哪个插件/哪段代码发起了对外请求、耗时多少、目标与参数为何"。
 *
 * **安全**:{@code url} 的查询串与 {@code headers} 中的敏感项(Authorization/Cookie/token 等)已由 agent 侧打码为 {@code ***};
 * 请求体不在捕获范围(发起时已流式写出,无法事后读取)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class HttpCall {
    /** 单调递增序号(增量拉取游标)。 */
    long seq;
    /** 发起时刻(相对 premain 的纳秒偏移)。 */
    long startRelNanos;
    /** 本次外呼耗时(毫秒);失败时为至失败的耗时。 */
    long durationMs;
    /** 请求方法(GET/POST/...;原始 TCP 连接为 "TCP")。 */
    String method;
    /** HTTP 响应码;未知/原始 TCP 为 -1。 */
    int responseCode;
    /** 是否失败(如连接异常、读响应码失败)。 */
    Boolean error;
    /** 目标主机。 */
    String host;
    /** 目标 URL(已脱敏 + 截断);原始 TCP 为 {@code host:port}。 */
    String url;
    /** 请求头(已脱敏,每项 {@code name=value});原始 TCP 为空。 */
    java.util.List<String> headers;
    /** 发起处调用栈({@code 类全名#方法名},栈顶在前),用于归因与展示触发处。 */
    java.util.List<String> callerFrames;
    /**
     * 调用栈各类的 ClassLoader 身份哈希(栈顶在前,去重);用于按 ClassLoader 归因到拥有该库/类的插件
     * (即便插件代码不在栈上,如连接池/驱动后台线程发起连接)。
     */
    @lombok.Builder.Default
    java.util.List<Integer> loaderHashes = java.util.Collections.emptyList();
    /** 归因到的插件名(由展示侧据 {@code loaderHashes} 优先、{@code callerFrames} 兜底解析);无法归因时为空串。 */
    @lombok.Builder.Default
    String plugin = "";
}
