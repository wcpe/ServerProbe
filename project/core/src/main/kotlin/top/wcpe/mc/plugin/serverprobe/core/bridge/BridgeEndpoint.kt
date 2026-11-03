package top.wcpe.mc.plugin.serverprobe.core.bridge

import java.net.URI

/**
 * 桥连接端点的日志脱敏工具。
 *
 * 凭证只以握手 query 参数形式存在（`?token=...`），一旦整串 URL 进日志就是泄漏。本工具把任意
 * 端点/URL 收敛为可记录的 `host:port`，供 [BridgeClient] 的启动日志统一使用。
 *
 * 独立成对象而非留在 [BridgeClient] 内，是为让"脱敏规则"这一安全关注点集中可审，
 * 且桥类的方法数（detekt 门禁）不因辅助函数膨胀。
 */
internal object BridgeEndpoint {

    /**
     * 提取仅 `host:port` 的可记日志部分（去掉 path/query，杜绝 token 入日志）。
     *
     * @param url 端点或完整 WS URL（可能带 query）。
     * @return `host:port`；解析失败返回固定占位文案。
     */
    fun redact(url: String): String = runCatching {
        val parsed = URI(url)
        "${parsed.host}:${if (parsed.port > 0) parsed.port else DEFAULT_PORT}"
    }.getOrDefault(UNRESOLVED)

    /** 未显式声明端口时的默认端口（仅用于日志展示）。 */
    private const val DEFAULT_PORT = 80

    /** 解析失败时的占位文案。 */
    private const val UNRESOLVED = "(地址解析失败)"
}
