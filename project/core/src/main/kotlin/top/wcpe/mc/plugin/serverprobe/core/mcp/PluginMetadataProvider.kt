package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 插件元数据契约(FR-15)。
 *
 * 由各平台模块在运行期提供"已加载插件"的清单元数据;core 的
 * [PluginScopedMcpToolProvider] 只依赖本契约,不触碰任何平台 API。
 * 代理端(BungeeCord/Velocity)无插件 ClassLoader 语义,不注册实现即可,
 * `plugin_list` 会返回空列表并标注平台不支持。
 */
interface PluginMetadataProvider {

    /**
     * 返回当前已加载插件清单。
     *
     * @return 已加载插件的元数据列表;平台不支持时返回空列表。
     */
    fun list(): List<PluginMeta>
}

/**
 * 单个已加载插件的元数据(只读快照)。
 *
 * @property name 插件名(与 ClassLoader 注册表键一致)。
 * @property version 插件版本(经 `PluginDescription` 读取;缺失时为空串)。
 * @property enabled 是否处于启用状态。
 * @property loaderSummary 插件 ClassLoader 的摘要描述(如类加载器类型),用于对外展示。
 */
data class PluginMeta(
    val name: String,
    val version: String,
    val enabled: Boolean,
    val loaderSummary: String,
)

/** 平台模块注册插件元数据实现的装配点,core 不反向依赖平台。 */
@Service
class PluginMetadataProviderRegistry {

    /** 手动注册存储；required=false 声明仅为满足 IoC 静态分析（该字段不经容器注入）。 */
    @Inject(required = false)
    private var provider: PluginMetadataProvider? = null

    /** 注册当前平台唯一的元数据提供者;重复注册时保留首个。 */
    fun register(provider: PluginMetadataProvider) {
        if (this.provider == null) this.provider = provider
    }

    /** 注销当前提供者(平台卸载时);仅当仍是给定实例时生效。 */
    fun unregister(provider: PluginMetadataProvider) {
        if (this.provider === provider) this.provider = null
    }

    /** 当前已注册的提供者;平台不支持时为 null。 */
    fun current(): PluginMetadataProvider? = provider
}
