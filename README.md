# ServerProbe

> 基于 [TabooLib](https://github.com/TabooLib/taboolib) 6.3.0 的 **Minecraft 服务器运维探针**:首要解决「开服慢」的可量化排查,并提供运维指标的采集 / 聚合 / 告警 / 可视化。覆盖 Bukkit 系 1.8 – 1.21.11 全版本(含 Folia)+ BungeeCord 代理端,单 jar 多端,核心 Java 8 字节码。

[![Version](https://img.shields.io/badge/version-v0.2--draft-orange)](docs/PRD.md)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8--1.21.11-blue)](#兼容性矩阵)
[![TabooLib](https://img.shields.io/badge/TabooLib-6.3.0-9cf)](https://github.com/TabooLib/taboolib)
[![Java](https://img.shields.io/badge/Java-8%2B-red)](#技术栈)

---

> ⚠️ **本项目处于早期设计阶段,以下为目标蓝图,功能尚未实现。**
>
> 当前仓库为空骨架(仅含配置文件与设计文档,源码目录为空),无任何可用功能。本 README 描述的是产品目标与规划,请勿将其中特性视为「已实现可用」。每项能力均标注了状态:🚧 规划中 / 🔬 设计阶段。

---

## 它解决什么问题

- **痛点一(首要):开服慢,且说不清慢在哪。** 服务端启动是一条串行链(JVM 启动 → 服务端 bootstrap → 各插件 onLoad/onEnable → 世界加载 → `Done!`),任一环节卡顿都会拖慢整体,现有手段只能「感觉慢」,无法量化定位元凶。
- **痛点二:缺乏统一运维探针。** TPS / MSPT / 内存 / GC / 线程 / 世界负载等指标散落各处,没有统一采集、聚合、告警与可视化,无法长期监控与回溯。

目标:用一条命令回答「这次开服慢在哪、比上次慢多少」,并为运维提供长期可观测的服务器健康数据。

---

## 核心特性

- **启动性能剖析** 🔬 设计阶段 —— 端到端启动总时长(`ServerLoadEvent` − JVM 启动时刻)、逐插件 onEnable 耗时榜、逐世界加载与 spawn-chunk 预加载耗时、生命周期分段(CONST/INIT/LOAD/ENABLE/ACTIVE)耗时,并支持与上次 / 基线对比、标注每项 Δ。
- **运维指标采集** 🔬 设计阶段 —— 多维度指标采集:
  - **JVM**:堆 / 非堆内存、各内存池、GC 次数与耗时、线程数 / 死锁、类加载、进程 & 系统 CPU、uptime、启动参数(全版本 + 全平台通用)。
  - **服务器(Bukkit)**:TPS(1/5/15min)、MSPT(均值 + p95/p99)、在线人数、运行时长。
  - **世界(Bukkit)**:按世界的区块数、实体数(按类型)、方块实体数(限频;Folia 走 `callRegion{}` 逐区域汇总)。
  - **网络**:在线人数、ping 分布。
  - **代理端(BungeeCord)**:总在线、各后端子服在线数、子服 ping / 可达性、玩家路由、JVM 全套。
- **四通道呈现** 🚧 规划中 ——
  - 游戏内命令 `/probe …`(权限受控)🔬 设计阶段
  - Prometheus `/metrics` 端点 + Grafana 看板(Bukkit 与 BC 各一套,端口可配)🚧 规划中
  - Web 面板(启动画像详情、历史趋势,需鉴权 + 绑定地址)🚧 规划中
  - 历史文件落盘与对比(本地文件,聚合后异步落盘)🚧 规划中
  - 开放接口(读取 API + 存储 SPI)🚧 规划中
- **全版本 + 多平台** 🔬 设计阶段 —— 单 jar 运行于 Bukkit 系 1.8 – 1.21.11(含 Folia)+ BungeeCord,核心 Java 8 字节码通用,版本 / 平台差异以最小胶水隔离。

> 设计原则:**只读优先,绝不成为事故源**。主体只读采集,运行期自身开销目标 < 2%;聚合 / 落盘 / 采样全异步;字节码插桩等危险手段默认关闭、失败静默降级。

---

## 兼容性矩阵

> 摘自 [产品需求文档 §2](docs/PRD.md)。当前为设计目标,尚未经实际验证。

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

> 🚧 所有命令均为规划中,尚未实现。语法与输出以最终实现为准。

| 命令 | 作用 | 状态 |
|---|---|---|
| `/probe health` | 服务器健康总览(TPS/MSPT/内存/在线等关键指标快照) | 🚧 规划中 |
| `/probe startup` | 启动画像:总时长、慢插件 Top-N、各世界耗时、与上次对比 | 🚧 规划中 |
| `/probe tps` | TPS(1/5/15min)与 MSPT(均值 + p95/p99) | 🚧 规划中 |
| `/probe gc` | GC 次数与耗时(young/old)、堆 / 内存池使用 | 🚧 规划中 |
| `/probe world` | 各世界区块数、实体数(按类型)、方块实体数 | 🚧 规划中 |
| `/probe proxy` | 代理端:总在线、各子服在线 / ping / 可达性、玩家路由 | 🚧 规划中 |

---

## 技术栈

| 组件 | 用途 |
|---|---|
| **Kotlin** 2.1.0 | 主开发语言 |
| **TabooLib** 6.3.0 | 插件框架:多版本抽象(`MinecraftVersion` / `nmsProxy`)、Folia 适配调度(`submit`)、单 jar 多端打包 |
| **taboolib-ioc** 0.0.5 | 依赖注入与组件装配 |
| **本地文件存储** | 启动画像/指标历史落本地文件(JSON/JSONL),不依赖数据库;提供读取 API + 存储 SPI 开放接口 |
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
