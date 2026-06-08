# 更新日志(Changelog)

本项目所有重要变更均记录于此文件。

本文件格式遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [语义化版本(SemVer)](https://semver.org/lang/zh-CN/)。

> 说明:ServerProbe 当前处于**设计阶段**,尚未发布任何版本。下方 `[未发布]` 段记录的是设计与文档产出(非功能代码);`规划路线` 段列出计划中的版本,均**计划中、日期待定**。

---

## [未发布] (Unreleased)

> 本阶段产出均为**设计与文档**,不包含可运行的功能代码。

### 新增
- 初始化项目骨架:`api` / `core`(`project:core`)/ `plugin` 三模块目录与基础构建配置(当前源码目录为空,从零开发)。
- 新增开源许可证 [`LICENSE`](LICENSE):**MIT License**。

### 变更
- 确立技术选型与技术决策(详见架构文档 ADR-1 ~ ADR-10):
  - **探针主体 = 纯 API + JMX(`java.lang.management`)+ 平台原生 API + 采样**,不引入 Java Agent、不裸写 ASM(只读优先,绝不成为事故源)。
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
- **关键决策敲定(同步至 Wiki 各页)**:将原"待定 / 开放问题 / 产品定义未定"的若干议题更新为明确结论。
  - **Folia TPS/MSPT 呈现**:采用 **per-region 明细 + 全局标 N/A**;分阶段实施 —— M1 先全局标 N/A,M2/M3 补 per-region 明细(原"标 N/A / 聚合 / per-region 待定")。
  - **依赖策略**:**允许按需引入轻量依赖,但每个新依赖需逐个确认**(原 Prometheus / Web 服务依赖"是否引入属开放问题")。
  - **CPU 热点 / 火焰图**:**不自研**,建议并用 [spark](https://spark.lucko.me);ServerProbe 专注指标监控 + 启动剖析(原"是否自研火焰图属开放问题")。
  - **代理端(BungeeCord)**:**各端独立采集与展示,暂不与后端联动汇总**(不引入 Porticus 等跨服汇总组件)。
  - **实例标识 `serverId`**:由实例**自动生成**,可在配置中以 **`server-name`** 覆盖为自定义值;本地文件**按实例分目录存放**(原"多服共存 serverId 标识规则属开放问题")。
  - **开源许可证**:选定 **MIT License**(原"待定")。

---

## 规划路线(Roadmap)

> 以下版本均为**计划中,日期待定**,与 PRD §10 的迭代规划(M1–M4)对应。版本号为初步规划,实际发布时以最终实现为准。

### [0.1.0] - 计划中:M1 启动剖析 + 基础指标 + 命令 + 多平台骨架

- **多版本 + 多平台骨架(FR6)**:单 jar 运行于 Bukkit 系 1.8–1.21.11(含 Folia)+ BungeeCord;面向接口 + 运行时装配。
- **启动性能剖析(FR1)**:端到端启动总时长、逐插件 onEnable 耗时榜、逐世界加载耗时、启动分段耗时、启动画像落盘与上次/基线对比、慢启动告警。
- **JVM / 服务器基础指标(FR2.1 / FR2.2)**:JVM 全套 MXBean 指标(内存/GC/线程/类加载/CPU/启动参数);TPS / MSPT(含多版本兼容 + Folia 语义处理)、在线人数、运行时长。
- **游戏内命令(FR4.1)**:`/probe health|startup|tps|gc|world|proxy`,权限受控。

### [0.2.0] - 计划中:M2 完整指标 + 存储聚合 + Prometheus + 告警

- **完整指标采集(FR2.3 – FR2.5)**:世界(区块/实体/方块实体,Folia 用 `callRegion{}`)、网络(在线/ping 分布)、代理端(子服在线/ping/路由/JVM)。
- **存储与聚合(FR3)**:内存环形缓冲(最近 N 分钟高频指标)、本地文件异步落盘(启动画像 JSON + 聚合后指标历史 JSONL)、聚合(TPS 滑窗 / MSPT 分位直方图 / GC 差分)。
- **开放接口(FR8:读取 API + 存储 SPI)**:`api` 模块暴露只读数据访问(最新快照 / 历史 / 启动画像,经 TabooLib 服务获取);存储 SPI 预留扩展点(默认且唯一内置 = 本地文件,第三方可自接 DB / 远程)。
- **Prometheus `/metrics`(FR4.2)**:Bukkit 与 BungeeCord 各一套,端口可配,对接 Grafana。
- **告警(FR5)**:TPS / MSPT / 堆 / GC / 死锁 / 慢启动等阈值告警,输出控制台 / 命令 /(可选)webhook。

### [0.3.0] - 计划中:M3 Web 面板 + CPU 采样归因

- **Web 面板(FR4.3)**:启动画像详情、历史趋势、(可选)火焰图;需鉴权 + 绑定地址。
- **CPU 采样归因(FR2.6)**:`ThreadMXBean` 周期采样栈,按插件 ClassLoader 归并(spark 模式,无 agent),给出各插件 CPU 占比。

### [0.4.0] - 计划中(可选):M4 Incision PoC + 方法级归因

- **Incision PoC(§5.5)**:在目标 Paper + 目标 JDK 上验证 self-attach / JVMTI 兜底能否织入、开销与可回滚性。
- **方法级精确归因(FR7)**:验证通过后启用,用于 `enablePlugin` 精确插桩、特定事件/方法耗时;**默认关闭**,失败静默降级。

[未发布]: https://github.com/
