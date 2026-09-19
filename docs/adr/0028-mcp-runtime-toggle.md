# ADR-0028：MCP 控制面支持运行期两级开关

## 状态

已接受

## 背景

FR-14 的 MCP 控制面与内嵌 Arthas 运行时的启停时机此前完全由启动期配置决定：`McpControlPlane` 在 `@PostEnable` 读取一次 `mcp.enabled`，`ArthasDiagnosticsLifecycle` 在同一个 `@PostEnable` 里凭同一开关完成 Arthas 闭包解包与 Instrumentation 附加。二者此后不存在任何运行期入口——`/probe` 没有开关子命令，项目也没有配置重载或写回机制。

单机托管数十个服务器实例的场景暴露了两个问题：

- 事故发生时无法开启诊断。要开 MCP 必须重启服务器，而重启会摧毁事故现场（线程栈、堆积的任务、内存状态），与"事故现场取证"的产品定位直接矛盾。
- 常开 MCP 的代价不可忽略。`mcp.enabled=true` 会触发 Arthas 闭包解包（Java 17+ 约 20 MB）与启动期 Instrumentation 附加尝试（可能 spawn 一次性 helper 进程），并常驻监听端口与持续增长的审计文件。实例数一多，这些成本乘以实例数。

## 决策

MCP 控制面拆成两级运行期开关，均只从服务器控制台/RCON 触发，且不写回配置文件：

1. **端点级**：`McpControlPlane` 暴露 `enable()` / `disable()` / `running`，运行期起停 MCP Streamable HTTP 端点。端点起来后原生工具（状态/命令执行/线程转储/日志检索/插件与玩家诊断）立即可用，不需要 Arthas。
2. **Arthas 级**：新增 core 契约 `ArthasRuntime` 与装配点 `ArthasRuntimeRegistry`，由 `ArthasDiagnosticsLifecycle` 实现，运行期解包、附加 Instrumentation、注册控制器，或反向卸载。

`@PostEnable` 的自动化语义保持向后兼容：`ArthasDiagnosticsLifecycle` 始终把启停能力注册进 registry，随后仅当 `mcp.enabled=true` 时自动启动 Arthas；`McpControlPlane` 仍按 `mcp.enabled` 决定端点是否在启动期打开。

## 理由

- **两级拆分的必要性**：Arthas 的闭包解包与 Instrumentation 附加是"重"操作，而原生诊断工具零依赖 Arthas。合并成单个开关会让"只想看线程栈"的操作付出解包与 attach 代价，也把 attach 风险（helper 进程、模块限制）引入本可避免的路径。
- **仅控制台、不持久化**：开启 MCP 等于授予 JVM 完整控制权限（类重定义、任意 JVM 内代码求值）。限定控制台可防住"已取得游戏内管理员权限"的攻击者借此提权；不写回配置可保证无论运行期怎么开，重启后都回到配置声明的姿态，"默认关闭"这一安全默认不会被运行期操作静默改写。
- **不引入配置热重载**：MCP 段之外的大量配置（如 `AlertEngine` 的规则集）是启动期一次性构建的只读状态，做全量热重载会出现"配置说 A、运行时是 B"的隐蔽不一致。定向只重载 `mcp.*` 同样面临段内启动期固化项（如 `ArthasTaskSettings`）的语义划分成本。
- **不引入远程桥下发**：经业务桥让 Worker 远程开启 MCP，等于把"授予 JVM 完整控制权"变成可远程触发的动作，Worker 被攻破即放大为全部被管实例失守。
- **沿用既有装配范式**：`core` 声明契约 + 诊断模块实现 + registry 运行期自注册，与既有的 `ArthasControl`/`ArthasControlRegistry`、`PlatformControl`/`PlatformControlRegistration` 一致，不破坏 ADR-0025 锁定的"core 不引用任何 Arthas 类型"。

## 后果

- `McpControlPlane` 的启停路径会在命令线程与生命周期线程间并发访问，此前无保护的句柄字段（`httpServer`/`artifacts`/`auditTrail`/`artifactCleanup`）必须加锁；同时修正 `stop()` 依赖 `httpServer != null` 判断的守卫——启动中途失败（端口占用）时会留下已注册但永不注销的工件工作区。
- Arthas 运行时的卸载必须彻底：注销控制器、关闭任务管理器（其线程池非 daemon，泄漏会阻止 JVM 退出）、关闭隔离 ClassLoader。由于 `ArthasTaskManager.close()` 会连带关闭 runner 且执行器永久终止，重新启用必须重建 runner 与任务管理器，不能复用实例。
- Java 8–16 路径的 `appendToBootstrapClassLoaderSearch(arthas-spy.jar)` 不可撤销。运行期二次加载的可行性以真机实测为准；若不成立，卸载降级为"注销控制器 + 关闭任务管理器但保留 ClassLoader"，代价是 off 后仍占约 20 MB Metaspace，收益是再次开启必定可用。
- 运行期开关状态仅存在内存，不落盘、不入审计之外的其他通道；关闭后重启回到 `mcp.enabled` 声明的姿态。
- 控制台开关属管理操作，须记中文分级日志；开启时复用既有的危险配置告警（空密钥 / 非回环）输出。

## 备选方案

- **只做端点级单开关**：Arthas 仍由启动期配置决定，`mcp.enabled=false` 启动的实例即便运行期打开端点，`arthas_*` 工具也全部报"未注册控制器"，无法覆盖用户"出事时才深度诊断"的主场景，否决。
- **配置热重载（`/probe reload` 或 `/probe mcp reload`）**：需要为全量配置或整段 `mcp.*` 逐项定义热改语义，且存量启动期固化项会造成配置与运行时不一致，否决。
- **经业务桥远程下发开关**：把完整控制权的授予变成可远程触发，放大攻击面，否决。
- **运行期写回 `config.yml` 持久化开关**：会被理解为"上次开了就一直开着"，削弱默认关闭的安全默认，且项目此前无配置写回先例，否决。
- **重启服务器开启诊断**：摧毁事故现场，与产品定位矛盾，否决。
