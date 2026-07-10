package top.wcpe.mc.plugin.serverprobe.bukkit.business

import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
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
 * 背包业务 Provider(JBIS,见 ServerProbe ADR-0015 装配范式 / ADR-0017 取代 ADR-0016 物品传输契约 / JianManager FR-125)。
 *
 * AllinInventorySync 是软依赖，本类不能在方法描述符中暴露其 API 类型，否则独立服未安装该插件时，IoC 反射扫描
 * `declaredMethods` 会因缺类失败并影响探针启用。真实调用只在插件桥开启且 AllinInventorySync 已就绪时发生，届时
 * 通过反射访问 Provider、读门面和写门面；未安装 / 未就绪统一降级为业务失败，不影响监控采集与插件桥心跳。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class InventoryProvider : BusinessProvider {

    /** 业务对接装配中心(core),初始化完成后自注册本 Provider。 */
    @Inject
    lateinit var businessHost: BusinessHost

    override val domain: String = InventoryEnvelope.DOMAIN

    /** 依赖注入完成后做平台门 + 桥开关门并自注册(仅 Bukkit 端、插件桥开启时)。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        if (!ProbeConfig.bridgeEnabled()) return
        businessHost.register(this)
        ProbeLogger.info("背包业务 Provider 已注册(domain=${InventoryEnvelope.DOMAIN},对接 AllinInventorySync,读 + 属性写)")
    }

    /** 背包域能力清单:只读 `view` + 基础属性写 `writeBasicAttrs`。 */
    override fun manifest(): Map<String, Any?> = InventoryEnvelope.manifest()

    /** 执行一条背包动作。未知动作降级失败。 */
    override fun dispatch(action: String, payload: String): BridgeCommandResult = when (action) {
        InventoryEnvelope.ACTION_VIEW -> view(payload)
        InventoryEnvelope.ACTION_WRITE_BASIC_ATTRS -> writeBasicAttrs(payload)
        InventoryEnvelope.ACTION_WRITE_INVENTORY, InventoryEnvelope.ACTION_WRITE_ENDER_CHEST ->
            InventoryEnvelope.itemWriteUnsupported(action)
        else -> BridgeCommandResult.fail("未知背包动作:$action")
    }

    /** 读玩家结构化背包视图(回源含离线)。未就绪 / 缺 player / 查询异常降级;玩家无数据回 exists=false。 */
    private fun view(payload: String): BridgeCommandResult {
        val api = readyApi() ?: return InventoryEnvelope.notReady()
        val req = Json.parse(payload)
        val player = req.getString("player")
        InventoryEnvelope.requireReadCommon(player)?.let { return it }
        val outcome = runCatching {
            val view = call(api, "getPlayerInventory", player)
            if (view == null) InventoryEnvelope.encodeNotFound(player) else InventoryEnvelope.encodeView(player, view)
        }.getOrElse { return InventoryEnvelope.executeError(InventoryEnvelope.ACTION_VIEW, it) }
        return BridgeCommandResult.ok(Json.encode(outcome))
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
            val writeApi = call(api, "getInventoryWriteApi") ?: error("AllinInventorySync 写门面为空")
            call(
                writeApi,
                "writeBasicAttrs",
                operator,
                player,
                decodeBasicAttrs(baseObj),
                decodeBasicAttrs(editedObj),
                taskId,
            ) as CompletableFuture<*>
        }.getOrElse { return InventoryEnvelope.executeError(action, it) }
        return awaitWrite(future, action)
    }

    /** 有界阻塞取写回执:超时优雅降级(短于 BusinessHost 派发超时),异常降级。 */
    private fun awaitWrite(future: CompletableFuture<*>, action: String): BridgeCommandResult =
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

    /** 解码基础属性对象:门面无 getDouble,数值字段以字符串承载,逐项解析(非法回退默认)。 */
    private fun decodeBasicAttrs(obj: JsonObject): Any = InventoryEnvelope.basicAttrs(
        health = obj.getString("health").toDoubleOrNull() ?: 0.0,
        foodLevel = obj.getString("foodLevel").toIntOrNull() ?: 0,
        xpLevel = obj.getString("xpLevel").toIntOrNull() ?: 0,
        xpProgress = obj.getString("xpProgress").toFloatOrNull() ?: 0f,
        xpTotal = obj.getString("xpTotal").toIntOrNull() ?: 0,
        gameMode = obj.getString("gameMode").ifBlank { "SURVIVAL" },
    )

    /** AllinInventorySync 就绪则返回 api,否则 null(未安装 / 就绪窗口异常均降级为 null)。 */
    private fun readyApi(): Any? {
        val provider = runCatching { Class.forName(ALLIN_PROVIDER) }.getOrNull() ?: return null
        val available = runCatching { provider.getMethod("isAvailable").invoke(null) as? Boolean }.getOrNull() ?: false
        if (!available) return null
        return runCatching { provider.getMethod("get").invoke(null) }.getOrNull()
    }

    /** 按方法名和参数个数反射调用可选 API；仅在可选插件已就绪时走到这里。 */
    private fun call(target: Any, methodName: String, vararg args: Any): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == args.size }
            ?: error("未找到 AllinInventorySync 方法:$methodName/${args.size}")
        return method.invoke(target, *args)
    }

    private companion object {
        /** 写回执有界等待(毫秒):短于 [BusinessHost] 5s 派发超时,确保超时由本类优雅回执而非被外层 cancel。 */
        const val WRITE_TIMEOUT_MS = 4000L
        const val ALLIN_PROVIDER = "top.wcpe.mc.plugin.allininventorysync.api.AllinInventorySyncProvider"
    }
}
