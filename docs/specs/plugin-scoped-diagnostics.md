# 功能规格：MCP 插件维度诊断入口

> 状态：草拟　·　关联 PRD：FR-15　·　分支：feature/fr15-plugin-scoped-diagnostics

## 1. 背景与目标

FR-14 已交付 MCP 深度诊断控制面（内嵌 Arthas、线程/死锁/平台命令等）。但现有工具均为 **JVM 全局视角**（`thread_top`/`thread_dump` 全线程、Arthas 命令需手敲类名），外部 agent 排查"某个插件的 bug"时缺乏**按插件维度**的入口：不知道目标插件加载了哪些类、其线程栈长什么样、CPU 热点在哪，只能全局翻。本 FR 让 agent 可以"列出插件 → 选中插件 → 看它的类/线程/CPU 热点 → 直接用它的类名调 Arthas"。

## 2. 需求（要什么）

- MCP 新增工具 `plugin_list`：列出全部**已加载**插件（名称、版本、ClassLoader 摘要、是否启用）。**Bukkit 语义边界**：`PluginManager.getPlugins()` 只返回已加载插件；被禁用（`plugin disable` 后）的插件仍在列表内（`isEnabled()==false`），但**"装了未加载"的插件（如依赖缺失）不在列表**——本工具无法列出它们。
- MCP 新增工具 `plugin_classes`：入参插件名，返回该插件 ClassLoader 可解析的代表性类清单（有界，默认 200 条、可截断），用于后续 Arthas 类名定位。
- MCP 新增工具 `plugin_threads`：入参插件名，返回当前线程栈中**归属于该插件**的线程栈帧（复用 core 的 `PluginClassLoaderRegistry` 类归属解析），并按帧数/CPU 时间排序；**结果大小上限与 `thread_dump` 对齐（≤128 线程×64 帧）**。
- MCP 新增工具 `plugin_cpu`：入参插件名，返回该插件在 **CPU 归因采样窗口（最近 N 轮窗口累计）** 内的样本计数与占比（复用 `CpuAttributionSampler` 聚合，与 `/probe cpu` 同一数据源）；未开启 `cpu.enabled` 时返回明确降级说明；**启用但窗口内零样本**时返回空列表 + `zeroSamples:true`。
- 所有入参校验：插件名必须存在于已注册插件集合，否则返回结构化错误。
- 范围内：插件清单 / 类清单 / 归属线程栈 / CPU 归因，只读。
- 不做：不修改插件状态（不启用/禁用/热重载）、不新增游戏内命令、不落盘插件明细、**不重复实现线程采样（仅复用现有 `NativeThreadDiagnostics.dump()` 叠加归属过滤）**、不覆盖 BungeeCord/Velocity（代理端无插件 ClassLoader 语义，`plugin_list` 返回空并注明平台不支持）。

## 3. 设计（怎么做）

- **core**：新增 `PluginScopedMcpToolProvider`（实现 `McpToolProvider`），依赖 `PluginClassLoaderRegistry`（core 已有，`pluginNames()`/`ownerOf()` 可复用）与现有 `NativeThreadDiagnostics`（复用 `dump()` 的线程信息，叠加归属过滤）。
- **core 新增归属过滤**：对每个线程栈帧的类名调用 `PluginClassLoaderRegistry.ownerOf(className)`，命中目标插件名即保留该帧；同一线程命中数 ≥1 则整线程进入结果（含未归属的公共帧）。结果上限 ≤128 线程×64 帧（与 `thread_dump` 对齐），防止超大型线程 dump 放大归因开销。
- **platform-bukkit**：现有 `BukkitPluginClassLoaderRegistrar` 已把插件 ClassLoader 注册进 `PluginClassLoaderRegistry`（FR-02/FR-2.6 产物）。`plugin_list` 需补充"插件元数据"（名称/版本/启用状态）：经 Bukkit `PluginManager.getPlugins()` 读取，平台模块注册一个 `PluginMetadataProvider` 实现到 core 契约。**插件名→jar 路径从 `JavaPlugin.getFile()` 实例解析，禁止字符串拼接**（插件目录名可能与插件名不一致）。插件元数据只读字段经 `Plugin#getDescription()` 读取，Bukkit 保证线程安全，无需主线程切换。
- **core 契约**：`interface PluginMetadataProvider { fun list(): List<PluginMeta> }`，`PluginMeta(name, version, enabled, loaderSummary)`；代理端无实现时不注册，`plugin_list` 返回空列表并注明平台不支持。
- **plugin_classes**：对目标插件 ClassLoader 不做全量枚举（Java ClassLoader 不支持直接枚举），采用**按需解析**策略：解析 Bukkit 插件 jar 内的类名列表（读 `plugins/<name>.jar` 的 JAR 条目，`ClassLoader` 可解析性经 `loadClass(name, false)` 验证），有界输出；解析失败时降级为"无法枚举，请用 arthas `sc` 按包名搜索"。
- **MCP 接线**：在 `McpControlPlane.startServer` 的 `NativeMcpToolProvider` 之后，把新 provider 注册进同一个 `McpJsonRpcDispatcher`（dispatcher 需支持多 provider 聚合 tools，机制由 FR-23 落地；本 FR 明确声明该接线依赖，见 §6）。
- 涉及并发：全部为只读查询，在 MCP 请求线程执行；`ownerOf` 遍历已注册 ClassLoader 有缓存（core 已有 classOwner 缓存），请求线程高频调用安全。

## 4. 任务拆分

- [ ] 新增 `PluginMetadataProvider` 契约与 core 的 `PluginScopedMcpToolProvider`（4 个工具）。
- [ ] 归属线程栈过滤（复用 `ownerOf`）+ 单测（归属命中/未命中/缓存、线程排序、入参校验）。
- [ ] `plugin_classes` 的 JAR 条目枚举 + 可解析性验证 + 单测（正常/缺 jar/无权限）。
- [ ] platform-bukkit 实现 `PluginMetadataProvider`（BukkitPluginManager）+ IOC 注册 + 契约测试。
- [ ] MCP 接线：多 provider 聚合 tools（与 FR-23 共用设计），E2E 真机验证。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG。

## 5. 验收标准

- 单测：4 个工具的参数校验、归属过滤、排序、降级路径全绿。
- 真机（Paper，受控场景）：预置含故意缺陷的 fixture 插件（在异步线程跑阻塞循环），`plugin_list` 返回真实插件清单（含 ServerProbe 自身）；`plugin_classes` 对真实插件返回可解析类；`plugin_threads` 断言 fixture 插件的栈帧出现在结果中；`plugin_cpu` 在 `cpu.enabled=true` 下返回占比、未开启时返回明确降级说明。
- 受控端到端：经 `plugin_list → plugin_classes → arthas_trace <类名>` 全链路对 fixture 插件定位到目标方法（不依赖真实第三方 bug）。

## 6. 风险 / 待定

- JAR 条目枚举对"已从插件目录移除但仍加载"的插件不可用（jar 不存在）→ 降级为提示用 `sc`。
- 代理端（BungeeCord/Velocity）无插件 ClassLoader 语义：明确返回"平台不支持"，不提供空壳实现。
- `plugin_threads` 的"归属"依赖 `ownerOf` 的缓存正确性；插件热加载（新类）时缓存按类名 miss 自然重解析（core 既有行为）。
- **接线依赖 FR-23**：本 FR 的 4 个新工具需注册进 `McpJsonRpcDispatcher`，而当前 dispatcher 只接受单一 `McpToolProvider`（`McpProtocol.kt`）——多 provider 组合机制由 FR-23 落地（`tool-descriptions.md` §3）。若 FR-23 未先交付，本 FR 需自带最小组合机制（仿 `PlatformControlRegistration` 模式）或调整排期。
