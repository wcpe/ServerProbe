# 功能规格：MCP 运行期 CPU 火焰图

> 状态：草拟　·　关联 PRD：FR-21　·　分支：feature/fr21-runtime-flamegraph

## 1. 背景与目标

FR-14 已内嵌 Arthas，其 `arthas_profiler` 工具能 `start/stop` async-profiler 采样，产物（JFR/flamegraph HTML）写入 `mcp-workspace/artifacts/`。HTML 火焰图（文本）现状已可经 `artifact_read_chunk` 取回，但**缺**：① JFR 二进制回传（FR-18 未交付前无法取回）；② "按产物类型自动分流读取"的入口（agent 需自行判断扩展名选工具）。本 FR 让外部 agent 能完整走通"启动采样 → 触发负载 → 停止采样 → 取回火焰图/JFR → 分析"，降低心智负担。

**与 ADR-8 的关系（关键）**：ADR-8 决策为"运行期 CPU 火焰图不自研，并用 spark"。本 FR **不推翻 ADR-8**——不引入自研采样器，而是**直接利用既有 arthas_profiler（async-profiler）的产出**，仅补"产物取回/查看"链路。ADR-8 的"不自研采样器"约束不受影响。

## 2. 需求（要什么）

- 复用既有 `arthas_profiler`（start/stop/status/list 语义不变）。
- 新增 MCP 工具 `flamegraph_view`：入参 `artifactName`（工作区内火焰图/JFR 文件名），返回：
  - 若为 HTML 火焰图（`*.html`）：返回自包含 HTML 全文（分块，复用 `artifact_read_chunk` 语义；调用方渲染/保存）。
  - 若为 JFR（`*.jfr`）：返回文件元信息（大小/生成时间）与"建议下载分析"说明（因 JFR 为二进制，走 FR-18 的二进制回传；本工具仅给可读提示）。
  - 其他扩展名（`.bin` 等）：结构化错误（async-profiler 产物扩展名通常仅 .html/.jfr/.txt）。
- 新增 MCP 工具 `flamegraph_start` / `flamegraph_stop`：**薄封装** `arthas_profiler`，参数对齐（`action`、`artifactName`），默认输出到 `mcp-workspace/artifacts/flamegraph-<epochMillis>.html`（stop 时自动命名，**毫秒级时间戳消歧**），减少 agent 记忆负担。
- **与既有工具的关系**：`flamegraph_view` 的增量价值 = 类型自动分流 + JFR 元信息提示 + 降低 agent 心智负担（`arthas_profiler` 原始参数、产物扩展名、读取工具选择由本工具封装）。若实现中发现纯组合既有工具（`artifact_list` + `artifact_read_chunk/binary`）已足够，则 `flamegraph_view` 可降级为薄封装（YAGNI 审查点）。
- 范围：采样控制（薄封装）、火焰图取回查看、JFR 元信息提示。
- 不做：自研采样器、火焰图解析/聚合（HTML 由 async-profiler 生成）、对 JFR 做统计分析、自动触发负载。

## 3. 设计（怎么做）

- **core** `NativeMcpToolProvider` 扩展：
  - `FLAMEGRAPH_START` / `FLAMEGRAPH_STOP`：映射到 `arthas_profiler` 的 `start`/`stop`，stop 时若未指定 `artifactName` 则自动用 `flamegraph-<epoch>.html`（经 `workspace.outputPath` 落盘）。
  - `FLAMEGRAPH_VIEW`：校验 `artifactName` 存在于工作区；按扩展名分流（`.html` → 分块读文本返回；`.jfr`/`.bin` → 返回元信息 + 提示走 `artifact_read_binary`；其他 → 结构化错误）。
- **产物路径**：全部经 `McpArtifactWorkspace.outputPath` / `pathOf` 白名单校验（与既有 `arthas_profiler` 一致）。
- **依赖**：本 FR 依赖 FR-18 交付（JFR 二进制回传），故排在 FR-18 之后；HTML 火焰图取回不依赖 FR-18（文本）。
- **并发**：采样任务沿用 Arthas 任务模型（异步任务 + task 状态/输出）；`flamegraph_view` 为同步只读。
- **数据模型**：`flamegraph_view` 返回 `{artifactName, kind:"html"|"jfr"|"other", content?（html 分块）, meta?（jfr 大小/时间）}`。

## 4. 任务拆分

- [ ] `FLAMEGRAPH_START` / `FLAMEGRAPH_STOP` 薄封装 + 自动命名 + 单测（参数映射、默认命名）。
- [ ] `FLAMEGRAPH_VIEW` 分流（html 分块/jfr 元信息/其他错误）+ 单测。
- [ ] 依赖 FR-18 交付后，联调 `artifact_read_binary` 取回 JFR。
- [ ] E2E 真机：`flamegraph_start` → 负载 → `flamegraph_stop` → `flamegraph_view` 取回 HTML 火焰图（可打开），JFR 经二进制回传字节一致。
- [ ] 文档同步：PRD 状态、ARCHITECTURE（含 ADR-8 澄清）、API、CHANGELOG。

## 5. 验收标准

- 单测：薄封装参数、自动命名、html/jfr/其他分流、非法名拒绝全绿。
- 真机（Paper）：真实负载下 `flamegraph_start/stop` 产出火焰图 HTML，`flamegraph_view` 返回自包含 HTML（可保存打开）；JFR 产物经 `artifact_read_binary` 取回且 SHA-256 一致。
- ADR-8 一致性：不引入自研采样器；arthas_profiler（async-profiler）为唯一采样来源。

## 6. 风险 / 待定

- async-profiler 在部分 JVM/平台（Windows 非 x86、某些 JDK 构建）不可用 → `flamegraph_start` 返回结构化失败并提示；Arthas 任务模型已兜底。
- HTML 火焰图可能很大（数 MB）：分块读取（复用 `artifact_read_chunk` 的 1 MiB 上限）；若 agent 环境渲染受限，可先保存再分析。
- 依赖 FR-18 的 JFR 回传：若 FR-18 延期，本 FR 的 JFR 部分顺延，HTML 部分不受阻。
- ARCHITECTURE 澄清：运行期深度分析仍推荐 spark（ADR-8 原文），async-profiler 为 Arthas 内嵌补充，两者互补不冲突。
- 待定：`flamegraph_stop` 的默认文件名是否应含世界名/场景标签（YAGNI，先毫秒时间戳）。
