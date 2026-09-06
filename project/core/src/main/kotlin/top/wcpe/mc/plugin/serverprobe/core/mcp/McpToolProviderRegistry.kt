package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.taboolib.ioc.annotation.Service
import java.util.Collections
import java.util.LinkedHashMap

/**
 * MCP 扩展工具提供者注册表（FR-23 多 provider 组合）。
 *
 * 各 FR（FR-15~22）的平台/诊断模块在启动期把独立 [McpToolProvider] 注册进来，
 * [McpControlPlane.providers] 汇总为 dispatcher 的 provider 列表。
 *
 * 内部用同步 [LinkedHashMap] 保持**注册顺序**——dispatcher 按工具名 `toMap()` 时
 * 同名工具由后注册者覆盖、`tools/list` 的 `groupBy.last()` 取最后注册者，注册序
 * 决定路由归属，必须确定而非依赖 CHM 的随机迭代序。
 */
@Service
class McpToolProviderRegistry {

    private val registered = Collections.synchronizedMap(LinkedHashMap<String, McpToolProvider>())

    /** 注册一个扩展工具提供者；重复注册同名类时以首次为准。 */
    fun register(provider: McpToolProvider) {
        registered.putIfAbsent(provider.javaClass.name, provider)
    }

    /** 注销（插件卸载时）。 */
    fun unregister(provider: McpToolProvider) {
        registered.remove(provider.javaClass.name, provider)
    }

    /** 当前全部扩展 provider（按注册序的快照副本）。 */
    fun providers(): List<McpToolProvider> = synchronized(registered) { registered.values.toList() }
}
