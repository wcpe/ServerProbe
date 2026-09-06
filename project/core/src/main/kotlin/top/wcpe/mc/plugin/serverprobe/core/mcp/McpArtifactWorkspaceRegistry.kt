package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP 工件工作区的运行期装配点（FR-18）。
 *
 * 工件目录由控制面按数据目录动态创建，无法作为静态 IOC Bean 注入；
 * 本注册表仿照 [ArthasControlRegistry] 的 AtomicReference 模式，把工作区实例
 * 以"控制面注册 → 扩展 provider 委托访问"的单向依赖装配起来，避免 core 反向耦合。
 */
@Service
class McpArtifactWorkspaceRegistry {

    private val workspaceRef = AtomicReference<McpArtifactWorkspace?>(null)

    /** 注册当前 MCP 控制面创建的工件工作区。 */
    fun register(workspace: McpArtifactWorkspace) {
        workspaceRef.set(workspace)
    }

    /** 注销工作区（控制面停止时）。 */
    fun unregister(workspace: McpArtifactWorkspace) {
        workspaceRef.compareAndSet(workspace, null)
    }

    /** 当前注册的工件工作区；未注册返回 null。 */
    fun current(): McpArtifactWorkspace? = workspaceRef.get()
}
