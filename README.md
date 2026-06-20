# ServerProbe

> 基于 [TabooLib](https://github.com/TabooLib/taboolib) 6.3.0 的 **Minecraft 服务器运维探针**:首要解决「开服慢」的可量化排查,并提供运维指标的采集 / 聚合 / 告警 / 可视化。覆盖 Bukkit 系 1.8 – 1.21.11 全版本(含 Folia)+ BungeeCord 代理端,单 jar 多端,核心 Java 8 字节码。

[![Version](https://img.shields.io/badge/version-0.1.0-blue)](docs/PRD.md)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8--1.21.11-blue)](#兼容性矩阵)
[![TabooLib](https://img.shields.io/badge/TabooLib-6.3.0-9cf)](https://github.com/TabooLib/taboolib)
[![Java](https://img.shields.io/badge/Java-8%2B-red)](#技术栈)

---

> ⚠️ **M1 + M2 已实现:`./gradlew build` 编译 + 单元测试通过,并已在 1.21.4 Paper 单端真机验证全通过;尚未发布、未经生产环境验证,其他端(1.8 / Folia / BungeeCord)仅编译通过、M2 功能未逐一真机。**
>
> M1(P1–P9)代码已落地——多平台骨架、启动剖析、JVM/服务器指标、`/probe` 命令、本地文件落盘、代理端基础。M2(M2-1~6)在此基础上补齐——世界指标完整、指标聚合、历史 JSONL 落盘、Prometheus `/metrics`、告警引擎、开放接口扩面,**全程零新增第三方依赖**。功能标注:✅ 已实现(待生产验证) / 🚧 规划中(M3–M4)。完整路线见 [CHANGELOG](CHANGELOG.md) 与 [PRD](docs/PRD.md)。

---

## 它解决什么问题

- **痛点一(首要):开服慢,且说不清慢在哪。** 服务端启动是一条串行链(JVM 启动 → 服务端 bootstrap → 各插件 onLoad/onEnable → 世界加载 → `Done!`),任一环节卡顿都会拖慢整体,现有手段只能「感觉慢」,无法量化定位元凶。
- **痛点二:缺乏统一运维探针。** TPS / MSPT / 内存 / GC / 线程 / 世界负载等指标散落各处,没有统一采集、聚合、告警与可视化,无法长期监控与回溯。

目标:用一条命令回答「这次开服慢在哪、比上次慢多少」,并为运维提供长期可观测的服务器健康数据。

---

## 核心特性

- **启动性能剖析** ✅ 已实现(待生产验证) —— 端到端启动总时长(`ServerLoadEvent` − JVM 启动时刻)、逐插件启用间隔耗时、逐世界加载耗时、生命周期分段(CONST/INIT/LOAD/ENABLE/ACTIVE)耗时,并与上次对比。
- **启动期 agent(可选,premain 注入,补加载前盲区)** ✅ 1.21.4 真机(可选增强,需手动启用) —— 启动命令加 `-javaagent:plugins/ServerProbe.jar` 后,额外提供逐插件 load/enable 精确耗时(纳秒级,覆盖本插件之前加载的插件)、库下载耗时、主线程栈采样(抓启动期"无日志卡顿"热点)。属**启动期 premain**(非运行时 self-attach,不受 JEP 451 限制);**不加参数则纯插件模式照常工作**,启用失败静默降级。M5 先 Bukkit 端(Folia 栈采样降级 N/A,BungeeCord 推迟);唯一新依赖 ASM。部署见[下文](#启动期-agent可选)。
- **运维指标采集** ——
  - **JVM** ✅ 已实现(待生产验证):堆 / 非堆内存、各内存池、GC(明细 + young/old)、线程数 / 死锁、类加载、进程 & 系统 CPU、uptime、启动参数(全版本 + 全平台通用)。
  - **服务器(Bukkit)** ✅ 已实现(待生产验证):TPS(1/5/15min)、MSPT(均值 + p95/p99)、在线人数、运行时长;多版本兼容(Paper API / 低版本 NMS / 自采样),Folia 全局 N/A。
  - **世界(Bukkit)** ✅ 已实现(待生产验证):按世界的已加载区块 / 实体 / 方块实体数及按类型实体分布;独立限频采样。Folia 走路线 1(仅区块数,实体 / 方块实体置 N/A;`callRegion{}` 完整支持留后续)。
  - **指标聚合** ✅ 已实现(待生产验证):近 N 份快照的 TPS 滑窗均值、MSPT 跨快照分位、GC 差分速率(FR3.3)。
  - **代理端(BungeeCord)**:总在线、各子服在线 ✅ 已实现(待生产验证);子服 ping / 可达性、玩家路由 🚧 规划中。
- **多通道呈现** ——
  - 游戏内命令 `/probe …`(权限受控)✅ 已实现(待生产验证)
  - 本地文件落盘(启动画像 JSON + 历史指标 JSONL)+ 与上次对比 ✅ 已实现(待生产验证)
  - 开放接口(只读 API + 存储 SPI + 静态门面)✅ 已实现(待生产验证)
  - Prometheus `/metrics`(`serverprobe_*` 指标,token + IP 白名单鉴权)+ Grafana 看板 ✅ 已实现(待生产验证)
  - 告警引擎(TPS / MSPT / 堆 / 死锁阈值,防抖 + 恢复,日志 / 游戏内 / Webhook 三通道)✅ 已实现(待生产验证)
  - Web 面板(启动画像详情、历史趋势)🚧 规划中(M3)
- **全版本 + 多平台** ✅ 已实现(待生产验证) —— 单 jar 运行于 Bukkit 系 1.8 – 1.21.11(含 Folia)+ BungeeCord,核心 Java 8 字节码通用,版本 / 平台差异以最小胶水隔离。

> 设计原则:**只读优先,绝不成为事故源**。主体只读采集,运行期自身开销目标 < 2%;聚合 / 落盘 / 采样全异步;字节码插桩等危险手段默认关闭、失败静默降级。

---

## 兼容性矩阵

> 摘自 [产品需求文档 §2](docs/PRD.md)。当前仅 1.21.4 Paper 单端经真机验证,其余版本 / 平台为设计目标、尚未逐一真机。

| 维度 | 范围 | 说明 |
|---|---|---|
| **MC 版本** | 1.8 – 1.21.11(及 26.1) | 与 TabooLib `MinecraftVersion.supportedVersion` 一致;被 `!` 跳过的紧急修复版按 TabooLib 行为处理 |
| **服务端类型** | CraftBukkit / Spigot / Paper / **Folia** / 其他 Bukkit 衍生 | Folia 被识别为 Bukkit 运行期变体(`Folia.isFolia`),非独立平台 |
| **代理端** | **BungeeCord** | Velocity 预留(架构已抽象,后续低成本接入) |
| **运行 JRE** | Java 8+ | 随服务端版本要求(1.17+ 服务端跑 17+,1.20.5+ 跑 21+);探针核心 Java 8 字节码,在所有 JRE 上可加载 |
| **编译 target** | 核心 **Java 8** | 仅「直接继承高版本 NMS 类」的个别胶水模块才用高 toolchain |

---

## 快速开始

### 作为玩家 / 服主

> 🚧 项目尚未发布,暂无可下载的构建产物。下述为发布后的预期安装方式。

1. 将构建出的 `ServerProbe-*.jar` 放入服务端的 `plugins/` 目录(代理端放入 BungeeCord 的 `plugins/`)。
2. 重启服务端,首次启动会生成默认配置。
3. 使用 `/probe …` 命令查看探针数据(详见[命令概览](#命令概览))。

### 启动期 agent(可选)

ServerProbe 是**二合一 jar**——同一个 `ServerProbe.jar` 既是 `plugins/` 下的插件,又可作 `-javaagent`。**默认无需任何额外操作**,丢进 `plugins/` 即是功能完整的纯插件。

若想额外采集 ServerProbe **自身加载之前**的盲区(逐插件精确耗时、库下载、主线程栈采样),在**启动命令**里加一行 `-javaagent` 指向同一个 jar 即可:

```bash
java -javaagent:plugins/ServerProbe.jar -jar paper.jar
```

- **手动启用**:必须自己改启动命令/脚本;**不加这行则纯插件模式照常工作**,所有既有能力不受影响。
- **为何安全**:这是**启动期命令行 premain**(`main` 之前由 JVM 加载),**不是运行时 self-attach**,在 Paper + JDK21/24 上零警告、不受 JEP 451 限制;premain 顶层兜底,启用失败一律静默降级,不会崩 JVM。
- **当前边界(诚实标注)**:仅 **1.21.4 Paper 单端真机验证**;M5 先 Bukkit 端(Folia 无单一主线程,主线程栈采样降级标 N/A;BungeeCord 推迟);引入唯一新依赖 ASM(已 relocate 隔离)。其他端(1.8 / Folia / BungeeCord)尚未逐一真机。

### 作为开发者

构建**发行版本**(用于正常使用,不含 TabooLib 本体):

```bash
./gradlew build
```

构建**开发版本**(包含 TabooLib 本体,供开发者使用,但不可运行):

```bash
./gradlew taboolibBuildApi -PDeleteCode
```

> 参数 `-PDeleteCode` 表示移除所有逻辑代码以减少体积。

---

## 命令概览

> ✅ 八命令均已实现(编译 + 单测通过,1.21.4 Paper 单端真机验证通过,待生产验证)。语法与输出以最终实现为准。

| 命令 | 作用 | 状态 |
|---|---|---|
| `/probe health` | 服务器健康总览(TPS/MSPT/内存/CPU/在线) | ✅ 已实现(待生产验证) |
| `/probe startup` | 启动画像:总时长、慢插件 Top-N、各世界耗时、与上次对比;挂载 agent 时附库下载/主线程热点/配置·事件·命令 Top-N | ✅ 已实现(待生产验证) |
| `/probe tps` | TPS(1/5/15min)与 MSPT(均值 + p95/p99),附近期聚合行(均值/分位/GC 速率) | ✅ 已实现(待生产验证) |
| `/probe gc` | GC(young/old)、堆 / 非堆 / 内存池 | ✅ 已实现(待生产验证) |
| `/probe world` | 各世界区块数、实体数、方块实体数(Folia 仅区块数) | ✅ 已实现(待生产验证) |
| `/probe proxy` | 代理端:总在线、各子服在线(ping/路由 🚧 规划中) | ✅ 已实现(待生产验证) |
| `/probe flamegraph` | 导出启动**火焰图 + 嵌套时间线**自包含 HTML 到 `data/flamegraph/`(需 `-javaagent` 挂载启动 agent) | ✅ 已实现(待生产验证) |
| `/probe http` | 查看近期**对外 HTTP/TCP 外呼**(哪个插件/代码触发、目标、响应码、耗时;需 `-javaagent`) | ✅ 已实现(待生产验证) |

---

## 技术栈

| 组件 | 用途 |
|---|---|
| **Kotlin** 2.1.0 | 主开发语言 |
| **TabooLib** 6.3.0 | 插件框架:多版本抽象(`MinecraftVersion` / `nmsProxy`)、Folia 适配调度(`submit`)、单 jar 多端打包 |
| **taboolib-ioc** 0.0.6 | 依赖注入与组件装配 |
| **本地文件存储** | 启动画像落 JSON、聚合后指标历史按日滚动落 JSONL(`data/metrics/<实例>/`),不依赖数据库;提供读取 API + 存储 SPI 开放接口 |
| **Java 8** | 核心编译 target,保证产物可被 1.8 – 1.21.x 全部 JRE 加载 |

---

## 技术选型一句话

**主体 = 纯 API + JMX(`java.lang.management`)+ 平台原生 API + 采样,不引入 Java Agent、不裸写 ASM**(90%+ 指标用现成稳定 API 即可,只读、零崩服风险);方法级精确插桩作为可选增强,预留 [TabooLib Incision](https://github.com/TabooLib/taboolib),**默认关闭**,引入前须先 PoC 验证。

---

## 文档导航

| 文档 | 说明 |
|---|---|
| [产品需求文档(PRD)](docs/PRD.md) | What / Why:背景、目标、兼容矩阵、功能需求、迭代规划 |
| [架构文档](docs/ARCHITECTURE.md) | How:模块分层、多版本兼容机制、Folia 适配、ADR |
| [Wiki](docs/wiki/Home.md) | 安装与构建、版本/平台兼容、命令与权限、指标说明、启动剖析指南、数据呈现、FAQ |
| [CHANGELOG](CHANGELOG.md) | 版本变更记录与 M1–M4 规划路线 |

---

## 许可

本项目采用 [MIT License](LICENSE)。
