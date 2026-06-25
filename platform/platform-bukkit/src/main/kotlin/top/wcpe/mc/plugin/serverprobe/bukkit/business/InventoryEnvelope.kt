package top.wcpe.mc.plugin.serverprobe.bukkit.business

import top.wcpe.mc.plugin.allininventorysync.api.model.BasicAttrsDto
import top.wcpe.mc.plugin.allininventorysync.api.model.InventoryViewDto
import top.wcpe.mc.plugin.allininventorysync.api.model.ItemDto
import top.wcpe.mc.plugin.allininventorysync.api.model.WriteResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult

/**
 * 背包域信封编解码与校验(JBIS FR-125,见 ServerProbe ADR-0016 / JianManager FR-125)。
 *
 * [InventoryProvider] 的纯逻辑伴随体:动作常量、manifest、payload 校验、物品 / 基础属性解码、视图 / 回执编码。
 * 抽出本对象的目的:① 让 [InventoryProvider] 函数数 / 复杂度落在 detekt 阈值内;② 纯逻辑可脱离 Bukkit 运行期单测。
 * 仅引 AllinInventorySync 的对外中性 DTO([ItemDto] / [InventoryViewDto] / [WriteResult] / [BasicAttrsDto],均 `api.model`)
 * 与 core 的 [BridgeCommandResult];不碰 Bukkit API、不调真实服务,故可独立测试。
 *
 * ## 物品传输契约(ADR-0016)
 * - **读富、写以 nbtBase64 为准、读写不对称**:`view` 编码出物品的全部 UI 便利字段(material/amount/displayName/
 *   lore/enchantments)供平台渲染;写只需 **slot + nbtBase64**(后者是 [ItemDto] 全保真往返真源,UI 字段 AllinInventorySync
 *   写门面一概忽略)。
 * - **base/edited 编码为 `List<String>`**:每个元素是一件物品的 JSON 对象串(`{"slot":N,"nbtBase64":"…",…}`)。
 *   探针 JSON 门面([top.wcpe.mc.plugin.serverprobe.core.json.JsonObject])只暴露 `getStringList` 取字符串列表,
 *   无数组对象迭代 / 动态键迭代能力;故用「字符串列表 + 逐串再解析」承载结构化物品集(零 core 改动、保 ADR-14 门面)。
 * - **金额无关**:背包无金额,无经济域的字符串化金额约束。
 */
object InventoryEnvelope {

    /** 背包业务域名,与 Worker 下发的 domain 对应。 */
    const val DOMAIN = "inventory"

    /** JianManager 作为写操作的默认操作者名(payload 未带 operator 时回退,写进 AllinInventorySync 审计)。 */
    const val DEFAULT_OPERATOR = "JianManager"

    const val ACTION_VIEW = "view"
    const val ACTION_WRITE_INVENTORY = "writeInventory"
    const val ACTION_WRITE_ENDER_CHEST = "writeEnderChest"
    const val ACTION_WRITE_BASIC_ATTRS = "writeBasicAttrs"

    // ======================== manifest ========================

    /** 背包域能力清单:只读 `view` + 三个写动作(背包 / 末影箱 / 基础属性)。 */
    fun manifest(): Map<String, Any?> = mapOf(
        "actions" to listOf(
            action(
                ACTION_VIEW, listOf("player"), readOnly = true,
                note = "player 为玩家 UUID;回源含离线,玩家无数据返 exists=false"
            ),
            action(
                ACTION_WRITE_INVENTORY, listOf("player", "base", "edited", "taskId"), readOnly = false,
                note = "player 为 UUID;base/edited 为物品 JSON 串列表(各 {slot,nbtBase64,…});taskId 幂等键(CP 生成);operator 由 CP 注入"
            ),
            action(
                ACTION_WRITE_ENDER_CHEST, listOf("player", "base", "edited", "taskId"), readOnly = false,
                note = "末影箱;base/edited 同 writeInventory(物品 JSON 串列表)"
            ),
            action(
                ACTION_WRITE_BASIC_ATTRS, listOf("player", "base", "edited", "taskId"), readOnly = false,
                note = "base/edited 为属性对象 {health,foodLevel,xpLevel,xpProgress,xpTotal,gameMode};只施加 base→edited 净改动"
            ),
        )
    )

    /** manifest 单条动作描述;note 非空时附带。 */
    fun action(name: String, args: List<String>, readOnly: Boolean, note: String? = null): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>("action" to name, "args" to args, "readOnly" to readOnly)
        if (note != null) entry["note"] = note
        return entry
    }

    // ======================== 校验 / 工具 ========================

    /** 校验只读动作:player(UUID)非空。通过返回 null,否则失败结果。 */
    fun requireReadCommon(player: String): BridgeCommandResult? =
        if (player.isBlank()) missingPlayer() else null

    /** 校验写动作:player(UUID)非空、taskId 非空。通过返回 null,否则失败结果。 */
    fun requireWriteCommon(player: String, taskId: String): BridgeCommandResult? = when {
        player.isBlank() -> missingPlayer()
        taskId.isBlank() -> missingTaskId()
        else -> null
    }

    /** 操作者:payload 的 operator,空白回退 [DEFAULT_OPERATOR](写门面要求非空操作者)。 */
    fun operatorOf(raw: String): String = raw.ifBlank { DEFAULT_OPERATOR }

    /**
     * 由解码出的字段构造一件 [ItemDto] 槽位条目(写入用)。
     *
     * nbtBase64 是全保真往返真源、写入唯一可信字段;material/amount/displayName 为 UI 便利字段(写门面忽略,仅留痕)。
     * lore / enchantments 写入不需要(且探针 JSON 门面无动态键迭代),一律置 null。
     *
     * @return `slot → ItemDto`;nbtBase64 空白(无效条目)返回 null,由调用方跳过。
     */
    fun itemFromParts(slot: Int, nbtBase64: String, material: String, amount: Int, displayName: String?):
        Pair<Int, ItemDto>? {
        if (nbtBase64.isBlank()) return null
        return slot to ItemDto(
            material = material,
            amount = amount,
            displayName = displayName?.ifBlank { null },
            lore = null,
            enchantments = null,
            nbtBase64 = nbtBase64,
        )
    }

    /** 由解码出的字段构造 [BasicAttrsDto](基础属性写入用)。 */
    @Suppress("LongParameterList")
    fun basicAttrs(
        health: Double,
        foodLevel: Int,
        xpLevel: Int,
        xpProgress: Float,
        xpTotal: Int,
        gameMode: String,
    ): BasicAttrsDto = BasicAttrsDto(health, foodLevel, xpLevel, xpProgress, xpTotal, gameMode)

    fun notReady(): BridgeCommandResult = BridgeCommandResult.fail("背包插件(AllinInventorySync)未就绪")

    fun missingPlayer(): BridgeCommandResult = BridgeCommandResult.fail("缺少 player(玩家 UUID)")

    fun missingTaskId(): BridgeCommandResult =
        BridgeCommandResult.fail("缺少幂等键 taskId(写操作必填,重试须复用同键防重发刷物品)")

    fun executeError(action: String, t: Throwable): BridgeCommandResult =
        BridgeCommandResult.fail("$action 执行异常:${t.message}")

    fun writeTimeout(action: String): BridgeCommandResult =
        BridgeCommandResult.fail("$action 写入超时(落盘回执未在限期内返回)")

    // ======================== 编码(信封,纯 Map 结构便单测) ========================

    /** 玩家无任何落盘数据(getPlayerInventory 返 null):exists=false,与「空背包」严格区分。 */
    fun encodeNotFound(player: String): Map<String, Any?> = linkedMapOf(
        "exists" to false,
        "player" to player,
    )

    /**
     * 编码结构化背包视图([InventoryViewDto])。
     *
     * 物品以**数组**承载(各元素含 slot,见 [encodeItem]),便于平台按槽位渲染;基础属性 / 在线态 / 数据版本一并带上。
     */
    fun encodeView(player: String, view: InventoryViewDto): Map<String, Any?> = linkedMapOf(
        "exists" to true,
        "player" to player,
        "online" to view.online,
        "dataVersion" to view.dataVersion,
        "inventory" to view.inventory.entries.sortedBy { it.key }.map { encodeItem(it.key, it.value) },
        "enderChest" to view.enderChest.entries.sortedBy { it.key }.map { encodeItem(it.key, it.value) },
        "basicAttrs" to encodeBasicAttrs(view.basicAttrs),
    )

    /** 编码单件物品(含槽位 + 全部 UI 便利字段;nbtBase64 全保真真源)。空 lore / enchantments 不写入。 */
    fun encodeItem(slot: Int, item: ItemDto): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "slot" to slot,
            "material" to item.material,
            "amount" to item.amount,
            "nbtBase64" to item.nbtBase64,
        )
        item.displayName?.let { entry["displayName"] = it }
        item.lore?.let { entry["lore"] = it }
        item.enchantments?.let { entry["enchantments"] = it }
        return entry
    }

    /** 编码基础属性。 */
    fun encodeBasicAttrs(attrs: BasicAttrsDto): Map<String, Any?> = linkedMapOf(
        "health" to attrs.health,
        "foodLevel" to attrs.foodLevel,
        "xpLevel" to attrs.xpLevel,
        "xpProgress" to attrs.xpProgress,
        "xpTotal" to attrs.xpTotal,
        "gameMode" to attrs.gameMode,
    )

    /** 编码写入落盘回执([WriteResult]):成功 / 在线态 / 新数据版本 / 错误码透传。 */
    fun encodeWriteResult(r: WriteResult): Map<String, Any?> = linkedMapOf(
        "success" to r.success,
        "online" to r.online,
        "newDataVersion" to (r.newDataVersion?.toString() ?: ""),
        "errorCode" to (r.errorCode ?: ""),
        "message" to r.message,
    )
}
