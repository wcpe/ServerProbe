# 功能规格：MCP 控制面运行期两级开关

> 状态：已交付@v0.5.0　·　关联 PRD：FR-24

## 1. 背景与目标

FR-14 交付的 MCP 控制面与内嵌 Arthas 运行时此前只在插件启动时按 `mcp.enabled` 决定启停，运行期没有任何开关入口。单机托管数十个实例的场景下这带来两个问题：事故发生时开启诊断必须重启服务器（摧毁事故现场），而常开 MCP 又要为每个实例付出 Arthas 解包（Java 17+ 约 20 MB）、Instrumentation 附加尝试、监听端口与持续增长的审计文件。

FR-24 提供两级运行期开关，使运维可以"平时零 Arthas 成本、出事时按需开启"，并可按诊断深度分档开启（只看线程栈就不必加载 Arthas）。

架构决策见 [ADR-0028](../adr/0028-mcp-runtime-toggle.md)。

## 2. 需求（要什么）

- 新增控制台命令组 `/probe mcp`，命令字面量：
  - `/probe mcp status`：输出 MCP 端点与 Arthas 运行时的当前状态。
  - `/probe mcp on`：起 MCP Streamable HTTP 端点。端点起来后原生工具立即可用，不依赖 Arthas。
  - `/probe mcp off`：停端点。若 Arthas 运行时仍在加载状态，额外提示可一并卸载。
  - `/probe mcp arthas on`：解包 Arthas 闭包、获取 Instrumentation、注册诊断控制器。
  - `/probe mcp arthas off`：卸载 Arthas 运行时（注销控制器、释放任务线程、关闭隔离 ClassLoader）。
- **仅控制台/RCON 可执行**：游戏内玩家即使持有权限节点也拒绝，并给出可 i18n 的拒绝提示。判定标准为**控制台类型白名单**（Bukkit `ConsoleCommandSender`/`RemoteConsoleCommandSender`、Bungee `ConsoleCommandSender`、Velocity `ConsoleCommandSource`，按底层发送者类型匹配），命令方块、实体执行者等非玩家发送者一律拒绝。
- 权限节点 `serverprobe.command.mcp`，子命令 `arthas` 另有 `serverprobe.command.mcp.arthas`。
- 幂等：重复 `on`（已运行）与重复 `off`（未运行）不得抛异常或产生副作用；对未运行状态执行 `off` 明确回报"未运行"。
- 运行期开关**不写回 `config.yml`**，仅内存态；重启回到 `mcp.enabled` 声明的姿态。
- 向后兼容：`mcp.enabled=true` 时启动期行为与此前完全一致（端点打开、Arthas 自动加载）；`mcp.enabled=false` 时不监听、不加载，但启停能力仍可用。
- 开启端点时复用既有的危险配置中文告警（空密钥、非回环监听）。
- 全部用户可见文案经语言文件（`zh_CN` / `en_US` 同步）。
- 范围内：端点运行期启停、Arthas 运行时运行期启停、控制台命令与鉴权、双语文案、单元测试、真机验收。
- 不做（范围外）：配置热重载（含 `/probe reload`）、经业务桥远程下发开关、运行期写回配置文件、新增 MCP 工具、新增配置项（含"Arthas 自动启动"开关）、改动 `mcp.enabled` 的既有启动期语义。

## 3. 设计（怎么做）

### 3.1 分层与装配

`core` 不引用任何 Arthas 类型（ADR-0025），运行期启停沿用既有"core 声明契约 + 诊断模块实现 + registry 运行期自注册"范式（同 `ArthasControl`/`ArthasControlRegistry`、`PlatformControl`/`PlatformControlRegistration`）：

- 新增 `core/mcp/ArthasRuntime.kt`：`ArthasRuntime`（`startRuntime()` / `stopRuntime()` / `runtimeReady`）、`ArthasRuntimeRegistration`（`register` / `unregister`）与 `@Service ArthasRuntimeRegistry`（`AtomicReference` 持有；`register` 覆盖式、`unregister` 按引用 CAS）。未注册时降级：`startRuntime()` 返回不可用快照、`stopRuntime()` 空操作、`runtimeReady=false`，不抛异常。
- 启停结果复用既有 `ArthasInstrumentationSnapshot(available, source, message)`，命令层直接回显 `message`。

### 3.2 端点级开关（`McpControlPlane`）

- 新增 `enable()` / `disable()` / `running`，内部复用既有 `startServer` / `stopServer`（后者内部全空安全，`startServer` 首行已内置 `stopServer()`）。
- 四个句柄字段（`httpServer` / `artifacts` / `auditTrail` / `artifactCleanup`）此前无锁，运行期开关会引入命令线程与生命周期线程的并发访问，故 `start` / `stop` / `enable` / `disable` 统一加 `@Synchronized`。
- 修正 `stop()` 依赖 `httpServer != null` 的守卫：启动中途失败（端口占用）时 `artifacts` 已创建并注册但 `httpServer` 仍为空，旧守卫会跳过清理留下幽灵注册；改为无条件走 `stopServer()`。
- `enable()` 内部捕获启动失败并回滚清理残留，向调用方返回结果而不是抛异常（与既有 `start()` 的 `runCatching` 降级一致）。

### 3.3 Arthas 级开关（`ArthasDiagnosticsLifecycle`）

- 改为实现 `ArthasRuntime`，拆出 `@Synchronized` 的 `startRuntime()` / `stopRuntime()`。
- `@PostEnable`：**始终**把启停能力注册进 `ArthasRuntimeRegistry`，随后仅当 `mcpEnabled()` 为真时经 `submitAsync` **异步** `startRuntime()`（保持既有自动化语义与向后兼容；加载含解包与 Instrumentation 附加、必要时 spawn helper 子进程注入，等待上限 30s，不得占用启用线程）。卸载置位 `destroyed` 后拒绝加载，避免异步任务晚于 `@PreDestroy` 被调度而残留线程阻止 JVM 退出。
- `@PreDestroy`：`stopRuntime()` 后从 registry 注销。
- **重新启用必须重建 runner 与任务管理器**：`ArthasTaskManager.close()` 会连带关闭 runner，且其执行器与超时调度器被 `shutdownNow()` 后永久终止，复用会抛 `RejectedExecutionException`。卸载顺序沿用既有 `stop()`：先按引用注销当前已注册的控制器，再关闭任务管理器（其线程池非 daemon，泄漏会阻止 JVM 退出），最后关闭 runner 释放隔离 ClassLoader。
- 二次加载可行性以真机实测为准（Java 8–16 路径的 `appendToBootstrapClassLoaderSearch` 不可撤销）。若实测不可重建，降级为卸载时保留 ClassLoader 以确保再次开启可用，代价与收益在 ADR-0028 的后果段已记录。

### 3.4 命令层

`ProbeCommand` 新增单个 `mcp` 子命令，用 `dynamic` 接收参数串后在代码内分发（项目此前无带参子命令先例，不用未经项目验证的三级 `literal` 嵌套）。控制台判定用**底层发送者类型白名单**（`isConsoleSender`：取 `ProxyCommandSender.origin` 按控制台类型匹配，跨平台零平台依赖）——**不得**用"非玩家即放行"：命令方块等非玩家发送者会连同放行，其 `isOp()` 在 1.16.5 上恒真，等于把 JVM 控制权交给红石信号。

## 4. 任务拆分

- [x] 登记 FR-24 到 PRD、新增 ADR-0028 与本 spec。
- [x] 新增 `core/mcp/ArthasRuntime.kt` 契约与 registry。
- [x] 写 `ArthasRuntimeRegistryTest`（未注册降级、注册转发、按引用注销、启停幂等回报）。
- [x] `McpControlPlane` 增加 `enable`/`disable`/`running`，加锁并修正 `stop()` 守卫。
- [x] 写 `McpControlPlaneToggleTest`（`running` 初值、未运行时 `disable()` 返回 false 且幂等不抛）。
- [x] `ArthasDiagnosticsLifecycle` 实现 `ArthasRuntime` 启停（经内部转发实现注册，避免 IOC 按类型注入歧义）。
- [x] `ProbeCommand` 新增 `mcp` 子命令与仅控制台门；阻塞动作经 `submitAsync` 异步执行，避免冻结主线程。
- [x] `lang/zh_CN.yml` 与 `lang/en_US.yml` 同步新增文案键。
- [x] `./gradlew build` 全绿（编译 + 单测 + detekt + IOC 静态诊断 errors=0）。
- [x] 文档同步：PRD 状态、CHANGELOG、`docs/wiki/Commands.md`、ADR 索引。
- [x] Paper 1.20.1 真机验收（8 项，见下）。

## 5. 验收标准

### 单元测试与静态检查

- `ArthasRuntimeRegistryTest`：未注册时启停降级为安全失败（`stopRuntime()` 回报未卸载而非抛异常）；注册后转发启停并暴露运行状态；按引用 CAS 注销；重复注册后者生效；注册本身不触发加载。
- `McpControlPlaneToggleTest`：初始未运行、`disable()` 返回 false、重复关闭幂等不抛。
- `project:core`（294）、`diagnostics:diagnostics-arthas`（19）、`platform:platform-bukkit`（64）共 **377 测试全绿**，0 失败。
- `./gradlew build` 通过（detekt + IOC 静态诊断 errors=0；本次改动同时消除了一条 Arthas 的 Bean 歧义警告，警告数 2→1）。

### 真机验收（Paper 1.20.1，Windows，Java 17）

**已通过**——`mcp.enabled=false` 启动后**全程不重启服务器**逐项确认：

| # | 验收项 | 结果与证据 |
|---|---|---|
| 1 | 启动期不监听、不加载 | 日志 `MCP 控制面未开启(mcp.enabled=false)，已跳过`；`/probe mcp status` 报"端点: 未运行 / Arthas 运行时: 未加载" |
| 2 | 仅控制台放行 | RCON（非玩家发送者）执行 `/probe mcp on` 成功；玩家拒绝逻辑为 `sender is ProxyPlayer`（跨平台判定，无玩家在线时以 RCON 路径确证放行分支）。**后续加固**：该判定已改为控制台类型白名单（命令方块等非玩家发送者不再放行），见 §3.4 |
| 3 | 端点级开启后原生工具立即可用 | `mcp on` 后 `tools/list` 返回 **42 工具零重复**；`server_status` 无需 Arthas 即返回真实数据（3 个世界、平台 BUKKIT） |
| 4 | Arthas 运行期加载 | `动态附加成功(SubprocessAttacher)：helper 子进程注入成功`；`arthas_execute version` → `4.3.2`；`arthas_execute thread --state RUNNABLE -n 3` 返回真实线程栈 |
| 5 | Arthas 运行期卸载 | `as-server destroy completed`；`arthas_execute` 降级为"当前未注册 Arthas 诊断控制器"；`server_status` 不受影响（验证两级独立） |
| 6 | **二次加载（主风险点）** | 卸载后重新 `arthas on` 成功（`as-server started in 628 ms`），`arthas_execute version` → `4.3.2`。**无需启用 ADR 记录的降级方案** |
| 7 | 端口释放与反复开关 | `mcp off` 后 9942 端口确认不再监听、端点不可达；`mcp on` 可重新监听 |
| 8 | 幂等 | 控制台重复 `mcp on` 回执"已在运行中，无需重复开启"；重复 `arthas off` 区分"已卸载"与"本就未加载" |
| 附 | 无资源泄漏 | 5 轮 `arthas on/off` 后 `arthas-*` 线程全部清理，`ServerProbe-*` 线程各恒定 1 个 |
| 附 | 向后兼容 | `mcp.enabled=true` 时启动期端点与 Arthas 自动就绪，`status` 报"运行中 / 已加载" |
| 附 | 插件卸载清理 | `stop` 服务器时 `MCP 控制面已停止`（运行时已开启状态下正确关闭） |
| 附 | **Java 8 分支复验** | Paper 1.12.2 + JDK 8 + premain（瘦 agent）：走 **Arthas 3.1.1** 分支；`tools/list` 42 工具；`version`→`3.1.1`；**二次加载成功且 `watch` 真实可用**（决定性证据：不可撤销的 spy 注入在重载后仍生效）；`on→off→on→off→on` 多轮无异常 |

### 真机验收中发现并修复的四个缺陷

1. **主线程阻塞（项目红线）**：四个启停动作的阻塞点——端点级要建目录并按保留期/容量清理工件（最多扫 10 GiB）；Arthas 级要解包约 20 MB 闭包，且 Instrumentation 附加可能 spawn helper 子进程并等待最长 30 秒。已改为经 `submitAsync` 异步执行后回执（`status` 为纯内存读取，保持同步）。
2. **并发下的幂等误报**：命令层"先查状态再调用"的写法在排队执行时失效——多条命令都在预检时看到未加载/已加载，导致重复 `arthas on` 复述上一次 attach 结果、连续 `arthas off` 把空操作回报成卸载成功。已把幂等判定收敛到实现内部（`startRuntime()` 返回"无需重复开启"说明；`stopRuntime()` 返回是否确实卸载），命令层不再做竞态预检。`McpControlPlane.enable()` 同理，避免并发 `on` 把已监听的端点在无人使用的情况下重启。
3. **增强类命令后卸载导致服务器崩溃**：执行 `watch`/`trace` 等 retransform 类命令后再卸载，若未还原字节码，被插桩方法仍回调已释放的 Arthas 类 → 下一 tick NPE 崩服务器（Java 8 + Arthas 3.1.1 实测 4/4 必现，崩溃点即被插桩方法）。修复：runner 的 `close()` 先执行 Arthas `reset` 再拆 bootstrap 与加载器；复位失败只告警并继续释放。**复验：7 次 `watch → off` 全部存活，0 次 NPE，`crash-reports/` 未生成，复位 WARN 0 次，且卸载后可再次加载。**
4. **attach 失败时状态误报“已加载”**：Java 8 纯 `-jar` 启动下 `com.sun.tools.attach.VirtualMachine` 不可见（在 `lib/tools.jar` 里），两条 attacher 链均失败，但 `status` 仍报“已加载”而 `arthas_execute` 实际 FAILED。修复：`runtimeReady` 改为“控制器已注册 **且** Instrumentation 可用”；并让重试可用——attach 失败时回收残留控制器，避免运维陷入“显示未加载、却被幂等拦截”而无法重试。

## 6. 风险

- **Arthas 运行时二次加载（已验证可行，双分支均通过）**：Java 17（Arthas 4.3.2）与 Java 8（Arthas 3.1.1，premain 模式）真机均验证通过——卸载后重新加载成功、`version` 与依赖 bootstrap spy 的 `watch` 命令均真实可用，证明不可撤销的 `appendToBootstrapClassLoaderSearch(spy.jar)` 在二次加载后仍有效。ADR-0028 记录的降级方案（卸载保留 ClassLoader）无需启用，仅作为兜底保留。
- **Java 8 上 Arthas 的前置条件（非代码缺陷，环境约束）**：Java 8 的 `com.sun.tools.attach.VirtualMachine` 位于 `lib/tools.jar`，纯 `-jar` 启动的 classpath 与 helper 子进程都看不到它，故两条 attacher 链全失败，必须用 **premain** 路径。且 premain 在 Paper 1.12.2 上**不能用完整发行 jar**（父优先类加载会把 `BukkitPlugin` 当系统类加载导致插件加载失败），须用项目自带的瘦 agent `serverprobe-mcp-java8-agent.jar`。
- **卸载前必须复位增强类（真机发现的崩溃缺陷，已修）**：执行 `watch`/`trace` 等 retransform 类命令后再卸载，若未还原字节码，被插桩方法仍回调已释放的 Arthas 类 → 下一 tick NPE 崩服务器（Java 8 真机 4/4 必现，崩溃点即被插桩方法）。修复：runner 的 `close()` 先执行 Arthas `reset` 再拆 bootstrap 与加载器；复位失败只告警不阻止释放（否则线程与加载器永不释放、JVM 无法退出）。
- **线程泄漏**：`ArthasTaskManager` 的固定线程池与超时调度器均非 daemon，卸载必须确保关闭，否则会阻止 JVM 退出。真机验收第 5 / 7 项与 5 轮开关的线程快照已确认清理彻底。
- 端点级开关的单测无法覆盖成功路径：`enable()` 需要插件数据目录与真实端口绑定，不宜在单测中执行；该路径由真机验收第 3 / 7 / 8 项覆盖。
- **异步执行的取舍**：启停动作改为异步后，回执不再与命令输入同步（快速连发时输出按完成时序排列）。这是避免主线程阻塞的必然代价；纯内存读取的 `status` 保持同步以便立即回显。
