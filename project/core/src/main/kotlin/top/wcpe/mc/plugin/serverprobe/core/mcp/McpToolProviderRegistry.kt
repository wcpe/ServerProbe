package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 扩展工具提供者注册表（FR-23 多 provider 组合）。
 *
 * 各 FR（FR-15~22）的平台/诊断模块在启动期把独立 [McpToolProvider] 注册进来，
 * [McpControlPlane.providers] 汇总为 dispatcher 的 provider 列表。
 */
@Service
class McpToolProviderRegistry {

    private val registered = ConcurrentHashMap<String, McpToolProvider>()

    /** 注册一个扩展工具提供者；重复注册同名类时以首次为准。 */
    fun register(provider: McpToolProvider) {
        registered.putIfAbsent(provider.javaClass.name, provider)
    }

    /** 注销（插件卸载时）。 */
    fun unregister(provider: McpToolProvider) {
        registered.remove(provider.javaClass.name, provider)
    }

    /** 当前全部扩展 provider（快照副本）。 */
    fun providers(): List<McpToolProvider> = registered.values.toList()
}
