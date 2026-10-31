package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject

/**
 * `core.mcp` 动作的执行体：把一条**完整 JSON-RPC 2.0 请求**交给 MCP 分发器，并把 JSON-RPC 响应原样回给控制面。
 *
 * ## 为什么复用 JSON-RPC 而不是另造一套命令
 * 控制面（与它内部的 MCP 客户端）本来就说 JSON-RPC；把 `initialize` / `tools/list` / `tools/call`
 * 整包透传后，探针侧**只需要一个动作名**，新工具上线不需要改桥或控制面协议，也不会出现“桥的命令集”
 * 与“MCP 工具集”两套能力表各自漂移。回执里直接给 `result` 或 `error` 对象，语义与本地 HTTP 端点完全一致。
 *
 * ## 与本地 HTTP 端点的分工
 * 托管模式下探针不监听任何本地端口，本类就是 MCP 的唯一入口；独立模式仍用 `mcp.enabled` 控制的本地端点。
 * 两条通道共用同一个 [McpJsonRpcDispatcher] 装配（工具面、工件工作区、审计），因此行为一致。
 *
 * ## 失败分级
 * - **传输/格式**问题（载荷为空、不是 JSON、超过上限、解码异常）→ `fail(中文原因)`：这是桥层面的请求错误，
 *   控制面应直接看到“请求本身不合法”；
 * - **协议层**问题（版本不符、方法未实现、参数错、工具不存在）→ `ok(JSON-RPC error 对象)`：必须保留 `id`
 *   与 `error.code`，否则 MCP 客户端无法把它归因到具体调用。
 *
 * @property dispatcher MCP 分发器（与本地端点同一套工具面）。
 * @property source 审计来源标识（写进审计轨迹，便于区分“经桥调用”与“本地调用”）。
 */
class McpBridgeEndpoint(
    private val dispatcher: McpJsonRpcDispatcher,
    private val source: String = SOURCE_BRIDGE,
) {

    /**
     * 字符串入口（生产路径）：解析 JSON 后交给 [handle] 对象入口。
     *
     * @param payload 下行帧 `payload` 的文本（JSON-RPC 请求）。
     * @return 回执结果。
     */
    fun handle(payload: String): BridgeCommandResult {
        if (payload.isBlank()) {
            return BridgeCommandResult.fail("mcp 载荷为空：需要完整 JSON-RPC 2.0 请求")
        }
        if (payload.length > MAX_REQUEST_CHARS) {
            return BridgeCommandResult.fail("mcp 载荷过大：上限 $MAX_REQUEST_CHARS 字符（大产物请用分页/工件类工具）")
        }
        val request = runCatching { Json.parse(payload) }.getOrElse { failure ->
            return BridgeCommandResult.fail("mcp 载荷不是合法 JSON：${failure.message}")
        }
        return handle(request)
    }

    /**
     * 对象入口（纯逻辑，便于单测在没有运行期 JSON 后端时验证分发语义）。
     *
     * @param request 已解析的 JSON-RPC 请求对象。
     * @return 回执结果；通知类请求（无 `id`）回空串表示已受理。
     */
    fun handle(request: JsonObject): BridgeCommandResult {
        val response = runCatching { dispatcher.dispatch(request, source) }.getOrElse { failure ->
            return BridgeCommandResult.fail("MCP 执行异常：${failure.javaClass.simpleName}${failure.message?.let { ":$it" } ?: ""}")
        }
        // 通知（initialized 等）：协议规定不应有响应，回空串让调用方知道“已受理但不回体”。
        response ?: return BridgeCommandResult.ok("")
        return runCatching { BridgeCommandResult.ok(McpJsonWriter.encode(response)) }.getOrElse { failure ->
            BridgeCommandResult.fail("MCP 响应编码失败：${failure.javaClass.simpleName}")
        }
    }

    private companion object {

        /** 审计来源：经插件桥。 */
        const val SOURCE_BRIDGE = "bridge"

        /** 单次请求的字符数上限（与本地端点的 2 MiB 请求体上限对齐）。 */
        const val MAX_REQUEST_CHARS = 2 * 1024 * 1024
    }
}
