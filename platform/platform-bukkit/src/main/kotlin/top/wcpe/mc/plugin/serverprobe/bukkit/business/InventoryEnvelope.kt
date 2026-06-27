package top.wcpe.mc.plugin.serverprobe.bukkit.business

import top.wcpe.mc.plugin.allininventorysync.api.model.BasicAttrsDto
import top.wcpe.mc.plugin.allininventorysync.api.model.InventoryViewDto
import top.wcpe.mc.plugin.allininventorysync.api.model.ItemDto
import top.wcpe.mc.plugin.allininventorysync.api.model.WriteResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult

/**
 * 背包域信封编解码与校验(JBIS FR-125,见 ServerProbe ADR-0017 取代 ADR-0016 / JianManager FR-125)。
 *
 * [InventoryProvider] 的纯逻辑伴随体:动作常量、manifest、payload 校验、基础属性解码、视图 / 回执编码。
 * 抽出本对象的目的:① 让 [InventoryProvider] 函数数 / 复杂度落在 detekt 阈值内;② 纯逻辑可脱离 Bukkit 运行期单测。
 * 仅引 AllinInventorySync 的对外中性 DTO([ItemDto] / [InventoryViewDto] / [WriteResult] / [BasicAttrsDto],均 `api.model`,
 * AllinInventorySync 2.0.0 起为纯 Java + Lombok)与 core 的 [BridgeCommandResult];不碰 Bukkit API、不调真实服务,故可独立测试。
 *
 * ## 物品传输契约(ADR-0017 取代 ADR-0016)
 * - **读:结构化富视图**:`view` 把每件物品的全部 UI 便利字段(material / amount / displayName / lore / enchantments)
 *   连同全保真 `nbtBase64` 编码出来供平台渲染。
 * - **物品写不提供**:AllinInventorySync 2.0.0 把背包 / 末影箱写门面的入参退回为不透明分区字节([InventoryWriteDto]
 *   的 `byte[] base/edited`,GZIP NBT 分区),外部集成无法从结构化物品产出这些 AllinInventorySync 内部字节;故
 *   `writeInventory` / `writeEnderChest` 暂不支持(经 [itemWriteUnsupported] 明确降级、不进 manifest),待 AllinInventorySync
 *   重新导出可外部消费的结构化物品写门面再恢复。
 * - **基础属性写保留**:`writeBasicAttrs` 入参是定形 [BasicAttrsDto](非分区字节),外部可构造,故保留;沿用
 *   base→edited 净改动 delta 语义与 `taskId` 幂等键。
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

    /**
     * 背包域能力清单:只读 `view` + 基础属性写 `writeBasicAttrs`。
     *
     * 物品写(`writeInventory` / `writeEnderChest`)**不进清单**:AllinInventorySync 2.0.0 写门面入参为不透明分区字节,
     * 外部无法从结构化物品构造,暂不支持(见对象 KDoc / ADR-0017);收到该动作经 [itemWriteUnsupported] 明确降级。
     */
    fun manifest(): Map<String, Any?> = mapOf(
        "actions" to listOf(
            action(
                ACTION_VIEW, listOf("player"), readOnly = true,
                note = "player 为玩家 UUID;回源含离线,玩家无数据返 exists=false"
            ),
            action(
                ACTION_WRITE_BASIC_ATTRS, listOf("player", "base", "edited", "taskId"), readOnly = false,
                note = "base/edited 为属性对象 {health,foodLevel,xpLevel,xpProgress,xpTotal,gameMode};" +
                    "只施加 base→edited 净改动;taskId 幂等键(CP 生成);operator 由 CP 注入"
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

    /** 物品写(writeInventory / writeEnderChest)在 AllinInventorySync 2.0.0 分区字节写门面下不可外部消费,明确降级。 */
    fun itemWriteUnsupported(action: String): BridgeCommandResult = BridgeCommandResult.fail(
        "$action 暂不支持:AllinInventorySync 2.0.0 物品写门面入参为不透明分区字节,外部集成无法从结构化物品构造" +
            "(待其导出可消费的结构化物品写门面再恢复);基础属性写 writeBasicAttrs 与读 view 不受影响"
    )

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
