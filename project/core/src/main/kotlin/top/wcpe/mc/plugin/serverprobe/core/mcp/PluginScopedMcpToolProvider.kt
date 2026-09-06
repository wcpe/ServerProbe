package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.api.model.PluginCpuMetric
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.cpu.CpuAttributionSampler
import top.wcpe.mc.plugin.serverprobe.core.cpu.PluginClassLoaderRegistry
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import top.wcpe.taboolib.ioc.annotation.PreDestroy

/**
 * MCP 插件维度诊断入口(FR-15)。
 *
 * 提供 `plugin_list` / `plugin_classes` / `plugin_threads` / `plugin_cpu` 四个只读工具,
 * 让外部 agent 按"列出插件 → 选中插件 → 看类/线程/CPU 热点"的路径排查单个插件的 bug,
 * 再以其类名转调 Arthas 深挖。
 *
 * 依赖边界:
 * - [PluginMetadataProviderRegistry]:插件清单元数据(平台模块注册,代理端缺省)。
 * - [PluginClassLoaderRegistry]:插件名集合校验与线程栈帧归属过滤(FR-2.6 复用)。
 * - [PluginClassResolverRegistry]:插件 jar 条目枚举(平台模块注册,代理端缺省)。
 * - CPU 归因复用 [CpuAttributionSampler.snapshot],不重复采样。
 *
 * 生命周期:作为 IOC [Service] 由容器实例化;[register] 在依赖注入完成后
 * ([PostConstruct])把自身注册进 [McpToolProviderRegistry],供
 * [McpControlPlane.providers] 汇总进 dispatcher(FR-23 多 provider 组合)。
 */
@Service
class PluginScopedMcpToolProvider : McpToolProvider {

    /** 插件 ClassLoader 注册表(core,FR-2.6),提供插件名集合与栈帧归属解析。 */
    @Inject
    lateinit var classLoaderRegistry: PluginClassLoaderRegistry

    /** 插件元数据注册表(平台模块在启动期注册实现)。 */
    @Inject
    lateinit var metadataRegistry: PluginMetadataProviderRegistry

    /** 插件类清单解析注册表(平台模块在启动期注册实现)。 */
    @Inject
    lateinit var classResolverRegistry: PluginClassResolverRegistry

    /** MCP 扩展工具注册表(FR-23),初始化完成后自注册。 */
    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    /** CPU 归因采样器(core,FR-2.6),提供窗口聚合快照。 */
    @Inject
    lateinit var cpuSampler: CpuAttributionSampler

    /** CPU 归因是否启用的取值源;默认走配置。 */
    var cpuEnabled: () -> Boolean = { ProbeConfig.cpuEnabled() }

    /** CPU 归因窗口快照;默认复用注入的 [CpuAttributionSampler]。 */
    var cpuSnapshot: () -> List<PluginCpuMetric> = { cpuSampler.snapshot(MAX_CPU_SAMPLES) }

    /** 线程采样源;默认复用 [NativeThreadDiagnostics.dump] 的线程信息。 */
    var threadSource: () -> List<PluginThreadSample> = {
        @Suppress("UNCHECKED_CAST")
        (NativeThreadDiagnostics().dump()["threads"] as? List<Map<String, Any?>>).orEmpty().map { row ->
            PluginThreadSample(
                id = row["id"] as? Long ?: -1L,
                name = row["name"] as? String ?: "",
                state = row["state"] as? String ?: "",
                stack = emptyList(),
                cpuTimeNanos = 0L,
            )
        }
    }

    /**
     * 依赖注入完成后把自身注册进 MCP 工具注册表。
     *
     * 采用 [PostConstruct] 而非构造期注册,确保 [mcpToolProviderRegistry] 已注入完毕。
     */
    @PostConstruct
    fun register() {
        mcpToolProviderRegistry.register(this)
        ProbeLogger.debug("插件维度诊断工具已注册")
    }

    /** 插件卸载时撤销注册,避免关闭后的工具面继续出现在 tools/list。 */
    @PreDestroy
    fun unregister() {
        mcpToolProviderRegistry.unregister(this)
    }

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = when (name) {
        PLUGIN_LIST -> pluginList()
        PLUGIN_CLASSES -> pluginClasses(arguments)
        PLUGIN_THREADS -> pluginThreads(arguments)
        PLUGIN_CPU -> pluginCpu(arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    /** `plugin_list`:返回平台提供的已加载插件清单;无实现时标注平台不支持。 */
    private fun pluginList(): Map<String, Any?> {
        val provider = metadataRegistry.current()
        if (provider == null) {
            return linkedMapOf(
                "available" to false,
                "platformUnsupported" to true,
                "plugins" to emptyList<PluginMeta>(),
            )
        }
        return linkedMapOf(
            "available" to true,
            "platformUnsupported" to false,
            "plugins" to provider.list().map { meta ->
                linkedMapOf(
                    "name" to meta.name,
                    "version" to meta.version,
                    "enabled" to meta.enabled,
                    "loaderSummary" to meta.loaderSummary,
                )
            },
        )
    }

    /** `plugin_classes`:枚举目标插件 jar 的可解析类清单(有界,默认 200)。 */
    private fun pluginClasses(arguments: JsonObject?): Map<String, Any?> {
        val plugin = requiredRegisteredPlugin(arguments)
        val enumeration = classResolverRegistry.current()?.enumerate(plugin) ?: return enumFallback()
        if (!enumeration.enumerable) {
            return linkedMapOf(
                "available" to false,
                "reason" to (enumeration.reason ?: "无法枚举该类清单,请用 arthas `sc` 按包名搜索"),
            )
        }
        val classNames = enumeration.classNames.take(MAX_PLUGIN_CLASSES)
        return linkedMapOf(
            "available" to true,
            "classes" to classNames,
            "count" to classNames.size,
            "truncated" to (enumeration.truncated || enumeration.classNames.size > MAX_PLUGIN_CLASSES),
        )
    }

    /** `plugin_threads`:当前线程栈中归属于目标插件的线程(排序 + 有界)。 */
    private fun pluginThreads(arguments: JsonObject?): Map<String, Any?> {
        val plugin = requiredRegisteredPlugin(arguments)
        val rows = threadSource()
            .map { sample -> filterOwned(sample, plugin) }
            .filter { it.hitFrames > 0 }
            .sortedWith(compareByDescending<PluginThreadRow> { it.hitFrames }.thenByDescending { it.cpuTimeNanos })
            .take(NativeThreadDiagnostics.MAX_THREADS)
            .map { it.toMap() }
        return linkedMapOf("threads" to rows)
    }

    /** `plugin_cpu`:窗口内目标插件的 CPU 归因样本;未启用或零样本时明确降级。 */
    private fun pluginCpu(arguments: JsonObject?): Map<String, Any?> {
        val plugin = requiredRegisteredPlugin(arguments)
        if (!cpuEnabled()) {
            return linkedMapOf(
                "available" to false,
                "reason" to "运行期 CPU 归因未启用(cpu.enabled=false),请开启后等待采样窗口积累",
            )
        }
        val samples = cpuSnapshot().filter { it.plugin == plugin }
        return linkedMapOf(
            "available" to true,
            "samples" to samples.map { sample ->
                linkedMapOf(
                    "plugin" to sample.plugin,
                    "sampleCount" to sample.sampleCount,
                    "percent" to sample.percent,
                )
            },
            "zeroSamples" to samples.isEmpty(),
        )
    }

    /**
     * 按归属过滤单个线程样本:任一栈帧类名归属目标插件即保留整线程(含未归属的公共帧)。
     *
     * @param sample 线程样本。
     * @param plugin 目标插件名。
     * @return 命中数与有界(64 帧)栈信息;未命中时 [PluginThreadRow.hitFrames] 为 0。
     */
    private fun filterOwned(sample: PluginThreadSample, plugin: String): PluginThreadRow {
        val frames = sample.stack.take(MAX_THREAD_STACK_FRAMES)
        val hit = frames.count { classLoaderRegistry.ownerOf(it.className) == plugin }
        return PluginThreadRow(
            id = sample.id,
            name = sample.name,
            state = sample.state,
            hitFrames = hit,
            cpuTimeNanos = sample.cpuTimeNanos,
            stack = frames.map(StackTraceElement::toString),
        )
    }

    /**
     * 校验插件名入参:必须非空且存在于已注册 ClassLoader 集合,否则抛结构化错误。
     *
     * @param arguments MCP 入参。
     * @return 校验通过的插件名。
     */
    private fun requiredRegisteredPlugin(arguments: JsonObject?): String {
        val plugin = arguments?.getString("plugin")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("缺少插件名参数 plugin")
        require(plugin in classLoaderRegistry.pluginNames()) { "未知插件: $plugin(请先用 plugin_list 确认已加载的插件名)" }
        return plugin
    }

    /** 无类解析实现或无法枚举时的一致降级说明。 */
    private fun enumFallback(): Map<String, Any?> = linkedMapOf(
        "available" to false,
        "reason" to "当前平台不支持枚举插件类清单,请用 arthas `sc` 按包名搜索",
    )

    private companion object {
        const val PLUGIN_LIST = "plugin_list"
        const val PLUGIN_CLASSES = "plugin_classes"
        const val PLUGIN_THREADS = "plugin_threads"
        const val PLUGIN_CPU = "plugin_cpu"

        /** 类清单对外有界上限(默认 200,可截断)。 */
        const val MAX_PLUGIN_CLASSES = 200

        /** 单线程最多保留的栈帧数(与 thread_dump 对齐)。 */
        const val MAX_THREAD_STACK_FRAMES = 64

        /** CPU 归因快照 Top-N 条数。 */
        const val MAX_CPU_SAMPLES = 50

        private val TOOLS = listOf(
            McpTool(PLUGIN_LIST, "列出全部已加载插件(名称/版本/启用状态/ClassLoader 摘要)", workflow = "同步调用", outputFields = mapOf(
                "available" to "是否可用", "platformUnsupported" to "平台是否不支持", "plugins" to "插件列表(name/version/enabled/loaderSummary)",
            )),
            McpTool(PLUGIN_CLASSES, "枚举指定插件可解析的代表性类清单(有界)", inputSchema = mapOf(
                "plugin" to mapOf("type" to "string", "description" to "插件名(须已加载,可用 plugin_list 查询)"),
            ), usageExample = "{\"plugin\":\"<插件名>\"}", workflow = "同步调用", outputFields = mapOf(
                "available" to "是否可枚举", "classes" to "可解析类名列表", "count" to "条目数", "truncated" to "是否截断", "reason" to "降级原因",
            )),
            McpTool(PLUGIN_THREADS, "读取线程栈中归属于指定插件的线程(按命中帧数/CPU 排序)", inputSchema = mapOf(
                "plugin" to mapOf("type" to "string", "description" to "插件名(须已加载)"),
            ), usageExample = "{\"plugin\":\"<插件名>\"}", workflow = "同步调用", outputFields = mapOf(
                "threads" to "命中线程列表(id/name/state/hitFrames/stack)",
            )),
            McpTool(PLUGIN_CPU, "读取指定插件的 CPU 归因窗口样本与占比", inputSchema = mapOf(
                "plugin" to mapOf("type" to "string", "description" to "插件名(须已加载)"),
            ), usageExample = "{\"plugin\":\"<插件名>\"}", workflow = "同步调用", outputFields = mapOf(
                "available" to "是否启用归因", "samples" to "样本列表(plugin/sampleCount/percent)", "zeroSamples" to "窗口内是否零样本", "reason" to "降级原因",
            )),
        )
    }
}

/**
 * 单个线程的采样快照(供测试替换与线程归属过滤复用)。
 *
 * @property id 线程 id。
 * @property name 线程名。
 * @property state 线程状态。
 * @property stack 栈帧(按自顶向下顺序)。
 * @property cpuTimeNanos 线程 CPU 时间(纳秒),用于同命中数下的排序。
 */
data class PluginThreadSample(
    val id: Long,
    val name: String,
    val state: String,
    val stack: List<StackTraceElement>,
    val cpuTimeNanos: Long,
)

/**
 * 归属过滤后的单线程结果(内部传递用)。
 *
 * @property id 线程 id。
 * @property name 线程名。
 * @property state 线程状态。
 * @property hitFrames 命中目标插件的栈帧数。
 * @property cpuTimeNanos 线程 CPU 时间(纳秒)。
 * @property stack 保留的全部栈帧(有界)。
 */
data class PluginThreadRow(
    val id: Long,
    val name: String,
    val state: String,
    val hitFrames: Int,
    val cpuTimeNanos: Long,
    val stack: List<String>,
) {
    /** 转换为对外 JSON 结构。 */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "state" to state,
        "hitFrames" to hitFrames,
        "cpuTimeNanos" to cpuTimeNanos,
        "stack" to stack,
    )
}

/**
 * 插件 jar 条目枚举的契约(FR-15)。
 *
 * 平台模块在运行期把"插件 jar 内类条目 → 可解析类名"的能力经
 * [PluginClassResolverRegistry] 注册;core 只依赖本契约与结果模型。
 */
interface PluginClassResolver {

    /**
     * 枚举目标插件的可解析类清单。
     *
     * @param pluginName 插件名。
     * @return 枚举结果;无法枚举时 [PluginClassEnumeration.enumerable] 为 false。
     */
    fun enumerate(pluginName: String): PluginClassEnumeration
}

/**
 * 插件类枚举结果(有界)。
 *
 * @property enumerable 是否可枚举(为 false 时 [reason] 说明降级原因)。
 * @property classNames 可解析类名清单(不超过 [PluginScopedMcpToolProvider.MAX_PLUGIN_CLASSES] 的对外上限)。
 * @property truncated 是否因上限截断。
 * @property reason 降级原因;成功时为空。
 */
data class PluginClassEnumeration(
    val enumerable: Boolean,
    val classNames: List<String> = emptyList(),
    val truncated: Boolean = false,
    val reason: String? = null,
) {
    companion object {
        /** 构造"成功枚举"结果。 */
        fun success(classNames: List<String>, truncated: Boolean) = PluginClassEnumeration(true, classNames, truncated)
        /** 构造"无法枚举"降级结果。 */
        fun failure(reason: String) = PluginClassEnumeration(false, reason = reason)
    }
}

/** 平台模块注册插件类解析实现的装配点,core 不反向依赖平台。 */
@Service
class PluginClassResolverRegistry {

    /** 手动注册存储；required=false 声明仅为满足 IoC 静态分析（该字段不经容器注入）。 */
    @Inject(required = false)
    private var resolver: PluginClassResolver? = null

    /** 注册当前平台唯一的类解析实现;重复注册时保留首个。 */
    fun register(resolver: PluginClassResolver) {
        if (this.resolver == null) this.resolver = resolver
    }

    /** 注销当前解析实现(平台卸载时);仅当仍是给定实例时生效。 */
    fun unregister(resolver: PluginClassResolver) {
        if (this.resolver === resolver) this.resolver = null
    }

    /** 当前已注册的解析实现;平台不支持时为 null。 */
    fun current(): PluginClassResolver? = resolver
}
