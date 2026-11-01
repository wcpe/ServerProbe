package top.wcpe.mc.plugin.serverprobe.core.bridge

import java.net.URI
import java.net.URLEncoder

/**
 * 桥端点地址的纯函数工具（从 `BridgeClient` 抽出来，降低单类体量）。
 *
 * 这里全部是无状态的字符串/URI 运算，不碰连接与状态，便于单测与复用：
 * - [buildTargetUri]：拼反向 WS 连接地址（手工切 authority，绕开 `URI.host` 对 `ws` 白名单的限制）；
 * - [safeAuthority]：仅取 `host:port` 用于日志，**query/path 一律丢弃**（query 里带 token）；
 * - [splitAuthority]：authority → (host, port)，兼容 IPv6 字面量；
 * - [encode]：RFC 3986 的 query 安全子集编码。
 */
internal object BridgeEndpointUris {

    /**
     * 拼反向 WS 连接地址：`scheme://host:port/path?token=…&instance=…`。
     *
     * 【为什么手工切】`java.net.URI` 的 `host` 只对「服务器型」scheme（http/https/ftp…）填充，
     * `ws`/`wss` 不在其列，直接构造多参 URI 会报 `URISyntaxException`。
     *
     * @param url 原始地址（可能带 query/path）。
     * @param token 凭据（会做 URL 编码）。
     * @param instance 实例标识（会做 URL 编码）。
     * @return 可直接交给 WS 客户端的 URI。
     */
    fun buildTargetUri(url: String, token: String, instance: String): URI {
        val trimmed = url.trim()
        val scheme = when {
            trimmed.contains("://") -> trimmed.substringBefore("://")
            else -> ""
        }
        if (scheme.isEmpty()) return URI(url)

        val afterScheme = trimmed.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val pathRaw = afterScheme.substring(authority.length).substringBefore('?').substringBefore('#')
        val path = if (pathRaw.isEmpty()) "" else pathRaw

        val (host, port) = splitAuthority(authority)
        val query = "token=${encode(token)}&instance=${encode(instance)}"
        return URI(scheme, null, host, port, path, query, null)
    }

    /**
     * 从 ws URL 提取仅 host:port 的可记日志部分（去掉 query，避免 token 入日志）。
     *
     * @param url 原始地址。
     * @return `host:port`（无端口补 80）；无法解析时返回可读占位符。
     */
    fun safeAuthority(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "(未配置)"
        val afterScheme = when {
            trimmed.contains("://") -> trimmed.substringAfter("://")
            trimmed.startsWith("//") -> trimmed.removePrefix("//")
            else -> trimmed
        }
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return "(地址解析失败)"
        return when {
            authority.startsWith("[") -> {
                val close = authority.indexOf(']')
                if (close < 0) {
                    "(地址解析失败)"
                } else {
                    val hostPart = authority.substring(0, close + 1)
                    val portPart = authority.substring(close + 1).removePrefix(":")
                    if (portPart.isEmpty()) "$hostPart:80" else "$hostPart:$portPart"
                }
            }
            authority.count { it == ':' } == 1 -> {
                val host = authority.substringBefore(':')
                val port = authority.substringAfter(':')
                if (port.isEmpty()) "$host:80" else "$host:$port"
            }
            authority.contains(':') -> authority
            else -> "$authority:80"
        }
    }

    /** 把 authority 拆成 host + port；无端口返回 -1。IPv6 字面量形如 `[::1]:9102`。 */
    private fun splitAuthority(authority: String): Pair<String, Int> {
        if (authority.isEmpty()) return "" to -1
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return authority to -1
            val host = authority.substring(0, close + 1)
            val portPart = authority.substring(close + 1).removePrefix(":")
            return host to (portPart.toIntOrNull() ?: -1)
        }
        val colons = authority.count { it == ':' }
        return when {
            colons == 1 -> {
                val host = authority.substringBefore(':')
                val port = authority.substringAfter(':').toIntOrNull() ?: -1
                host to port
            }
            colons == 0 -> authority to -1
            else -> authority to -1
        }
    }

    /** URL 编码（RFC 3986 的 query 安全子集）。 */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
