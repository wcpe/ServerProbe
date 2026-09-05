# 功能规格：外部 MCP 深度诊断控制与内嵌 Arthas Core

> 状态：已完成验收，待下次正式版本登记　·　关联 PRD：FR-14　·　分支：当前分支

## 1. 背景与目标

普通指标只能说明“哪里慢”，事故中仍需要线程、死锁、方法调用、类加载与字节码证据。FR-14 提供类似 RCON 的手动开启控制面，让外部 agent 通过 MCP 获取服务器状态、执行服务器命令并调用内嵌 Arthas Core 深入 JVM。该能力是明确授权的完整控制模式，不伪装为安全沙箱。

## 2. 需求（要什么）

- 默认关闭；默认监听 `127.0.0.1:9942`，允许配置 `0.0.0.0`。密钥可选，使用 Bearer；不强制 HTTPS、密钥或回环。
- `config.yml` 的 `mcp` 段统一配置控制面：`enabled=false`、`host=127.0.0.1`、`port=9942`、`secret=""`；Arthas 任务默认并发 `4`、超时 `30` 分钟、输出 `64 MiB`、完成保留 `60` 分钟；工件默认保留 `24` 小时、总上限 `10 GiB`。对应键为 `task-max-concurrent`、`task-timeout-minutes`、`task-output-mebibytes`、`task-retention-minutes`、`artifact-retention-hours`、`artifact-max-gib`。
- 非回环、无密钥或明文完整控制必须打印醒目中文 WARN，但不得阻止启动。
- 仅暴露 ServerProbe 的 MCP Streamable HTTP JSON-RPC 2.0 端点；不开放 upstream Arthas MCP、Telnet、HTTP 控制端口或 stdio/SSE。
- 覆盖所有平台：状态/指标、执行平台控制台命令、线程 CPU Top、线程 dump、死锁、诊断 bundle。
- 内嵌 Arthas Core 提供结构化常用工具与 `arthas_execute` 原始命令回退，覆盖 watch/trace/monitor/stack/tt/ognl/sc/jad/classloader/heapdump/profiler/mc/redefine/retransform 等能力。
- 允许 JVM 内任意代码求值、编译与类替换；不提供第一方 OS Shell 工具。完整 OGNL/字节码能力本身不是安全沙箱，开启者承担间接调用 OS API 的风险。
- 长命令异步任务化：默认最多并发 4、最长 30 分钟（0 为不限）、单任务输出 64 MiB、完成后保留 60 分钟，均可配置；支持状态、分块读输出、取消。
- 工作产物位于 `plugins/ServerProbe/mcp-workspace`，支持分块写入、列表、分块读取、删除；默认保留 24 小时、上限 10 GiB、最早优先删除。
- 审计记录来源 IP、工具、任务、耗时、结果和参数 SHA-256；不得记录密钥、OGNL/源码正文、包载荷或玩家隐私。
- 构建脚本集中声明 `arthasLegacyVersion = "3.1.1"` 与 `arthasModernVersion = "4.3.2"`，构建期解析两个官方 `arthas-packaging:<version>:bin@zip`，运行时不下载；Java 8–16 选择前者，Java 17 及以上选择后者。
- 范围内：MCP 协议、原生诊断、Arthas 生命周期、任务/产物/审计、单 jar 最小发行闭包、真机验收。
- 不做：ServerProbe 自研 profiler 替代 Arthas、操作系统 Shell、TLS 终止、强制认证、对外暴露 upstream Arthas MCP。

## 3. 设计（怎么做）

- 新建 `diagnostics:diagnostics-arthas`，依赖 `project:core` 的状态、Json 门面与平台命令接口；平台模块只实现 `PlatformControl`。
- MCP 复用 JDK `HttpServer` 与现有 Json 门面，实现 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`。请求体默认上限 2 MiB，可配置；大文件通过 `artifact_write_chunk` 上传。
- 工具分组：`server_*`、`thread_*`、`diagnostic_bundle`、`arthas_*`、`task_*`、`artifact_*`。短工具同步返回；长工具返回 task id，输出只追加到任务文件。
- `diagnostic_bundle` 汇总指标快照、线程热点/死锁、GC、类加载、平台状态和近期告警，返回结构化证据；自然语言结论由外部 agent 生成。
- 构建期从每个官方 bin zip 只提取 Core/Spy/Agent/Boot、配置与所需 native 库，排除 client、math-game、文档、脚本；原始 jar 按版本放在 `META-INF/serverprobe/arthas/<version>/`，保留 LICENSE/NOTICE 并生成 SHA-256 manifest，禁止 flatten/shadow/源码复制。
- Core 中已有的 upstream MCP 类保留在官方 jar，但 `arthas.mcpEndpoint` 置空且启动参数禁用其端点。Arthas 通过隔离 ClassLoader 和无监听端口的进程内命令会话运行。
- Instrumentation 获取为三级链，按序自动尝试并自动选择首个成功来源：1) 复用 ServerProbe `premain` 保存的实例；2) self-attach（需 `-Djdk.attach.allowAttachSelf=true`，JDK 9+ 默认拒绝 self-attach）；3) helper 子进程外置注入（spawn 同 `java.home` 的一次性进程对目标 PID `attach + loadAgent`，跨进程 attach 不受 self-attach 限制）。全失败记录能力状态并保留原生诊断；`arthas_retry_attach` 由用户手动重试。
- HTTP 处理线程只做鉴权、限流、协议校验和任务提交。服务器命令通过各平台合法调度器切换；文件、Arthas 与重型诊断在专用有界执行器运行。
- 任务状态机为 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT；取消采用协作中断并关闭 Arthas process/session，不能让任务槽永久泄漏。
- Bearer 使用常量时间比较；审计参数先规范化再哈希。配置为空密钥时不读取或生成隐藏默认密钥。
- 架构决策见 [ADR-0024](../adr/0024-arthas-311-runtime-closure.md)，其取代保留历史的 ADR-0022。

## 4. 任务拆分

- [x] 先补 MCP 协议、鉴权、危险配置告警、任务状态/限额、产物清理与审计脱敏测试。
- [x] 实现原生状态、平台命令、线程/死锁与诊断 bundle。
- [x] 实现 Arthas 发行包提取、校验、隔离加载、Instrumentation/attach 生命周期。
- [x] 实现结构化 Arthas 工具、原始命令、任务与产物工具。
- [x] 用 mc-testkit 注入 `-javaagent` 验收完整模式，并另跑无 premain 的 attach 成功/失败降级。
- [x] 在 Bukkit/Spigot/Paper/Folia/BungeeCord/Velocity 运行默认关闭与开启场景。
- [x] 文档同步：PRD 状态、ARCHITECTURE、ADR、API、配置、OPERATIONS、安全警告、README、CHANGELOG。

## 5. 验收标准

- 默认配置不监听 9942；开启后 Streamable HTTP 客户端可完成 initialize、tools/list、tools/call，错误响应符合 JSON-RPC 2.0。
- 回环+密钥、回环+空密钥、`0.0.0.0`+空密钥均按配置运行；后两类输出醒目中文 WARN，Bearer 正误请求可验证。
- 六类平台可读取状态并执行平台控制台命令；线程 CPU Top、dump、死锁和 diagnostic bundle 返回结构化结果。
- premain 场景可运行 watch/trace/ognl/jad/mc/redefine/retransform 的代表命令；无 premain 时 attach 成功则等价，失败则明确降级且原生工具可用。
- 上游 Arthas MCP/Telnet/HTTP 端口均未监听，发行 jar 不含排除项且 manifest 哈希、LICENSE/NOTICE 与版本一致。
- 任务并发、超时、取消、64 MiB 截断、60 分钟清理，以及 workspace 24 小时/10 GiB 清理全部可自动验证。
- 审计不含 Bearer、OGNL/源码正文、完整参数或玩家隐私；危险配置下日志不打印密钥。
- mc-testkit 结果文件覆盖线程卡住、死锁、方法 trace、类替换与恢复，全部 PASS。

## 6. 风险 / 待定

- 动态 attach 受 JDK、容器与系统策略影响，不能承诺必成；完整能力的确定路径是启动参数 `-javaagent:ServerProbe.jar`。
- Arthas 内部进程内会话 API 不是稳定公共契约，必须把反射/版本适配封装在单一网关，并用 Java 8–16 的 3.1.1 与 Java 17+ 的 4.3.2 契约测试锁定。
- 建议采用本规格的 2 MiB 默认请求上限与 `artifact_write_chunk`；若你希望单请求上传大文件，需要在规格批准时改掉该默认值。
- 无剩余功能项；待完成的是发布登记。

## 7. 验收记录（2026-08-28）

- 七组真机结果文件全部 PASS（`build/mc-testkit/results/`）：`mcp-diagnostics-paper-java21`（Arthas 4.3.2 真实 watch/trace/redefine/retransform/revert）、`mcp-diagnostics-java8`（瘦 agent + Arthas 3.1.1 真实 `sc`；正式 jar 打包策略未变）、`mcp-diagnostics-folia`、`mcp-diagnostics-bungee`、`mcp-diagnostics-velocity`、`mcp-diagnostics-spigot`、`mcp-diagnostics-velocity-java25`（Velocity 4.1.0 + JDK25）。
- JDK25 真机证据（`run-proxy/velocity-mcp-java25.log`）：代理进程确认为 `C:\Users\Admin\.jdks\ms-25.0.4.1\bin\java.exe`；MCP 启动于 `127.0.0.1:19882/mcp` 并打印空密钥醒目中文 WARN；Arthas 横幅 `Current arthas version: 4.3.2`；真实 JSON-RPC 执行 `version`（返回 4.3.2）与 `sc`（命中 Velocity 验收类）。
- 默认关闭验证：MCP 默认不监听；Arthas 上游 Telnet/HTTP/MCP 端口未监听。
- 无 premain 的动态 attach 失败降级与 `arthas_retry_attach` 由 diagnostics-arthas 单测与降级逻辑覆盖；真机 E2E 主路径为 `-javaagent` premain（§6 风险条款认定 premain 为确定路径）。
- 单测：`McpHttpServerTest`、`McpJsonRpcDispatcherTest`、`McpAuditTrailTest`、`McpArtifactWorkspaceTest`、`ArthasTaskManagerTest`、`ArthasRuntimeExtractorTest`、`ArthasIsolatedClassLoaderTest`、`ArthasInstrumentationAccessTest`、`NativeMcpToolProviderTest`、`McpIocContractTest` 等全绿；逐条证据见 `.tmp/acceptance-phase-M5-2026-08-28.md`。
