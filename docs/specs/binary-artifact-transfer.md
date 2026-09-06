# 功能规格：MCP 二进制产物安全回传

> 状态：草拟　·　关联 PRD：FR-18　·　分支：feature/fr18-binary-artifact

## 1. 背景与目标

FR-14 的 `artifact_read_chunk` 按 UTF-8 文本分块读取工件（`McpArtifactChunk.content` 是 `String`），二进制产物（heap dump、async-profiler JFR/flamegraph HTML、`arthas` 输出的 `.class` 等）会被破坏或不可读。排查"堆内存泄漏 / 运行期 CPU 热点"时，外部 agent 无法把 heapdump/profiler 结果取回本地分析。本 FR 为二进制产物提供安全回传通道。

## 2. 需求（要什么）

- 新增 MCP 工具 `artifact_read_binary`：入参 `name`、`offset`（字节偏移，默认 0）、`maxBytes`（默认 64 KiB，上限 1 MiB），返回 `{name, contentBase64, nextOffset, truncated, size}`。
  - `contentBase64`：按 Base64（无填充）编码的二进制分块，供 agent 解码还原。
  - 游标语义与 `artifact_read_chunk` 一致（`nextOffset` 供翻页）。
- 与 `artifact_read_chunk` 并存：文本工件继续走原工具；二进制工件走新工具。新工具对任意工件均可用（不校验内容是否二进制）。
- 大小限制：单块默认 64 KiB、上限 1 MiB（与 `McpArtifactWorkspace.MAX_READ_BYTES` 对齐）；Base64 放大 4/3 倍，单块 1 MiB 二进制 → 约 1.37 MiB Base64。**MCP 2 MiB 上限是请求体上限（`McpHttpServer.MAX_REQUEST_BYTES`），响应体无该限制**；1.37 MiB Base64 在 JSON 写入器与 HTTP 响应能力内。内存峰值 = 1 MiB 字节数组 + ~1.37 MiB String + JSON 输出，属受控常数。
- 安全：仅读 `mcp-workspace/artifacts` 内文件（沿用 `McpArtifactWorkspace.resolve` 的名称白名单校验），拒绝任意路径；不落盘 Base64 副本。
- 范围内：二进制分块读取、游标翻页、Base64 编码、与文本工具并存。
- 不做：二进制写入（`artifact_write_chunk` 保持文本）、大文件流式下载通道（SSE）、对产物内容做解析。

## 3. 设计（怎么做）

- **core** `NativeMcpToolProvider` 新增 `ARTIFACT_READ_BINARY` 常量与 `artifactReadBinary(arguments)` 实现：
  - 复用 `McpArtifactWorkspace`：新增 `readBinaryChunk(name, offset, maxBytes)` 方法（读取字节数组、返回 `McpBinaryChunk(content: ByteArray, nextOffset, truncated)`）。
  - 编码：`Base64.getEncoder().encodeToString(content)`（Java 8 标准库，无新增依赖）。
  - 响应字段：`name`、`contentBase64`、`size`（文件总字节数）、`nextOffset`、`truncated`。
- **McpArtifactWorkspace** 新增 `readBinaryChunk`（与 `readChunk` 共享定位逻辑，抽出私有 `readBytes(name, offset, limit)`；**新方法与 `readChunk` 一样 `@Synchronized`**）。
- **McpJsonWriter**：**禁止把 `ByteArray` 直接放进响应 Map**（`McpJsonWriter.encode` 对数组走 JSON 数组分支，会把每个字节写成数字，1 MiB 会触发 `MAX_ITEMS=256` 静默截断）；本实现始终先转 Base64 String 再编码，无需改 writer。
- **工具注册**：加入 `TOOLS` 列表，description 说明"二进制产物用本工具，文本用 artifact_read_chunk"；并提示"先 `profiler stop` / 等任务完成再读取，避免读到不完整文件"（与 FR-23 描述规范衔接）。
- **并发**：只读文件、`@Synchronized` 工作区（既有），无新增状态。

## 4. 任务拆分

- [ ] `McpArtifactWorkspace.readBinaryChunk` + 单测（偏移/截断/上限/名称校验）。
- [ ] `NativeMcpToolProvider.artifactReadBinary` + 单测（Base64 往返、游标、非法名）。
- [ ] 工具注册进 `TOOLS` + tools/list 描述。
- [ ] E2E 真机：用 `arthas_profiler` 产出 JFR/flamegraph 文件到 workspace，`artifact_read_binary` 分块取回本地，校验字节一致性。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG。

## 5. 验收标准

- 单测：二进制往返一致（含 0 字节、超 1 MiB 截断）、offset 定位、nextOffset 连续、**`truncated` 边界（恰好等于块大小/小于块大小）、offset 超出文件末尾（`coerceIn(0,size)` 行为）、空文件读取（contentBase64 空串、nextOffset=0、truncated=false）**、非法名拒绝全绿。
- 真机（Paper）：heapdump（或 profiler JFR）产物经 `artifact_read_binary` 分块取回，SHA-256 与服务器原文件一致。
- 单块 1 MiB 二进制 → Base64 约 1.37 MiB，HTTP 响应可完整返回且不触发 `McpJsonWriter.MAX_ITEMS` 截断。

## 6. 风险 / 待定

- 大产物（heap dump 常 >100 MiB）需多次翻页：默认 64 KiB/块 → 1600 次请求，agent 侧成本高。待定：是否提供"直读文件路径"的替代（如 `artifact_read_binary` 支持 `maxBytes` 上限调大到 4 MiB，Base64 ~5.5 MiB 超 MCP 2 MiB 限制——故维持 1 MiB 上限，不做大块）。
- 产物文件可能很大且正在写入（profiler 未停止时读）：`readBinaryChunk` 读到的可能是不完整文件——工具文档提示"先 `profiler stop` 再读取"。
- 不做异步下载/断点续传之外的传输协议增强（YAGNI）。
