package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * Arthas 运行时的运行期启停契约（FR-24）。
 *
 * 与 [ArthasControl] 的职责边界：后者负责**已加载运行时**内的任务提交/查询/取消；本接口负责
 * 【运行时自身的加载与卸载】——解包 Arthas 闭包、获取 Instrumentation、注册诊断控制器，或反向卸载。
 * 二者分离是因为端点级开关（[McpControlPlane]）不依赖 Arthas，只有深度诊断才需要付加载代价。
 *
 * core 不引用任何 Arthas 类型（ADR-0025），故本接口只暴露跨模块稳定形态：启停结果复用
 * [ArthasInstrumentationSnapshot]（其 `message` 为可直接回显的中文说明）。
 */
interface ArthasRuntime {

    /**
     * 运行期加载 Arthas 运行时；已加载时为幂等空操作。
     *
     * **幂等判定由实现内部完成**，调用方不应先查 [runtimeReady] 再调用：并发调用若都在预检时看到
     * "未加载"，后到的一次会落到已加载分支并回执上一次的结果，造成重复开启的误报。实现应在已加载时
     * 返回带"无需重复开启"说明的成功快照。
     *
     * @return 加载后的 Instrumentation 状态快照；失败或未注册实现时以 `available=false` 的说明返回，不抛异常。
     */
    fun startRuntime(): ArthasInstrumentationSnapshot

    /**
     * 运行期卸载 Arthas 运行时并释放其线程与隔离加载器；未加载时为幂等空操作。
     *
     * 返回本次是否**确实**执行了卸载，供调用方区分"已卸载"与"本就未加载"。与 [startRuntime] 同理，
     * 判定必须由实现内部完成：调用方先查 [runtimeReady] 再卸载时，多条排队命令会都在检查时看到
     * "已加载"，导致后到的几条把空操作回报成卸载成功。
     *
     * @return 本次是否确实卸载（此前未加载返回 false）。
     */
    fun stopRuntime(): Boolean

    /** 运行时当前是否已加载。 */
    val runtimeReady: Boolean
}

/** [ArthasRuntime] 的生命周期注册契约；诊断模块不依赖具体 registry。 */
interface ArthasRuntimeRegistration {
    fun register(runtime: ArthasRuntime)
    fun unregister(runtime: ArthasRuntime)
}

/**
 * core 与 diagnostics 模块之间的运行期装配点（FR-24）。
 *
 * 沿用 [ArthasControlRegistry] / [McpArtifactWorkspaceRegistry] 的 AtomicReference 范式：诊断模块于
 * `@PostEnable` 自注册，命令层经本注册表按需启停，core 编译期不依赖具体实现。
 *
 * 未注册时全部方法降级为安全失败（不抛异常）：部分平台可能不装配 Arthas 诊断模块，此时命令仍须可执行。
 */
@Service
class ArthasRuntimeRegistry : ArthasRuntime, ArthasRuntimeRegistration {

    private val runtimeRef = AtomicReference<ArthasRuntime?>(null)

    override fun register(runtime: ArthasRuntime) {
        val previous = runtimeRef.getAndSet(runtime)
        if (previous != null && previous !== runtime) {
            ProbeLogger.warn("Arthas 运行期控制器被重复注册，已替换为：${runtime.javaClass.name}")
        }
    }

    override fun unregister(runtime: ArthasRuntime) {
        runtimeRef.compareAndSet(runtime, null)
    }

    override fun startRuntime(): ArthasInstrumentationSnapshot = runtimeRef.get()
        ?.startRuntime()
        ?: ArthasInstrumentationSnapshot(false, RUNTIME_UNAVAILABLE_SOURCE, "当前未注册 Arthas 运行期控制器，无法在运行期加载 Arthas")

    override fun stopRuntime(): Boolean = runtimeRef.get()?.stopRuntime() ?: false

    override val runtimeReady: Boolean
        get() = runtimeRef.get()?.runtimeReady == true

    private companion object {
        /** 未注册实现时的来源标识，与 [ArthasControlRegistry] 的降级语义保持一致。 */
        const val RUNTIME_UNAVAILABLE_SOURCE = "UNAVAILABLE"
    }
}
