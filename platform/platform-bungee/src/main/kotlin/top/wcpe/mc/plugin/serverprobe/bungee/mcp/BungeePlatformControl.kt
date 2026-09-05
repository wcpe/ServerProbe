package top.wcpe.mc.plugin.serverprobe.bungee.mcp

import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.ProxyServer
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.core.mcp.BoundedCommandOutput
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformCommandRequest
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformCommandResult
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControl
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControlRegistration
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

/** BungeeCord 的 MCP 控制台命令适配器。 */
@Service
@PlatformSide(Platform.BUNGEE)
class BungeePlatformControl : PlatformControl {

    @Inject
    lateinit var registry: PlatformControlRegistration

    @PostConstruct
    fun register() {
        if (Platform.CURRENT == Platform.BUNGEE) registry.register(this)
    }

    @PreDestroy
    fun unregister() {
        registry.unregister(this)
    }

    /** 经 TabooLib 的 Bungee 调度器投递，避免 HTTP 工作线程直接触碰代理命令管理器。 */
    override fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult> {
        val future = CompletableFuture<PlatformCommandResult>()
        submit(async = false) {
            if (future.isDone) return@submit
            future.complete(dispatch(request))
        }
        return future
    }

    private fun dispatch(request: PlatformCommandRequest): PlatformCommandResult = runCatching {
        val proxy = ProxyServer.getInstance()
        val accepted = proxy.pluginManager.dispatchCommand(capturingConsole(proxy.getConsole(), request.output), request.command)
        val output = request.output.snapshot()
        if (accepted) PlatformCommandResult.success(output)
        else PlatformCommandResult.failure("控制台命令未被代理接受", output)
    }.getOrElse { error ->
        PlatformCommandResult.failure("执行 BungeeCord 控制台命令异常：${error.javaClass.simpleName}", request.output.snapshot())
    }

    /** 以 CommandSender 接口代理截获命令回显，同时保留真实控制台行为。 */
    private fun capturingConsole(delegate: CommandSender, output: BoundedCommandOutput): CommandSender =
        Proxy.newProxyInstance(
            CommandSender::class.java.classLoader,
            arrayOf(CommandSender::class.java),
        ) { _, method, arguments ->
            if (method.name.startsWith(SEND_METHOD_PREFIX)) capture(output, arguments)
            // 委托原始方法的变长参数透传必须使用 spread 展开实际参数。
            @Suppress("SpreadOperator")
            method.invoke(delegate, *(arguments ?: emptyArray()))
        } as CommandSender

    private fun capture(output: BoundedCommandOutput, arguments: Array<out Any?>?) {
        arguments.orEmpty().forEach { argument ->
            if (argument is Array<*>) argument.forEach { output.append(it.toString()) }
            else output.append(argument.toString())
        }
    }

    private companion object {
        private const val SEND_METHOD_PREFIX = "send"
    }
}
