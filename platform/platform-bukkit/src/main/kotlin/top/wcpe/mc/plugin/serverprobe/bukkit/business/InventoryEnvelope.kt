package top.wcpe.mc.plugin.serverprobe.bukkit.business

import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult

/**
 * 背包域信封编解码与校验(JBIS FR-125,见 ServerProbe ADR-0017 取代 ADR-0016 / JianManager FR-125)。
 *
 * [InventoryProvider] 的纯逻辑伴随体:动作常量、manifest、payload 校验、基础属性解码、视图 / 回执编码。
 * 本对象刻意不在公开方法签名中暴露 AllinInventorySync DTO 类型：该插件是软依赖，独立服未安装时
 * IoC 反射扫描仍会读取方法描述符，若描述符含可选 API 类型会触发 NoClassDefFoundError 并拖垮探针启用。
 * 实际背包域被调用且 AllinInventorySync 已在场时，再通过反射读取 DTO 属性或构造写入 DTO。
 */
@Suppress("TooManyFunctions")
object InventoryEnvelope {

    /** 背包业务域名,与 Worker 下发的 domain 对应。 */
    const val DOMAIN = "inventory"

    /** JianManager 作为写操作的默认操作者名(payload 未带 operator 时回退,写进 AllinInventorySync 审计)。 */
    const val DEFAULT_OPERATOR = "JianManager"

    const val ACTION_VIEW = "view"
    const val ACTION_WRITE_INVENTORY = "writeInventory"
    const val ACTION_WRITE_ENDER_CHEST = "writeEnderChest"
    const val ACTION_WRITE_BASIC_ATTRS = "writeBasicAttrs"

    /** 背包域能力清单:只读 `view` + 基础属性写 `writeBasicAttrs`。 */
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

    /** 由解码出的字段反射构造 BasicAttrsDto(基础属性写入用)。 */
    @Suppress("LongParameterList")
    fun basicAttrs(
        health: Double,
        foodLevel: Int,
        xpLevel: Int,
        xpProgress: Float,
        xpTotal: Int,
        gameMode: String,
    ): Any {
        val type = Class.forName(BASIC_ATTRS_DTO)
        val ctor = type.getConstructor(
            java.lang.Double.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Float.TYPE,
            java.lang.Integer.TYPE,
            String::class.java,
        )
        return ctor.newInstance(health, foodLevel, xpLevel, xpProgress, xpTotal, gameMode)
    }

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

    /** 玩家无任何落盘数据(getPlayerInventory 返 null):exists=false,与「空背包」严格区分。 */
    fun encodeNotFound(player: String): Map<String, Any?> = linkedMapOf(
        "exists" to false,
        "player" to player,
    )

    /** 编码结构化背包视图。view 为 AllinInventorySync 的 InventoryViewDto 实例。 */
    fun encodeView(player: String, view: Any): Map<String, Any?> = linkedMapOf(
        "exists" to true,
        "player" to player,
        "online" to read(view, "online"),
        "dataVersion" to read(view, "dataVersion"),
        "inventory" to readItemMap(view, "inventory"),
        "enderChest" to readItemMap(view, "enderChest"),
        "basicAttrs" to encodeBasicAttrs(read(view, "basicAttrs") ?: error("缺少 basicAttrs")),
    )

    /** 编码单件物品(含槽位 + 全部 UI 便利字段;nbtBase64 全保真真源)。空 lore / enchantments 不写入。 */
    fun encodeItem(slot: Int, item: Any): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "slot" to slot,
            "material" to read(item, "material"),
            "amount" to read(item, "amount"),
            "nbtBase64" to read(item, "nbtBase64"),
        )
        read(item, "displayName")?.let { entry["displayName"] = it }
        read(item, "lore")?.let { entry["lore"] = it }
        read(item, "enchantments")?.let { entry["enchantments"] = it }
        return entry
    }

    /** 编码基础属性。attrs 为 AllinInventorySync 的 BasicAttrsDto 实例。 */
    fun encodeBasicAttrs(attrs: Any): Map<String, Any?> = linkedMapOf(
        "health" to read(attrs, "health"),
        "foodLevel" to read(attrs, "foodLevel"),
        "xpLevel" to read(attrs, "xpLevel"),
        "xpProgress" to read(attrs, "xpProgress"),
        "xpTotal" to read(attrs, "xpTotal"),
        "gameMode" to read(attrs, "gameMode"),
    )

    /** 编码写入落盘回执:成功 / 在线态 / 新数据版本 / 错误码透传。 */
    fun encodeWriteResult(r: Any): Map<String, Any?> = linkedMapOf(
        "success" to read(r, "success"),
        "online" to read(r, "online"),
        "newDataVersion" to (read(r, "newDataVersion")?.toString() ?: ""),
        "errorCode" to (read(r, "errorCode") ?: ""),
        "message" to read(r, "message"),
    )

    private fun readItemMap(view: Any, property: String): List<Map<String, Any?>> {
        val items = read(view, property) as? Map<*, *> ?: return emptyList()
        return items.entries.sortedBy { slotOf(it.key) }.map { encodeItem(slotOf(it.key), it.value ?: return@map emptyMap()) }
    }

    private fun slotOf(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        else -> raw?.toString()?.toIntOrNull() ?: 0
    }

    private fun read(target: Any, property: String): Any? {
        val suffix = property.substring(0, 1).uppercase() + property.substring(1)
        for (methodName in listOf("get$suffix", "is$suffix")) {
            val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            if (method != null) return method.invoke(target)
        }
        val field = target.javaClass.fields.firstOrNull { it.name == property }
        return field?.get(target)
    }

    private const val BASIC_ATTRS_DTO = "top.wcpe.mc.plugin.allininventorysync.api.model.BasicAttrsDto"
}
