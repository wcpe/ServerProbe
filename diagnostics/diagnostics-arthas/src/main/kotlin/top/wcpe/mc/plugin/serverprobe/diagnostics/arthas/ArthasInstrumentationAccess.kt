package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Instrumentation 的可用来源。 */
enum class InstrumentationSource {
    PREMAIN,
    DYNAMIC_ATTACH,
    UNAVAILABLE,
}

/** 供 Arthas 运行时使用的 Instrumentation 状态。 */
data class ArthasInstrumentationStatus(
    val source: InstrumentationSource,
    val instrumentation: Instrumentation?,
    val message: String,
)

/** 动态附加 Java Agent 的隔离契约，便于测试失败降级与显式重试。 */
interface DynamicAttacher {
    fun attach(agentJar: Path): DynamicAttachResult
}

/** 单次动态附加结果。 */
data class DynamicAttachResult(
    val successful: Boolean,
    val message: String,
)

/**
 * 优先读取 premain 已保存的 Instrumentation；仅在首次缺失时尝试一次动态 attach。
 *
 * 普通状态查询绝不重复自挂载，只有 MCP 的显式重试操作才会再次尝试。
 */
class ArthasInstrumentationAccess(
    private val instrumentationReader: () -> Instrumentation?,
    private val attachers: List<DynamicAttacher> = listOf(ReflectiveSelfAttacher(), SubprocessAttacher()),
) {
    /** 兼容单一 attacher 的构造形态。 */
    constructor(
        instrumentationReader: () -> Instrumentation?,
        attacher: DynamicAttacher,
    ) : this(instrumentationReader, listOf(attacher))

    private var attempted = false

    fun acquire(agentJar: Path): ArthasInstrumentationStatus = synchronized(this) {
        readPremain()?.let { return@synchronized it }
        if (attempted) return@synchronized unavailable("动态附加此前已失败，可通过显式重试再次尝试")
        attempted = true
        attach(agentJar)
    }

    fun retry(agentJar: Path): ArthasInstrumentationStatus = synchronized(this) {
        readPremain()?.let { return@synchronized it }
        attempted = true
        attach(agentJar)
    }

    private fun readPremain(): ArthasInstrumentationStatus? = instrumentationReader()?.let { instrumentation ->
        ArthasInstrumentationStatus(InstrumentationSource.PREMAIN, instrumentation, "已复用 premain Instrumentation")
    }

    private fun attach(agentJar: Path): ArthasInstrumentationStatus {
        // 三级链按序尝试:self-attach(需 allowAttachSelf)→ helper 子进程外置注入(跨进程 attach 不受限);
        // 首个成功且 Instrumentation 已回填的来源即被自动选择,全失败才降级原生诊断。
        val failures = StringBuilder()
        for (attacher in attachers) {
            val result = attacher.attach(agentJar)
            val instrumentation = instrumentationReader()
            if (result.successful && instrumentation != null) {
                return ArthasInstrumentationStatus(
                    InstrumentationSource.DYNAMIC_ATTACH,
                    instrumentation,
                    "动态附加成功(${attacher.javaClass.simpleName})：${result.message}",
                )
            }
            failures.append(attacher.javaClass.simpleName).append(':').append(result.message).append('；')
        }
        unavailable("动态附加失败，已降级：${failures.trimEnd('；')}").let { return it }
    }

    private fun unavailable(message: String) = ArthasInstrumentationStatus(InstrumentationSource.UNAVAILABLE, null, message)
}

/** 反射读取 bootstrap/system ClassLoader 中唯一数据桥保存的 Instrumentation。 */
object ProbeAgentInstrumentationReader {
    private const val bridgeClass = "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgentBridge"

    fun read(): Instrumentation? = sequenceOf<ClassLoader?>(null, ClassLoader.getSystemClassLoader())
        .mapNotNull { loader -> runCatching { Class.forName(bridgeClass, false, loader) }.getOrNull() }
        .mapNotNull { type -> runCatching { type.getMethod("getInstrumentation").invoke(null) as? Instrumentation }.getOrNull() }
        .firstOrNull()
}

/** 使用 JDK Attach API 的反射实现，缺少模块或被 JVM 禁止时返回中文降级结果。 */
class ReflectiveSelfAttacher : DynamicAttacher {

    override fun attach(agentJar: Path): DynamicAttachResult = runCatching {
        require(Files.isRegularFile(agentJar)) { "ServerProbe Agent 文件不存在" }
        val type = Class.forName("com.sun.tools.attach.VirtualMachine")
        val processId = currentProcessId()
        val vm = type.getMethod("attach", String::class.java).invoke(null, processId)
        try {
            type.getMethod("loadAgent", String::class.java).invoke(vm, agentJar.toAbsolutePath().toString())
        } finally {
            type.getMethod("detach").invoke(vm)
        }
        DynamicAttachResult(true, "agentmain 已调用")
    }.getOrElse { failure ->
        DynamicAttachResult(false, rootCause(failure).message ?: "当前 JVM 不支持动态附加")
    }

    private fun currentProcessId(): String = currentProcessIdValue()

    private fun rootCause(error: Throwable): Throwable = when (error) {
        is InvocationTargetException -> error.targetException ?: error
        else -> error
    }
}

/** 当前 JVM 的 PID(Attach API 目标)。 */
internal fun currentProcessIdValue(): String = ManagementFactory.getRuntimeMXBean().name.substringBefore('@')

/** 从当前发行 Jar 的 code source 定位动态附加需要的同一个 Agent 文件。 */
object ServerProbeAgentJarLocator {
    fun locate(anchor: Class<*>): Path? = runCatching {
        anchor.protectionDomain.codeSource.location.toURI()
    }.mapCatching { uri -> Paths.get(uri) }
        .getOrNull()
        ?.takeIf { path -> Files.isRegularFile(path) }
}
