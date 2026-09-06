package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.UUID

/** 玩家查询请求：name 与 uuid 至少一个；uuid 必须为合法 UUID 格式。 */
data class PlayerLookupRequest(
    val name: String? = null,
    val uuid: UUID? = null,
) {
    init {
        require(name != null || uuid != null) { "玩家查询至少需要 name 或 uuid" }
    }
}

/** 玩家最近事件条目（时间线，有界）。 */
data class PlayerActivityEvent(
    val type: String,
    val atMillis: Long,
    val detail: String? = null,
)

/** 背包摘要（不含 NBT 等敏感细节）。 */
data class InventorySummary(
    val material: String,
    val amount: Int,
    val slot: Int,
)

/** 在线玩家诊断明细（隐私敏感，仅经 MCP 单次返回，不落盘/不进监控）。 */
data class PlayerLookupResult(
    val name: String,
    val uuid: String,
    val online: Boolean,
    val world: String? = null,
    val location: Map<String, Double>? = null,
    val gameMode: String? = null,
    val health: Double? = null,
    val foodLevel: Int? = null,
    val saturation: Double? = null,
    val xpLevel: Int? = null,
    val inventory: List<InventorySummary>? = null,
    val enderChest: List<InventorySummary>? = null,
    val equipment: List<InventorySummary>? = null,
    val chunk: Map<String, Int>? = null,
    val recentActivity: List<PlayerActivityEvent> = emptyList(),
    val reason: String? = null,
)

/** 玩家诊断提供者契约（FR-20）：平台模块实现并注册；代理端不注册，工具返回平台不支持。 */
interface PlayerDiagnosticsProvider {
    fun lookup(request: PlayerLookupRequest): PlayerLookupResult?
}

/** 玩家诊断工具：经注入的 provider 查询，provider 缺失返回平台不支持；隐私边界见 spec。 */
@Service
class PlayerDiagnosticsToolProvider : McpToolProvider {

    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    @Inject(required = false)
    var provider: PlayerDiagnosticsProvider? = null

    @PostConstruct
    fun register() {
        mcpToolProviderRegistry.register(this)
    }

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = when (name) {
        PLAYER_LOOKUP -> playerLookup(arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    private fun playerLookup(arguments: JsonObject?): Map<String, Any?> {
        val name = arguments?.getString("name")?.trim()?.takeIf(String::isNotBlank)
        val uuidRaw = arguments?.getString("uuid")?.trim()?.takeIf(String::isNotBlank)
        // 前置格式校验（标准 36 字符含连字符），再严格解析；避免 UUID.fromString 对非标准格式的宽容解析
        val uuid = uuidRaw?.let { raw ->
            require(UUID_PATTERN.matches(raw)) { "uuid 必须是合法 UUID 格式" }
            UUID.fromString(raw)
        }
        if (name == null && uuid == null) require(false) { "玩家查询至少需要 name 或 uuid" }
        val request = PlayerLookupRequest(name, uuid)
        val result = provider?.lookup(request)
            ?: return linkedMapOf("available" to false, "reason" to "平台不支持在线玩家明细")
        return linkedMapOf(
            "name" to result.name,
            "uuid" to result.uuid,
            "online" to result.online,
            "world" to result.world,
            "location" to result.location,
            "gameMode" to result.gameMode,
            "health" to result.health,
            "foodLevel" to result.foodLevel,
            "saturation" to result.saturation,
            "xpLevel" to result.xpLevel,
            "inventory" to result.inventory,
            "enderChest" to result.enderChest,
            "equipment" to result.equipment,
            "chunk" to result.chunk,
            "recentActivity" to result.recentActivity,
            "reason" to result.reason,
        )
    }

    private companion object {
        const val PLAYER_LOOKUP = "player_lookup"
        /** 标准 UUID 格式：8-4-4-4-12 十六进制含连字符。 */
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val TOOLS = listOf(
            McpTool(PLAYER_LOOKUP, "按玩家名或 UUID 查询在线玩家诊断明细（位置/状态/背包摘要/区块/最近事件；隐私数据仅单次返回不落盘）",
                mapOf(
                    "name" to mapOf("type" to "string", "description" to "玩家名（精确匹配在线玩家）"),
                    "uuid" to mapOf("type" to "string", "description" to "玩家 UUID（合法 UUID 格式）"),
                ),
                usageExample = "{\"name\":\"<玩家名>\"}",
                workflow = "同步调用；离线返回 online=false，平台不支持返回结构化降级",
                outputFields = mapOf(
                    "online" to "是否在线", "world" to "所在世界", "location" to "坐标",
                    "health" to "血量", "inventory" to "背包摘要（无 NBT）", "recentActivity" to "最近事件时间线",
                )),
        )
    }
}
