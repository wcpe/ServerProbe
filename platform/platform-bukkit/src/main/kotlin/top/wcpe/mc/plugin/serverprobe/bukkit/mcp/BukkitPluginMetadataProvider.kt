package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import top.wcpe.mc.plugin.serverprobe.core.cpu.PluginClassLoaderRegistry
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpTool
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpToolProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpToolProviderRegistry
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginClassEnumeration
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginClassResolver
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginClassResolverRegistry
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginMeta
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginMetadataProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginMetadataProviderRegistry
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginScopedMcpToolProvider
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.io.File
import java.util.jar.JarFile

/**
 * Bukkit 插件维度元数据与类清单提供者(FR-15)。
 *
 * 在 Bukkit/Paper/Folia 端为 core 的 [PluginScopedMcpToolProvider] 提供两份平台能力:
 * - [PluginMetadataProvider]:经 `PluginManager.getPlugins()` 读取已加载插件的
 *   名称/版本/启用状态/ClassLoader 摘要(`plugin_list` 数据源)。
 * - [PluginClassResolver]:经插件实例 `JavaPlugin.getFile()` 解析 jar 文件枚举类条目
 *   (`plugin_classes` 数据源)。**插件名 → jar 路径必须从 `JavaPlugin.getFile()` 实例解析,
 *   禁止按插件目录名拼路径**(插件目录名可能与插件名不一致)。
 *
 * 生命周期:作为 IOC [Service] 由容器实例化,依赖注入完成后([PostConstruct])
 * 把元数据实现与类解析实现注册进 core 契约,并把工具面合并进 [McpToolProviderRegistry]
 * (工具目录与 core 的 [PluginScopedMcpToolProvider] 相同,由它统一声明,本类按
 * [McpToolProviderRegistry.register] 的"以首次注册为准"语义在 [PostConstruct] 阶段
 * 兜底注册自身,防止 core provider 缺席时工具缺失;core provider 先注册时自动让位)。
 * 插件卸载时([PreDestroy])撤销注册。
 */
@Service
@PlatformSide(Platform.BUKKIT)
class BukkitPluginMetadataProvider : PluginMetadataProvider, PluginClassResolver, McpToolProvider {

    /** 插件 ClassLoader 注册表(core),用于组装 ClassLoader 摘要。 */
    @Inject
    lateinit var classLoaderRegistry: PluginClassLoaderRegistry

    /** 插件元数据注册表(core 契约),启动期注册本实现。 */
    @Inject
    lateinit var metadataRegistry: PluginMetadataProviderRegistry

    /** 插件类解析注册表(core 契约),启动期注册本实现。 */
    @Inject
    lateinit var classResolverRegistry: PluginClassResolverRegistry

    /** MCP 扩展工具注册表(FR-23),启动期兜底注册工具面。 */
    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    /**
     * 依赖注入完成后注册两份数据源到 core 契约,并兜底注册工具面。
     *
     * 工具目录由 core 的 [PluginScopedMcpToolProvider] 作为 [McpToolProvider]
     * 统一注册;本类在 [PostConstruct] 阶段以 putIfAbsent 语义兜底,避免 core provider
     * 未就绪时工具缺失(重复注册无害,registry 保留首个)。
     */
    @PostConstruct
    fun register() {
        if (Platform.CURRENT != Platform.BUKKIT) return
        metadataRegistry.register(this)
        classResolverRegistry.register(this)
        mcpToolProviderRegistry.register(this)
        ProbeLogger.info("Bukkit 插件维度诊断数据源已注册(元数据 + 类清单 + 工具面)")
    }

    /** 插件卸载时撤销已注册的数据源与工具面,避免关闭后的组件继续被查询。 */
    @PreDestroy
    fun unregister() {
        metadataRegistry.unregister(this)
        classResolverRegistry.unregister(this)
        mcpToolProviderRegistry.unregister(this)
    }

    /** 工具目录与 core 的 [PluginScopedMcpToolProvider] 保持一致（同名去重后路由到实际实现）。 */
    override fun tools(): List<McpTool> = FALLBACK_TOOLS

    /** 工具调用由 core 的 [PluginScopedMcpToolProvider] 提供；本类仅兜底占位，路由到本类时结构化降级而非抛错。 */
    override fun call(name: String, arguments: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject?): Map<String, Any?> =
        linkedMapOf("available" to false, "reason" to "插件维度诊断工具未就绪，请稍后重试")

    override fun list(): List<PluginMeta> = Bukkit.getPluginManager().plugins.map { plugin ->
        PluginMeta(
            name = plugin.name,
            version = plugin.description?.version ?: "",
            enabled = plugin.isEnabled,
            loaderSummary = loaderSummary(plugin.name),
        )
    }

    /**
     * 枚举目标插件 jar 内的可解析类清单(有界,默认 200 条)。
     *
     * 经插件实例 `JavaPlugin.getFile()` 解析 jar(禁止字符串拼接插件目录路径);
     * 逐条目以 `loadClass(name, false)` 验证可解析性(不初始化类,避免触发静态块)。
     *
     * @param pluginName 插件名。
     * @return 有界枚举结果;jar 缺失/损坏/无权限时降级为 [PluginClassEnumeration.failure]。
     */
    override fun enumerate(pluginName: String): PluginClassEnumeration {
        val plugin = Bukkit.getPluginManager().getPlugin(pluginName) ?: return PluginClassEnumeration.failure("插件未加载: $pluginName")
        val jar = pluginFile(plugin) ?: return PluginClassEnumeration.failure("无法定位插件 jar 文件,请用 arthas `sc` 按包名搜索")
        val loader = pluginLoader(plugin) ?: return PluginClassEnumeration.failure("插件 ClassLoader 不可用,请用 arthas `sc` 按包名搜索")
        return runCatching {
            val classNames = mutableListOf<String>()
            var truncated = false
            JarFile(jar).use { jarFile ->
                jarFile.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { entry -> entry.name.removeSuffix(".class").replace('/', '.') }
                    .forEach { className ->
                        if (classNames.size >= MAX_ENUMERATED_CLASSES) {
                            truncated = true
                            return@use
                        }
                        if (loadable(loader, className)) classNames.add(className)
                    }
            }
            PluginClassEnumeration.success(classNames, truncated)
        }.getOrElse { error ->
            ProbeLogger.warn("枚举插件类清单失败($pluginName): ${error.javaClass.simpleName}")
            PluginClassEnumeration.failure("枚举插件类清单失败: ${error.javaClass.simpleName},请用 arthas `sc` 按包名搜索")
        }
    }

    /**
     * 从插件实例解析 jar 文件。
     *
     * 语义上即 `JavaPlugin.getFile()`:jar 路径必须来自插件实例本身,禁止按插件目录名拼路径
     * (目录名可能与插件名不一致)。不同 Bukkit 版本上 `file` 字段可见性不一,统一经字段反射 +
     * setAccessible 安全读取(同 `classLoader` 字段的手法),兼容全版本;失败返回 null 走降级。
     *
     * @param plugin Bukkit 插件实例。
     * @return 插件 jar 文件;不可用时为 null。
     */
    private fun pluginFile(plugin: Plugin): File? {
        if (plugin !is JavaPlugin) return null
        return runCatching {
            val field = JavaPlugin::class.java.getDeclaredField("file")
            field.isAccessible = true
            field.get(plugin) as File
        }.getOrNull()
    }

    /**
     * 反射取插件 ClassLoader(同 BukkitPluginClassLoaderRegistrar 的手法)。
     *
     * `JavaPlugin.classLoader` 字段在不同版本可见性不一,统一经字段反射 + setAccessible
     * 安全读取;失败即返回 null,不影响整体。
     *
     * @param plugin Bukkit 插件实例。
     * @return 插件的 ClassLoader;不可用时为 null。
     */
    private fun pluginLoader(plugin: Plugin): ClassLoader? {
        if (plugin !is JavaPlugin) return null
        return runCatching {
            val field = JavaPlugin::class.java.getDeclaredField("classLoader")
            field.isAccessible = true
            field.get(plugin) as ClassLoader
        }.getOrNull()
    }

    /**
     * 取插件 ClassLoader 摘要(展示用)。
     *
     * @param pluginName 插件名。
     * @return 摘要文本;未注册时返回"未注册"。
     */
    private fun loaderSummary(pluginName: String): String {
        val loader = classLoaderRegistry.ownerOf(pluginName) ?: return "未注册"
        return loader.javaClass.simpleName.ifEmpty { loader.javaClass.name }
    }

    private companion object {

        /** 类清单枚举有界上限(与 core 对外上限一致,默认 200)。 */
        const val MAX_ENUMERATED_CLASSES = 200

        /** 兜底工具目录：与 core provider 同名，dispatcher 同名去重后保留后注册者（core）。 */
        val FALLBACK_TOOLS = listOf(
            McpTool("plugin_list", "列出全部已加载插件"),
            McpTool("plugin_classes", "按插件枚举可解析类清单"),
            McpTool("plugin_threads", "按插件过滤归属线程栈"),
            McpTool("plugin_cpu", "插件 CPU 归因占比"),
        )
    }
}

/**
 * 用插件 ClassLoader 验证类可解析性(不初始化,避免触发静态块)。
 *
 * @param loader 插件 ClassLoader。
 * @param className 全限定类名。
 * @return 是否可解析。
 */
private fun loadable(loader: ClassLoader, className: String): Boolean = runCatching {
    loader.loadClass(className)
    true
}.getOrDefault(false)
