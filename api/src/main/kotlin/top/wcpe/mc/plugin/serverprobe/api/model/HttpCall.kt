package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 一次对外网络外呼记录(M5,启动 agent 增强数据)。
 *
 * 由启动 agent 在 `HttpURLConnection.getInputStream`(完整 HTTP/HTTPS 语义)与 `java.net.Socket.connect`
 * (原始 TCP,[method] = "TCP")处插桩捕获;仅当挂载启动 agent(`-javaagent:plugins/ServerProbe.jar`)且
 * 外呼监控开启时才有此数据。用于回答"哪个插件/哪段代码发起了对外请求、耗时多少、目标与参数为何"。
 *
 * **安全**:[url] 的查询串与 [headers] 中的敏感项(Authorization/Cookie/token 等)已由 agent 侧打码为 `***`;
 * 请求体不在捕获范围(发起时已流式写出,无法事后读取)。
 *
 * @property seq 单调递增序号(增量拉取游标)。
 * @property startRelNanos 发起时刻(相对 premain 的纳秒偏移)。
 * @property durationMs 本次外呼耗时(毫秒);失败时为至失败的耗时。
 * @property method 请求方法(GET/POST/...;原始 TCP 连接为 "TCP")。
 * @property responseCode HTTP 响应码;未知/原始 TCP 为 -1。
 * @property error 是否失败(如连接异常、读响应码失败)。
 * @property host 目标主机。
 * @property url 目标 URL(已脱敏 + 截断);原始 TCP 为 `host:port`。
 * @property headers 请求头(已脱敏,每项 `name=value`);原始 TCP 为空。
 * @property callerFrames 发起处调用栈(`类全名#方法名`,栈顶在前),用于归因与展示触发处。
 * @property loaderHashes 调用栈各类的 ClassLoader 身份哈希(栈顶在前,去重);用于按 ClassLoader 归因到拥有该库/类的插件
 *  (即便插件代码不在栈上,如连接池/驱动后台线程发起连接)。
 * @property plugin 归因到的插件名(由展示侧据 [loaderHashes] 优先、[callerFrames] 兜底解析);无法归因时为空串。
 */
data class HttpCall(
    val seq: Long,
    val startRelNanos: Long,
    val durationMs: Long,
    val method: String,
    val responseCode: Int,
    val error: Boolean,
    val host: String,
    val url: String,
    val headers: List<String>,
    val callerFrames: List<String>,
    val loaderHashes: List<Int> = emptyList(),
    val plugin: String = ""
)
