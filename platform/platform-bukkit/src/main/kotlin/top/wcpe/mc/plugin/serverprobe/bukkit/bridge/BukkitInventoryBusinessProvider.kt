package top.wcpe.mc.plugin.serverprobe.bukkit.bridge

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.mcp.InventorySummary
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerDiagnosticsProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlayerLookupRequest
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.util.UUID

/**
 * 原版背包业务域（`inventory`）。
 *
 * ## 为什么这里可以内置，而 ADR-0030 禁止的是另一回事
 *
 * ADR-0030 删除的是「直接 compileOnly 依赖第三方业务插件 API、把金额/货币/物品/NBT
 * 硬编码进探针」的内置集成。原版背包不属于第三方业务语义——它是 Bukkit 的
 * `Player.inventory`，是平台自身的数据，不引入任何第三方 API 类型或私有载荷格式。
 *
 * 因此本类是**平台能力的对外出口**，不是第三方业务集成。
 *
 * ## 不重复实现读取
 *
 * 读取逻辑已由 [PlayerDiagnosticsProvider]（`BukkitPlayerDiagnosticsProvider`）完成：
 * 它按 tick 在主线程刷新缓存，含 `inventory` / `enderChest` / `equipment`。
 * 本类只做「域注册 + 入参解析 + 结果序列化」，不碰 Bukkit API，也不直接读背包。
 *
 * ## 隐私边界
 *
 * 只返回 Material 名称、数量、槽位，**不含 NBT**（与 MCP `player_lookup` 同口径）。
 * 不做落盘、不做日志转储。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitInventoryBusinessProvider : BusinessProvider {

    /** 域注册入口。 */
    @Inject
    lateinit var businessHost: BusinessHost

    /** 已有的玩家诊断提供者：它已经算好了背包摘要。 */
    @Inject(required = false)
    var diagnostics: PlayerDiagnosticsProvider? = null

    /** 依赖注入完成后做平台门并自注册。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        if (!ProbeConfig.bridgeEnabled()) return
        if (diagnostics == null) {
            // 没有平台实现时注册一个「恒不可用」的域没有意义，直接不注册，
            // 让下游收到「业务域不可用」而不是收到一个永远返回空的域。
            ProbeLogger.warn("原版背包域未注册：玩家诊断提供者缺失（非 Bukkit 端或平台模块未加载）")
            return
        }
        runCatching { businessHost.register(this) }
            .onSuccess { ProbeLogger.info("原版背包域已注册:domain=$DOMAIN") }
            .onFailure { ProbeLogger.error("原版背包域注册失败", it) }
    }

    override fun domain(): String = DOMAIN

    override fun manifest(): DomainManifest = DomainManifest.of(
        DOMAIN,
        "原版背包",
        "读取玩家原版背包、末影箱与装备栏（只读，不含 NBT）",
        listOf(
            ActionSpec.read(ACTION_DETAIL, "按玩家名或 UUID 查询背包摘要；需玩家在线"),
        ),
        mapOf("source" to "bukkit-vanilla"),
    )

    override fun dispatch(request: ActionRequest): ActionResult {
        val player = parsePlayer(request.payloadJson)
            ?: return ActionResult.fail("INVALID_PAYLOAD", "payload 需要 name 或 uuid")

        val provider = diagnostics ?: return ActionResult.fail("UNAVAILABLE", "玩家诊断提供者不可用")
        val result = provider.lookup(player)
            ?: return ActionResult.fail("UNAVAILABLE", "玩家诊断暂无结果")

        if (!result.online) {
            // 离线玩家没有原版背包可读：这不是错误，是事实
            return ActionResult.ok(
                buildJson(result.name, result.uuid, InventoryViews.ABSENT, false, result.reason),
            )
        }
        return ActionResult.ok(
            buildJson(
                result.name,
                result.uuid,
                InventoryViews(result.inventory, result.enderChest, result.equipment),
                true,
                null,
            ),
        )
    }

    /** 健康检查：有 provider 即视作健康（真正的可用性由 dispatch 的 UNAVAILABLE 反映）。 */
    override fun health() = ProviderHealth.available()

    /** 从 payload JSON 里取玩家标识；只认 name / uuid 两个字段。 */
    internal fun parsePlayer(payloadJson: String): PlayerLookupRequest? {
        if (payloadJson.isBlank()) return null
        val name = extractString(payloadJson, "name")
        val uuidRaw = extractString(payloadJson, "uuid")
        val uuid = uuidRaw?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        if (name == null && uuid == null) return null
        return PlayerLookupRequest(name, uuid)
    }

    /**
     * 极简 JSON 取值。
     *
     * 不引 JSON 库：探针侧对第三方依赖有硬约束（见 ADR-0030 的依赖方向要求），
     * 而这里只需要从扁平的 `{"name":"x"}` 里取两个字符串字段。
     * 取不到就返回 null，让上层按「参数缺失」处理，而不是猜。
     */
    private fun extractString(json: String, field: String): String? {
        val marker = "\"$field\""
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + marker.length)
        if (colon < 0) return null
        val quoteStart = json.indexOf('"', colon + 1)
        if (quoteStart < 0) return null
        // 跳过转义引号：`"a\"b"` 的结尾引号是第 2 个，不是第 1 个。
        // 直接取 `indexOf('"')` 会把值截成 `a\`，而该值随后会当玩家名去查——查不到却看不出原因。
        //
        // 递归解析而不是 while+break/continue：后者会触发 detekt 的
        // LoopWithTooManyJumpStatements。递归把“跳过下一个字符”表达成参数，天然没有跳转。
        val end = closingQuoteIndex(json, quoteStart + 1)
        if (end < 0) return null
        val value = unescapeJson(json.substring(quoteStart + 1, end))
        return value.ifBlank { null }

    }

    /**
     * 三份原版背包视图。
     *
     * 收成一个参数而不是平铺三个，是为了让 [buildJson] 的参数个数落在 detekt 阈值内；
     * 更重要的是这三份视图在语义上本就是一组——离线玩家三份同时不可得，不存在「只有
     * inventory 而没有 equipment」的合法状态。打包成一个类型后，这种同生共死的约束
     * 由类型表达，而不是靠调用方记得同时传三个 null。
     */
    private data class InventoryViews(
        val inventory: List<InventorySummary>?,
        val enderChest: List<InventorySummary>?,
        val equipment: List<InventorySummary>?,
    ) {
        companion object {
            /** 离线玩家：三份视图都不可得。 */
            val ABSENT = InventoryViews(null, null, null)
        }
    }

    /** 拼结果 JSON（手工拼装，理由同 [extractString]）。 */
    private fun buildJson(
        name: String,
        uuid: String,
        views: InventoryViews,
        online: Boolean,
        reason: String?,
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"name\":\"").append(escape(name)).append("\",")
        sb.append("\"uuid\":\"").append(escape(uuid)).append("\",")
        sb.append("\"online\":").append(online).append(",")
        sb.append("\"source\":\"vanilla\",")
        if (reason != null) sb.append("\"reason\":\"").append(escape(reason)).append("\",")
        sb.append("\"inventory\":").append(itemsJson(views.inventory)).append(",")
        sb.append("\"enderChest\":").append(itemsJson(views.enderChest)).append(",")
        sb.append("\"equipment\":").append(itemsJson(views.equipment))
        sb.append("}")
        return sb.toString()
    }

    /**
     * 物品列表序列化：只输出 material/amount/slot。
     *
     * 直接读 [InventorySummary] 的强类型字段，不用反射：
     * 反射会在字段改名时静默产出空串，而编译器不会提醒。
     */
    private fun itemsJson(items: List<InventorySummary>?): String {
        if (items.isNullOrEmpty()) return "[]"
        return items.joinToString(",", "[", "]") {
            "{\"material\":\"${escape(it.material)}\",\"amount\":${it.amount},\"slot\":${it.slot}}"
        }
    }

    /** JSON 字符串转义：只处理必要字符。 */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        /** 业务域名；须匹配 `^[a-z0-9-]{1,32}$`。 */
        const val DOMAIN = "inventory"

        /** 查询动作名。 */
        const val ACTION_DETAIL = "detail"
    }

    /**
     * 从 [from] 起找到真正结束字符串的引号下标；找不到返回 -1。
     *
     * 转义符 `\` 后面那个字符一定不是结束引号，所以遇到它直接跳过两位。
     * 用递归表达“跳过”而不是 `while` + `continue`，避免循环里出现多个跳转。
     */
    private tailrec fun closingQuoteIndex(json: String, from: Int): Int {
        if (from >= json.length) return -1
        return when (json[from]) {
            '\\' -> closingQuoteIndex(json, from + 2)
            '"' -> from
            else -> closingQuoteIndex(json, from + 1)
        }
    }

    /** 把 JSON 字符串字面量内部的转义还原成原字符。 */
    private tailrec fun unescapeJson(raw: String, index: Int = 0, out: StringBuilder = StringBuilder()): String =
        if (index >= raw.length) {
            out.toString()
        } else if (raw[index] == '\\' && index + 1 < raw.length) {
            out.append(raw[index + 1])
            unescapeJson(raw, index + 2, out)
        } else {
            out.append(raw[index])
            unescapeJson(raw, index + 1, out)
        }
}
