# 功能规格：MCP 区块/实体明细

> 状态：草拟　·　关联 PRD：FR-17　·　分支：feature/fr17-world-detail

## 1. 背景与目标

FR-14 的 `server_status` 只输出最新指标快照，其中 `worlds` 是聚合值（loadedChunks / entityCount / tileEntityCount / entitiesByType），没有"某区块有哪些实体"“某世界实体类型分布明细”等细粒度信息。排查"实体泄漏 / 区块卡死 / 某世界负载异常"时缺乏证据。本 FR 在 `server_status` 输出中增强世界明细（不新建专用工具，保持工具数稳定）。

## 2. 需求（要什么）

- 增强 `server_status` 返回的 `worlds` 结构：每个世界新增
  - `loadedChunkCount`（已有 loadedChunks，保持兼容）
  - `entityTypeCounts`：按实体类型名 → 计数的完整分布。**口径与既有 `entitiesByType` 相同（均为 `world.entities` 全量 `groupingBy`）**，差异仅在：`entitiesByType` 受 `world-entity-types` 配置开关门控、Folia 为 null；`entityTypeCounts` 由本 FR 的 `WorldDetailProvider` 提供（复用 `BukkitWorldCollector` 缓存，不重复遍历实体）。
  - `tileEntityCount`（已有）
  - `regionStats`：Folia 下复用 FR-12 已采集的 `ObservedRegionMetrics`（**仅透传，不新增采集能力**），按 region 输出；非 Folia 为 null。
- **"区块分布"口径**（对齐 PRD 措辞）：本 FR 的"区块分布"指**每世界的已加载区块数**（`loadedChunks` 全量统计）与区块内实体/方块实体负载汇总，**不做逐区块坐标明细**（逐区块枚举成本过高，YAGNI）；如需逐区块定位由后续 FR 覆盖。
- 保持向后兼容：`worlds` 既有字段（name/loadedChunks/entityCount/tileEntityCount/entitiesByType）语义不变，新字段增量添加。
- Folia：实体/方块实体数维持 N/A（-1），新增字段在 Folia 为 null/N/A；低版本（<1.17 无 `World#getLoadedChunks` 全量）降级为已有口径。
- 范围内：`server_status` 输出增强、实体类型分布全量、region 明细（Folia 复用 FR-12 观测数据）。
- 不做：新增独立工具、落盘明细、Prometheus 暴露明细（仍只出聚合）。

## 3. 设计（怎么做）

- **core**：`McpControlPlane.serverStatus()` 的 `worlds` 由 `readApi.latestSnapshot().server.worlds` 而来。本 FR 在 core 新增 `WorldDetailProvider` 契约，返回 `List<WorldDetail>`（含 entityTypeCounts、regionStats），由平台模块实现；`serverStatus()` 在输出中合并 provider 结果，provider 缺失时仅输出快照既有字段（降级兼容）。**本 FR 不新增 MCP 工具、不依赖 FR-23 的多 provider 组合机制，可独立交付。**
- **platform-bukkit**：实现 `WorldDetailProvider`：
  - 实体类型分布：**复用 `BukkitWorldCollector` 的主线程限频采样缓存**（`entitiesByType` 口径；`world-entity-types=false` 时不采集，返回 null），**不重复遍历实体**。
  - regionStats：复用 platform-bukkit 的 `FoliaObservedRegionService.snapshot()` / `ObservedRegionTracker`（位于 **platform-bukkit**，非 core），返回 FR-12 的 `ObservedRegionMetrics`（含 foliaRegionId/regionSequence/centerChunkX/Z/playerCount/sampleCount/tps 分位/mspt 分位）。
- **接口**：`interface WorldDetailProvider { fun details(): List<WorldDetail> }`，`WorldDetail(name, entityTypeCounts: Map<String,Int>?, regionStats: List<RegionStat>?)`。
- **并发**：MCP 请求线程直接读 Bukkit 世界需切主线程——`serverStatus()` 在 MCP 线程执行；为避免阻塞，平台实现在主线程缓存最近一次明细（周期刷新），MCP 线程读缓存（与 `BukkitWorldCollector` 同模式：主线程限频采样 + `@Volatile` 缓存）。
- **数据模型**：新增字段 `entityTypeCounts`（Map，复用采集器缓存）、`regionStats`（List<`ObservedRegionMetrics` 模型>），缺失为 null。
- **低版本**：现有 `BukkitWorldCollector` 在 1.8.8 真机已用 `world.loadedChunks` 正常工作（该 API 自 1.8 存在），故"低版本降级"仅指沿用现有采集路径，无需特殊版本分支。

## 4. 任务拆分

- [ ] core `WorldDetailProvider` 契约 + `serverStatus()` 合并逻辑 + 单测（provider 缺失降级、字段合并）。
- [ ] platform-bukkit `BukkitWorldDetailProvider`：主线程限频采样实体类型分布、regionStats 复用 FR-12；单测（Folia N/A、低版本降级）。
- [ ] MCP E2E 真机验证：`server_status` 返回真实世界明细。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG。

## 5. 验收标准

- 单测：合并逻辑、provider 缺失降级、Folia/低版本 N/A、字段向后兼容全绿。
- 真机（Paper，`world-entity-types=true`）：`server_status` 的 worlds 含真实 `entityTypeCounts`；与 `/probe world` 口径一致。
- 真机（Folia）：worlds 中实体分布为 N/A、regionStats 含 FR-12 已观测 region 明细（透传）；全局 TPS 仍 N/A。
- 真机（1.8.8）：worlds 降级为既有字段（复用现有采集路径），无新字段异常。

## 6. 风险 / 待定

- `entityTypeCounts` 全量返回的 MCP 输出体积：世界实体类型通常 <100 种（受 `Registry` 约束），但仍可能超单次快照输出——**现状 MCP 无分页机制**（`server_status` 单次返回）。若实测超限（>约 200 项或超响应限制），收敛为 Top-N（按计数降序）+ `truncated` 标记；不依赖 FR-18 的分页（那是工件读取，非快照）。
