package top.wcpe.mc.plugin.serverprobe.command

import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.function.submitAsync
import taboolib.module.lang.asLangText
import taboolib.module.lang.sendLang
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasRuntime
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpControlPlane

/**
 * `/probe mcp` 子命令的动作执行与输出(FR-24,见 ADR-0028)。
 *
 * 独立成文件的原因:[ProbeCommand] 是命令声明宿主,已承载全部分支的输出渲染,再内联本组动作会超出
 * detekt 对单 object 函数数的阈值;此处只做"调度 + 调用控制面 + 发文案"。
 *
 * ## 为什么启停一律异步
 * 四个启停动作都含阻塞操作,在主线程执行会冻结服务器:**端点级**要建目录并按保留期/容量清理工件
 * (最多扫 10 GiB);**Arthas 级**要解包约 20 MB 闭包,且 Instrumentation 附加可能 spawn helper 子进程
 * 并等待其退出(最长 30 秒)。故除纯内存读取的 [status] 外,全部经 [submitAsync] 转异步执行后回执
 * (TabooLib 调度,已适配 Folia;项目红线禁止主线程阻塞磁盘 IO / 外部进程)。
 *
 * 回执在异步线程直接发送:本命令已在 [ProbeCommand.mcp] 前置"仅控制台"门([isConsoleSender]),发送者只可能是
 * 控制台/RCON 的实现,其 `sendMessage` 与插件日志同路径,不触碰世界/实体状态,异步发送安全。
 *
 * 两级开关语义:端点级([McpControlPlane])只起 HTTP 端点,原生工具立即可用;Arthas 级([ArthasRuntime])
 * 才付出闭包解包与 Instrumentation 附加代价。两者独立开关,`off` 不完结对方的加载状态。
 */
internal object McpCommandHandler {

    /**
     * 允许执行 `/probe mcp` 的底层发送者类型白名单(ADR-0028"仅控制台/RCON")。
     *
     * 经 `Class.forName` 惰性解析:插件是跨平台统一 jar,不能直接引用某一平台的编译期类型;
     * 某平台没有的类型解析失败只表示本平台不适用,不影响其余类型。
     */
    private val consoleSenderTypes: List<Class<*>> by lazy {
        listOf(
            // Bukkit/CraftBukkit:控制台与 RCON(命令方块等非玩家发送者不实现二者,故不放行)
            "org.bukkit.command.ConsoleCommandSender",
            "org.bukkit.command.RemoteConsoleCommandSender",
            // BungeeCord:控制台无 API 接口,按其实现类判定
            "net.md_5.bungee.command.ConsoleCommandSender",
            // Velocity:控制台
            "com.velocitypowered.api.proxy.ConsoleCommandSource"
        ).mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
    }

    /**
     * 判定发送者是否为服务器控制台/RCON([ProbeCommand.mcp] 的"仅控制台"门)。
     *
     * 取底层发送者([ProxyCommandSender] 的 origin)按类型白名单匹配;白名单外一律拒绝(玩家、命令方块、
     * 实体执行者、其它插件代发的自定义发送者),即**失败关闭**:运行时没有任何白名单类型(被裁剪的
     * 非常规运行时)时同样拒绝。不得改用"非玩家即放行"——命令方块的 `isOp()` 在 1.16.5 上恒真。
     *
     * @param sender 待判定的命令发送者。
     * @return 底层发送者命中控制台白名单时为 true。
     */
    fun isConsoleSender(sender: ProxyCommandSender): Boolean {
        val origin = sender.origin ?: return false
        return consoleSenderTypes.any { it.isInstance(origin) }
    }

    /** 开启端点级控制面;幂等与失败原因均由控制面回执(见 [McpControlPlane.enable])。 */
    fun on(sender: ProxyCommandSender, plane: McpControlPlane) = submitAsync {
        val outcome = plane.enable()
        sender.sendLang(if (outcome.success) "command-mcp-on" else "command-mcp-on-failed", outcome.message)
    }

    /** 关闭端点级控制面;Arthas 仍加载时提示可一并释放(两级开关各自独立)。 */
    fun off(sender: ProxyCommandSender, plane: McpControlPlane, arthas: ArthasRuntime) = submitAsync {
        if (!plane.disable()) {
            sender.sendLang("command-mcp-off-not-running")
            return@submitAsync
        }
        sender.sendLang("command-mcp-off")
        if (arthas.runtimeReady) sender.sendLang("command-mcp-off-arthas-hint")
    }

    /** 渲染端点与 Arthas 两级状态(纯内存读取,保持同步以便立即回显)。 */
    fun status(sender: ProxyCommandSender, plane: McpControlPlane, arthas: ArthasRuntime) {
        sender.sendLang("command-mcp-status-title")
        sender.sendLang(
            "command-mcp-status-endpoint",
            sender.asLangText(if (plane.running) "command-mcp-status-running" else "command-mcp-status-stopped"),
        )
        sender.sendLang(
            "command-mcp-status-arthas",
            sender.asLangText(if (arthas.runtimeReady) "command-mcp-status-loaded" else "command-mcp-status-unloaded"),
        )
        sender.sendLang("command-mcp-status-hint")
    }

    /** 运行期加载 Arthas 运行时；失败时回显原因（运行包不可用或 Instrumentation 附加降级）。 */
    fun arthasOn(sender: ProxyCommandSender, arthas: ArthasRuntime) = submitAsync {
        val snapshot = arthas.startRuntime()
        sender.sendLang(
            if (snapshot.available) "command-mcp-arthas-on" else "command-mcp-arthas-on-failed",
            snapshot.message,
        )
    }

    /** 卸载 Arthas 运行时并释放其线程与隔离加载器;幂等判定与结果由运行时回执。 */
    fun arthasOff(sender: ProxyCommandSender, arthas: ArthasRuntime) = submitAsync {
        if (arthas.stopRuntime()) {
            sender.sendLang("command-mcp-arthas-off")
        } else {
            sender.sendLang("command-mcp-arthas-off-not-loaded")
        }
    }
}
