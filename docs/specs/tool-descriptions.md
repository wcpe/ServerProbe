# 功能规格：MCP 工具描述增强

> 状态：草拟　·　关联 PRD：FR-23　·　分支：feature/fr23-tool-descriptions

## 1. 背景与目标

FR-14 的 `tools/list` 目前只返回每个工具**一句话描述**（如"异步执行内嵌 Arthas 原始命令"）+ 无 `required` 的 inputSchema。外部 MCP agent（Claude 等）接上后**调用不明白**：参数含义不清、必填项未标注、不知道异步任务要先 `arthas_task_status` 轮询、看不懂输出字段（`{taskId,state,message}`、`{nextOffset,truncated}`）、不知道分页限制。本 FR 为每个工具下发**完整中文使用说明**，使 agent 仅凭 `tools/list` 即可正确调用。

## 2. 需求（要什么）

- `tools/list` 为每个工具返回增强后的 `description`，包含：
  - **用途**：一句话概括（保留现有中文描述）。
  - **参数说明**：逐参数列出 `名称 / 类型 / 必填 / 含义`（`required` 由 inputSchema 标注，description 内说明默认值）。
  - **使用示例**：至少一个 JSON 示例（参数格式正确）。
  - **异步工作流提示**：对异步工具（`arthas_*` 系列、任务类）明确"返回 taskId → 用 arthas_task_status 轮询 → arthas_task_output 分片读 → 完成后可选 arthas_task_cancel"。
  - **输出字段说明**：返回结构字段列表及含义。
  - **限制提示**：分页上限、超时默认值、Base64/二进制处理（FR-18 后补充）。
- `inputSchema` 补全：为每个参数标 `required`（必填列表）、`type`（string/integer/boolean）、`description`（参数含义）。
- **不改工具名、参数键、协议**：仅增强 `tools/list` 返回内容；不改变既有 `tools/call` 语义。
- 新增工具（FR-15/16/17/18/19/20/21/22）沿用同一描述规范。
- 范围内：描述规范、inputSchema 补全、示例/工作流/输出说明。
- 不做：修改协议版本、新增工具、改变调用语义、生成外部文档站（示例内嵌 description）。

## 3. 设计（怎么做）

- **core** `McpTool` 模型扩展：
  - 现有 `data class McpTool(name, description, inputSchema)` 保持兼容；新增 `usageExample: String?`（示例 JSON）、`outputFields: Map<String,String>?`（输出字段名→含义）、`workflow: String?`（工作流提示文本）。
  - `toolDescription()`（`McpJsonRpcDispatcher`）在序列化时把新字段并入 `description`（拼接为结构化文本：`用途：... \n 参数：... \n 示例：... \n 异步：... \n 输出：... \n 限制：...`），并补 `inputSchema.required`。
- **多 provider 组合（关键，FR-15/16/17/20/22 等新增工具的接线依赖）**：`McpJsonRpcDispatcher` 现只接受单一 `McpToolProvider`。本 FR 将其扩展为接受 `List<McpToolProvider>`：`tools/list` 返回所有 provider 工具的并集（按名去重、重名报配置错误），`tools/call` 按名路由到所属 provider。`NativeMcpToolProvider` 保持单 provider 形态，新增工具各自实现 `McpToolProvider` 注册进 dispatcher；FR-23 负责该组合机制落地并补契约测试（多 provider 去重/路由/缺失）。
- **description 生成**：新增 `ToolDescriptionBuilder`（纯函数，可单测），输入 `McpTool` 元数据 → 输出最终 description 字符串 + 补全的 inputSchema。
- **示例占位符约定**：description 内嵌示例中的玩家名/类名/表达式等一律用占位符（如 `<类名>`、`<玩家名>`、`<expression>`），**禁止真实值**，防止 agent 把示例当真实输入执行。
- **工具元数据**：`NativeMcpToolProvider.TOOLS` 每个工具补齐示例/输出/工作流；新工具（FR-15~22 交付时）按同规范登记。单工具 description 总长控制在 ≤1.5KB（防超出客户端工具描述长度限制）。
- **校验**：新增契约测试断言每个工具 description 含关键段（`参数`/`示例`/`异步`或`同步`/`输出`）、inputSchema 必填字段与 description 一致。
- **向后兼容**：旧客户端忽略新增 description 文本长度（无协议变化）；`tools/list` 响应体增大（估测 <8KB/工具，响应无 2 MiB 限制，请求体限制 2 MiB 不受影响）。

## 4. 任务拆分

- [ ] `McpTool` 模型扩展 + `ToolDescriptionBuilder` + 单测（各段生成、缺失段兜底）。
- [ ] `toolDescription()` 序列化增强 + `required` 补全 + 契约测试（全部工具过断言）。
- [ ] 逐工具补齐元数据（示例/输出/工作流，含异步工具的工作流提示）。
- [ ] E2E 真机：外部客户端 `tools/list` 后仅凭描述走通"异步 Arthas 工具 → 轮询 → 分片读"。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API（MCP 工具契约）、CHANGELOG。

## 5. 验收标准

- 单测：Builder 各段正确、缺失兜底、契约测试全部工具过"参数/示例/异步或同步/输出"断言、required 与描述一致。
- 真机（Paper）：`tools/list` 返回每个工具完整描述；外部 agent 仅凭描述完成一次 `arthas_watch` → `arthas_task_status` → `arthas_task_output` 全链路。
- 向后兼容：`tools/call` 行为不变；既有工具名/参数键不变。

## 6. 风险 / 待定

- 描述过长可能超出某些 MCP 客户端的工具描述长度限制（如 Claude 单工具描述上限）→ 控制每工具描述 ≤ 约 1.5KB（示例精简、占位符）。
- **多 provider 组合的排期依赖**：FR-23 落地 `List<McpToolProvider>` 组合机制是 FR-15/16/20 等新工具接线的**硬前置**（`McpJsonRpcDispatcher` 现为单 provider）。若 FR-23 单独先交付，组合机制随之可用；若并行开发，需在排期上把组合机制提前（或各 FR 自带最小组合，不推荐重复）。
- 新工具（FR-15~22）需同步登记描述；建议在 `docs/specs/` 建立"工具元数据登记表"避免遗漏（或并入本 FR 的契约测试）。
- 待定：是否把 `gc_events` 合并进 `diagnostic_bundle` 一并描述（见 FR-22 待定）。
