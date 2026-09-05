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
| 业务插件（MultiCurrencyEconomy / AllinInventorySync） | 经探针桥承接 JianManager 下发的业务命令与事件上报 |

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
| FR-10 | 内置业务集成模块化：MultiCurrencyEconomy / AllinInventorySync 独立 Gradle 模块，发布时合入单 jar | P2 | 已交付@v0.3.0 |
| FR-11 | 全平台网络流量与数据包取证：双向 bytes/s、packets/s、包类型计数，本地 SQLite 白名单取证 | P2 | 已交付@v0.3.0 |
| FR-12 | Folia 已观测 region TPS/MSPT 明细与世界汇总 | P2 | 已交付@v0.3.0 |
| FR-13 | Velocity 3.1.1–4.x 平台支持（单 jar、双编译门、Java 8 入口） | P2 | 已交付@v0.3.0 |
| FR-14 | 外部 MCP 深度诊断控制：内嵌 Arthas Core（默认关闭、明确授权控制面） | P2 | 已交付@v0.3.0 |

> 状态取值：计划 / 开发中 / 已交付@vX.Y.Z。优先级：P1(MVP) / P2 / P3。
> FR 标了 `已交付` 实际是断的（false-done）：功能坏了要修回 done 走 `sdd-fix-bug` 把状态归真（从没真正工作过 → 回退 `开发中`）；需求本身要撤 / 推迟则走 `sdd-rollback-change`。FR 标了 `已交付` 实际是断的（false-done）：功能坏了要修回 done 走 `sdd-fix-bug` 把状态归真（从没真正工作过 → 回退 `开发中`）；需求本身要撤 / 推迟则走 `sdd-rollback-change`。
> 各 FR 的详细能力与验收：FR-07 见 [method-incision](specs/method-incision.md)、FR-08/09 见 [open-api-bridge-e2e](specs/open-api-bridge-e2e.md)、FR-10 见 [built-in-integrations](specs/built-in-integrations.md)、FR-11 见 [network-forensics](specs/network-forensics.md)、FR-12 见 [folia-observed-regions](specs/folia-observed-regions.md)、FR-13 见 [velocity-platform](specs/velocity-platform.md)、FR-14 见 [mcp-diagnostics](specs/mcp-diagnostics.md)；早期 FR-01~06 的实现与验收细节见 [CHANGELOG](../CHANGELOG.md) 对应版本段与 [ARCHITECTURE](ARCHITECTURE.md)。

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
| 内置集成（FR-10） | 真实 MCE 1.2.0 / AIS 2.1.0-SNAPSHOT 注入与双缺失/单缺失场景 E2E 通过 | 真机 E2E | ◐ 验收通过，待发版登记 |
| 网络取证（FR-11） | 六平台真实协议 E2E 全 PASS（速率/包类型/白名单/脱敏/分页/清理/驱动缺失降级） | 真机 E2E | ◐ 验收通过，待发版登记 |
| Folia region（FR-12） | 双真实 bot 验证隔离 region、受控负载 p95、世界汇总、全局 N/A、离开后过期 | 真机 E2E | ◐ 验收通过，待发版登记 |
| Velocity（FR-13） | 3.1.1 / 3.5.1 / 4.1.0（JDK25）三组真机矩阵 PASS | 真机 E2E | ◐ 验收通过，待发版登记 |
| MCP 诊断（FR-14） | Java 8/21/25 × 六平台七组真机场景 PASS；默认关闭不监听；动态 attach 失败降级 | 真机 E2E + 单测 | ◐ 验收通过，待发版登记 |

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
