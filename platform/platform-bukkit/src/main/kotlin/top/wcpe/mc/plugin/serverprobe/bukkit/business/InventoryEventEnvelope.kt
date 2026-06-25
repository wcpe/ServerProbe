package top.wcpe.mc.plugin.serverprobe.bukkit.business

/**
 * 背包追踪事件信封编码与去重键(JBIS FR-125,见 ServerProbe ADR-0016 / JianManager FR-126 汇聚)。
 *
 * [BukkitInventoryEventListener] 的纯逻辑伴随体:把 AllinInventorySync 的 `TrackedItemActionEvent`(重点物品流转:
 * 登录携带 / 丢出 / 拾取 / 移入容器)折算成上报信封(全字符串载荷),并合成去重键。抽出本对象的目的:① 让监听器
 * 函数数 / 复杂度落在 detekt 阈值内;② 纯逻辑(无 Bukkit / AllinInventorySync 运行期依赖)可脱离游戏服独立单测。
 *
 * ## 去重键(与经济域 ledgerId 不同)
 * 追踪事件是**瞬时观测**,无 AllinInventorySync 侧持久单调 ID(不同于经济 ledgerId),且无 relay / catchup 重投——
 * 在线即至多一次投递、断连即丢。故去重键合成为 `playerUuid:action:occurredAtMs:seq`(seq 为探针会话内单调序号):
 * 保证每条观测各异(同毫秒内多次同动作靠 seq 区分),CP 据此落库且对极端情形下桥重发的同一帧幂等。
 *
 * ## 物品字段(无 nbtBase64)
 * AllinInventorySync 的全保真 `ItemStackCodec` 在其 **core** 而非 `api`,探针(仅 compileOnly api)拿不到 nbtBase64;
 * 追踪为观测语义,信封只携 Bukkit-API 可得的便利字段(material / amount / displayName),足够 CP 记录「谁、何动作、什么物品」。
 */
object InventoryEventEnvelope {

    /** 背包业务域名,与 [InventoryEnvelope.DOMAIN] / Worker / CP 解析约定一致。 */
    const val DOMAIN = InventoryEnvelope.DOMAIN

    // 信封字段键名(须与 CP 侧解析约定逐字一致)。
    const val FIELD_PLAYER_NAME = "playerName"
    const val FIELD_PLAYER_UUID = "playerUuid"
    const val FIELD_ACTION = "action"
    const val FIELD_RULE_ID = "ruleId"
    const val FIELD_RULE_DESC = "ruleDescription"
    const val FIELD_MATERIAL = "material"
    const val FIELD_AMOUNT = "amount"
    const val FIELD_DISPLAY_NAME = "displayName"
    const val FIELD_OCCURRED_AT = "occurredAt"

    /**
     * 上报去重键:`playerUuid:action:occurredAtMs:seq`。
     *
     * @param playerUuid 玩家 UUID。
     * @param action 动作名(JOIN_CARRY / DROP / PICKUP / MOVE_TO_CONTAINER)。
     * @param occurredAtMs 观测时刻(epoch 毫秒)。
     * @param seq 探针会话内单调序号(同毫秒多事件去歧义)。
     */
    fun dedupKey(playerUuid: String, action: String, occurredAtMs: Long, seq: Long): String =
        "$playerUuid:$action:$occurredAtMs:$seq"

    /**
     * 折算一条追踪事件为上报信封字段(全字符串;空 displayName 由 [BridgeClient][
     * top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient.emitBusinessEvent] 过滤不发)。
     *
     * @param playerName 玩家名。
     * @param playerUuid 玩家 UUID。
     * @param action 动作名。
     * @param ruleId 命中的追踪规则 id。
     * @param ruleDescription 规则描述(用于展示)。
     * @param material 物品材质名。
     * @param amount 物品数量。
     * @param displayName 物品显示名(无则空串)。
     * @param occurredAtMs 观测时刻(epoch 毫秒)。
     */
    @Suppress("LongParameterList")
    fun encode(
        playerName: String,
        playerUuid: String,
        action: String,
        ruleId: String,
        ruleDescription: String,
        material: String,
        amount: Int,
        displayName: String,
        occurredAtMs: Long,
    ): Map<String, String> = linkedMapOf(
        FIELD_PLAYER_NAME to playerName,
        FIELD_PLAYER_UUID to playerUuid,
        FIELD_ACTION to action,
        FIELD_RULE_ID to ruleId,
        FIELD_RULE_DESC to ruleDescription,
        FIELD_MATERIAL to material,
        FIELD_AMOUNT to amount.toString(),
        FIELD_DISPLAY_NAME to displayName,
        FIELD_OCCURRED_AT to occurredAtMs.toString(),
    )
}
