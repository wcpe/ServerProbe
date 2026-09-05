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

    private fun controlOrNull(): ArthasControl? = controlRef.get()

    private fun unavailableSnapshot(taskId: String = ""): ArthasTaskSnapshot =
        ArthasTaskSnapshot(taskId, ArthasTaskState.FAILED, "当前未注册 Arthas 诊断控制器")
}
