package top.wcpe.mc.plugin.serverprobe.velocity.mcp

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.proxy.ProxyServer
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.server
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

/** Velocity 的 MCP 控制台命令适配器。 */
@Service
@PlatformSide(Platform.VELOCITY)
class VelocityPlatformControl : PlatformControl {

    @Inject
    lateinit var registry: PlatformControlRegistration

    @PostConstruct
    fun register() {
        if (Platform.CURRENT == Platform.VELOCITY) registry.register(this)
    }

    @PreDestroy
    fun unregister() {
        registry.unregister(this)
    }

    /** 使用 Velocity CommandManager 的异步执行入口，不从 MCP HTTP 线程直接调用命令实现。 */
    override fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult> {
        val result = CompletableFuture<PlatformCommandResult>()
        val proxy = server<ProxyServer>()
        val source = capturingConsole(proxy.consoleCommandSource, request.output)
        proxy.commandManager.executeAsync(source, request.command).whenComplete { accepted, error ->
            if (result.isDone) return@whenComplete
            val output = request.output.snapshot()
            when {
                error != null -> result.complete(PlatformCommandResult.failure("执行 Velocity 控制台命令异常：${error.javaClass.simpleName}", output))
                accepted == true -> result.complete(PlatformCommandResult.success(output))
                else -> result.complete(PlatformCommandResult.failure("控制台命令未被 Velocity 接受", output))
            }
        }
        return result
    }

    /** 命令源保持 ConsoleCommandSource 类型，捕获 Audience 的回显消息。 */
    private fun capturingConsole(delegate: ConsoleCommandSource, output: BoundedCommandOutput): CommandSource =
        Proxy.newProxyInstance(
            ConsoleCommandSource::class.java.classLoader,
            arrayOf(ConsoleCommandSource::class.java),
        ) { _, method, arguments ->
            if (method.name.startsWith(SEND_METHOD_PREFIX)) capture(output, arguments)
            // 委托原始方法的变长参数透传必须使用 spread 展开实际参数。
            @Suppress("SpreadOperator")
            method.invoke(delegate, *(arguments ?: emptyArray()))
        } as CommandSource

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
