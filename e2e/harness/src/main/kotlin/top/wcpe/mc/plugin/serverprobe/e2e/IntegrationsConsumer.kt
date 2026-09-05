package top.wcpe.mc.plugin.serverprobe.e2e

import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigDecimal
import java.util.UUID

/**
 * FR10 真实业务插件验收消费者。
 *
 * 只经 ServerProbe 的 [BusinessHost][top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHost] 派发真实 Provider，
 * 不加载或模拟 JianManager；外部 API 均由生产 Provider 自行发现与调用。
 */
class IntegrationsConsumer(
    private val plugin: JavaPlugin,
    private val fixture: ProtocolWorkerFixture,
    private val onServerThread: ServerThreadInvoker,
) {

    /** 执行指定组合的读写、业务失败恢复与插件卸载验收。 */
    fun verify(scenario: String): Map<String, String> {
        waitForDomains(expectedDomains(scenario))
        return when (scenario) {
            SCENARIO_BOTH -> verifyBoth()
            SCENARIO_MCE_ONLY -> verifyMceOnly()
            SCENARIO_AIS_ONLY -> verifyAisOnly()
            SCENARIO_NONE -> verifyNone()
            else -> error("未知 FR10 场景:$scenario")
        }
    }

    private fun verifyBoth(): Map<String, String> {
        val mce = verifyMce()
        val ais = verifyAis()
        disableAndVerify(AIS_PLUGIN, INVENTORY_DOMAIN)
        disableAndVerify(MCE_PLUGIN, ECONOMY_DOMAIN)
        return mce + ais + mapOf("lifecycle" to "both-unregistered")
    }

    private fun verifyMceOnly(): Map<String, String> {
        val details = verifyMce()
        disableAndVerify(MCE_PLUGIN, ECONOMY_DOMAIN)
        return details + mapOf("lifecycle" to "mce-unregistered")
    }

    private fun verifyAisOnly(): Map<String, String> {
        val details = verifyAis()
        disableAndVerify(AIS_PLUGIN, INVENTORY_DOMAIN)
        return details + mapOf("lifecycle" to "ais-unregistered")
    }

    private fun verifyNone(): Map<String, String> {
        check(domains().none { it == ECONOMY_DOMAIN || it == INVENTORY_DOMAIN }) { "缺失组合仍注册了业务 Provider" }
        return mapOf("providers" to "absent")
    }

    /** 覆盖真实余额读写、同幂等键重放、余额不足和失败后的继续写入。 */
    private fun verifyMce(): Map<String, String> {
        val before = command(ECONOMY_DOMAIN, "balance", balancePayload())
        check(before.success) { "MCE 初始余额查询失败:${before.error}" }
        val initial = balanceOf(before.output)

        val deposited = command(ECONOMY_DOMAIN, "deposit", amountPayload(DEPOSIT_AMOUNT, DEPOSIT_TASK))
        requireSuccess(deposited, "MCE 存款")
        val replay = command(ECONOMY_DOMAIN, "deposit", amountPayload(DEPOSIT_AMOUNT, DEPOSIT_TASK))
        check(replay.success && booleanField(replay.output, "idempotentHit")) { "MCE 未命中确定性幂等键:${replay.output}" }

        val insufficient = command(ECONOMY_DOMAIN, "withdraw", amountPayload(INSUFFICIENT_AMOUNT, WITHDRAW_TASK))
        check(insufficient.success && !booleanField(insufficient.output, "success")) { "MCE 余额不足未返回业务失败:${insufficient.output}" }
        val recovered = command(ECONOMY_DOMAIN, "deposit", amountPayload(RECOVERY_AMOUNT, RECOVERY_TASK))
        requireSuccess(recovered, "MCE 失败后恢复写入")

        val after = command(ECONOMY_DOMAIN, "balance", balancePayload())
        val expected = initial + DEPOSIT_AMOUNT + RECOVERY_AMOUNT
        check(sameAmount(balanceOf(after.output), expected)) { "MCE 最终余额不符合幂等与恢复语义:${after.output}" }
        return mapOf("mceBalance" to expected.toPlainString(), "mceIdempotent" to "true", "mceRecovery" to "true")
    }

    /** 覆盖真实玩家视图、重点物品事件、属性写回执、错误写入及其后的恢复读。 */
    private fun verifyAis(): Map<String, String> {
        val playerId = awaitPlayerId()
        seedOnlineSnapshot(playerId)
        val view = awaitInventoryView(playerId)
        triggerTrackedPickup()
        val event = fixture.awaitInventoryEvent()

        val written = command(INVENTORY_DOMAIN, "writeBasicAttrs", attributesPayload(playerId, WRITE_TASK, EDITED_HEALTH))
        requireSuccess(written, "AIS 基础属性写入")
        val invalid = command(INVENTORY_DOMAIN, "writeBasicAttrs", invalidAttributesPayload())
        check(!invalid.success || !booleanField(invalid.output, "success")) { "AIS 非法玩家写入未失败:${invalid.output}" }
        val recoveredView = awaitInventoryView(playerId)
        check(recoveredView.output.contains("\"health\":$EDITED_HEALTH")) { "AIS 写入后读取未恢复目标属性:${recoveredView.output}" }
        return mapOf("aisView" to "true", "aisEvent" to event.getValue("dedupKey"), "aisRecovery" to "true")
    }

    /**
     * AIS 的公开读 API 读取其权威快照；新上线玩家尚未经过原版保存时不存在该快照。
     * 先经公开属性写门面以零差异写入实时状态，建立可读取的在线快照，不依赖内部 Manager 或等待自动保存。
     */
    private fun seedOnlineSnapshot(playerId: UUID) {
        val seeded = command(INVENTORY_DOMAIN, "writeBasicAttrs", attributesPayload(playerId, SEED_TASK, BASE_HEALTH))
        requireSuccess(seeded, "AIS 在线快照初始化")
    }

    /** 插件禁用必须经真实 Bukkit 生命周期撤销对应 Provider。 */
    private fun disableAndVerify(pluginName: String, domain: String) {
        onServerThread.call {
            val external = checkNotNull(plugin.server.pluginManager.getPlugin(pluginName)) { "未找到待卸载插件:$pluginName" }
            plugin.server.pluginManager.disablePlugin(external)
        }
        waitForAbsentDomain(domain)
    }

    private fun waitForDomains(expected: Set<String>) {
        repeat(PROVIDER_ATTEMPTS) {
            if (domains().containsAll(expected) && domains().intersect(BUSINESS_DOMAINS) == expected) return
            Thread.sleep(RETRY_DELAY_MS)
        }
        error("业务 Provider 未按组合收敛:期望=$expected,实际=${domains()}")
    }

    private fun waitForAbsentDomain(domain: String) {
        repeat(PROVIDER_ATTEMPTS) {
            if (domain !in domains()) return
            Thread.sleep(RETRY_DELAY_MS)
        }
        error("插件卸载后业务域仍存在:$domain")
    }

    private fun awaitPlayerId(): UUID {
        repeat(PLAYER_ATTEMPTS) {
            onServerThread.call { plugin.server.getPlayer(AIS_PLAYER)?.uniqueId }?.let { return it }
            Thread.sleep(RETRY_DELAY_MS)
        }
        error("AIS 验收 bot 未上线:$AIS_PLAYER")
    }

    private fun awaitInventoryView(playerId: UUID): RemoteResult {
        repeat(PLAYER_ATTEMPTS) {
            val result = command(INVENTORY_DOMAIN, "view", "{\"player\":\"$playerId\"}")
            if (result.success && booleanField(result.output, "exists")) return result
            Thread.sleep(RETRY_DELAY_MS)
        }
        error("AIS 未提供真实玩家背包视图")
    }

    /** 投放配置中的重点物品，真实触发 AIS Bukkit 事件与 ServerProbe 上行事件监听器。 */
    private fun triggerTrackedPickup() {
        onServerThread.call {
            val player = checkNotNull(plugin.server.getPlayer(AIS_PLAYER)) { "AIS 验收 bot 已离线" }
            player.world.dropItem(player.location, org.bukkit.inventory.ItemStack(Material.DIAMOND, PICKUP_AMOUNT))
        }
    }

    private fun balancePayload(): String = "{\"player\":\"$MCE_PLAYER\",\"currency\":\"coin\"}"

    private fun amountPayload(amount: BigDecimal, taskId: String): String =
        "{\"player\":\"$MCE_PLAYER\",\"currency\":\"coin\",\"amount\":\"${amount.toPlainString()}\",\"taskId\":\"$taskId\"}"

    private fun attributesPayload(playerId: UUID, taskId: String, health: Double): String =
        "{\"player\":\"$playerId\",\"taskId\":\"$taskId\",\"base\":${attributes(BASE_HEALTH)},\"edited\":${attributes(health)}}"

    private fun invalidAttributesPayload(): String =
        "{\"player\":\"$INVALID_UUID\",\"taskId\":\"$INVALID_TASK\",\"base\":${attributes(BASE_HEALTH)},\"edited\":${attributes(EDITED_HEALTH)}}"

    private fun attributes(health: Double): String =
        "{\"basicAttrs\":{\"health\":$health,\"foodLevel\":20,\"xpLevel\":0,\"xpProgress\":0.0,\"xpTotal\":0,\"gameMode\":\"SURVIVAL\"}}"

    private fun command(domain: String, action: String, payload: String): RemoteResult {
        val host = businessHost()
        val result = host.javaClass.getMethod("dispatch", String::class.java, String::class.java, String::class.java)
            .invoke(host, domain, action, payload)
        return RemoteResult(
            success = result.javaClass.getMethod("getSuccess").invoke(result) as Boolean,
            output = result.javaClass.getMethod("getOutput").invoke(result) as String,
            error = result.javaClass.getMethod("getError").invoke(result) as String,
        )
    }

    private fun domains(): Set<String> = businessHost().javaClass.getMethod("domains").invoke(businessHost()) as Set<String>

    private fun businessHost(): Any {
        val probe = checkNotNull(plugin.server.pluginManager.getPlugin(SERVER_PROBE_PLUGIN)) { "未找到 ServerProbe 插件" }
        val loader = probe.javaClass.classLoader
        val hostClass = loader.loadClass(BUSINESS_HOST_CLASS)
        val container = loader.loadClass(CONTAINER_CLASS).getField("INSTANCE").get(null)
        return checkNotNull(container.javaClass.getMethod("getBean", Class::class.java, String::class.java).invoke(container, hostClass, null)) {
            "业务宿主尚未就绪"
        }
    }

    private fun requireSuccess(result: RemoteResult, action: String) {
        check(result.success && booleanField(result.output, "success")) { "$action 未成功:${result.error}/${result.output}" }
    }

    private fun balanceOf(output: String): BigDecimal = BigDecimal(stringField(output, "balance"))

    private fun booleanField(output: String, key: String): Boolean =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false)").find(output)?.groupValues?.get(1)?.toBoolean()
            ?: error("业务回执缺少布尔字段:$key/$output")

    private fun stringField(output: String, key: String): String =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(output)?.groupValues?.get(1)
            ?: error("业务回执缺少字符串字段:$key/$output")

    private data class RemoteResult(val success: Boolean, val output: String, val error: String)

    private companion object {
        const val SCENARIO_BOTH = "integrations-both"
        const val SCENARIO_MCE_ONLY = "integrations-mce-only"
        const val SCENARIO_AIS_ONLY = "integrations-ais-only"
        const val SCENARIO_NONE = "integrations-none"
        const val ECONOMY_DOMAIN = "economy"
        const val INVENTORY_DOMAIN = "inventory"
        const val MCE_PLUGIN = "MultiCurrencyEconomy"
        const val AIS_PLUGIN = "AllinInventorySync"
        const val SERVER_PROBE_PLUGIN = "ServerProbe"
        const val CONTAINER_CLASS = "top.wcpe.mc.plugin.serverprobe.ioc.bean.BeanContainer"
        const val BUSINESS_HOST_CLASS = "top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHost"
        const val MCE_PLAYER = "ServerProbeEconomy"
        const val AIS_PLAYER = "SpInventory"
        const val DEPOSIT_TASK = "integrations-deposit"
        const val WITHDRAW_TASK = "integrations-insufficient"
        const val RECOVERY_TASK = "integrations-recovery"
        const val SEED_TASK = "integrations-ais-seed"
        const val WRITE_TASK = "integrations-ais-write"
        const val INVALID_TASK = "integrations-ais-invalid"
        const val INVALID_UUID = "not-a-uuid"
        const val PROVIDER_ATTEMPTS = 60
        const val PLAYER_ATTEMPTS = 80
        const val RETRY_DELAY_MS = 250L
        const val PICKUP_AMOUNT = 1
        const val BASE_HEALTH = 20.0
        const val EDITED_HEALTH = 19.0
        val DEPOSIT_AMOUNT: BigDecimal = BigDecimal.TEN
        val INSUFFICIENT_AMOUNT: BigDecimal = BigDecimal("999")
        val RECOVERY_AMOUNT: BigDecimal = BigDecimal.ONE
        val BUSINESS_DOMAINS = setOf(ECONOMY_DOMAIN, INVENTORY_DOMAIN)

        fun expectedDomains(scenario: String): Set<String> = when (scenario) {
            SCENARIO_BOTH -> BUSINESS_DOMAINS
            SCENARIO_MCE_ONLY -> setOf(ECONOMY_DOMAIN)
            SCENARIO_AIS_ONLY -> setOf(INVENTORY_DOMAIN)
            SCENARIO_NONE -> emptySet()
            else -> error("未知 FR10 场景:$scenario")
        }
    }
}

/** 金额比较忽略存储层返回的尾随零精度。 */
internal fun sameAmount(actual: BigDecimal, expected: BigDecimal): Boolean = actual.compareTo(expected) == 0

/** 将后台验收中的 Bukkit 访问委托给平台线程，避免测试本身突破线程边界。 */
interface ServerThreadInvoker {
    fun <T> call(action: () -> T): T
}
