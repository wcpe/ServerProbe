package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
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

/** Bukkit、Paper 与 Folia 的 MCP 控制台命令适配器。 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitPlatformControl : PlatformControl {

    @Inject
    lateinit var registry: PlatformControlRegistration

    /** 平台就绪后注册，避免 core 反向依赖 Bukkit。 */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT == Platform.BUKKIT) registry.register(this)
    }

    /** 插件卸载时撤销当前实现，禁止关闭后的命令继续进入 Bukkit。 */
    @PreDestroy
    fun unregister() {
        registry.unregister(this)
    }

    /** 通过 TabooLib 正确切至 Bukkit 主线程，Folia 由其平台调度适配处理。 */
    override fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult> {
        val future = CompletableFuture<PlatformCommandResult>()
        submit(async = false) {
            if (future.isDone) return@submit
            future.complete(dispatch(request))
        }
        return future
    }

    /** 在服务器线程执行命令，同时以代理控制台收集有界回显。 */
    private fun dispatch(request: PlatformCommandRequest): PlatformCommandResult = runCatching {
        val accepted = Bukkit.dispatchCommand(capturingConsole(request.output), request.command)
        val output = request.output.snapshot()
        if (accepted) PlatformCommandResult.success(output)
        else PlatformCommandResult.failure("控制台命令未被服务器接受", output)
    }.getOrElse { error ->
        PlatformCommandResult.failure("执行 Bukkit 控制台命令异常：${error.javaClass.simpleName}", request.output.snapshot())
    }

    /** 代理保持 ConsoleCommandSender 类型，既转发原控制台又收集命令回显。 */
    private fun capturingConsole(output: BoundedCommandOutput): CommandSender {
        val delegate = Bukkit.getConsoleSender()
        return Proxy.newProxyInstance(
            ConsoleCommandSender::class.java.classLoader,
            arrayOf(ConsoleCommandSender::class.java),
        ) { _, method, arguments ->
            if (method.name.startsWith(SEND_METHOD_PREFIX)) capture(output, arguments)
            // 委托原始方法的变长参数透传必须使用 spread 展开实际参数。
            @Suppress("SpreadOperator")
            method.invoke(delegate, *(arguments ?: emptyArray()))
        } as CommandSender
    }

    /** 兼容字符串、组件数组等各版本控制台消息重载。 */
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
