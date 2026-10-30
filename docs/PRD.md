# 产品需求文档（PRD）：ServerProbe

> 需求的单一真源（WHAT / WHY），也是产品的**需求登记册 + 路线图**——全生命周期都在记，不是一次性文档。每个需求在 §4 加一行 FR（带优先级/期 + 状态），交付即标版本；§4 FR 表是迭代中最常动的部分（🔥 高频），分期（§7）只是其中很小、很静的一页粗线条规划。单功能的详细规格放 `docs/specs/`，PRD 只保留"一行 FR + 期 + 状态"的索引级。

## 1. 背景与目标

面向 Minecraft 服务器的**运维探针**，核心价值：让"开服慢"可量化定位、让运行指标可统一采集分析，并覆盖全版本、多平台。

- **痛点一（首要）：开服慢、且说不清慢在哪。** 服务端启动是一条串行链，任一环节（插件 enable 卡顿、世界加载、依赖在线下载、数据升级）都会拖慢整体，但现有手段只能"感觉慢"，无法量化定位元凶。
- **痛点二：缺乏统一运维探针。** TPS/MSPT/内存/GC/线程/世界负载等指标散落各处，没有统一采集、聚合、告警与可视化，运维无法长期监控与回溯。
- **产品目标**：
  - **G1（首要）：可量化定位"开服慢"。** 给出端到端启动总时长 + 逐插件 onEnable、逐世界加载、各生命周期阶段的耗时排名，并能"与上次/基线对比"，回答"这次慢在哪、比上次慢多少"。
  - **G2：运维探针数据采集与分析。** 覆盖 JVM、服务器、世界、网络及代理端核心指标的采集、聚合、告警与多通道呈现。
  - **G3：全版本 + 多平台。** Bukkit 系 1.8–1.21.11 全版本（含 Folia）+ BungeeCord + Velocity 3.1.1–4.x，单 jar 多端，核心 Java 8 字节码通用，版本/平台差异以最小胶水隔离。

### 非目标

- 不做服务端内核（NMS）/DataFixerUpper 等 **bootstrap 阶段的逐方法级归因**（对普通插件不可见，需重型字节码织入）——以"整体时长对比"覆盖该层。
- 不自研重型 CPU 采样分析器替代 [spark]；**运行期**深度 CPU 火焰图建议并用 spark（自研采样仅作轻量增强）。**例外**：**启动期（premain 窗口）火焰图自研**——spark 难介入 premain，而启动期恰是首要场景（见 ADR-8）。
- 不做玩家行为分析（属 Plan 领域）。
- 不将 Incision 扩展到服务端 bootstrap/NMS、BungeeCord 或 Folia；其仅在 Bukkit/Paper 的 `enablePlugin` 路径作为默认关闭的可选增强。
- 不做跨服务器聚合联动（各端独立采集与展示，见 ADR-9）。

## 2. 角色

| 用户 | 核心场景 |
|---|---|
| 服主 | 开服明显变慢时，一条命令定位是哪个插件/世界拖慢了启动 |
| 运维 | 长期监控 TPS/MSPT/内存/GC，接 Grafana 看板与报警，事故回溯 |
| 插件开发者 | 排查自己插件的 enable 耗时与运行时开销占比 |
| 业务插件（经 `api` 自注册，FR-32） | 经探针桥承接 JianManager 下发的业务命令与事件上报 |

## 3. 用户故事

- 作为**服主**，我希望一条命令看到启动总时长和慢插件/慢世界排名，以便快速回答"这次开服为什么慢"。
- 作为**运维**，我希望 TPS/MSPT/内存/GC 统一采集并可经 Prometheus/Web/文件多通道查看，以便长期监控与事故回溯。
- 作为**插件开发者**，我希望看到自己插件的 enable 耗时与运行期 CPU 占比，以便优化性能。
- 作为**运维**，我希望在不换服的情况下用同一个 jar 覆盖 Bukkit 系全版本、Folia、BungeeCord 与 Velocity，以便统一部署。
- 作为**事故响应者**，我希望在默认关闭的控制面下能对 JVM 做线程/死锁/类重定义级深度诊断，以便定位疑难事故。

## 4. 功能需求（FR）

| 编号 | 需求 | 优先级 | 状态 |
|---|---|---|---|
| FR-01 | 启动性能剖析：端到端总时长、逐插件/逐世界/生命周期分段耗时、启动画像落盘与上次对比、慢启动告警；可选 premain agent 补加载前盲区（精确耗时/栈采样/火焰图/外呼监控） | P1 | 已交付@v0.2.0 |
| FR-02 | 运维指标采集：JVM、服务器 TPS/MSPT、世界、网络 ping 分布、代理端子服健康、插件运行期 CPU 归因 | P1 | 已交付@v0.2.0 |
| FR-03 | 存储与聚合：内存环形缓冲、本地文件异步落盘（JSON/JSONL）、TPS 滑窗 / MSPT 分位 / GC 差分聚合 | P1 | 已交付@v0.2.0 |
| FR-04 | 数据呈现四通道：游戏内命令 `/probe`、Prometheus `/metrics`、Web 面板、历史文件对比 | P1 | 已交付@v0.2.0 |
| FR-05 | 告警：阈值 + 防抖 + 日志 / 游戏内 / Webhook 三通道 | P2 | 已交付@v0.2.0 |
| FR-06 | 全版本与多平台：单 jar 运行于 Bukkit 系 1.8–1.21.11（含 Folia）+ BungeeCord + Velocity 3.1.1–4.x | P1 | 已交付@v0.2.0 |
| FR-07 | 方法级精确归因：Incision 采集 `enablePlugin` 逐插件精确耗时（可选、默认关闭） | P3 | 已交付@v0.2.0 |
| FR-08 | 开放接口：只读数据访问 API + 存储 SPI + 导出端点 | P2 | 已交付@v0.2.0 |
| FR-09 | 业务对接 agent（JBIS）：经反向 WS 桥承接业务命令路由到业务插件 Provider 执行并回执、业务事件上报，事故域隔离 | P2 | 已交付@v0.2.0 |
| FR-10 | 内置业务集成模块化：MultiCurrencyEconomy / AllinInventorySync 独立 Gradle 模块，发布时合入单 jar（**已由 FR-32 移除**，改为第三方插件自注册） | P2 | 已交付@v0.3.0 |
| FR-11 | 全平台网络流量与数据包取证：双向 bytes/s、packets/s、包类型计数，本地 SQLite 白名单取证 | P2 | 已交付@v0.3.0 |
| FR-12 | Folia 已观测 region TPS/MSPT 明细与世界汇总 | P2 | 已交付@v0.3.0 |
| FR-13 | Velocity 3.1.1–4.x 平台支持（单 jar、双编译门、Java 8 入口） | P2 | 已交付@v0.3.0 |
| FR-14 | 外部 MCP 深度诊断控制：内嵌 Arthas Core（默认关闭、明确授权控制面） | P2 | 已交付@v0.3.0 |
| FR-15 | MCP 插件维度诊断入口：列出全部已加载插件（名称/版本/ClassLoader/状态），按插件查看类清单、线程栈、CPU 热点，无需手敲类名 | P2 | 已交付@v0.4.0（Paper 1.20.1 Windows/Linux 真机） |
| FR-16 | MCP 日志流式检索：tail 读取 `logs/latest.log`，支持关键字过滤与分页，只读 | P2 | 已交付@v0.4.0（Paper 1.20.1 Windows/Linux 真机） |
| FR-17 | MCP 区块/实体明细：`server_status` 按世界输出区块分布、实体类型分布与总数；Folia/低版本降级 N/A | P2 | 已交付@v0.4.0（Paper 1.20.1 Windows 真机；Folia 降级待验） |
| FR-18 | MCP 二进制产物安全回传：新增 `artifact_read_binary`（Base64 编码二进制分块读取），heapdump/profiler 产物可下载 | P2 | 已交付@v0.4.0（Paper 1.20.1 Windows/Linux 真机） |
| FR-19 | MCP 补丁前自动备份：`redefine`/`retransform` 前自动备份原始字节码，可回滚/对比 | P2 | 已交付@v0.4.0（Paper 1.20.1 Windows/Linux 真机，含非法名拒绝） |
| FR-20 | MCP 在线玩家诊断明细：按玩家名查询在线玩家的位置/血量/背包摘要/所在区块与事件链路聚合；默认开启（随 MCP 开关），鉴权+审计+不落库 | P2 | 已交付@v0.4.0（Paper 1.20.1 Windows/Linux 真机，离线降级） |
| FR-21 | MCP 运行期 CPU 火焰图：直接利用 `arthas_profiler` 产出（start/stop + 产物查看），不自研采样器（不推翻 ADR-8） | P2 | 已交付@v0.4.0（**Linux 真机产出火焰图 HTML**；Windows 因 async-profiler 平台限制降级） |
| FR-22 | MCP GC/JFR 详诊：GC 事件明细与 JFR 采集入口（低优先，可经 Arthas vmoption/jfr 兜底） | P3 | 已交付@v0.4.0（Paper 1.20.1 Windows/Linux 真机，G1 收集器明细） |
| FR-23 | MCP 工具描述增强：`tools/list` 为每个工具下发完整中文使用说明（参数含义、使用示例、异步工作流提示、输出字段说明），供外部 agent 正确调用；不改变工具名与参数键 | P2 | 已交付@v0.4.0（Paper 1.20.1 Windows/Linux 真机，42 工具零重复） |
| FR-24 | MCP 控制面运行期两级开关：控制台 `/probe mcp on\|off\|status` 起停端点（原生工具立即可用）、`/probe mcp arthas on\|off` 单独加载/卸载 Arthas 运行时；仅控制台可执行，不写回配置 | P2 | 已交付@v0.5.0（Paper 1.20.1 Windows 真机，8 项验收 + 5 轮开关零泄漏） |
| FR-25 | 启动画像指标进 Prometheus：`startup_total_seconds` 与逐插件/逐世界耗时序列（内存画像直出，不读盘；代理端无此概念不输出） | P2 | 已交付@v0.6.0 |
| FR-26 | 只读 API 暴露历史指标回读：`ProbeReadApi.historySnapshots(since, until, limit)`（default 方法保向后兼容；可能读盘、宜异步） | P2 | 已交付@v0.6.0 |
| FR-27 | Grafana 看板与 Prometheus 告警规则随发行提供：导入即用 dashboard + 告警规则模板（纯产物，不改代码；依赖 FR-25） | P3 | 已交付@v0.6.0 |
| FR-28 | 代理端 MCP 日志检索：BungeeCord `proxy.log` 与 Velocity `logs/` 的 `LogPathProvider` 实现，补齐 FR-16 代理端欠条 | P2 | 已交付@v0.7.0 |
| FR-29 | 告警规则扩面与告警历史：新增 GC 频繁 / 启动超基线规则（把 wiki 宣称做成真），告警事件 JSONL 落盘 + `/probe alerts` 查询 | P2 | 已交付@v0.7.0 |

> 状态取值：计划 / 开发中 / 已交付@vX.Y.Z。优先级：P1(MVP) / P2 / P3。
> FR 标了 `已交付` 实际是断的（false-done）：功能坏了要修回 done 走 `sdd-fix-bug` 把状态归真（从没真正工作过 → 回退 `开发中`）；需求本身要撤 / 推迟则走 `sdd-rollback-change`。FR 标了 `已交付` 实际是断的（false-done）：功能坏了要修回 done 走 `sdd-fix-bug` 把状态归真（从没真正工作过 → 回退 `开发中`）；需求本身要撤 / 推迟则走 `sdd-rollback-change`。
> 各 FR 的详细能力与验收：FR-07 见 [method-incision](specs/method-incision.md)、FR-08/09 见 [open-api-bridge-e2e](specs/open-api-bridge-e2e.md)、FR-10 见 [built-in-integrations](specs/built-in-integrations.md)、FR-11 见 [network-forensics](specs/network-forensics.md)、FR-12 见 [folia-observed-regions](specs/folia-observed-regions.md)、FR-13 见 [velocity-platform](specs/velocity-platform.md)、FR-14 见 [mcp-diagnostics](specs/mcp-diagnostics.md)、FR-24 见 [mcp-runtime-toggle](specs/mcp-runtime-toggle.md)、FR-25 见 [startup-metrics](specs/startup-metrics.md)、FR-26 见 [readapi-history](specs/readapi-history.md)、FR-27 见 [grafana-pack](specs/grafana-pack.md)、FR-28 见 [proxy-log-tail](specs/proxy-log-tail.md)、FR-29 见 [alert-extended](specs/alert-extended.md)；早期 FR-01~06 的实现与验收细节见 [CHANGELOG](../CHANGELOG.md) 对应版本段与 [ARCHITECTURE](ARCHITECTURE.md)。
| FR-30 | 桥对外契约下沉 `api`：业务 Provider / 桥指令 / 结果 / 注册句柄 / 健康状态迁入纯 Java 8 的 `api` 模块，新增 `ServerProbeAPI` 稳定门面（可缓存、永不 null、永不抛），支持外部插件在 `onEnable` 自注册、`onDisable` 注销 | P1 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-31 | 桥线路治理：读线程只解析+投递（绝不阻塞、绝不抛）、命令/事件/重载三池有界隔离、事件可丢与不可丢分级+落盘兜底、写侧有界与入站上限、`core.bridge_status` 运行状态 | P1 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-32 | 配置重载通知：`core.config_reload` 按路径匹配监听器（精确 + 目录前缀），独立 worker 池执行、5s 有界、路径越界拒绝、在途互斥、超时熔断（指数退避）；只回传插件声明，不推断生效 | P1 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-33 | 事件主题订阅：`event` 帧增 `topic`，`core.subscribe` 支持精确与前缀通配；无订阅时全量上报（兼容），有订阅后只上报匹配主题 + 全部非业务事件 | P2 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-34 | 大数据帧分片：单帧超 256 KiB 自动切分（`chunk` + `groupId`/`seq`/`total`，base64 精确重组），同组原子入队、绝不发前缀；控制帧旁路保证心跳 | P2 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-35 | Provider 健康状态：`BusinessProvider.health()` 默认方法（旧实现零改动兼容）+ `BusinessHost.healthOf(domain)` + `core.business_status`（`order=110`）返回各域可用性、初始化状态、最近失败原因；健康值由独立刷新线程（`serverprobe-health` / `serverprobe-health-probe`）采集并缓存，**命令线程只读缓存**，超时/异常按域隔离、probe 卡死时以 `refresh.stalled` 暴露 | P2 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-36 | 桥连接自愈与状态：重连前从磁盘重读 token（轮换即自愈）、断开原因分类（认证/不可达/对端关闭/心跳超时）、`core.bridge_status` 暴露重连次数与最近断开原因（脱敏） | P2 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-37 | 业务对接改为纯第三方自注册：移除内置 MCE/AIS 集成，探针不含任何具体业务实现，只做业务面板 ↔ 插件的路由中介；`api` 只提供跨域通用协议词汇（真实帧字段 `requestId`/`success`/`output`/`error`/`dedupKey`/`domain` + 约定词 `taskId`）与 `ActionSpec` 动作声明容器（**取代 FR-10 的交付形态**，FR-10 历史保留） | P1 | 开发中（实现、单测与真机 E2E 完成，待发版登记） |
| FR-38 | manifest 强类型化与动作规格（feat，需 spec，替代 FR-37 的裸 `Map` 形态）：`BusinessProvider.manifest()` 返回值从 `Map<String,Object>` 改为强类型 `DomainManifest`——含 `apiVersion`、`domain`、`displayName`、`description`、`actions: List<ActionSpec>`、`attributes`（扩展位，平台不解释）。`ActionSpec` 声明 `name`/`description`/`risk`（READ/WRITE/DESTRUCTIVE）/`idempotent`/`inputSchema`（JSON Schema）/`outputSchema`/`errorCodes`。目的：让下游业务台能据此渲染表单、前置校验、按风险决定二次确认，而非硬编码每个动作。验收：旧 Provider（返回裸 Map）仍可注册并在 manifest 聚合时降级为「未声明规格」；新 Provider 的 schema 能通过一致性校验；`apiVersion` 不匹配时明确拒绝而非静默错误。 | P1 | 已完成（2026-10-24）：api 新增 `DomainManifest` / `ActionSpec` / `RiskLevel`（final class + 静态工厂，因 api 模块发行面向 Java 8，record 不可用）；`BusinessHost.manifest()` 返回 `{apiVersion, domains}`；注册期 + 聚合期各判一次 apiVersion（只在注册期判会让「注册后才返回新版本号」的实现绕过降级）；旧 Provider 经 `LegacyBusinessProviderAdapter` 适配，域标 `specDeclared=false`。空动作名不算重复（替 Provider 判空名非法会让漏填一个名字的域整体不可用）。23 项新测试。 |
| FR-39 | 强类型分发入参与结果（feat，需 spec，依赖 FR-38）：`dispatch` 从 `(String action, String payloadJson)` 改为 `ActionResult dispatch(ActionRequest request)`；`ActionRequest` 携带 `domain`/`action`/`payloadJson`/`requestId`（平台保证非空）/**`Principal`（operator/operatorId/nodeId/reason）**；`ActionResult` 携带 `success`/`outputJson`/**`errorCode`（机器可读）**/`errorMessage`（人可读）/`metadata`（耗时等诊断）。目的：业务台能按错误码分流处理，而非只能读文本；操作者上下文可透传进插件审计。验收：`requestId` 恒非空且同请求重发可被 Provider 判重；`errorCode` 在 manifest 声明的 `errorCodes` 范围内；`metadata` 不影响协议兼容。 | P1 | 已完成（2026-10-24）：`ActionRequest`（含 `Principal`）与 `ActionResult`（含机器可读 `errorCode`）；旧签名保留在 `LegacyBusinessProvider`，`BusinessHost.registerLegacy` 而非重载 `register`（两个重载会让既有 `register(null)` 无法编译）。`getRisk()`（默认生效值 WRITE）与 `getDeclaredRisk()`（原始声明，可为 null）分开——若构造时就把 null 抹成 WRITE，WARN 永远发不出来。兼容入口拿不到 errorCode，失败原因写成 `[CODE] message` 前缀。 |
| FR-40 | 已注册插件与业务域可视化数据面（feat，需 spec）：提供「本服务端探针注册了哪些业务域、各自健康状态、manifest 摘要、最近调用」的完整查询面，供 JM 渲染插件面板。现有 `BusinessStatusRoute` 只有 domain 级状态；本项补齐：`DomainStatus` 增加 `manifestDigest`（规格指纹，避免每次全量传输）、`lastDispatchAt`、`dispatchCount`/`failureCount`、`degradedReason`。目的：JM 业务插件面板能直观显示「这台服装了哪些业务插件、哪些可用、哪些降级」，无需业务台参与。验收：查询不触发 Provider 初始化（只读注册表快照）；域数量 0~N 都能正确返回；`manifestDigest` 变化能被检出。 | P1 | 已完成（2026-10-24）：`DomainStatus` 新增 `manifestDigest` / `lastDispatchAt` / `dispatchCount` / `failureCount` / `degradedReason` / `available`；digest 在**注册时**算一次并缓存（手写紧凑 JSON：动作按 name 排序、键字典序、`errorCodes` 排序、零空白，`inputSchema`/`outputSchema` 只 trim 不解析），查询不触发 Provider 初始化。计数为运行期指标，重启归零。 |
| FR-41 | 可靠事件传输层序号（feat，需 spec）：`BusinessEventSink.emitReliable` 增加带 `seq` 的重载；探针保证**同一 `domain` 内 `seq` 单调递增**并透传到帧（`dedupKey` 独立于 `seq`，前者去重后者定位）。可丢事件不占用 `seq` 序列。目的：下游可用 `seq` 检测事件缺口并触发对账；与快照位点可比。**边界**：`seq` 是传输层编号，探针不规定业务 payload 内容。验收：同 domain 内 `seq` 严格递增无跳号（除明确 gap）；重启后序号策略明确（续接或重置并在帧中标注）；JM/业务台能凭单一 `seq` 判断「快照之后是否漏事件」。 | P1 | 已完成（2026-10-24）：per-domain `AtomicLong` 分配，可丢事件不占序列；`EventSeqStore` 持久化到 `<spill-dir>/seq.state`（制表符分隔），每 256 条 + 停机 flush；重启按方案 B 续接。**过程中发现并修掉真实缺陷**：序号状态损坏后若立刻回写，下次启动会读成 Present 导致各域从 1 起，与刚发出的号段冲突 —— 改为保持损坏态、等正常停机再写真实域表。`emitReliable(...,seq)` 做成 default 方法（否则第三方实现类会 AbstractMethodError）。 |

> 状态取值：计划 / 开发中 / 已交付@vX.Y.Z。优先级：P1(MVP) / P2 / P3。
> FR 标了 `已交付` 实际是断的（false-done）：功能坏了要修回 done 走 `sdd-fix-bug` 把状态归真（从没真正工作过 → 回退 `开发中`）；需求本身要撤 / 推迟则走 `sdd-rollback-change`。FR 标了 `已交付` 实际是断的（false-done）：功能坏了要修回 done 走 `sdd-fix-bug` 把状态归真（从没真正工作过 → 回退 `开发中`）；需求本身要撤 / 推迟则走 `sdd-rollback-change`。
> 各 FR 的详细能力与验收：FR-07 见 [method-incision](specs/method-incision.md)、FR-08/09 见 [open-api-bridge-e2e](specs/open-api-bridge-e2e.md)、FR-10 见 [ADR-0018](adr/0018-built-in-business-integration-modules.md)（已被 FR-37 取代）、FR-11 见 [network-forensics](specs/network-forensics.md)、FR-12 见 [folia-observed-regions](specs/folia-observed-regions.md)、FR-13 见 [velocity-platform](specs/velocity-platform.md)、FR-14 见 [mcp-diagnostics](specs/mcp-diagnostics.md)、FR-24 见 [mcp-runtime-toggle](specs/mcp-runtime-toggle.md)；FR-30 见 [bridge-external-api](specs/bridge-external-api.md)、FR-31 见 [bridge-line-hardening](specs/bridge-line-hardening.md)、FR-32 见 [config-reload](specs/config-reload.md)、FR-33 见 [event-topic-subscription](specs/event-topic-subscription.md)、FR-34 见 [frame-chunking](specs/frame-chunking.md)、FR-35 见 [provider-health](specs/provider-health.md)、FR-36 见 [bridge-reconnect](specs/bridge-reconnect.md)、FR-37 见 [business-self-registration](specs/business-self-registration.md)；早期 FR-01~06 的实现与验收细节见 [CHANGELOG](../CHANGELOG.md) 对应版本段与 [ARCHITECTURE](ARCHITECTURE.md)。

## 5. 非功能需求（NFR）

### 性能

- 运行期自身开销目标 <2%；MSPT 仅取 `nanoTime`；聚合/落盘/采样全异步或限频；环形缓冲定容；文件写入异步且原子。**严禁主线程阻塞磁盘 IO / 远程调用**。
- 数据包取证不得阻塞 Netty EventLoop；取证库双上限（60 天 / 4 GiB）按最早记录优先清理。

### 稳定性

- 探针绝不能成为事故源：主体只读；可选插桩 / agent / MCP 控制面默认关闭、失败静默降级。
- 跨 ClassLoader 启动 agent 通道只放最小桥接类，premain 顶层 `catch(Throwable)` 兜底，绝不崩 JVM。

### 兼容性

| 维度 | 范围 | 说明 |
|---|---|---|
| MC 版本 | **1.8 – 1.21.11**（及 26.1） | 与 TabooLib `MinecraftVersion.supportedVersion` 一致 |
| 服务端类型 | CraftBukkit / Spigot / Paper / **Folia** / 其他 Bukkit 衍生 | Folia 识别为 Bukkit 变体，非独立平台 |
| 代理端 | **BungeeCord + Velocity 3.1.1–4.x** | 两端独立采集与展示，不做跨服务器聚合 |
| 运行 JRE | Java 8+ | 探针核心 Java 8 字节码，在所有 JRE 上可加载 |
| 编译 | 核心 **Java 8** target | 仅直接继承高版本 NMS 类的个别胶水模块才用高 toolchain |

### 安全

- Web / Prometheus 端点鉴权 + 绑定地址限制；输出不泄露路径 / token；外部输入校验。
- FR-14 是默认关闭、经用户明确授权的完整控制面：允许无 TLS / 无密钥 / 非回环监听，此时必须醒目中文 WARN；审计不得记录密钥或控制正文；不提供 OS Shell。

### 可观测性

- 探针自身日志全中文、按 ERROR/WARN/INFO/DEBUG 分级；管理操作记审计日志。

## 6. 验收标准

> 期级整批验收清单。单 FR 的详细验收见对应 spec 与 [CHANGELOG](../CHANGELOG.md)。带「真机」维度的项需由用户在真实服务端环境确认通过——测试绿不替代真的能用。

| 验收项 | 判据 | 维度 | 状态 |
|---|---|---|---|
| 启动剖析（FR-01） | `/probe startup` 输出总时长、慢插件 Top-N、各世界耗时、与上次对比；挂 `-javaagent` 后精确耗时升级、`/probe flamegraph` 导出自包含 HTML、`/probe http` 回看外呼 | 真机 | ✅ 已确认（v0.2.0） |
| 指标采集（FR-02） | `/probe health/tps/gc/world/ping/proxy/cpu` 输出真实数据；TPS/MSPT 在 Paper/低版本/Folia 各路径降级正确；Folia 全局 TPS 为 N/A | 真机 | ✅ 已确认（v0.2.0） |
| 存储聚合（FR-03） | 启动画像 JSON + 指标历史 JSONL 落盘、滚动与清理正确；TPS 滑窗 / MSPT 分位 / GC 差分聚合正确 | 单测 + 真机 | ✅ 已确认（v0.2.0） |
| 数据呈现（FR-04） | 四通道可用；Prometheus token + IP 鉴权、端口占用优雅降级；Web 面板三页鉴权 + 绑定地址 | 真机 | ✅ 已确认（v0.2.0） |
| 告警（FR-05） | 阈值触发、防抖（持续 N 周期）、恢复状态机、三通道输出 | 单测 + 真机 | ✅ 已确认（v0.2.0） |
| 全版本多平台（FR-06） | 同一 jar 在 Spigot 1.8.8 + Java 8、Paper 1.21.11 + JDK21、Folia 1.21.4、BungeeCord #2088 加载并采集对应指标 | 真机 | ✅ 已确认（v0.2.0） |
| 方法级归因（FR-07） | 默认关闭无精确数据；开启记录真实插件耗时；模拟无效切点仍完成启动并标记未激活；受控负载 MSPT p95 劣化 ≤5% | 真机 | ✅ 已确认（v0.2.0） |
| 开放接口（FR-08） | 第三方插件经只读 API 取到 TPS/MSPT/启动画像；经 `ServerProbeStorageApi.install` 安装 `MetricStore`，关闭注册后回退默认文件后端 | 真机 E2E | ✅ 已确认（v0.2.0） |
| 业务桥（FR-09） | fixture 完成桥握手、命令路由/回执、业务事件、失败/超时后恢复；Provider 事故域隔离 | 真机 E2E | ✅ 已确认（v0.2.0） |
| 内置集成（FR-10） | 真实 MCE 1.2.0 / AIS 2.1.0-SNAPSHOT 注入与双缺失/单缺失场景 E2E 通过（该能力已由 FR-32 移除，历史验收记录保留） | 真机 E2E | ◐ 验收通过，待发版登记 |
| 网络取证（FR-11） | 六平台真实协议 E2E 全 PASS（速率/包类型/白名单/脱敏/分页/清理/驱动缺失降级） | 真机 E2E | ◐ 验收通过，待发版登记 |
| Folia region（FR-12） | 双真实 bot 验证隔离 region、受控负载 p95、世界汇总、全局 N/A、离开后过期 | 真机 E2E | ◐ 验收通过，待发版登记 |
| Velocity（FR-13） | 3.1.1 / 3.5.1 / 4.1.0（JDK25）三组真机矩阵 PASS | 真机 E2E | ◐ 验收通过，待发版登记 |
| MCP 诊断（FR-14） | Java 8/21/25 × 六平台七组真机场景 PASS；默认关闭不监听；动态 attach 失败降级 | 真机 E2E + 单测 | ◐ 验收通过，待发版登记 |
| MCP 运行期开关（FR-24） | 全程不重启：状态查询、控制台放行、端点开启后工具可用（验收时 42，当前 44）、Arthas 加载/卸载/二次加载、端口释放与重开、重复开关幂等；5 轮开关零线程泄漏；`mcp.enabled=true` 时启动期行为不变 | 真机 + 单测 | ◐ 验收通过，待发版登记 |

> `◐ 验收通过，待发版登记`：证据已齐（见对应 spec），需用户在真实环境复验确认后，由下次正式版本 `sdd-release-version` 统一标 `已交付@vX.Y.Z`。

## 7. 分期（路线）

各期只描述**主题 / 目标**；**具体哪个 FR 属于哪期，以 §4 FR 表的优先级 / 状态列为唯一来源**——本节不重复列编号、不随 FR 增长而改。

- **第一期（MVP）**：把核心立起来——启动性能剖析 + 基础运维指标采集 + 存储聚合 + 游戏内命令呈现，覆盖全版本多平台骨架。
- **第二期**：增强——开放接口、业务对接 agent、告警、Prometheus / Web 出口、代理端健康与运行期归因。
- **第三期**：规模化与深度诊断——全平台网络取证、Folia region 明细、Velocity 平台、外部 MCP 深度诊断控制面。

> **分期不会堆到上百**：期是粗粒度路线图横轴，数量很少（走到成熟通常 3~6 个），一期含很多 FR、跨很多版本。**产品成熟（1.0 后稳态迭代）就不再加"第 N 期"**，改按版本（CHANGELOG / tag）+ 功能（FR 表 / specs）组织。期数往二十、上百涨，是把"期"误当版本 / 功能单位的滥用信号。
> 某期是否完成，看 §4 表里该期 FR 的"状态"是否都 `已交付`——进度不在本节维护。
> 仍开放：第三期已验收待登记，具体发版日期待定。

## 8. 术语表

| 术语 | 含义 |
|---|---|
| TPS | 每秒 tick 数（理想 20）；**Folia 为 per-region，无全局值** |
| MSPT | 单 tick 耗时（毫秒），>50ms 即掉 tick；关注 p95/p99 |
| 启动画像（Startup Profile） | 一次启动的结构化耗时报告（总时长 + 分段 + 慢插件榜 + 世界耗时 + JVM 参数快照） |
| 指标快照（Metric Snapshot） | 某时刻一组运维指标的采样值 |
| 采集器（Collector） | 采集某类指标的组件，平台无关接口 + 平台/版本实现 |
| 胶水模块 | 仅当通用 API 不支持某版本/平台功能时才编写的最小适配实现 |
| 代理端 | BungeeCord / Velocity 等反向代理，**无世界/TPS/MSPT 概念** |
| 环形缓冲 | 定容内存队列，保存最近 N 分钟高频指标，避免磁盘 IO 压主线程 |
| 本地文件落盘 | 启动画像与聚合后历史以本地文件（JSON/JSONL）持久化；网络数据包取证按 FR-11 例外使用本地 SQLite |
| 开放接口 | 对外只读数据访问 API + 存储 SPI 扩展点，供第三方消费或自接后端 |
| 已观测 region | 当前或最近含在线玩家并成功采样的真实 Folia ticking region（FR-12） |
| MCP | Model Context Protocol；FR-14 的 Streamable HTTP（JSON-RPC 2.0）诊断控制面 |
| JBIS | JianManager 业务对接平台协议（FR-09，对应 JM FR-115~127 / ADR-025~029） |
