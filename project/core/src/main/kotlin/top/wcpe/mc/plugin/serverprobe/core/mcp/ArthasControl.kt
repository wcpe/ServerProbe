package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicReference

/** Arthas 任务状态；core 仅保留协议模型，不依赖任何 Arthas 类。 */
enum class ArthasTaskState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}

/** 提交到内嵌 Arthas 的原始命令请求。 */
data class ArthasCommandRequest(val command: String, val timeoutMillis: Long)

/** 可轮询的 Arthas 任务快照。 */
data class ArthasTaskSnapshot(val taskId: String, val state: ArthasTaskState, val message: String)

/** 有界读取的任务输出分片。 */
data class ArthasTaskOutput(val taskId: String, val content: String, val nextOffset: Int, val truncated: Boolean)

/** Instrumentation 的可用状态；仅保留跨模块的稳定字符串契约。 */
data class ArthasInstrumentationSnapshot(val available: Boolean, val source: String, val message: String)

/** 由 diagnostics-arthas 注册的运行期控制器。 */
interface ArthasControl {
    fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot
    fun status(taskId: String): ArthasTaskSnapshot
    fun output(taskId: String, offset: Int): ArthasTaskOutput
    fun cancel(taskId: String): ArthasTaskSnapshot
    fun retryAttach(): ArthasInstrumentationSnapshot = ArthasInstrumentationSnapshot(false, "UNAVAILABLE", "当前未注册 Arthas 诊断控制器")

    /**
     * 读取指定类的当前已加载字节码（补丁前自动备份）。
     *
     * 返回 null 表示备份能力未提供（既有实现未适配本方法），调用方应跳过备份并继续；
     * 返回非 null 但为空数组表示读取失败，调用方应拒绝替换。默认实现返回 null 以保持向后兼容。
     */
    fun dumpClassBytes(className: String): ByteArray? = null

    /**
     * 同步执行一条 Arthas 命令并返回其文本输出。
     *
     * 用于「必须先读到当前状态才能决定下一步」的场景——异步 submit 只能拿到 taskId，
     * 调用方还得轮询 output，对一条短命令而言既麻烦又多一次往返。
     *
     * 与 [submit] 的区别只是调用方式：实现可以复用同一执行器，但**不能**在包内循环上调用
     * （会阻塞心跳），调用方应在异步上下文里用它。
     *
     * 默认实现返回 null（能力未提供），调用方自行降级。
     *
     * @param command 原始命令。
     * @param timeoutMillis 超时毫秒。
     * @return 命令输出文本；不可用或超时时返回 null。
     */
    fun executeSync(command: String, timeoutMillis: Long): String? = null
}

/** Arthas 控制器的生命周期注册契约，平台/诊断模块不依赖具体 registry。 */
interface ArthasControlRegistration {
    fun register(control: ArthasControl)
    fun unregister(control: ArthasControl)
}

/** core 与 diagnostics 模块之间的运行期装配点，避免 core 反向依赖 Arthas。 */
@Service
class ArthasControlRegistry : ArthasControl, ArthasControlRegistration {

    private val controlRef = AtomicReference<ArthasControl?>(null)

    override fun register(control: ArthasControl) {
        val previous = controlRef.getAndSet(control)
        if (previous != null && previous !== control) {
            ProbeLogger.warn("Arthas 诊断控制器被重复注册，已替换为：${control.javaClass.name}")
        }
    }

    override fun unregister(control: ArthasControl) {
        controlRef.compareAndSet(control, null)
    }

    override fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot = controlOrNull()
        ?.submit(request)
        ?: unavailableSnapshot()

    override fun status(taskId: String): ArthasTaskSnapshot = controlOrNull()
        ?.status(taskId)
        ?: unavailableSnapshot(taskId)

    override fun output(taskId: String, offset: Int): ArthasTaskOutput = controlOrNull()
        ?.output(taskId, offset)
        ?: ArthasTaskOutput(taskId, "当前未注册 Arthas 诊断控制器", offset, false)

    override fun cancel(taskId: String): ArthasTaskSnapshot = controlOrNull()
        ?.cancel(taskId)
        ?: unavailableSnapshot(taskId)

    override fun retryAttach(): ArthasInstrumentationSnapshot = controlOrNull()
        ?.retryAttach()
        ?: ArthasInstrumentationSnapshot(false, "UNAVAILABLE", "当前未注册 Arthas 诊断控制器")

    /** 转发补丁前备份字节码读取；未注册控制器时返回 null（能力缺失，调用方跳过备份继续）。 */
    override fun dumpClassBytes(className: String): ByteArray? = controlOrNull()?.dumpClassBytes(className)

    private fun controlOrNull(): ArthasControl? = controlRef.get()

    private fun unavailableSnapshot(taskId: String = ""): ArthasTaskSnapshot =
        ArthasTaskSnapshot(taskId, ArthasTaskState.FAILED, "当前未注册 Arthas 诊断控制器")
}
