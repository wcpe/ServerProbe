# 功能规格：MCP 在线玩家诊断明细

> 状态：草拟　·　关联 PRD：FR-20　·　分支：feature/fr20-player-diagnostics

## 1. 背景与目标

排查"某玩家的数据/行为异常"时，现有 MCP 只能看 JVM 全局（`server_status` 仅有 `onlinePlayers` 计数）。无法按玩家名定位其位置、血量、背包摘要、所在区块与事件链路。本 FR 提供在线玩家诊断明细，支持后续按玩家维度聚合链路（为指标查询"某玩家链路"铺路）。

**隐私与安全边界（关键）**：玩家数据属个人隐私。本功能**默认开启（随 MCP 开关）**，但必须受 MCP 鉴权约束（Bearer 密钥非空时强制；空密钥时回环地址可用并打印安全 WARN），**所有响应经审计**（复用 `McpAuditTrail`，记录工具名+玩家名参数 SHA-256），**不落盘、不进 Prometheus/Web/历史文件**。

## 2. 需求（要什么）

- MCP 新增工具 `player_lookup`：入参 `name`（玩家名，精确匹配在线玩家）或 `uuid`（可选），返回在线玩家明细：
  - 基础：`name`、`uuid`、`online`、`world`、`location`（x/y/z/yaw/pitch）、`gameMode`
  - 状态：`health`、`foodLevel`、`saturation`、`xpLevel`、`playerTime`
  - 背包摘要：`inventory`（物品 id/类型/数量/槽位，**不含 NBT/附魔等敏感细节**，仅摘要）、`enderChest`（同摘要）、`equipment`（手持/护甲）
  - 区块：`chunk`（所在区块坐标，世界内）
  - 链路聚合：`recentActivity`（最近事件时间线，如 join/quit/chat/换世界，**有界**默认最近 20 条，来源为 plugin 模块注册的 `PlayerJoinEvent` / `PlayerQuitEvent` / `AsyncPlayerChatEvent` / `PlayerChangedWorldEvent` 监听——**非 FR-09**，FR-09 是 JBIS 业务桥，不承载玩家行为日志）
- 玩家不在线时：返回 `{online:false, reason:"玩家不在线"}`，不抛异常。
- 输入校验：`name`/`uuid` 非空且为合法格式；不匹配时结构化错误。
- 范围内：在线玩家查询、位置/状态/背包摘要/区块/事件时间线。
- 不做：离线玩家查询（不含历史玩家数据）、背包写入、踢人/封禁等管理动作、把玩家明细落盘/进 Prometheus/Web/历史。

## 3. 设计（怎么做）

- **core** 新增 `PlayerDiagnosticsProvider` 契约：`fun lookup(request: PlayerLookupRequest): PlayerLookupResult?`，`PlayerLookupRequest(name, uuid)`（两者至少一个；`uuid` 必须为合法 UUID 格式）、`PlayerLookupResult`（见 §2 字段，Java 8 兼容模型放 core 或 api）。
- **platform-bukkit** 实现 `BukkitPlayerDiagnosticsProvider`（`@PlatformSide(BUKKIT)`）：
  - 经 `Bukkit.getPlayerExact(name)`（精确名）或 `Bukkit.getPlayer(uuid)`（UUID，含离线 UUID 匹配逻辑）解析在线玩家。
  - **主线程安全**：MCP 请求线程不直接读 Bukkit——采用**主线程周期缓存**（默认与 `world.sample-period-ticks` 一致）：主线程刷新在线玩家摘要到 `@Volatile` 缓存，MCP 请求读缓存。**缓存遍历开销设上限**：单周期最多刷新 N 名玩家（默认 200，超出截断标记），避免数百玩家时背包摘要遍历卡 tick；未在缓存中的玩家返回"数据未就绪，稍后重试"。
  - 背包摘要：遍历 `player.inventory` / `enderChest`，仅取 `Material`、数量、槽位；**不读 NBT**（避免重量级序列化与隐私扩散）。
  - 事件时间线：在 `plugin` 模块注册监听器收集 `PlayerJoinEvent` / `PlayerQuitEvent` / `AsyncPlayerChatEvent` / `PlayerChangedWorldEvent` 到有界环形缓冲（core 提供 `BoundedEventRing<T>`，默认容量 2000 事件），按玩家 UUID 过滤返回最近 N 条。
- **代理端降级**：BungeeCord/Velocity 无 Bukkit 玩家实体，`player_lookup` 返回 `{available:false, reason:"平台不支持在线玩家明细"}`（与 FR-15 的代理端降级口径一致）。
- **鉴权与审计**：在 MCP 接线时，`player_lookup` 注册进 `McpJsonRpcDispatcher`；审计对 `tools/call` 全量记录（含来源 IP、工具名、参数 SHA-256，**现状既有行为，无需新增代码**），本 FR 补充**隐私确认测试**：`player_lookup` 的参数（玩家名）默认进入审计参数 SHA-256（复用既有 `McpAuditRecord`，不存明文玩家名）。
- **隐私**：响应仅经 MCP 单次调用返回，**不写日志、不写磁盘、不进 Prometheus/Web/历史**；实现层面在 `McpControlPlane.serverStatus()` 中**不**包含玩家明细（只由 `player_lookup` 提供）。
- **默认开启**：随 MCP 开关（`mcp.enabled`），无需额外配置；若后续需要可加 `mcp.player-diagnostics` 开关（默认 true），本次不做配置项（YAGNI）。
- **接线依赖 FR-23**：本 FR 新增 `player_lookup` 工具需注册进 `McpJsonRpcDispatcher`，而当前 dispatcher 只接受单一 `McpToolProvider`（`McpProtocol.kt`）——多 provider 组合机制由 FR-23 落地（`tool-descriptions.md` §3）。**若 FR-23 未先交付，本 FR 需自带最小组合机制或调整排期**（与 FR-15 的接线依赖一致）。
- **低版本兼容**：`getPlayerExact` 全版本可用；部分状态字段（saturation/xp）在极低版本可能缺失 → 缺失字段置 null。

## 4. 任务拆分

- [ ] core `PlayerDiagnosticsProvider` 契约 + `BoundedEventRing` 事件缓冲 + 单测（环形、按玩家过滤、容量）。
- [ ] platform-bukkit `BukkitPlayerDiagnosticsProvider`（主线程缓存、背包摘要、区块、事件时间线）+ 单测（在线/离线/字段缺失/无 NBT）。
- [ ] plugin 注册玩家事件监听器（join/quit/chat/切服）进环形缓冲。
- [ ] MCP 接线：`player_lookup` 工具 + 审计参数 SHA-256 + 隐私确认（不落盘）。
- [ ] E2E 真机：真实玩家在线时 `player_lookup` 返回明细；离线/非法名结构化错误；审计记录含参数哈希。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG、SECURITY 隐私声明。

## 5. 验收标准

- 单测：在线/离线/非法输入（含非法 UUID）、背包摘要无 NBT、环形缓冲有界、审计含参数哈希（验证既有审计覆盖 `player_lookup`）、主线程缓存上限截断全绿。
- 真机（Paper）：真实玩家上线后 `player_lookup` 返回其位置/血/背包摘要/区块/最近事件；玩家名在审计日志中仅以 SHA-256 出现；**明文玩家明细不落盘**（审计仅存参数 SHA-256，响应不写日志/磁盘）。
- 代理端（BungeeCord/Velocity）：`player_lookup` 返回"平台不支持"结构化降级。
- 默认开启验证：`mcp.enabled=true` 且无额外配置时工具可用；空密钥 + 回环时仍工作且打印安全 WARN（`McpBearerAuth` 空密钥时任意来源可连并 WARN，与 FR-14 一致）。
- 隐私红线：Prometheus/Web/历史输出中**不存在**玩家名/位置/背包内容。

## 6. 风险 / 待定

- 事件时间线依赖监听器；服务器无事件时 `recentActivity` 为空列表（不报错）。
- 背包摘要不含 NBT：若后续需要"完整背包"评估，应走 FR-10 的 AllinInventorySync 集成（本 FR 明确不做）。
- 待定：是否在 MCP 工具描述中明确"玩家数据不落盘"以对齐 agent 预期（会纳入 FR-23 描述规范）。
