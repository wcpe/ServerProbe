# ServerProbe 产品需求文档(PRD)

| 项 | 内容 |
|---|---|
| 项目 | ServerProbe —— Minecraft 服务器运维探针 |
| 版本 | 0.1.0(2026-06-20 首发;本地 tag,真机仅 1.21.4 Paper) |
| 日期 | 2026-06-08 |
| 适用平台 | Bukkit 系(CraftBukkit/Spigot/Paper/Folia)**1.8 – 1.21.11 全版本** + BungeeCord 代理端,**单 jar 多端** |
| 运行 JRE | Java 8+(随服务端;1.17+ 服务端运行于 17+,1.20.5+ 运行于 21+) |
| 技术栈 | Kotlin 2.1.0 / TabooLib 6.3.0 / taboolib-ioc 0.0.5 / **本地文件存储 + 开放接口** / **核心 Java 8 字节码** |
| groupId | `top.wcpe.mc.plugin.serverprobe` |
| 关联文档 | [架构文档](ARCHITECTURE.md) · [CHANGELOG](../CHANGELOG.md) · [README](../README.md) · [Wiki](wiki/) |

---

## 1. 背景与目标

### 1.1 背景
- **痛点一(首要):开服慢、且说不清慢在哪。** 服务端启动是一条串行链:JVM 启动 → 服务端 bootstrap → 各插件 onLoad/onEnable → 世界加载 → `Done!`。任一环节(某插件 enable 卡顿、世界 spawn-chunk 预加载、依赖在线下载、数据升级)都会拖慢整体,但现有手段只能"感觉慢",无法量化定位元凶。
- **痛点二:缺乏统一运维探针。** TPS/MSPT/内存/GC/线程/世界负载等指标散落各处,没有统一采集、聚合、告警与可视化,运维无法长期监控与回溯。
- **现状:项目是空骨架。** 当前 git 仅含配置文件,三模块源码目录全空,从零开发。当前仅 `install(Bukkit)`。

### 1.2 产品目标
- **G1(首要):可量化定位"开服慢"。** 给出端到端启动总时长 + 逐插件 onEnable、逐世界加载、各生命周期阶段的耗时排名,并能"与上次/基线对比",回答"这次慢在哪、比上次慢多少"。
- **G2:运维探针数据采集与分析。** 覆盖 JVM、服务器、世界、网络及代理端核心指标的采集、聚合、告警与多通道呈现。
- **G3:全版本 + 多平台。** Bukkit 系 1.8–1.21.11 全版本(含 Folia) + BungeeCord,单 jar 多端,核心 Java 8 字节码通用,版本/平台差异以最小胶水隔离。

### 1.3 非目标(首期明确不做,防止范围蔓延)
- 不做服务端内核(NMS)/DataFixerUpper 等 **bootstrap 阶段的逐方法级归因**(对普通插件不可见,需重型字节码织入)——首期以"整体时长对比"覆盖该层。
- 不自研重型 CPU 采样分析器替代 [spark];**运行期**深度 CPU 火焰图建议并用 spark(自研采样仅作 M3 轻量增强)。**例外**:**启动期(premain 窗口)**火焰图自研——spark 难介入 premain,而启动期恰是本项目首要场景(M5,`/probe flamegraph`,见 ADR-8)。
- 不做玩家行为分析(属 Plan 领域)。
- **首期不启用字节码插桩 / Incision**(仅预留架构 + PoC 验证)。

---

## 2. 兼容性支持矩阵

| 维度 | 范围 | 说明 |
|---|---|---|
| MC 版本 | **1.8 – 1.21.11**(及 26.1) | 与 TabooLib `MinecraftVersion.supportedVersion` 一致;被 `!` 跳过的紧急修复版(如 1.20.3/1.21/1.21.2)按 TabooLib 行为处理 |
| 服务端类型 | CraftBukkit / Spigot / Paper / **Folia** / 其他 Bukkit 衍生 | Folia 被识别为 Bukkit 变体(`Folia.isFolia`),非独立平台 |
| 代理端 | **BungeeCord** | Velocity 预留(架构已抽象,后续低成本接入) |
| 运行 JRE | Java 8+ | 随服务端版本要求;探针核心 Java 8 字节码,在所有 JRE 上可加载 |
| 编译 | 核心 **Java 8** target | 仅"直接继承高版本 NMS 类"的个别胶水模块才用高 toolchain(详见架构文档) |

---

## 3. 术语

| 术语 | 含义 |
|---|---|
| TPS | 每秒 tick 数(理想 20);**Folia 为 per-region,无全局值** |
| MSPT | 单 tick 耗时(毫秒),>50ms 即掉 tick;关注 p95/p99 |
| 启动画像(Startup Profile) | 一次启动的结构化耗时报告(总时长+分段+慢插件榜+世界耗时+JVM 参数快照) |
| 指标快照(Metric Snapshot) | 某时刻一组运维指标的采样值 |
| 采集器(Collector) | 采集某类指标的组件,平台无关接口 + 平台/版本实现 |
| 胶水模块 | 仅当通用 API 不支持某版本/平台功能时才编写的最小适配实现 |
| 代理端 | BungeeCord 等反向代理,**无世界/TPS/MSPT 概念** |
| 环形缓冲 | 定容内存队列,保存最近 N 分钟高频指标,避免磁盘 IO 压主线程 |
| 本地文件落盘 | 启动画像与聚合后历史以本地文件(JSON/JSONL)持久化,不依赖任何数据库 |
| 开放接口 | 对外只读数据访问 API + 存储 SPI 扩展点,供第三方消费或自接后端 |

---

## 4. 目标用户与场景

| 用户 | 核心场景 |
|---|---|
| 服主 | 开服明显变慢时,一条命令定位是哪个插件/世界拖慢了启动 |
| 运维 | 长期监控 TPS/MSPT/内存/GC,接 Grafana 看板与报警,事故回溯 |
| 插件开发者 | 排查自己插件的 enable 耗时与运行时开销占比 |

---

## 5. 技术选型决策(核心)

### 5.1 探针实现路线
**主体 = 纯 API + JMX(`java.lang.management`) + 平台原生 API + 采样,主体不用 Java Agent、不裸写 ASM。** 在此之上提供两类**可选增强**:
- **启动期 premain agent(可选,手动启用)**:命令行 `-javaagent:plugins/ServerProbe.jar` 启用,补 ServerProbe 自身加载前的盲区(逐插件精确耗时、库下载、主线程栈采样)。它是**启动期命令行 premain**,**不是被本表否决的运行时 self-attach**,不受 JEP 451 限制(详见架构文档 §13 / ADR-11);默认不启用,失败静默降级。
- **方法级精确插桩(可选)**:若启用,采用 **TabooLib Incision**(而非裸 ASM),且**默认关闭、需先 PoC 验证**(taboolib 本仓库零真实用例,成熟度待验证)。

依据(三方案对比;此处否决的是**运行时 self-attach**,非启动期 premain):

| 维度 | 裸 ASM + 运行时 self-attach Agent | TabooLib Incision | **纯 API + JMX + 采样(主体)** |
|---|---|---|---|
| 实现成本 | 最高 | 中 | **最低** |
| MC 上可用性 | ❌ self-attach 在 Paper/JDK21+ 默认失效 | ⚠️ 有 JVMTI 兜底,本仓库零用例 | ✅ 稳定 |
| 崩服风险 | 最高 | 高 | **最低(只读)** |
| 全版本+多平台 | ⚠️ 各端各 JDK 行为不一 | ⚠️ 代理端需自适配 | ✅ **最佳** |
| 运行开销 | 高 | 高 | **极低** |

> 探针 90%+ 指标用现成稳定 API 即可,**连 spark 都不用 Java Agent**。
> 注:上表否决的"self-attach"指**运行时自挂载**;**启动期命令行 premain agent** 走标准 `premain` 入口、不受 JEP 451 约束,作为可选增强专补加载前盲区(见 §5.4 / FR1 / 架构 §13)。

### 5.2 多版本兼容机制(依赖 TabooLib)
- **版本判断**:`MinecraftVersion`(`major`/`minor`/`isUniversal`/`isHigherOrEqual` 等),`isUniversal = major≥1.17`。
- **NMS 多版本抽象**:`nmsProxy<T>()` —— 写一套 Mojang 映射的抽象类 + Impl,运行期 ASM 重映射到任意服务端。优先**反射访问**(`nmsClass`/`getProperty`),避免直接 `extends` 高版本类型而被迫抬 toolchain。
- **原则:先通用,跑不通再拆胶水。** 绝大多数指标走 Bukkit API + JMX,全版本通用;仅 TPS/MSPT 低版本兜底等少数点需版本分支。

### 5.3 Folia 适配
- **调度零胶水**:TabooLib `submit()/submitAsync()` 已原生适配 Folia(自动走 GlobalRegion/Async Scheduler)。探针定时采集**一律走 `submit`,严禁直接 `Bukkit.getScheduler()`**。
- **需自处理两点**:① TPS/MSPT 在 Folia 无全局值(per-region)→ **per-region 明细 + 全局标 N/A**(M1 先全局 N/A,后续补 per-region);② Folia 下读实体/区块须用 `Location.callRegion{}`/`Entity.callRegion{}`(TabooLib 已提供)逐区域采集汇总。

### 5.4 关键技术手段

| 能力 | 手段 |
|---|---|
| 启动总时长 | `ServerLoadEvent(STARTUP)` 时刻 − `RuntimeMXBean.getStartTime()` |
| 逐插件 onEnable | 本插件:TabooLib 生命周期打点;全部插件:解析 `logs/latest.log` 时间戳;(可选/M4)Incision 插桩 |
| 启动分段 | `@Awake(CONST/INIT/LOAD/ENABLE/ACTIVE)` 各阶段 `nanoTime` |
| JVM 指标 | `java.lang.management` 全套 MXBean(全版本+全平台通用,最稳) |
| **TPS** | Paper `Bukkit.getTPS()`;无该 API 的版本/纯 CraftBukkit → `nmsProxy` 读 `MinecraftServer.recentTps` 或自建 tick 采样器;Folia → per-region/标 N/A |
| **MSPT** | 每 tick `nanoTime` 入直方图算 p95/p99;Folia 需 per-region |
| 世界/实体/区块 | Bukkit API,限频;Folia 用 `callRegion{}` |
| 代理端 | `ProxyServer.getServers()`+`getPlayers()`、`ServerInfo.ping()`、玩家路由 |
| CPU 归因(M3) | `ThreadMXBean` 周期采样栈,按插件 ClassLoader 归并(spark 模式,无 agent) |

### 5.5 Incision 预留与验证(FR7)
首期不启用。引入前**必须 PoC 验证**(目标 Paper + 目标 JDK 上 self-attach/JVMTI 兜底能否织入、开销、可回滚)。验证通过后,Incision **仅用于**"方法级精确归因"可选模块,默认关闭、失败静默降级。

---

## 6. 总体架构(摘要,详见[架构文档](ARCHITECTURE.md))

模块(全部 **Java 8**,除个别 NMS 胶水):

| 模块 | 职责 | 平台 |
|---|---|---|
| `api` | 契约与数据模型:采集器接口、指标/启动画像模型、呈现接口 | 通用 |
| `core`(现 `project:core`) | 通用核心:采集编排/调度、JMX 采集、聚合、告警、呈现核心、**本地文件存储 + 开放接口** | 通用 |
| `platform-bukkit` | Bukkit/Paper/**Folia** 采集:在线/世界/区块/实体/TPS/MSPT;Folia 分流写在此 | Bukkit |
| `platform-bungee` | 代理端采集:子服在线/延迟/路由/JVM | BungeeCord |
| `nms-vXXX`(可选,按需) | 仅当某指标必须直接引用 NMS 专有类型时才建 | Bukkit |
| `plugin` | 壳 + env install + 单 jar 打包 | 多端 |

数据流:`采集器(submit 定时/事件) → 快照/启动画像 → 环形缓冲 + 异步落盘(本地文件) → 聚合 → 四通道(命令/Prometheus/Web/文件) → 告警`。对外另提供只读数据访问 API 与存储 SPI(见 FR8)。

---

## 7. 功能需求

> 优先级:P0=首期必做,P1=次期,P2=增强。
> 交付状态图例:✅ 已交付 · ◑ 部分交付 · ○ 计划中。

### 7.0 功能交付状态总览

| FR | 能力 | 优先级 | 状态 |
|---|---|---|---|
| FR1 | 启动性能剖析 | P0 | ✅ 已交付¹ |
| FR1.7 | 可选 premain 启动 agent 增强(精确耗时/栈采样/火焰图/外呼监控) | P0 | ✅ 已交付¹ |
| FR2.1 | JVM 指标 | P0 | ✅ 已交付 |
| FR2.2 | 服务器 TPS/MSPT | P0 | ✅ 已交付 |
| FR2.3 | 世界指标 | P1 | ✅ 已交付(Folia 路线 1,仅区块数) |
| FR2.4 | 网络(在线 / ping 分布 / 流量) | P1·P2 | ◑ 在线已交付;ping 分布 / 流量计划 |
| FR2.5 | 代理端(BungeeCord) | P1 | ◑ 总在线 + 各子服在线已交付;ping / 路由 / 每玩家 ping 计划 |
| FR2.6 | 插件**运行期** CPU 归因 | P2 | ○ 计划(M3;**启动期**栈采样已由 agent 提供) |
| FR3 | 存储与聚合(环形缓冲 / 文件落盘 / 聚合) | P0 | ✅ 已交付 |
| FR4.1 | 游戏内命令 `/probe`(health/startup/tps/gc/world/proxy,+ flamegraph/http 见 FR1.7) | P0 | ✅ 已交付 |
| FR4.2 | Prometheus `/metrics` | P1 | ✅ 已交付 |
| FR4.3 | Web 面板 | P2 | ○ 计划(M3) |
| FR4.4 | 历史文件对比 | P1 | ✅ 已交付 |
| FR5 | 告警(阈值 + 防抖 + 三通道) | P1 | ✅ 已交付 |
| FR6 | 全版本与多平台(单 jar) | P0 | ✅ 已交付¹ |
| FR7 | 方法级精确归因(Incision) | P2 | ○ 计划(M4,默认关闭,先 PoC) |
| FR8 | 开放接口(只读 API + 存储 SPI + 静态门面) | P1 | ✅ 已交付 |

> ¹ 已交付但**仅 1.21.4 Paper 单端真机验证**;其他端(1.8 / Folia / BungeeCord)仅编译通过、未逐一真机。
> ✅ 已交付项随 **0.1.0**(2026-06-20)首发,版本口径即 `@v0.1.0`;◑ 部分与 ○ 计划项留后续版本。

### FR1 启动性能剖析(P0,首要)
- **FR1.1** 端到端启动总时长(`ServerLoadEvent` − JVM 启动时刻)。
- **FR1.2** 逐插件 onEnable 耗时榜(Top-N):生命周期(本插件)+ 日志解析(全部)。
- **FR1.3** 逐世界加载耗时 + spawn-chunk 预加载耗时。
- **FR1.4** 启动分段耗时(CONST/INIT/LOAD/ENABLE/ACTIVE)。
- **FR1.5** 启动画像**落盘为本地文件**,与上次/基线对比,标注每项 Δ。
- **FR1.6** 慢启动告警(总时长 > 基线 ×1.5)。
- **FR1.7(可选增强,需手动启用)** **premain Java Agent 补加载前盲区**:命令行加 `-javaagent:plugins/ServerProbe.jar` 后,额外提供 ① 逐插件 load/enable **精确耗时**(纳秒级,优于日志解析,且覆盖本插件之前加载的插件)② **库下载耗时**(`LibraryLoader`,1.17+)③ **世界创建 / 配置加载 / 事件注册 / 命令注册耗时**(插桩 `CraftServer.createWorld` / `YamlConfiguration.loadConfiguration` / `registerEvents` / `register`,逐项纳秒级)④ **多线程折叠栈采样**(`Server thread` / `Netty` / `ServerMain`,保留调用层级)⑤ **启动火焰图 + 嵌套时间线导出**(`/probe flamegraph` 把最近启动画像导出为**自包含 HTML**——CSS/JS 全内联、无 CDN——到 `data/flamegraph/`,含真正多层多线程火焰图(折叠栈逐层并树)+ 按区间包含关系分泳道的嵌套时间线;专注启动期、与运行期并用 spark 互补,见 ADR-8)⑥ **HTTP/TCP 对外网络外呼监控(运行期常驻)**(插桩 `HttpURLConnection.getInputStream` + `Socket.connect`,记录哪个插件/哪段代码发起对外请求、目标、耗时、响应码、脱敏后的请求头与查询串;实时日志 + 落盘 `data/http/` + `/probe http` 回看 + 启动期外呼并入报告。敏感项 agent 侧打码、请求体不捕获、有界缓冲防泄漏、可配开关,见 ADR-12)。属**启动期 premain**、非被否决的运行时 self-attach(见 §5.1 / 架构 §13);采集严格收敛在启动窗口(插件就绪即关闭,杜绝运行期泄漏);默认不启用,不加参数则纯插件模式照常工作,启用失败静默降级。M5 先 Bukkit 端,Folia 栈采样降级 N/A。**当前仅 1.21.4 Paper 单端真机验证。**
- **验收**:`/probe startup` 输出含总时长、慢插件 Top-N、各世界耗时、与上次对比;启用 `-javaagent` 后,逐插件耗时由日志秒级口径升级为精确纳秒级,世界/配置/事件/命令耗时与库下载/主线程热点一并呈现,`/probe flamegraph` 可导出火焰图+时间线 HTML,`/probe http` 可回看对外外呼并定位触发插件。

### FR2 运维指标采集(P0/P1)
- **FR2.1 JVM(P0)**:堆/非堆内存、各内存池、GC 次数与耗时(young/old)、线程数/死锁、类加载、进程&系统 CPU、uptime、启动参数。**全版本+全平台通用**。
- **FR2.2 服务器(P0,Bukkit)**:TPS(1/5/15min)、MSPT(均值+p95/p99)、在线人数、运行时长。**TPS/MSPT 需按 §5.4 做版本兼容 + Folia 语义处理**。
- **FR2.3 世界(P1,Bukkit)**:按世界的区块数、实体数(按类型)、方块实体数(限频);**Folia 用 `callRegion{}`**。
- **FR2.4 网络(P1)**:在线人数、ping 分布。(流量/数据包速率需 Netty 注入,P2)
- **FR2.5 代理端(P1,BungeeCord)**:总在线、各后端子服在线数、子服 ping/可达性、玩家路由、每玩家 ping、JVM 全套。
- **FR2.6 插件运行时归因(P2)**:事件监听/调度任务耗时(本插件自采)。**各插件 CPU 占比/火焰图不自研,建议并用 [spark](https://spark.lucko.me)**。注:**启动期**主线程栈采样已由可选 premain agent 特化提供(见 FR1.7,抓启动期"无日志卡顿"热点);此处指**运行期**的常态 CPU 归因。
- **约束**:采集周期可配;主线程只做轻量取值(MSPT 仅 `nanoTime`);聚合/遍历异步或限频;调度走 `submit`。

### FR3 存储与聚合(P0)
- **FR3.1** 内存环形缓冲:最近 N 分钟高频指标(定容)。
- **FR3.2** **本地文件异步落盘**:启动画像(每次一份 JSON)+ 指标历史(**聚合后**写 JSONL,按日期/会话滚动,可配保留策略与体积上限)。**不依赖任何数据库**。
- **FR3.3** 聚合:TPS 滑窗、MSPT 分位直方图、GC 差分。

### FR4 数据呈现(四通道)
- **FR4.1 游戏内命令(P0)**:`/probe health|startup|tps|gc|world|proxy`,权限受控。
- **FR4.2 Prometheus `/metrics`(P1)**:Bukkit 与 BC 各一套,端口可配,对接 Grafana。
- **FR4.3 Web 面板(P2)**:启动画像详情、历史趋势、(可选)火焰图;需鉴权+绑定地址。
- **FR4.4 历史文件对比(P1)**:见 FR1.5/FR3.2。

### FR5 告警(P1)
阈值(可配):TPS<18 警/<15 重;MSPT p95>50ms;堆>90% 持续;Old GC 频繁/单次>200ms;死锁立即;启动超基线 ×1.5。输出控制台/命令/(可选)webhook。

### FR6 全版本与多平台(P0)
- 单 jar 运行于 Bukkit 系 1.8–1.21.11(含 Folia)+ BungeeCord。
- **代理端定位 = 网络与子服健康监控**;不采世界/区块/实体/TPS/MSPT。
- **验收**:同一 jar 在 1.8 Spigot、1.21.x Paper、Folia、BungeeCord 上均能正常加载并采集对应指标。
  - **0.1.0 真机口径(收窄)**:**仅 1.21.4 Paper 单端经真机验证**;1.8 / Spigot 低版本 / Folia / BungeeCord 当前为**构建通过、实验性、尚未逐一真机**。完整多端真机留后续版本补齐后再上调口径(连带 FR2.5 BungeeCord、FR4.2 BungeeCord 端 Prometheus 的真机同此)。

### FR7 方法级精确归因(P2,可选,Incision,默认关闭)
先 PoC(§5.5),验证通过才启用。用途:`enablePlugin` 精确插桩、特定事件/方法耗时。

### FR8 开放接口(P1)
探针只落本地文件、不内置数据库;通过开放接口让数据可被外部消费或扩展后端。
- **FR8.1 读取 API**:`api` 模块暴露只读数据访问接口(最新指标快照、指标历史、启动画像),第三方插件经 TabooLib 服务获取;不暴露写入/控制能力。
- **FR8.2 存储 SPI**:定义存储后端接口,**默认且唯一内置 = 本地文件实现**;预留扩展点,第三方可自行实现 DB/远程后端(本插件不内置、不依赖)。
- **FR8.3 导出端点**:Prometheus `/metrics` + 可选 Web/HTTP 只读 API(见 FR4),作为对外数据出口。
- **验收**:第三方插件可经读取 API 取到当前 TPS/MSPT/启动画像;可经实现存储 SPI 替换默认文件后端。

---

## 8. 非功能需求

| 类别 | 要求 |
|---|---|
| 性能 | 运行期自身开销目标 <2%;MSPT 仅取 `nanoTime`;聚合/落盘/采样全异步;周期任务限频;环形缓冲定容;文件写入异步且原子。**严禁主线程阻塞磁盘 IO / 远程调用**。 |
| 稳定性 | 探针绝不能成为事故源;主体只读;可选插桩默认关闭、失败静默降级。 |
| 兼容性 | **1.8–1.21.11 全版本 + Folia + BungeeCord**;TPS/MSPT 按版本/平台降级;`com.sun.management` 在不同 JDK 的差异容错;严守"先通用再胶水"。 |
| 安全 | Web/Prometheus 端点鉴权 + 绑定地址限制;输出不泄露路径/token;外部输入校验。 |
| 可观测性 | 探针自身日志全中文、按 ERROR/WARN/INFO/DEBUG 分级;管理操作记审计日志。 |

---

## 9. 数据模型(本地文件记录)

以本地文件持久化,**不依赖数据库**。建议:启动画像每份一个 JSON;指标历史按 JSONL 行式追加、按日期/会话滚动。

- **StartupProfile**(JSON):`schemaVersion, serverId(恒有值,未配置时自动生成), platform, mcVersion, jvmStartTimeMs, totalMs, phaseTimings, pluginTimings, worldTimings, jvmArgs, createdAtMs`
- **MetricHistory**(JSONL,聚合后):`schemaVersion, ts, tps1/5/15, msptAvg/P95/P99, heapUsed/Max, gcYoungCount, gcOldCount, threadCount, cpuProcess, onlinePlayers`(写入频率受控;另含各 GC 收集器原始明细 gcCollectors)

> 落盘根对象均含 `schemaVersion`(M1=1),用于格式演进与向后兼容。

> 经统一存储 SPI 写入(见 FR8),默认实现为本地文件;原子写入(临时文件 + rename),可配保留策略与体积上限。

---

## 10. 迭代规划

| 里程碑 | 范围 |
|---|---|
| **M1(对应首要需求)** | 多版本+多平台骨架(FR6) + 启动剖析(FR1) + JVM/服务器基础指标(FR2.1/2.2,含 TPS/MSPT 兼容) + 游戏内命令(FR4.1) |
| **M2** | 完整指标(FR2.3-2.5) + 环形缓冲/文件落盘(FR3) + 开放接口(FR8) + Prometheus(FR4.2) + 告警(FR5) |
| **M3** | Web 面板(FR4.3) + 插件耗时归因(FR2.6;CPU 火焰图引导用 spark) |
| **M4(可选)** | Incision PoC + 方法级归因(FR7) |

---

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| TPS/MSPT 多版本+Folia 兼容 | 抽象 `ServerTickSampler` 接口:Paper 走 `getTPS()`;低版本 `nmsProxy` 兜底;Folia 明确 per-region 语义;均有 JMX 兜底 |
| Incision 本仓库零用例 | PoC 先行;主体不依赖 |
| 代理端能力受限 | 明确定位网络/子服健康 |
| 过早抽象一堆空胶水模块 | 严守"先通用,跑不通再拆";胶水按需新建 |
| 直接继承高版本 NMS 致 toolchain 传染 | 优先反射访问;确需直接引用才独立 nms-vXXX 模块抬 toolchain |
| 文件体积膨胀 | 聚合后落盘 + 滚动 + 保留策略 + 体积上限 |

---

## 12. 关键决策(原开放问题,已敲定 2026-06-08)

1. **依赖策略**:允许按需引入轻量依赖,但每个新依赖需逐个确认(JSON 优先用 TabooLib 自带 gson;Prometheus 可手写文本或按需引 simpleclient;Web 面板按需引轻量库)。
2. **CPU 火焰图**:不自研,建议并用 [spark](https://spark.lucko.me);ServerProbe 专注指标监控 + 启动剖析。
3. **代理端联动**:暂不与后端联动汇总,各端独立采集与展示(不引入 Porticus);联动作为后续可选增强。
4. **多实例标识**:自动生成实例 ID + 配置可覆盖为自定义 `server-name`;本地文件按实例分目录存放;跨服聚合由外部系统经开放接口完成。
5. **Folia TPS/MSPT**:**per-region 明细 + 全局标 N/A**;分阶段——M1 先全局 N/A,M2/M3 补 per-region 明细。

> 仍开放:各里程碑的具体发布日期待定。
