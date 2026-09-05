package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** 平台控制台命令的无平台 API 契约，由各平台模块在运行期实现。 */
interface PlatformControl {

    /** 在平台规定的调度线程执行命令，并异步返回受限输出。 */
    fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult>
}

/** 平台控制器的生命周期注册契约，平台模块不依赖具体 registry。 */
interface PlatformControlRegistration {
    fun register(control: PlatformControl)
    fun unregister(control: PlatformControl)
}

/** 单次平台命令及其输出收集器。 */
data class PlatformCommandRequest(
    val command: String,
    val output: BoundedCommandOutput,
)

/** 平台命令的可序列化结果。 */
data class PlatformCommandResult(
    val success: Boolean,
    val output: String,
    val outputTruncated: Boolean,
    val error: String? = null,
) {

    companion object {

        /** 根据已收集的输出构造成功结果。 */
        fun success(output: CapturedCommandOutput): PlatformCommandResult =
            PlatformCommandResult(true, output.text, output.truncated)

        /** 根据已收集的输出构造失败结果。 */
        fun failure(error: String, output: CapturedCommandOutput = CapturedCommandOutput("", false)): PlatformCommandResult =
            PlatformCommandResult(false, output.text, output.truncated, error)
    }
}

/** 有界控制台输出快照。 */
data class CapturedCommandOutput(val text: String, val truncated: Boolean)

/** 线程安全的控制台输出收集器，避免远程命令无限占用内存。 */
class BoundedCommandOutput(private val maxChars: Int) {

    private val text = StringBuilder()
    private var truncated = false

    init {
        require(maxChars > 0) { "控制台输出上限必须大于零" }
    }

    /** 追加一条控制台消息，超出上限时保留前缀并标记截断。 */
    @Synchronized
    fun append(message: String) {
        val remaining = maxChars - text.length
        if (remaining <= 0) {
            truncated = true
            return
        }
        if (message.length > remaining) {
            text.append(message, 0, remaining)
            truncated = true
            return
        }
        text.append(message)
    }

    /** 取得当前不可变快照。 */
    @Synchronized
    fun snapshot(): CapturedCommandOutput = CapturedCommandOutput(text.toString(), truncated)
}

/** core 与各平台控制器之间的运行期装配点，保持依赖单向。 */
@Service
class PlatformControlRegistry : PlatformControl, PlatformControlRegistration {

    private val controlRef = AtomicReference<PlatformControl?>(null)

    /** 注册当前运行平台唯一的控制器。 */
    override fun register(control: PlatformControl) {
        val previous = controlRef.getAndSet(control)
        if (previous != null && previous !== control) {
            ProbeLogger.warn("平台控制器被重复注册，已替换为：${control.javaClass.name}")
        }
    }

    /** 仅当当前注册器仍是给定实例时注销。 */
    override fun unregister(control: PlatformControl) {
        controlRef.compareAndSet(control, null)
    }

    /** 将 MCP 命令委托给已注册的平台控制器。 */
    override fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult> {
        val control = controlRef.get()
            ?: return CompletableFuture.completedFuture(PlatformCommandResult.failure("当前平台未注册控制台命令执行器"))
        return control.execute(request)
    }
}
