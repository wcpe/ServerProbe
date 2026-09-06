package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.Base64

/**
 * FR-18 MCP 二进制产物安全回传工具（只读）。
 *
 * 提供 `artifact_read_binary`：按字节偏移分块读取工件，以 Base64（无填充）编码
 * 返回，供外部 agent 解码还原二进制产物（heap dump / JFR / flamegraph 等）。
 * - 与文本工具 `artifact_read_chunk` 并存：二进制产物走本工具，文本走原工具；
 *   本工具对任意工件均可用，不校验内容是否二进制。
 * - 游标语义与 `artifact_read_chunk` 一致：`nextOffset` 供翻页，`truncated` 表示是否还有更多。
 * - 大小限制：单块默认 64 KiB、上限 1 MiB（与工作区 `MAX_READ_BYTES` 对齐）；
 *   内容始终先经 Base64 编码为字符串再进响应 Map，避免 `McpJsonWriter` 把
 *   ByteArray 按 JSON 数组分支逐字节序列化触发 `MAX_ITEMS` 静默截断。
 * - 安全：仅读工件工作区内文件，名称校验走工作区白名单，拒绝任意路径。
 *
 * 无共享可变状态：每次调用经 [McpArtifactWorkspaceRegistry] 取当前工作区只读快照。
 */
@Service
class BinaryArtifactToolProvider(
    private val testWorkspaceRegistry: McpArtifactWorkspaceRegistry? = null,
) : McpToolProvider {

    @Inject
    lateinit var workspaceRegistry: McpArtifactWorkspaceRegistry

    @Inject
    lateinit var mcpToolProviderRegistry: McpToolProviderRegistry

    /** 启动期注册进扩展工具注册表，由控制面聚合进 dispatcher。 */
    @PostConstruct
    fun register() {
        mcpToolProviderRegistry.register(this)
    }

    override fun tools(): List<McpTool> = TOOLS

    override fun call(name: String, arguments: JsonObject?): Map<String, Any?> = when (name) {
        ARTIFACT_READ_BINARY -> artifactReadBinary(arguments)
        else -> throw IllegalArgumentException("未找到 MCP 工具")
    }

    /** 二进制分块读取：返回 Base64 编码的分片与游标，未装配工作区时结构化降级。 */
    private fun artifactReadBinary(arguments: JsonObject?): Map<String, Any?> {
        val workspace = (testWorkspaceRegistry ?: workspaceRegistry).current()
            ?: return linkedMapOf("available" to false, "reason" to "当前未启用 MCP 工件工作区")
        val name = arguments?.getString("name")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("工件操作缺少 name 参数")
        val offset = arguments?.getRaw("offset")?.toString()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val maxBytes = arguments?.getRaw("maxBytes")?.toString()?.toIntOrNull() ?: DEFAULT_READ_BYTES
        val chunk = workspace.readBinaryChunk(name, offset, maxBytes)
        return linkedMapOf(
            "name" to name,
            "contentBase64" to Base64.getEncoder().encodeToString(chunk.content),
            "size" to (workspace.list().firstOrNull { it.name == name }?.size ?: 0L),
            "nextOffset" to chunk.nextOffset,
            "truncated" to chunk.truncated,
        )
    }

    private companion object {
        const val ARTIFACT_READ_BINARY = "artifact_read_binary"
        const val DEFAULT_READ_BYTES = 64 * 1024
        val TOOLS = listOf(
            McpTool(
                ARTIFACT_READ_BINARY,
                "按字节分块读取 MCP 工件并以 Base64 返回，供还原二进制产物（heap dump/JFR/flamegraph 等）",
                mapOf(
                    "name" to mapOf("type" to "string", "description" to "工件名（仅字母数字点下划线连字符）"),
                    "offset" to mapOf("type" to "integer", "description" to "字节偏移，默认 0"),
                    "maxBytes" to mapOf("type" to "integer", "description" to "本分片最大字节，默认 64 KiB，上限 1 MiB"),
                ),
                usageExample = "{\"name\":\"<工件名>\",\"offset\":0}",
                workflow = "同步调用，按 nextOffset 游标分页；二进制产物用本工具，文本用 artifact_read_chunk；请先 profiler stop / 等任务完成再读取，避免读到不完整文件",
                outputFields = mapOf(
                    "name" to "工件名", "contentBase64" to "Base64 编码的二进制分片", "size" to "文件总字节数",
                    "nextOffset" to "下一页字节偏移", "truncated" to "是否还有更多",
                ),
            ),
        )
    }
}
