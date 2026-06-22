package top.wcpe.mc.plugin.serverprobe.bukkit.bridge

import org.bukkit.Bukkit
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandHandler
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandRegistry
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bukkit 子服端治理执行器(FR-067,JianManager ADR-016 探针侧)。
 *
 * 替代退役的 RCON,经平台 API 执行 CP 下发的治理/查询指令(踢/封/解封/白名单/在线列表)。
 * 子服本地视角:对**本子服**生效(踢出/封禁本服在线玩家、增删本服白名单、列本服在线/白名单);
 * 跨服聚合由 CP 对群组内各子服探针结果汇总(本端只管本服)。
 *
 * ## 线程模型:切回主线程同步执行
 * 桥读线程(core [top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient])调用 [handle];而 Bukkit
 * 玩家操作(kick/ban/whitelist/在线遍历)须在服务器主线程。故经 `submit(async = false)` 切回主线程执行,
 * 用 [CountDownLatch] 同步等待结果回传(限时 [SYNC_TIMEOUT_SECONDS],超时即失败,绝不永久占用桥读线程)。
 *
 * ## 形态与自注册
 * `@Service` + `@PlatformSide(BUKKIT)`;IOC 不感知 [PlatformSide] 会在所有平台实例化,故 [register]
 * 在 [PostConstruct] 显式平台门,仅 Bukkit 端自注册到 [BridgeCommandRegistry](沿用 InGameAlertChannel 范式)。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitBridgeCommandHandler : BridgeCommandHandler {

    /** 治理指令处理器注册中心,初始化完成后自注册。 */
    @Inject
    lateinit var registry: BridgeCommandRegistry

    /**
     * 依赖注入完成后做平台门并自注册。
     *
     * 仅 Bukkit 端、且插件桥开启时注册:独立使用探针(桥关闭)或代理端不应承接子服治理。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        if (!ProbeConfig.bridgeEnabled()) return
        registry.register(this)
        ProbeLogger.info("Bukkit 治理执行器已启用(插件桥治理:踢/封/解封/白名单/在线列表)")
    }

    /**
     * 执行一条治理/查询指令(切回主线程同步等待结果)。
     *
     * @param command 待执行指令。
     * @return 执行结果(成功/输出/失败原因);未知 action 或超时返回失败。
     */
    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val ref = AtomicReference<BridgeCommandResult>()
        val latch = CountDownLatch(1)
        submit(async = false) {
            ref.set(runCatching { dispatch(command) }
                .getOrElse { BridgeCommandResult.fail("执行异常:${it.message}") })
            latch.countDown()
        }
        if (!latch.await(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return BridgeCommandResult.fail("主线程执行超时")
        }
        return ref.get() ?: BridgeCommandResult.fail("无执行结果")
    }

    /**
     * 主线程内按 action 分派执行。
     *
     * @param command 待执行指令。
     * @return 执行结果。
     */
    @Suppress("DEPRECATION") // Bukkit getOfflinePlayer(name)/BanList String API 跨版本通用,故意用名称态
    private fun dispatch(command: BridgeCommand): BridgeCommandResult {
        return when (command.action) {
            ACTION_KICK -> kick(command.target, command.reason)
            ACTION_BAN -> ban(command.target, command.reason)
            ACTION_UNBAN -> unban(command.target)
            ACTION_WHITELIST_ADD -> whitelistAdd(command.target)
            ACTION_WHITELIST_REMOVE -> whitelistRemove(command.target)
            ACTION_LIST -> listOnline()
            ACTION_WHITELIST_LIST -> listWhitelist()
            else -> BridgeCommandResult.fail("未知治理动作:${command.action}")
        }
    }

    /** 踢出本服在线玩家;不在线视为成功(目标已不在本服)。 */
    private fun kick(name: String, reason: String): BridgeCommandResult {
        val player = Bukkit.getPlayerExact(name)
            ?: return BridgeCommandResult.ok("$name 不在本服在线")
        player.kickPlayer(reason.ifEmpty { DEFAULT_KICK_REASON })
        return BridgeCommandResult.ok("已踢出 $name")
    }

    /**
     * 封禁玩家:经控制台分派 vanilla `ban` 命令(写入本服 banned-players)。
     *
     * 用控制台命令而非 `BanList` API:后者在不同 Bukkit/Paper 版本签名漂移(泛型化后 String 重载被弃用/移除),
     * 控制台 `ban`/`pardon` 跨版本稳定;且本方法已在主线程([dispatch] 经 submit 切回),分派命令安全。
     * vanilla `ban` 自身会踢出在线会话,无需另行 kick。
     */
    private fun ban(name: String, reason: String): BridgeCommandResult {
        val r = reason.ifEmpty { DEFAULT_BAN_REASON }
        val ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban $name $r")
        return if (ok) BridgeCommandResult.ok("已封禁 $name") else BridgeCommandResult.fail("封禁命令执行失败:$name")
    }

    /** 解封玩家:经控制台分派 vanilla `pardon` 命令(从本服 banned-players 移除)。 */
    private fun unban(name: String): BridgeCommandResult {
        val ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon $name")
        return if (ok) BridgeCommandResult.ok("已解封 $name") else BridgeCommandResult.fail("解封命令执行失败:$name")
    }

    /** 加入本服白名单。 */
    private fun whitelistAdd(name: String): BridgeCommandResult {
        val target = Bukkit.getOfflinePlayer(name)
        target.isWhitelisted = true
        return BridgeCommandResult.ok("已加入白名单:$name")
    }

    /** 移出本服白名单。 */
    private fun whitelistRemove(name: String): BridgeCommandResult {
        val target = Bukkit.getOfflinePlayer(name)
        target.isWhitelisted = false
        return BridgeCommandResult.ok("已移出白名单:$name")
    }

    /**
     * 本服在线玩家名册(逗号分隔)。
     *
     * 输出口径与 CP 侧 parsePlayerList 兼容:`在线 N 名: a, b, c`;空时 `在线 0 名:`。
     */
    private fun listOnline(): BridgeCommandResult {
        val names = Bukkit.getOnlinePlayers().map { it.name }
        return BridgeCommandResult.ok("在线 ${names.size} 名: ${names.joinToString(", ")}")
    }

    /** 本服白名单名册(逗号分隔)。 */
    private fun listWhitelist(): BridgeCommandResult {
        val names = Bukkit.getWhitelistedPlayers().mapNotNull { it.name }
        return BridgeCommandResult.ok("白名单 ${names.size} 名: ${names.joinToString(", ")}")
    }

    private companion object {

        /** 切回主线程执行的同步等待上限(秒);须小于 Worker 侧 5s 超时,留网络余量。 */
        private const val SYNC_TIMEOUT_SECONDS = 3L

        /** 默认踢出文案(指令未带 reason 时)。 */
        private const val DEFAULT_KICK_REASON = "你已被管理员踢出"

        /** 默认封禁文案(指令未带 reason 时)。 */
        private const val DEFAULT_BAN_REASON = "你已被管理员封禁"

        private const val ACTION_KICK = "kick"
        private const val ACTION_BAN = "ban"
        private const val ACTION_UNBAN = "unban"
        private const val ACTION_WHITELIST_ADD = "whitelist_add"
        private const val ACTION_WHITELIST_REMOVE = "whitelist_remove"
        private const val ACTION_LIST = "list"
        private const val ACTION_WHITELIST_LIST = "whitelist_list"
    }
}
