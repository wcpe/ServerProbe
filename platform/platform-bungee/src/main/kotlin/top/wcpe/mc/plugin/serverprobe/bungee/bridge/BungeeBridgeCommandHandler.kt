package top.wcpe.mc.plugin.serverprobe.bungee.bridge

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.TextComponent
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandHandler
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandRegistry
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * BungeeCord 代理端治理执行器(FR-067,JianManager ADR-016 探针侧)。
 *
 * 代理端只持有「玩家在代理上的连接」,不持有 banned-players / whitelist(那是各子服的范畴)。故代理端:
 * - kick:把玩家从代理整体断开(等价于踢下线,作用于跨子服的代理会话);
 * - list:代理视角的全网在线玩家(跨所有子服去重的总在线);
 * - ban/unban/whitelist_*:代理无对应平台 API,返回失败(CP 的封禁/白名单只对后端子服 fanout,
 *   正常不会下发到代理;此处显式拒绝以免静默成功误导)。
 *
 * ## 线程模型:无需切主线程
 * BungeeCord 无「主线程」约束,[ProxyServer] 的玩家查询/断开 API 线程安全,可直接在桥读线程执行(零阻塞)。
 *
 * ## 形态与自注册
 * `@Service` + `@PlatformSide(BUNGEE)`;[register] 在 [PostConstruct] 显式平台门,仅 BungeeCord 端
 * 自注册到 [BridgeCommandRegistry](沿用 InGameAlertChannel / Bukkit 端范式)。
 */
@Service
@PlatformSide(Platform.BUNGEE)
class BungeeBridgeCommandHandler : BridgeCommandHandler {

    /** 治理指令处理器注册中心,初始化完成后自注册。 */
    @Inject
    lateinit var registry: BridgeCommandRegistry

    /**
     * 依赖注入完成后做平台门并自注册。
     *
     * 仅 BungeeCord 端、且插件桥开启时注册。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUNGEE) return
        if (!ProbeConfig.bridgeEnabled()) return
        registry.register(this)
        ProbeLogger.info("BungeeCord 治理执行器已启用(插件桥治理:踢下线/全网在线)")
    }

    /**
     * 执行一条治理/查询指令。
     *
     * @param command 待执行指令。
     * @return 执行结果;代理不支持的动作返回失败。
     */
    override fun handle(command: BridgeCommand): BridgeCommandResult {
        return runCatching {
            when (command.action) {
                ACTION_KICK -> kick(command.target, command.reason)
                ACTION_LIST -> listOnline()
                else -> BridgeCommandResult.fail("代理端不支持治理动作:${command.action}(请对后端子服执行)")
            }
        }.getOrElse { BridgeCommandResult.fail("执行异常:${it.message}") }
    }

    /** 把玩家从代理整体断开;不在线视为成功。 */
    private fun kick(name: String, reason: String): BridgeCommandResult {
        val player = ProxyServer.getInstance().getPlayer(name)
            ?: return BridgeCommandResult.ok("$name 不在代理在线")
        player.disconnect(TextComponent(reason.ifEmpty { DEFAULT_KICK_REASON }))
        return BridgeCommandResult.ok("已踢下线 $name")
    }

    /**
     * 代理视角全网在线玩家名册(逗号分隔)。
     *
     * 输出口径与 CP 侧 parsePlayerList 兼容:`在线 N 名: a, b, c`。
     */
    private fun listOnline(): BridgeCommandResult {
        val names = ProxyServer.getInstance().players.map { it.name }
        return BridgeCommandResult.ok("在线 ${names.size} 名: ${names.joinToString(", ")}")
    }

    private companion object {

        /** 默认踢出文案(指令未带 reason 时)。 */
        private const val DEFAULT_KICK_REASON = "你已被管理员踢下线"

        private const val ACTION_KICK = "kick"
        private const val ACTION_LIST = "list"
    }
}
