package top.wcpe.mc.plugin.serverprobe.core.web

import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import java.net.URLDecoder
import java.net.URLEncoder

/** Web 取证查询的受限参数模型，保证页面始终通过有界 FR8 查询读取数据。 */
data class NetworkForensicsWebQuery(
    val sinceMs: Long,
    val untilMs: Long,
    val direction: PacketDirection? = null,
    val packetType: String? = null,
    val playerUuid: String? = null,
    val playerName: String? = null,
    val ip: String? = null,
    val cursorCapturedAtMs: Long? = null,
    val cursorId: Long? = null,
    val limit: Int = DEFAULT_PAGE_SIZE,
) {

    init {
        require(sinceMs <= untilMs) { "时间范围无效" }
        require(limit in MIN_PAGE_SIZE..MAX_PAGE_SIZE) { "单页条数无效" }
        require((cursorCapturedAtMs == null) == (cursorId == null)) { "游标不完整" }
    }

    /** 转换为既有 FR8 只读查询对象。 */
    fun toApiQuery(): NetworkPacketQuery = NetworkPacketQuery.builder()
        .sinceMs(sinceMs)
        .untilMs(untilMs)
        .direction(direction)
        .packetType(packetType)
        .playerUuid(playerUuid)
        .playerName(playerName)
        .ip(ip)
        .cursorCapturedAtMs(cursorCapturedAtMs)
        .cursorId(cursorId)
        .limit(limit)
        .build()

    /** 生成下一页链接使用的原始查询串，不混入任何敏感之外的额外状态。 */
    fun nextPageQuery(nextCapturedAtMs: Long, nextId: Long): String = copy(
        cursorCapturedAtMs = nextCapturedAtMs,
        cursorId = nextId,
    ).toQueryString()

    /** 生成表单和分页链接共用的 URL 编码查询串。 */
    fun toQueryString(): String = buildList {
        add("sinceMs" to sinceMs.toString())
        add("untilMs" to untilMs.toString())
        direction?.let { add("direction" to it.name) }
        packetType?.let { add("packetType" to it) }
        playerUuid?.let { add("playerUuid" to it) }
        playerName?.let { add("playerName" to it) }
        ip?.let { add("ip" to it) }
        cursorCapturedAtMs?.let { add("cursorCapturedAtMs" to it.toString()) }
        cursorId?.let { add("cursorId" to it.toString()) }
        add("limit" to limit.toString())
    }.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 100

        /** 可选参数带值解析：值存在但非法时抛出，缺失时返回 null。 */
        private fun <T> optionalParsed(params: Map<String, String>, key: String, message: String, transform: (String) -> T?): T? =
            optional(params, key)?.let { value -> transform(value) ?: throw IllegalArgumentException(message) }

        /** 从原始 URL 查询串解析参数；缺失时间范围或非法游标立即拒绝。 */
        fun parse(rawQuery: String?): NetworkForensicsWebQuery {
            val params = parseParameters(rawQuery)
            return NetworkForensicsWebQuery(
                sinceMs = requiredLong(params, "sinceMs"),
                untilMs = requiredLong(params, "untilMs"),
                direction = optional(params, "direction")?.let(PacketDirection::valueOf),
                packetType = optional(params, "packetType"),
                playerUuid = optional(params, "playerUuid"),
                playerName = optional(params, "playerName"),
                ip = optional(params, "ip"),
                cursorCapturedAtMs = optionalParsed(params, "cursorCapturedAtMs", "游标时间无效", String::toLongOrNull),
                cursorId = optionalParsed(params, "cursorId", "游标标识无效", String::toLongOrNull),
                limit = optionalParsed(params, "limit", "单页条数无效", String::toIntOrNull) ?: DEFAULT_PAGE_SIZE,
            )
        }

        /** 解析并拒绝重复参数，避免同名过滤器产生歧义。 */
        private fun parseParameters(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            return rawQuery.split('&').associate { segment ->
                val index = segment.indexOf('=')
                require(index >= 0) { "查询参数无效" }
                decode(segment.substring(0, index)) to decode(segment.substring(index + 1))
            }.also { params ->
                require(params.size == rawQuery.split('&').size) { "查询参数重复" }
            }
        }

        /** 读取非空可选字符串。 */
        private fun optional(params: Map<String, String>, key: String): String? = params[key]?.takeIf { it.isNotBlank() }

        /** 读取必填长整型时间。 */
        private fun requiredLong(params: Map<String, String>, key: String): Long =
            optional(params, key)?.toLongOrNull() ?: throw IllegalArgumentException("缺少或无效的 $key")

        /** URL 解码失败统一视为参数无效。 */
        private fun decode(value: String): String = runCatching { URLDecoder.decode(value, UTF_8) }
            .getOrElse { throw IllegalArgumentException("查询参数编码无效") }

        /** URL 编码供分页链接安全复用。 */
        private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)

        private const val UTF_8 = "UTF-8"
    }
}
