# 更新日志(Changelog)

本项目所有重要变更均记录于此文件。

本文件格式遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [语义化版本(SemVer)](https://semver.org/lang/zh-CN/)。

> 最新版本 **0.2.0(2026-08-24)** 已正式发布；推送版本标签后由 GitHub Actions 构建并附加发行 jar。

---

## [未发布] - 待版本

### 新增（FR-15~23 MCP 深度诊断能力扩展，同一分支并行开发）

- **FR-23 MCP 工具描述增强（接线地基）**：`McpJsonRpcDispatcher` 支持多 provider 组合（`List<McpToolProvider>` 按工具名路由），新增 `McpToolProviderRegistry` 供扩展 provider 注册；`McpTool` 增加 `usageExample`/`outputFields`/`workflow` 元数据，`tools/list` 为每个工具下发结构化中文描述（用途/参数/示例/异步工作流/输出字段/限制），外部 agent 仅凭描述即可正确调用。
- **FR-15 MCP 插件维度诊断入口**：`plugin_list`（已加载插件清单/元数据）、`plugin_classes`（按插件枚举可解析类，经 `JavaPlugin.file` 实例解析禁止路径拼接）、`plugin_threads`（按 `PluginClassLoaderRegistry.ownerOf` 过滤归属线程栈，≤128×64 有界）、`plugin_cpu`（复用 `CpuAttributionSampler` 窗口占比，未启用/零样本降级）。
- **FR-16 MCP 日志流式检索**：`log_tail`/`log_search`（尾部 N 行/关键字搜索 + 字节游标分页；路径规范化前缀校验防穿越；平台字符集解码；超长行截断且游标必前进；文件缺失结构化降级）。
- **FR-17 MCP 区块/实体明细**：`server_status` 的 worlds 输出增强 `entityTypeCounts`（复用 `BukkitWorldCollector` 缓存，不重复遍历）与 `regionStats`（Folia 透传 FR-12 `ObservedRegionMetrics`）；provider 缺失降级保持向后兼容。
- **FR-18 MCP 二进制产物安全回传**：`McpArtifactWorkspace.readBinaryChunk` + `artifact_read_binary`（Base64 编码二进制分块，默认 64 KiB/上限 1 MiB；与文本 `artifact_read_chunk` 并存；名称白名单防穿越）。
- **FR-19 MCP 补丁前自动备份**：`ArthasControl.dumpClassBytes` 接口扩面（默认 null=能力缺失→WARN+跳过+继续，读取失败→拒绝替换）；`arthas_redefine`/`arthas_retransform` 替换前自动备份 `backup_<类名转义>_<时间戳>.class`（扁平命名符合工作区白名单）；`artifact_backup_list`/`artifact_backup_restore`（恢复写回 `restored_` 供重新替换）。
- **FR-20 MCP 在线玩家诊断明细**：`player_lookup`（位置/血量/背包摘要无 NBT/区块/最近事件时间线；主线程周期缓存防阻塞，单周期上限 200；`BoundedEventRing` 有界事件缓冲）；**隐私红线**：默认开启随 MCP 开关、鉴权+审计参数 SHA-256、不落盘/不进 Prometheus/Web/历史；代理端结构化降级。
- **FR-21 MCP 运行期 CPU 火焰图**：`flamegraph_start`/`flamegraph_stop`（薄封装 `arthas_profiler`，默认命名 `flamegraph-<epochMillis>.html` 消歧）+ `flamegraph_view`（按扩展名分流：`.html` 文本分块 / `.jfr` 元信息提示走 `artifact_read_binary`）；**不推翻 ADR-8**（不自研采样器）。
- **FR-22 MCP GC/JFR 详诊**：`gc_events`/`gc_stats`（`GcDiff` 纯函数差分，计数/耗时回退钳制归零；请求驱动差分窗口）+ `jfr_start`/`jfr_stop`（经内嵌 Arthas，默认命名 `jfr-<epochMillis>.jfr`，产物配合 FR-18 回传）。

> 全部 9 条 FR 均完成**实现 + 单元测试 + detekt + IoC 静态分析（errors=0）**，`./gradlew build` 全绿；**真机验收**：Paper 1.20.1（Windows）与 WSL Linux（Paper 1.20.1 + Temurin 17）双平台真机通过，详见下方"修复"与验收记录。

### 修复（真机验收发现）

- **MCP 工具列表重复爆炸（真机）**：`McpJsonRpcDispatcher` 多 provider 组合下 `providers.values` 含同一实例的多个引用，`flatMap` 展开导致 `tools/list` 返回 256 个重复工具（26 工具 × ~10 次）。修复：`tools/list` 按 provider 实例 `distinct()` + 同名工具 `groupBy.last()` 去重，现 42 个工具零重复；补"同实例多工具不重复展开"回归测试。
- **MCP 工件工作区未注册（真机）**：`McpControlPlane.startServer` 创建 `McpArtifactWorkspace` 后未注册进 `McpArtifactWorkspaceRegistry`，导致 `artifact_read_binary`/`flamegraph_*`/`jfr_*`/备份等扩展工具返回"当前未启用 MCP 工件工作区"。修复：`startServer` 补 `register`、`stopServer` 补 `unregister`。
- **扩展 provider 构造参数注入失效（真机）**：`BinaryArtifactToolProvider`/`FlamegraphToolProvider`/`PrePatchBackupToolProvider` 以构造参数注入 `McpArtifactWorkspaceRegistry`/`ArthasControl`，真机 IOC 装配下与 `McpControlPlane` 注入的 registry 非同一实例。修复：统一改为**字段注入 + 构造参数仅测试注入**（与 `GcJfrToolProvider` 同款）。
- **MCP HTTP 服务 Linux backlog=0 连接挂起（真机）**：`McpHttpServer` 的 `BACKLOG=0` 在 Linux 上 accept 队列为空、连接超时（Windows 有默认值掩盖）。修复：`BACKLOG=64` 兼容两平台。
- **Arthas 闭包缺失 async-profiler 原生库（真机）**：FR-14 构建脚本仅从官方 bin zip 提取 4 个 jar，漏了 `async-profiler/libasyncProfiler-*.so`，Linux 上 `flamegraph_*` 报 "Can not find libasyncProfiler so"。修复：构建脚本提取 3 平台原生库入闭包；运行期提取补 `async-profiler/` 子目录布局（`ProfilerCommand` 按 core jar codeSource 相对定位）+ 无后缀 `libasyncProfiler.so`（`AsyncProfiler.loadLibrary` 的 `one.profiler.libraryPath` 属性，`ArthasMemoryShellRunner` 初始化 Bootstrap 前显式 `System.setProperty`）。WSL Linux 真机验证：`flamegraph_start/stop` 真实产出火焰图 HTML，`flamegraph_view` 分块取回完整。
- **WSL2 mirrored 网络模式破坏 loopback（环境结论）**：mirrored 下 WSL 内连 `127.0.0.1` 被 Windows loopback 劫持导致 MCP 不可达（非代码缺陷）；回退 NAT 模式 + MCP 监听 `0.0.0.0` 后 WSL 内正常。

### 修复（代码审查发现，sdd-review-code 556660f）

- **`plugin_threads` 生产恒返回空列表（blocking）**：`threadSource` 默认实现把 dump 的 `stack` 丢弃（`stack=emptyList()`），`filterOwned` 只数 stack 命中帧 → 生产调用恒 0 命中。修复：`NativeThreadDiagnostics` 新增 `threadSamples()` 保留原始 `StackTraceElement`（含 className），默认 `threadSource` 改用它；补"默认采样源保留真实栈帧"回归测试。
- **`ArthasControlRegistry` 未转发 `dumpClassBytes`（blocking）**：FR-19 自动备份经 Registry 委托链恒返回 null（"能力缺失→跳过备份"），生产 100% 不生效。修复：Registry 显式转发；`ArthasMemoryShellRunner` 新增 `dumpClassBytes`（经 Instrumentation `getBytecodes`），`ArthasTaskManager` 经 `bytecodeReader` 参数接入；补委托链回归测试。
- **`GcJfrToolProvider.arthasControl` 构造参数未改字段注入（blocking）**：生产 IOC 下 `jfr_start/stop` 恒 FAILED。修复：与 Flamegraph/PrePatch 一致改 `@Inject lateinit var arthasControlRegistry` + 构造参数仅测试注入。
- **`BukkitWorldDetailProvider` 对可选注入无条件解引用（blocking）**：`foliaObservedRegionService` 标 `@Inject(required=false)` 但直接调用，注入缺失时 NPE 全量降级。修复：判空 `?.snapshot()?.regions ?: emptyMap()`。
- **日志路径回退工作目录架空前缀校验（major）**：`BukkitLogPathProvider` 取不到根目录时回退进程工作目录，使 core 的 `LogPathGuard.withinRoot` 前缀校验形同虚设。修复：回退改为 null（工具降级"平台未提供日志文件"）；KDoc 修正"世界容器目录"语义。
- **玩家事件监听注册依赖插件加载时序且不重试（major）**：`BukkitPlayerDiagnosticsProvider` 的 `getPlugin("ServerProbe")` 失败被吞且不重试，`recentActivity` 可能永久为空。修复：`registerListenerWithRetry` 按 20 tick 间隔重试（最多 10 次）。
- **`BukkitPluginMetadataProvider` 空工具面占位（major）**：`tools()=emptyList()` + `call()` 抛错，core provider 未就绪时 FR-15 工具全挂。修复：兜底返回与 core 同名工具目录 + `call()` 结构化降级。
- **`McpControlPlane.startServer` 注册早于 HttpServer.start（major）**：端口占用启动失败时留下工作区幽灵注册与审计线程泄漏。修复：`register` 移到 `server.start()` 成功之后。
- **`arthas_ognl` 描述误标"同步"（major）**：补 `ASYNC_WORKFLOW`，对齐其他异步工具描述。
- **`BoundedEventRing` 并发非精确有界（major）**：`ConcurrentLinkedDeque.size()` O(n) 弱一致，瞬时可能超 capacity。修复：改用 `LinkedBlockingDeque`（`offerLast` 满则先 `pollFirst`，严格有界）。
- **`backupRestore` 先解析后校验前缀（minor）**：调为前缀校验先行（最小特权），测试断言同步。
- **`artifact_read_binary` maxBytes 负值/零语义不明（minor）**：入参层与 offset 同款钳制（非正回退默认 64 KiB）。

## [0.3.0] - 2026-09-05

### 新增
- **FR-08 存储扩展注册**：新增 `ServerProbeStorageApi.install` 与 `MetricStoreRegistration`。运行期最多启用一个第三方 `MetricStore`，关闭注册后原子回退内置本地文件实现；重复安装和陈旧句柄均有明确保护。
- **FR-08/FR-09 自动化验收**：接入 mc-testkit，在真实 Paper 1.20.1 中验收第三方读取 API、存储 SPI 替换，以及不依赖 JianManager 的本地 RFC 6455 Worker 协议夹具。FR-09 覆盖桥握手、manifest、命令成功/失败/超时回执、业务事件和超时后的恢复。
- **内置业务集成模块化（FR-10）**：新增 `integration-multicurrencyeconomy` 与 `integration-allininventorysync` 仓库内模块并合入单 jar；未安装对应插件时不加载其类型、不影响探针。mc-testkit 注入真实 MultiCurrencyEconomy 1.2.0 与 AllinInventorySync 2.1.0-SNAPSHOT 完成读写动作、幂等命中、业务事件与失败/超时恢复验收，另验证双缺失/单缺失场景；运行期断网（HTTP/HTTPS 代理 `127.0.0.1:9`）下由 616 项离线闭包支撑。
- **全平台网络取证（FR-11）**：Bukkit/Spigot/Paper/Folia/BungeeCord/Velocity 全平台采集双向流量、包速率与包类型计数；本地 SQLite 保存包元数据与白名单载荷（单包默认 64 KiB、前缀截断保留原始长度与完整 SHA-256），默认保留 60 天、上限 4 GiB；Prometheus 仅输出聚合速率、包类型计数与脱敏 IP Top100，完整 IP/载荷仅经鉴权 Web 面板与 FR-08 只读 API 可查；驱动失败仅关闭取证并打印中文 WARN。六平台真实协议 E2E 全部 PASS。SQLite JDBC 现为发行 jar 内嵌闭包。
- **Folia 已观测 region 指标（FR-12）**：真实 ticking region 的 TPS/MSPT avg/p95/p99 明细与按世界汇总，region 离开玩家 60 秒后过期；全局 TPS/MSPT 保持 N/A。双协议 bot 真机验证隔离 region 与受控负载隔离。
- **Velocity 3.1.1–4.x 平台支持（FR-13）**：新增 `platform-velocity`，同一 jar 覆盖 Velocity 3.1.1 至 4.1.0；能力与 BungeeCord 对齐（总在线、后端 RTT/可达性、玩家路由、玩家 ping）并接入网络取证；共同源码经 API 3.1.1 与 4.1.0 双 `compileOnly` 编译门，发行类保持 Java 8。3.1.1（legacy forwarding，唯一允许 legacy 的版本）/3.5.1/4.1.0（JDK25）三组真机矩阵 PASS。
- **存储治理：磁盘占用大幅下降 + 通用数据库命名**：取证库更名 `network-forensics.sqlite` → `serverprobe-store.sqlite`（未来所有结构化数据的统一库，旧库启动时自动迁移）；启动画像 agent 栈样本按 `agent-stack-max-samples`（默认 10）均匀抽稀，单份画像从 4.64MB 降至 KB 级；指标 jsonl 过期后 gzip 归档（实测 14.6MB→0.9MB），归档保留期 `history-file.archive-days` 默认 30 天，历史查询自动透明解压；外呼日志（`http-monitor.file-retention-days`/`file-archive-days`）与 MCP 审计（`mcp.audit-max-file-mb`/`mcp.audit-retention-days`）同步支持过期归档与轮转。
- **旧版本兼容性修复（多版本矩阵真机发现）**：修复 JDK 8 上 IoC 注入链因 `@PlatformSide` 枚举数组注解解析异常而整体中断的问题（1.8–1.16 上组件 `@Inject` 字段全部未注入）；1.8–1.11 服务端改用首帧兜底装配启动画像（该平台无 `ServerLoadEvent`）；1.16 之前 Paper 缺原生 MSPT API 时改由自研 tick 时钟直方图兜底估算（TPS 仍走官方 API）。多版本矩阵（Paper 1.8.8–1.21.1 + Spigot 1.8.8/1.16.5，Java 8/17/21）十场景真机全部 PASS。
- **Arthas 获取链三级化（FR-14 增强）**：Instrumentation 获取按序自动尝试 premain → self-attach → helper 子进程外置注入（spawn 一次性进程对目标 PID attach + loadAgent，跨进程 attach 不受 JDK 9+ self-attach 限制），自动选择首个成功来源；未挂 `-javaagent`、未加 `-Djdk.attach.allowAttachSelf=true` 的服务器现在无需任何启动参数即可启用 Arthas 深度诊断。
- **外部 MCP 深度诊断控制（FR-14）**：默认关闭的 MCP Streamable HTTP（JSON-RPC 2.0）控制面，覆盖状态/指标、平台命令、线程 CPU Top/栈/死锁与诊断 bundle；内嵌 Arthas Core（Java 8–16 用 3.1.1、Java 17+ 用 4.3.2，构建期原子提取、SHA-256 校验、隔离 ClassLoader 引导），`arthas_execute` 兜底全部命令，持续命令异步任务化（并发/超时/输出/保留均可配）；产物统一写 `mcp-workspace/` 并支持分块读写与清理；审计记录来源 IP/工具/任务/耗时/参数 SHA-256。七组真机场景（Java 8/21/25 × Bukkit/Paper/Folia/BungeeCord/Velocity/Spigot）全部 PASS，Arthas 上游 Telnet/HTTP/MCP 端口不监听，非回环或空密钥时打印醒目中文 WARN。

### 修复
- **CI 依赖解析修复**：`integration-allininventorysync` 的 `allininventorysync-api` 依赖由 `2.1.0-SNAPSHOT` 降级为远程已发布的 `2.0.0`——`2.1.0-SNAPSHOT` 仅存在于本地 Maven 缓存、从未发布到 `maven.wcpe.top` 等公共仓库，导致 GitHub Actions 全新环境（空 mavenLocal）解析 `compileClasspath` 失败。已核对 `2.1.0-SNAPSHOT` 相对 `2.0.0` 仅新增 `PlayerBackup` 与 `listPlayerBackups`/`executePlayerRollback`/带 reason 物品写重载（探针代码均为反射调用、未使用新 API），降级兼容无破坏；模块 18 项单测全绿。
- **TabooLib 仓库收口 + 升级至 `6.3.0-wcpe.1`**：`repoTabooLib` 由聚合仓库 `maven.wcpe.top/repository/maven-public`（混有 test2 等版本）切到仅含 release 的 `maven-tabooproject-release` 镜像，消除版本解析噪音；运行库同步升级至 `6.3.0-wcpe.1`（含 `incision` 在内的全模块闭包均解析自新镜像）。产物 `META-INF/taboolib/version.properties` 确认 `taboolib=6.3.0-wcpe.1`，`env.properties` 的 `repo-taboolib` 指向 `maven-tabooproject-release`；本地 `./gradlew build` 全绿。

### 文档
- **PRD 结构规范化（realign）**：按 SDD 模板重构 `docs/PRD.md` 为 §1~§8 结构——FR 统一 `FR-NN` 零填充编号（FR-01~FR-14，子能力收进正文）、优先级收敛为 P1/P2/P3、状态列采用模板枚举（`已交付@vX.Y.Z` / `开发中`，FR-10~FR-14 待发版登记）、新增 §2 角色 / §3 用户故事 / §6 期级验收清单 / §7 分期主题；移除 §10 里程碑表与过程性验收日志（并入 CHANGELOG / specs）。
- **specs 文件语义化重命名**：`fr8-fr9-e2e-acceptance.md` → `open-api-bridge-e2e.md`、`incision-poc.md` → `method-incision.md`、`fr10-built-in-integrations.md` → `built-in-integrations.md`、`fr11-network-forensics.md` → `network-forensics.md`、`fr12-folia-observed-regions.md` → `folia-observed-regions.md`、`fr13-velocity-platform.md` → `velocity-platform.md`、`fr14-mcp-diagnostics.md` → `mcp-diagnostics.md`；PRD / README / ARCHITECTURE / specs 内部引用同步更新。
- **过期注记清理**：`CONTRIBUTING.md` / `doc-sync.md` / `adr/README.md` 的 ADR 编号指引更新至 ADR-27；`scope-discipline.md` 活跃范围更新为 v0.2.0 已交付 + FR-10~FR-14 待登记；`API.md` Web 面板描述由"规划中"改为"已交付"；`specs/README.md` / `_template.md` 的 M3/M4 过时措辞改为 P1/P2/P3。

## [0.2.0] - 2026-08-24

### 变更
- **标签发布自动化**：推送 `v*` 标签后，GitHub Actions 执行完整构建、上传 `ServerProbe-*.jar`，并创建或更新对应 GitHub Release。

### 新增
- **Incision 方法级精确归因(FR7)**：Bukkit/Paper 端以 VanillaModify 同款 `@Surgeon`、`@Lead`、`@Trail` 接入 `SimplePluginManager#enablePlugin`；TabooLib Gradle 插件升级为 `2.0.37-fix`、运行库升级为 `6.3.0-5b6fe60`，运行期模块改用可用镜像。`incision.enabled` 默认 `false`，关闭时不写精确数据；无效切点保持普通启动画像。Paper `1.21.11-132` + JDK `21.0.4` 真机验证已记录 `IncisionTarget` 精确耗时；受控 5ms/tick 负载下 MSPT p95 为 `6.7194ms → 6.6765ms`（`-0.64%`）。
- **在线玩家 ping 分布(FR2.4)**:服务端按固定区间桶(`<50ms`/`50-100ms`/`100-200ms`/`200-500ms`/`500ms+`)统计在线玩家 RTT 分布,1.16.1+ 经 `Player#getPing()`(内存读取、开销小),低版本无该方法时降级 N/A(绝不成为事故源)。纯分桶逻辑抽 `PingBuckets`(可单测,5 例边界/非法/空列表)。新增 `/probe ping` 子命令查看分布;Prometheus 导出 `serverprobe_players_ping_bucket{range}`。api 模型 `ServerMetrics` 加 `pingDistribution`(向后兼容,默认 null)。`./gradlew build` 全模块编译 + detekt + 单测全绿;真机各版本口径待端到端复验。
- **代理端子服 ping / 可达性 / 玩家路由 / 每玩家 ping(FR2.5)**:platform-bungee 后台调用 `ServerInfo#ping(Callback)` 计时 RTT并缓存，采集线程只读结果；同时采集玩家所在子服与玩家 ping。BungeeCord #2088 + 两个 Paper 1.20.1 后端 + 两名玩家真机已验证总在线、后端在线、RTT/可达性、切服路由与玩家 ping；BungeeCord `1.19-R0.1 #1700` + Java 8 另已验证单 jar、命令与 `/metrics`。
- **多端兼容性验收(FR6)**：同一发行 jar 已在 Spigot 1.8.8 + Java 8、Paper 1.21.x + JDK21、Folia 1.21.4 + JDK21 与 BungeeCord 真机加载并采集对应指标；Folia 全局 TPS 按设计降级为 N/A。
- **运行期 CPU 采样归因(FR2.6,M3,P2)**:core 新增 `CpuAttributionSampler`(`ThreadMXBean.dumpAllThreads` 周期采样全部线程栈,每线程至多 40 帧)+ `PluginClassLoaderRegistry`(插件 ClassLoader 注册表 + 类名→插件解析缓存,避免每帧遍历 CL)。窗口滑动聚合(默认 60 轮 ≈ 1 分钟),输出各插件样本计数与占比。platform-bukkit 新增 `BukkitPluginClassLoaderRegistrar`(反射取 `JavaPlugin#getClassLoader`,兼容各版本可见性)。新增 `/probe cpu` 子命令;Prometheus 导出 `serverprobe_plugin_cpu_samples_total{plugin}` / `serverprobe_plugin_cpu_percent{plugin}`。**默认关闭**(`cpu.enabled=false`,P2 增强)。`./gradlew build` 全模块编译 + IOC(errors=0) + detekt + 单测(注册表归并/缓存/注销)全绿;真机(Paper 多插件负载)待端到端复验。
- **Web 面板(FR4.3,M3,P2)**:core 新增 `WebPanelServer`(JDK 内置 `HttpServer` 零依赖,daemon 线程池)+ `WebPanelHtml`(无状态纯渲染)。三个只读页面:总览 `/`(最新快照 JVM/TPS/在线/内存)、启动画像 `/startup`(总时长/慢插件 Top-N/世界耗时)、历史趋势 `/history`(最近 120 份快照 TPS/MSPT/在线时序表);自包含 HTML(内联 CSS、无 CDN、文本 HTML 转义防注入)。安全:token(`Authorization: Bearer`)+ IP 白名单双重校验,默认仅本机、**默认关闭**(`web.enabled=false`),起服失败/单请求异常静默降级(探针不成事故源)。`./gradlew build` 全模块编译 + IOC(errors=0) + detekt + 单测(页面占位/转义)全绿;真机(浏览器访问三页)待端到端复验。

### 修复
- **BungeeCord 单 jar 平台隔离**：移除未使用的 `BukkitUI` 环境模块，避免 Bungee 启动加载 `bukkit-nms`；以项目内兼容扫描器在反射前按 `@PlatformSide` 过滤 IoC 组件，避免解析 Bukkit 方法签名。BungeeCord Java 8 与 #2088 多子服环境均已验收。
- **业务对接 agent 骨架(JBIS,ADR-0015)**:ServerProbe 演进为 JianManager 业务对接 agent 的探针侧骨架。`core/bridge` 新增 `BusinessProvider` 接口(业务域 / 动作 / 能力清单 / 派发)+ `BusinessHost`(域键路由 + **事故域隔离**:独立 daemon 业务线程池 + 有界超时 + 异常边界 + 合并 manifest);`BridgeCommand` 加 `domain`/`payload`,`BridgeClient` 收到带 domain 的业务 `command` 帧时路由到对应 Provider,治理命令走既有路径(业务 / 监控分流)。Provider 卡死 / 抛异常只降级该次回执,**监控采集与桥读线程不受影响**。core 平台无关(无 Bukkit 符号),业务 Provider 实现落 platform 层。`./gradlew build` 编译 + 单测(路由 / 未注册降级 / 抛异常隔离 / 卡死有界超时 / 注册)+ detekt 全绿;**业务 Provider 接入(经济等)与端到端真机待续**。
- **经济业务 Provider(对接 MultiCurrencyEconomy,JBIS)**:platform-bukkit 新增 `EconomyProvider`(`economy` 域),`compileOnly` MultiCurrencyEconomy 公开 api(运行期由目标服务端提供),经 `MultiCurrencyEconomyApi` 发现 + 就绪判定;首个动作只读 `economy.balance`(按 player + currency 查余额,金额以 BigDecimal 字符串承载防失真),mce 未就绪 / 参数缺失 / 查询异常一律降级失败。`@Service` + `@PlatformSide(BUKKIT)` + `@PostConstruct` 平台门 + 桥开关门自注册到 `BusinessHost`(沿用 `BukkitBridgeCommandHandler` 范式)。`./gradlew build` 全模块编译 + IOC 静态分析 + detekt 全绿;**真机(真 MultiCurrencyEconomy 服查真实余额)待端到端复验**。
- **业务能力清单桥查询(JBIS 元查询)**:`BridgeClient` 收到保留元命令(domain=`jbis` + action=`manifest`)时返回 `BusinessHost` 汇总的各业务 Provider 能力清单 JSON,供 JianManager 动态发现业务能力(不硬编码具体插件);该元命令不派发到任何业务 Provider。core 编译 + detekt 全绿。
- **经济变更事件上报(JBIS,对接 JM FR-122)**:platform-bukkit 新增 `BukkitEconomyEventListener`(`object` + `@SubscribeEvent`),订阅 MultiCurrencyEconomy `PlayerEconomyChangeEvent`(持久化投递流,覆盖 web 后台/跨服一切余额变更,下游唯一可靠源)与 `PlayerEconomyCatchupEvent`(玩家上线补发离线缺口),折算为 `economy` 业务**事件**经既有反向 WS 桥上报本机 Worker(→ JM CP 按 ledgerId 去重 + node→zone 聚合)。`core/bridge` 的 `BridgeClient` 新增 `emitBusinessEvent`(`event` 帧带顶层 `domain`/`dedupKey`,信封 data 携 currencyId→identifier、zoneId、signedAmount/balanceAfter 字符串、entryType、seq、occurredAt;空值不发、未连静默丢弃、绝不抛)。currencyId(Int 主键)经 `getActiveCurrencies()` 映射为全局稳定 identifier(跨服/跨区同币主键可能不同,按 identifier 聚合方不串味),映射缺失回退 Int 不丢事件;纯折算/映射逻辑抽 `EconomyEventEnvelope`(脱离 Bukkit 运行期、可单测、降 detekt 复杂度)。两事件均 mce 异步事件,监听器在非主线程触发且整体 runCatching 兜底(事故域隔离,绝不拖垮探针/mce)。`./gradlew :platform:platform-bukkit:build` 全模块编译 + IOC 静态分析(errors=0) + detekt + 单测(映射/回退/去重键/信封编码/大额防科学计数)全绿;**真机(web 后台/其他服改余额汇聚到 JM、跨区不混)待端到端复验**。
- **经济业务 Provider 扩写动作(JBIS,对接 MultiCurrencyEconomy)**:`EconomyProvider` 在只读 `balance` 基础上新增七个写动作——`deposit`(加)/`withdraw`(扣)/`adjust`(有符号差额校正)/`set`(无原生设值,经「查余额→算差额→adjust」实现、非原子)/`transfer`(玩家间转账)/`consume`(原子消费产流水号)/`refund`(按消费流水号退款),各经 mce `MultiCurrencyEconomyService` 子服务执行。守 mce 写契约:**幂等键** pluginName=`JianManager` + `IdempotencyMode.BusinessOrder(taskId)`(taskId 由 JianManager 侧生成,缺则拒绝;重试须复用同键防 `MCE-LEDGER-0001` 冲突);**金额 BigDecimal 字符串承载**(禁浮点);mce 业务失败(余额不足 / 账户冻结 / 金额非法…)以结构化结果回传(success/status/errorCode 透传),仅 Provider 级错误(未就绪 / 参数缺失 / 解析失败 / 调用抛异常)回 `BridgeCommandResult.fail`。纯解析 / 校验 / 结果编码逻辑抽到 `EconomyEnvelope`(脱离 Bukkit 运行期、降 detekt 复杂度)。`./gradlew build` 全模块编译 + IOC 静态分析 + detekt + 单测全绿;**真机(真 mce 加 / 扣 / 转账成功且幂等、余额不足等错误码正确)待端到端复验**。
- **背包业务 Provider(对接 AllinInventorySync 2.0.0,JBIS,对接 JM FR-125)**:platform-bukkit 新增 `InventoryProvider`(`inventory` 域),`compileOnly` AllinInventorySync 自包含 api(2.0.0 起纯 Java + Lombok,运行期由目标服务端提供),经 `AllinInventorySyncProvider.isAvailable/get` 发现 + 降级。只读 `view`(`getPlayerInventory(uuid)` 回源含离线 → 结构化视图:背包 / 末影箱物品数组 + 基础属性 + online + dataVersion,玩家无数据回 `exists=false`)+ 基础属性写 `writeBasicAttrs`(经写门面 `getInventoryWriteApi()`,落盘回执 `WriteResult` 透传 success/online/newDataVersion/errorCode)。守写契约:幂等键 `taskId`(CP 注入)→ 写门面 `requestId` 持久去重(缺则拒绝),`base + edited` 净改动 delta 透传,operator 透传(空回退 `JianManager`)。**物品写 `writeInventory`/`writeEnderChest` 暂不提供**——AllinInventorySync 2.0.0 物品写门面入参退回不透明分区字节(`byte[]`),外部集成无法从结构化物品构造,收到即明确降级、不进 manifest(见 [ADR-0017](docs/adr/0017-inventory-write-degraded-byte-facade.md) 取代 [ADR-0016](docs/adr/0016-inventory-business-item-transport.md) 决策 1/2,待其导出可外部消费的结构化物品写门面再恢复)。`@Service` + `@PlatformSide(BUKKIT)` + `@PostConstruct` 自注册到 `BusinessHost`;纯解析 / 校验 / 编码逻辑抽 `InventoryEnvelope`(可单测,encode 返纯 Map)。`./gradlew build` 全模块编译 + IOC(errors=0)+ detekt + 单测(manifest 二动作 / 物品写降级 / 读写校验 / 属性解码 / 视图 · 物品 · 回执编码)全绿;**真机(真 AllinInventorySync 服读真实背包 + 写属性、回执正确)待端到端复验**。
- **背包追踪事件上报(JBIS,对接 JM FR-126)**:platform-bukkit 新增 `BukkitInventoryEventListener`(`object` + `@SubscribeEvent(bind=FQCN)`,软依赖按名绑定避免漏注册,同经济监听器教训),订阅 AllinInventorySync `TrackedItemActionEvent`(重点物品流转:登录携带 / 丢出 / 拾取 / 移入容器),折算为 `inventory` 业务**事件**经反向 WS 桥上报本机 Worker。信封 data 携 playerName / playerUuid / action / ruleId / ruleDescription / material / amount / displayName / occurredAt;物品只编 Bukkit-API 便利字段(无 `nbtBase64`——全保真 codec 在 AllinInventorySync core 非 api,见 ADR-0016);瞬时观测无插件侧持久 ID,去重键 `playerUuid:action:occurredAtMs:seq`(会话单调 seq 去歧义)。`@SubscribeEvent` 收 `OptionalEvent`、`get<T>()` 取强类型(无 AllinInventorySync 时 bind 不触发,零副作用);整段 runCatching 兜底(事故域隔离,绝不拖垮探针)。纯折算逻辑抽 `InventoryEventEnvelope`(可单测,3 例);plugin softdepend `AllinInventorySync`。`./gradlew build` 全绿;**真机(重点物品流转汇聚到 JM)待端到端复验**。

### 修复
- **BungeeCord 类型隔离与新版 ping API 兼容**：采集器不再让 `ServerPing`/`ProxiedPlayer` 进入可被 Bukkit IOC 反射的方法签名；子服延迟改为反射查找 `ping(Callback)` 并以动态代理接收回调，失败或超时统一降级为不可达。成功、失败、超时单测与 BungeeCord #2088 多后端真机均通过。
- **BukkitPluginClassLoaderRegistrar 反射取 classLoader 兼容性(真机修,FR2.6)**:原用 `getMethod("getClassLoader")`,Paper 1.20.1 的 `JavaPlugin.classLoader` 为 private 且无公开 getter,致真机**注册 0 个插件 ClassLoader、CPU 归因归并失败**。改 `getDeclaredField("classLoader")` + `setAccessible(true)` 读取(兼容各版本可见性)。真机复验:注册 4 个插件(ServerProbe/CoreLib/AllinInventorySync/MultiCurrencyEconomy),`/probe cpu` 与 `serverprobe_plugin_cpu_*` 正常输出。
- **背包基础属性写解码对齐 JianManager 契约(FR-125/126/127,真机修)**:`InventoryProvider.writeBasicAttrs` 的 base/edited 解码原直读容器顶层字段且仅按字符串解析,而 JianManager 前端与 CP 审计的既定契约为 `{dataVersion,basicAttrs:{health,foodLevel,xpLevel,xpProgress,xpTotal,gameMode}}` 嵌套容器、数值字段为 JSON 数值——致契约 payload 全字段回退默认(血量 0.0/饱食 0/经验 0)写入 AllinInventorySync,**在线玩家被直接写死、离线玩家上线即死**(真机 Paper 1.20.1 + AllinInventorySync 2.1.0 复现)。解码逻辑下沉 `InventoryEnvelope.decodeBasicAttrs`(可单测):容器先取 `basicAttrs` 嵌套、缺失回退扁平直发;数值逐项先按字符串解析、再经新增 `JsonObject.getDouble`/`getInt` 按数值节点兜底;manifest note 同步为嵌套契约。真机复验:契约格式在线/离线写属性精确落库生效(xp 20→9、hp 20→15.5)、物品与其余属性不动、幂等重发 dv 不前移、玩家存活。补嵌套数值容器 + 扁平字符串兼容两单测(先红后绿)。
- **经济事件监听器按名绑定避免漏注册(对接 JM FR-122,真机修)**:`BukkitEconomyEventListener` 原用 `@SubscribeEvent` 直收 mce `PlayerEconomyChangeEvent`/`PlayerEconomyCatchupEvent`——探针 enable 时(即便 plugin.yml softdepend mce 保证后于 mce 加载)TabooLib 仍按方法反射参数类型解析事件类失败(`事件未能找到` WARN),致监听器**漏注册、经济事件零捕获**。改 `@SubscribeEvent(bind="事件全限定名")` 按名绑定(不在 enable 时解析类)+ 处理器收 `taboolib.common.platform.event.OptionalEvent`、`get<T>()` 取强类型(无 mce 时该 bind 自然不触发,零副作用);并在 `plugin/build.gradle.kts` 的 `description.dependencies` 加 `name("MultiCurrencyEconomy").optional(true)` softdepend 保序。真机(Paper1.20.1+mce)重启后 WARN 消失、监听器注册成功。
- **经济写动作透传操作者身份进 mce 流水(对接 JM FR-121,真机修)**:`EconomyProvider` 七个写动作原用默认 `OperationContexts.system()`,致 mce 审计流水操作者恒 `SYSTEM`、JM 管理员身份无法落到插件流水。改为从 payload 取 JianManager 注入的 `operator`/`nodeId`,经 `EconomyEnvelope.operationContext` 构造 `OperationContexts.of`(PLUGIN 类型、operator=管理员、sourceAction=`economy.<动作>`、nodeId 入 metadata 供追溯),operator 空回退 system()。真机验:CP 写路径下发后 mce 流水 `操作者=<管理员>`(非 SYSTEM)。补 operationContext 单测。

### 变更
- **JSON 编解码统一收口到可换适配器**(ADR-14):新增 `core/json` 的 `JsonCodec` 接口 + `Json` 门面 + 默认实现 `ConfigJsonCodec`(后端复用运行时 TabooLib `Configuration` / nightconfig,**零额外依赖**)。插件桥(删手写 `MiniJson` + `escapeJson`)、Webhook 告警、指标历史 / 启动画像落盘的 JSON 序列化与解析全部改走 `Json.encode` / `parse` / `decode`,换库(gson 等)只换实现、调用点不动。同时修正 ADR-10「优先用 gson」的事实偏差(gson 仅在构建期、运行期不携带)。火焰图查看器数据(递归树 / 瀑布数组、只构造不解析、绑定前端格式且性能敏感)为有据例外保留专用手拼。真机:Paper 1.21.1 插件桥连接握手 + 在线名册 + 踢人端到端通过;`./gradlew build` 编译 + 单测 + detekt 全绿。
- **`api` 模块改用纯 Java(Lombok 不可变模型)**(ADR-13):消除 Kotlin `@Metadata`,使任意 Kotlin(含 1.x)/ Java 版本第三方均可编译依赖 `serverprobe-api`。落盘字段名 JSON 不变(向后兼容既有 `data/`);内部对 api 模型的构造改用 builder。编译 + 单元测试 + detekt 已绿;**TabooLib 序列化落盘/读盘往返待 1.21.4 Paper 真机复验**。

## [0.1.0] - 2026-06-20

> 首个版本(本地 tag,未推送、无公开下载产物)。汇集启动剖析 + 运维指标 + 存储聚合 + 四通道呈现 + 告警 + 开放接口,以及可选启动期 premain agent 增强。`./gradlew build` 编译 + 单元测试 + detekt 全通过,并已在 **1.21.4 Paper 单端真机验证全通过**;其他端(1.8 / Folia / BungeeCord)构建通过、实验性未逐一真机。核心**零新增第三方依赖**,**仅可选启动 agent 引入唯一新依赖 ASM**(需手动 `-javaagent` 启用,relocate 隔离)。

### 新增
- 初始化项目骨架:`api` / `core`(`project:core`)/ `plugin` 三模块目录与基础构建配置(当前源码目录为空,从零开发)。
- 新增开源许可证 [`LICENSE`](LICENSE):**MIT License**。
- **M1 代码实现(P1–P9;编译 + 单测通过,真机验证待进行)**:
  - **多平台骨架**:Bukkit 系 + BungeeCord 单 jar 多端;env 按模块下沉;IOC(`@Service`/`@Inject`)装配。
  - **启动剖析(FR1)**:端到端总时长 + 各生命周期分段 + 逐插件启用间隔 + 启动画像 + 与上次对比。
  - **JVM 指标(FR2.1)**:堆/非堆/内存池/GC(明细 + young·old)/线程 + 死锁/类加载/CPU/uptime/jvmArgs。
  - **服务器 TPS/MSPT(FR2.2)**:多版本(Paper API / 低版本 NMS 反射 / 自采样)+ Folia 全局 N/A;MSPT p95/p99。
  - **`/probe` 命令(FR4.1)**:health/startup/tps/gc/world/proxy 六子命令 + 中英 i18n + 只读 API(FR8.1)。
  - **本地文件落盘(FR1.5)**:启动画像 JSON(TabooLib `Configuration` 序列化)+ `config.yml` 配置。
  - **代理端基础**:总在线 + 各子服在线(BungeeCord)。
  - 留 M2+:世界指标完整(FR2.3)、代理 ping/路由(FR2.5)、Prometheus(FR4.2)、Web(FR4.3)、告警(FR5)、Incision(FR7)。
- **M2 代码实现(M2-1~6;编译 + 单测 + 1.21.4 Paper 单端真机验证通过,零新增依赖)**:
  - **世界 / 实体采集(FR2.3)**:按世界统计已加载区块数、实体数、方块实体数,并可按实体类型给出分布;开销较高故独立限频采样(默认约 30 秒)、采样后缓存。Folia 走路线 1——仅给出区块数,实体 / 方块实体计数置 N/A。`/probe world` 由占位升级为真实数据。
  - **指标聚合(FR3.3)**:对近 N 份快照做跨快照统计——TPS 滑窗均值、MSPT 跨快照分位(p95 / p99)、GC 差分速率(年轻代 / 老年代的次数与耗时速率);`/probe tps` 增加"近期聚合"补充行。
  - **历史 JSONL 落盘(FR3.2)**:把每次采集的快照按实例分目录、按自然日滚动追加为 JSON Lines(`data/metrics/<实例>/metrics-<yyyyMMdd>.jsonl`);保留天数 + 总体积双闸清理,绝不删除当天文件。与内存环形缓冲、启动画像归档相互独立。
  - **开放接口扩面(FR8)**:`ProbeReadApi` 新增历史区间查询与聚合查询;`MetricStore` SPI 新增 `readStartupProfiles` / `readHistory` / 批量 `appendHistory`(均带默认实现,旧实现向后兼容);并提供 `ServerProbeApi` 静态门面便于第三方取用。
  - **Prometheus `/metrics`(FR4.2)**:基于 JDK 内置 `HttpServer` 零依赖实现;Bukkit 与 BungeeCord 各为独立进程、各起一个端点;鉴权采用 token(`Authorization: Bearer`)+ IP 白名单双重校验,安全默认为关闭且仅本机。指标统一前缀 `serverprobe_`,涵盖 JVM / 服务器 / 世界维度。
  - **告警引擎(FR5)**:内置枚举规则(TPS 偏低 / 过低、MSPT p95 过高、堆占用率过高、死锁)+ 防抖(持续 N 周期才触发)与恢复状态机;三通道输出——日志 / 游戏内 / Webhook(Webhook 走 JDK `HttpURLConnection`)。安全默认为关闭。
  - 坚持"探针不成事故源":各导出 / 落盘 / 告警通道全异步或限频、失败静默降级,真机印证 Prometheus 端口被占用时优雅降级、不影响插件启用。
  - 留 M3+:Web 面板(FR4.3)、CPU 采样归因(FR2.6)、Incision 方法级插桩(FR7)。
- **M5 启动期 agent 增强(可选,需手动 `-javaagent` 启用;1.21.4 Paper 单端真机验证通过)**:
  - **形态——二合一 jar**:同一个 `ServerProbe.jar` 既是 `plugins/` 插件、又可作 `-javaagent`;启动命令加 `-javaagent:plugins/ServerProbe.jar` 启用,**不加则纯插件模式照常工作**,功能完整。
  - **premain 注入服务器启动流程**:由 JVM 在 `main` 之前经标准 `premain` 入口加载,**不是被 ADR-1 否决的运行时 self-attach**,不受 JEP 451 限制(Paper + JDK21/24 零警告)。专补 ServerProbe 自身加载前的盲区(FR1.7)。
  - **逐插件精确耗时**:premain `ClassFileTransformer` 插桩 Bukkit `SimplePluginManager`,纳秒级 load/enable 计时,优于日志解析的秒级,且覆盖本插件之前加载的插件(真机:ServerProbe onEnable 精确 0.3s vs 日志 1.0s)。
  - **库下载耗时**:插桩 `LibraryLoader.createLoader`(1.17+),量化插件依赖在线下载耗时。
  - **扩展插桩点(世界/配置/事件/命令)**:新增 `CraftServer.createWorld`(世界创建,按 `/CraftServer` 后缀匹配兼容含/不含版本号包名)、`YamlConfiguration.loadConfiguration`(配置加载)、`SimplePluginManager.registerEvents`(事件注册)、`SimpleCommandMap.register`(命令注册)四个 hook,逐项纳秒级计时;`/probe startup` 与启动日志按 Top-N 呈现配置加载/事件注册/命令注册耗时,世界耗时由 agent 实测回填(不再恒为 0)。
  - **多线程折叠栈采样**:对 `Server thread` / `Netty Server IO` / `ServerMain` 等关键线程 5ms 周期抓**完整调用栈**并以折叠栈(folded stack)聚合,保留父→子调用关系;主线程扁平热点榜由其派生。
  - **启动火焰图 + 嵌套时间线(`/probe flamegraph`)**:由折叠栈生成**真正的多层、多线程火焰图**(宽度=调用路径采样占比、纵轴=调用深度,支持线程切换/缩放/搜索),并由逐事件时间线生成**按区间包含关系分泳道的嵌套时间线**(父区间含其内部 register/config 子区间);输出为**自包含 HTML**(CSS/JS 全内联、无 CDN 依赖)到 `data/flamegraph/`。此火焰图**专注启动期**(premain 阶段 spark 难以介入),与"运行期 CPU 归因建议并用 spark"的既定方向不冲突。
  - **更精确的统计 + 不成事故源**:时间线时刻取真实出口 `nanoTime`(纳秒级、相对 premain),不再用毫秒反推;`/probe startup` 慢插件榜在挂载 agent 时择优用 agent 实测 onEnable;引入**启动窗口**标志,插件就绪即关闭采集,杜绝被插桩方法在运行期持续追加导致的内存泄漏。
  - **HTTP/TCP 对外网络外呼监控(运行期常驻)**:插桩 `sun.net.www.protocol.http.HttpURLConnection.getInputStream`(覆盖 HTTP/HTTPS,含 TabooLib 依赖下载)与 `java.net.Socket.connect`(原始 TCP 兜底,去重),记录**哪个插件/哪段代码**发起了对外请求、目标 URL、响应码、耗时、(脱敏的)请求头与查询串;实时中文日志 + 落盘 `data/http/` + `/probe http` 回看 + 启动期外呼并入 `/probe flamegraph` 报告。**安全**:Authorization/Cookie/token 等敏感头与敏感查询参数由 agent 侧打码为 `***`,请求体不捕获;**不成事故源**:注入极简、逻辑全程 `try/catch` 兜底(经真实 JDK 类 + ASM `CheckClassAdapter` 校验合法),有界环形缓冲防泄漏,可配开关/限频。这正是定位"开服卡在依赖下载"类隐形瓶颈的利器(对应本轮发现的 TabooLib 下载卡顿)。
  - **唯一新增第三方依赖 = ASM**(relocate 到 `...agent.shadow.asm` 隔离);跨 ClassLoader 通道经 `appendToBootstrapClassLoaderSearch` 把极薄的 `ProbeAgentBridge` 放 bootstrap CL,供插桩字节码 / 插件反射 / 栈采样共享同一份数据;premain 顶层 `catch(Throwable)` 兜底,失败静默降级,绝不崩 JVM。
  - **范围(诚实)**:M5 先 Bukkit 端;Folia 主线程栈采样降级标 N/A(无单一主线程,引导用 spark),插件计时复用 Bukkit 路径;BungeeCord 推迟。**仅 1.21.4 Paper 单端真机验证,其他端(1.8 / Folia / BungeeCord)未逐一真机。**
  - 详见架构文档 ADR-11 / §13。

### 变更
- 确立技术选型与技术决策(详见架构文档 ADR-1 ~ ADR-11):
  - **探针主体 = 纯 API + JMX(`java.lang.management`)+ 平台原生 API + 采样**,主体不用 Java Agent、不裸写 ASM(只读优先,绝不成为事故源);被否决的是**运行时 self-attach**,而非启动期命令行 premain(后者作为 M5 可选增强补加载前盲区,见 ADR-11)。
  - **字节码插桩 / Incision 仅作架构预留**:首期不启用,引入前必须先 PoC 验证,默认关闭、失败静默降级。
  - **核心 Java 8 字节码**:保证产物可被 1.8–1.21.x 所有 JRE 加载;仅必须直接引用高版本 NMS 类型的胶水才独立抬 toolchain。
  - **多版本 + 多平台方案**:依赖 TabooLib `MinecraftVersion` / `nmsProxy` / `@PlatformSide` 三件套,覆盖 Bukkit 系 1.8–1.21.11(含 Folia)+ BungeeCord,**单 jar 多端**;遵循"先通用,跑不通再拆胶水"原则。
  - **Folia 适配**:调度统一走 TabooLib `submit`(零胶水);TPS/MSPT 抽象 `ServerTickSampler` 接口多实现;实体/区块用 `callRegion{}` 逐区域采集。
  - **存储**:本地文件落盘(JSON/JSONL,原子写入,可配滚动与保留),不依赖数据库;提供读取 API + 存储 SPI 开放接口。
  - 技术栈:Kotlin 2.1.0 / TabooLib 6.3.0 / taboolib-ioc 0.0.5 / 本地文件存储 + 开放接口;groupId `top.wcpe.mc.plugin.serverprobe`。

### 文档
- 新增产品需求文档 [`docs/PRD.md`](docs/PRD.md):明确背景与目标(开服慢可量化定位、统一运维探针、全版本多平台)、兼容性矩阵、功能需求(FR1–FR8)、非功能需求、数据模型与迭代规划(M1–M4)。初稿日期 2026-06-08。
- 新增架构文档 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md):明确分层架构(`plugin → platform-* / nms-* → core → api`)、多版本兼容机制、多平台架构、JDK/Toolchain 策略、Folia 适配、核心数据流、启动剖析机制、运行时装配与关键技术决策记录(ADR)。
- 新增本更新日志 `CHANGELOG.md`。
- **引入 SDD(规格驱动开发)治理脚手架**:新增 `.claude/rules/`(架构不变量 + 范围/决策/文档/质量/静态检查等防漂移规则)、`docs/adr/`(ADR-1~12 由 `ARCHITECTURE.md` 内联迁移为独立文件 + 索引,`ARCHITECTURE.md` §11 改为索引)、`docs/API.md`(对外接口契约)、`docs/OPERATIONS.md`、`SECURITY.md`、`docs/CONTRIBUTING.md`、`docs/specs/`、`.github/` 模板与 `.editorconfig`;`PRD.md` §7 增"功能交付状态总览"。**仅新增治理文档,未改动任何业务代码**;现有能力的交付状态见 [PRD §7.0](docs/PRD.md)。
- **关键决策敲定(同步至 Wiki 各页)**:将原"待定 / 开放问题 / 产品定义未定"的若干议题更新为明确结论。
  - **Folia TPS/MSPT 呈现**:采用 **per-region 明细 + 全局标 N/A**;分阶段实施 —— M1 先全局标 N/A,M2/M3 补 per-region 明细(原"标 N/A / 聚合 / per-region 待定")。
  - **依赖策略**:**允许按需引入轻量依赖,但每个新依赖需逐个确认**(原 Prometheus / Web 服务依赖"是否引入属开放问题")。
  - **CPU 热点 / 火焰图**:**不自研**,建议并用 [spark](https://spark.lucko.me);ServerProbe 专注指标监控 + 启动剖析(原"是否自研火焰图属开放问题")。
  - **代理端(BungeeCord)**:**各端独立采集与展示,暂不与后端联动汇总**(不引入 Porticus 等跨服汇总组件)。
  - **实例标识 `serverId`**:由实例**自动生成**,可在配置中以 **`server-name`** 覆盖为自定义值;本地文件**按实例分目录存放**(原"多服共存 serverId 标识规则属开放问题")。
  - **开源许可证**:选定 **MIT License**(原"待定")。

---

[未发布]: https://github.com/
[0.2.0]: https://github.com/
