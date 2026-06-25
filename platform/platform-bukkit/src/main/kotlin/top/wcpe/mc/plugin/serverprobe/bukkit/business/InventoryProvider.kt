package top.wcpe.mc.plugin.serverprobe.bukkit.business

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.allininventorysync.api.AllinInventorySyncApi
import top.wcpe.mc.plugin.allininventorysync.api.AllinInventorySyncProvider
import top.wcpe.mc.plugin.allininventorysync.api.InventoryWriteApi
import top.wcpe.mc.plugin.allininventorysync.api.model.BasicAttrsDto
import top.wcpe.mc.plugin.allininventorysync.api.model.InventoryWriteDto
import top.wcpe.mc.plugin.allininventorysync.api.model.ItemDto
import top.wcpe.mc.plugin.allininventorysync.api.model.WriteResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 背包业务 Provider(JBIS,见 ServerProbe ADR-0015 装配范式 / ADR-0016 物品传输契约 / JianManager FR-125)。
 *
 * 对接 **AllinInventorySync** 插件:经其公开 api(`allininventorysync-api`,`compileOnly` 依赖、运行期由目标服务端提供)
 * 读写玩家背包 / 末影箱 / 基础属性。住在 platform-bukkit——业务对接层、唯一认识 AllinInventorySync 具体 API 的地方;
 * 经 [BusinessHost] 注册为 `inventory` 域 Provider,由 [BusinessHost] 在独立业务线程池调用(事故域隔离)。
 * 纯解析 / 校验 / 编码逻辑下沉 [InventoryEnvelope](无 Bukkit 依赖、可单测),本类只管装配、JSON 拆装与真实 API 调用。
 *
 * ## 动作
 * - 只读:`view`(读结构化背包视图,回源含离线)。
 * - 写:`writeInventory`(背包槽 0-35)/ `writeEnderChest`(末影箱槽 0-26)/ `writeBasicAttrs`(生命/食物/经验/游戏模式)。
 *
 * ## 写硬约束(守 AllinInventorySync 契约,JianManager FR-125)
 * - **幂等键**:每个写动作必带 `taskId`(JianManager 侧生成的稳定业务单号,FR-121 同款),映射为 [InventoryWriteDto.requestId];
 *   AllinInventorySync 据此持久去重——同 requestId 二次写直接返回首次落盘回执、不重新施加(防重发刷物品);缺 taskId 直接拒绝。
 * - **delta 语义**:写门面收 base + edited 两份,只把净改动叠加到玩家实时背包(防全量盲覆盖);本类原样透传 CP 给的 base/edited。
 * - **回执透传**:[WriteResult] 的 success / online / newDataVersion / errorCode(NO_SNAPSHOT / OWNED_ELSEWHERE / INVALID_UUID /
 *   INTERNAL_ERROR)结构化回传,不吞码;仅 Provider 级错误(未就绪 / 参数缺失 / 解析失败 / 超时 / 调用抛异常)回 [BridgeCommandResult.fail]。
 *
 * ## 物品传输(ADR-0016)
 * `view` 编码出物品全部 UI 字段供渲染;写的 base/edited 为**物品 JSON 串列表**(各 `{slot,nbtBase64,…}`,经 JSON 门面
 * [JsonObject.getStringList] + 逐串再 [Json.parse] 解码),只取 slot + nbtBase64(全保真真源)还原 [ItemDto]。
 * `writeBasicAttrs` 的 base/edited 为嵌套属性对象(经 [JsonObject.getObject]),字段值以字符串承载(门面无 getDouble)。
 *
 * ## 线程模型与降级
 * 由 [BusinessHost] 在业务线程池(非主线程、非桥读线程)调用;写门面返回的 future 由本类**有界阻塞**等待
 * ([WRITE_TIMEOUT_MS],短于 [BusinessHost] 派发超时,超时优雅回 writeTimeout)。AllinInventorySync 未安装 / 未就绪时
 * 各动作降级失败,绝不抛、绝不拖垮探针(守 ADR-0015 事故域隔离)。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class InventoryProvider : BusinessProvider {

    /** 业务对接装配中心(core),初始化完成后自注册本 Provider。 */
    @Inject
    lateinit var businessHost: BusinessHost

    override val domain: String = InventoryEnvelope.DOMAIN

    /**
     * 依赖注入完成后做平台门 + 桥开关门并自注册(仅 Bukkit 端、插件桥开启时;同 [EconomyProvider] 范式)。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        if (!ProbeConfig.bridgeEnabled()) return
        businessHost.register(this)
        ProbeLogger.info("背包业务 Provider 已注册(domain=${InventoryEnvelope.DOMAIN},对接 AllinInventorySync,读+写)")
    }

    /** 背包域能力清单:只读 `view` + 三个写动作(委托 [InventoryEnvelope.manifest])。 */
    override fun manifest(): Map<String, Any?> = InventoryEnvelope.manifest()

    /**
     * 执行一条背包动作。未知动作降级失败。
     *
     * @param action 动作名。
     * @param payload 结构化参数 JSON。
     */
    override fun dispatch(action: String, payload: String): BridgeCommandResult = when (action) {
        InventoryEnvelope.ACTION_VIEW -> view(payload)
        InventoryEnvelope.ACTION_WRITE_INVENTORY ->
            writeItems(payload, InventoryEnvelope.ACTION_WRITE_INVENTORY) { api, op, uuid, dto ->
                api.writeInventory(op, uuid, dto)
            }
        InventoryEnvelope.ACTION_WRITE_ENDER_CHEST ->
            writeItems(payload, InventoryEnvelope.ACTION_WRITE_ENDER_CHEST) { api, op, uuid, dto ->
                api.writeEnderChest(op, uuid, dto)
            }
        InventoryEnvelope.ACTION_WRITE_BASIC_ATTRS -> writeBasicAttrs(payload)
        else -> BridgeCommandResult.fail("未知背包动作:$action")
    }

    // ======================== 只读 ========================

    /** 读玩家结构化背包视图(回源含离线)。未就绪 / 缺 player / 查询异常降级;玩家无数据回 exists=false。 */
    private fun view(payload: String): BridgeCommandResult {
        val api = readyApi() ?: return InventoryEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        InventoryEnvelope.requireReadCommon(player)?.let { return it }
        val outcome = runCatching {
            val view = api.getPlayerInventory(player)
            if (view == null) InventoryEnvelope.encodeNotFound(player) else InventoryEnvelope.encodeView(player, view)
        }.getOrElse { return InventoryEnvelope.executeError(InventoryEnvelope.ACTION_VIEW, it) }
        return BridgeCommandResult.ok(Json.encode(outcome))
    }

    // ======================== 写 ========================

    /**
     * writeInventory / writeEnderChest 的公共骨架:校验 player/taskId、解码 base/edited 物品集、调 [call] 写、阻塞取回执。
     */
    private inline fun writeItems(
        payload: String,
        action: String,
        call: (InventoryWriteApi, String, String, InventoryWriteDto) -> CompletableFuture<WriteResult>,
    ): BridgeCommandResult {
        val api = readyApi() ?: return InventoryEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val taskId = req.getString("taskId")
        InventoryEnvelope.requireWriteCommon(player, taskId)?.let { return it }
        val operator = InventoryEnvelope.operatorOf(req.getString("operator"))
        val future = runCatching {
            val dto = InventoryWriteDto(
                base = decodeItems(req, "base"),
                edited = decodeItems(req, "edited"),
                requestId = taskId,
            )
            call(api.getInventoryWriteApi(), operator, player, dto)
        }.getOrElse { return InventoryEnvelope.executeError(action, it) }
        return awaitWrite(future, action)
    }

    /** writeBasicAttrs:解码 base/edited 属性对象,调写门面,阻塞取回执。 */
    private fun writeBasicAttrs(payload: String): BridgeCommandResult {
        val action = InventoryEnvelope.ACTION_WRITE_BASIC_ATTRS
        val api = readyApi() ?: return InventoryEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        val taskId = req.getString("taskId")
        InventoryEnvelope.requireWriteCommon(player, taskId)?.let { return it }
        val baseObj = req.getObject("base") ?: return BridgeCommandResult.fail("writeBasicAttrs 缺少 base 属性对象")
        val editedObj = req.getObject("edited") ?: return BridgeCommandResult.fail("writeBasicAttrs 缺少 edited 属性对象")
        val operator = InventoryEnvelope.operatorOf(req.getString("operator"))
        val future = runCatching {
            api.getInventoryWriteApi()
                .writeBasicAttrs(operator, player, decodeBasicAttrs(baseObj), decodeBasicAttrs(editedObj), taskId)
        }.getOrElse { return InventoryEnvelope.executeError(action, it) }
        return awaitWrite(future, action)
    }

    /** 有界阻塞取写回执:超时优雅降级(短于 BusinessHost 派发超时),异常降级。 */
    private fun awaitWrite(future: CompletableFuture<WriteResult>, action: String): BridgeCommandResult =
        runCatching { future.get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .map { BridgeCommandResult.ok(Json.encode(InventoryEnvelope.encodeWriteResult(it))) }
            .getOrElse { t ->
                if (t is TimeoutException) {
                    future.cancel(true)
                    InventoryEnvelope.writeTimeout(action)
                } else {
                    InventoryEnvelope.executeError(action, t)
                }
            }

    // ======================== 解码 / 服务获取 ========================

    /**
     * 解码一组物品槽位条目:JSON 门面取字符串列表(各一件物品 JSON 串)→ 逐串解析 → 取 slot + nbtBase64 还原 [ItemDto]。
     *
     * 无效条目(nbtBase64 空)跳过;空列表得空 map(=清空对应区域,delta 语义由写门面处理)。
     */
    private fun decodeItems(req: JsonObject, key: String): Map<Int, ItemDto> =
        req.getStringList(key).mapNotNull { entry ->
            val item = Json.parse(entry)
            InventoryEnvelope.itemFromParts(
                slot = item.getInt("slot"),
                nbtBase64 = item.getString("nbtBase64"),
                material = item.getString("material"),
                amount = item.getInt("amount"),
                displayName = item.getString("displayName"),
            )
        }.toMap()

    /** 解码基础属性对象:门面无 getDouble,数值字段以字符串承载,逐项解析(非法回退默认)。 */
    private fun decodeBasicAttrs(obj: JsonObject): BasicAttrsDto = InventoryEnvelope.basicAttrs(
        health = obj.getString("health").toDoubleOrNull() ?: 0.0,
        foodLevel = obj.getString("foodLevel").toIntOrNull() ?: 0,
        xpLevel = obj.getString("xpLevel").toIntOrNull() ?: 0,
        xpProgress = obj.getString("xpProgress").toFloatOrNull() ?: 0f,
        xpTotal = obj.getString("xpTotal").toIntOrNull() ?: 0,
        gameMode = obj.getString("gameMode").ifBlank { "SURVIVAL" },
    )

    /** AllinInventorySync 就绪则返回 api,否则 null(就绪窗口内 get 抛异常亦兜为 null)。 */
    private fun readyApi(): AllinInventorySyncApi? =
        if (AllinInventorySyncProvider.isAvailable()) {
            runCatching { AllinInventorySyncProvider.get() }.getOrNull()
        } else {
            null
        }

    private companion object {
        /** 写回执有界等待(毫秒):短于 [BusinessHost] 5s 派发超时,确保超时由本类优雅回执而非被外层 cancel。 */
        const val WRITE_TIMEOUT_MS = 4000L
    }
}
