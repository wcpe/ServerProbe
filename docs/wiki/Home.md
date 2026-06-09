# ServerProbe Wiki

> ⚠️ 本页为设计阶段文档,描述目标行为,功能尚未实现。

ServerProbe 是一个基于 **TabooLib 6.3.0** 的 Minecraft 服务器运维探针。它有两件首要工作:

1. **排查"开服慢"** —— 量化定位服务端启动慢在哪个插件、哪个世界、哪个生命周期阶段,并与历史对比。
2. **运维指标采集** —— 统一采集、聚合、告警与可视化 TPS/MSPT/内存/GC/线程/世界负载等运维指标。

覆盖 **Bukkit 系 1.8 – 1.21.11 全版本(含 Folia)+ BungeeCord 代理端**,单 jar 多端运行,核心 Java 8 字节码。

> ⚠️ **当前状态:本项目处于设计阶段,为空骨架、尚无功能代码。** 本 Wiki 是面向未来用户的使用手册草案。文中涉及的命令、配置项、端点均标注 🚧 规划中,**请勿据此认为功能已可用**。

---

## 特性总览

| 特性 | 说明 | 状态 |
|---|---|---|
| 启动剖析 | 端到端启动总时长 + 逐插件/逐世界/逐阶段耗时排名 + 历史对比 | 🚧 规划中 |
| JVM 指标 | 堆/非堆内存、内存池、GC、线程、类加载、CPU、启动参数(全版本通用) | 🚧 规划中 |
| 服务器指标 | TPS、MSPT(p95/p99)、在线人数、运行时长 | 🚧 规划中 |
| 世界指标 | 按世界的区块/实体/方块实体计数(Folia 走 `callRegion`) | 🚧 规划中 |
| 代理端指标 | 总在线、子服在线/ping/路由、JVM(BungeeCord) | 🚧 规划中 |
| 多通道呈现 | 游戏内命令、Prometheus `/metrics`、Web 面板、本地文件历史落盘 | 🚧 规划中 |
| 开放接口 | 读取 API + 存储 SPI(供第三方读取/扩展后端) | 🚧 规划中 |
| 阈值告警 | TPS/MSPT/堆/GC/死锁/慢启动告警 | 🚧 规划中 |
| 全版本多平台 | 1.8–1.21.11 + Folia + BungeeCord,单 jar | 🚧 规划中 |

设计上**主体只读**(纯 API + JMX + 采样),主体不用 Java Agent、不裸写 ASM,绝不成为事故源。另提供一个**可选的启动期 premain agent**(命令行 `-javaagent` 手动启用,补加载前盲区,非运行时 self-attach,见[启动剖析指南](Startup-Profiling.md))。详见 [常见问题FAQ](FAQ.md)。

---

## 页面导航

| 页面 | 内容 |
|---|---|
| [安装与构建](Installation.md) | 服主安装步骤、开发者构建命令、各 MC 版本运行环境要求 |
| [版本与平台兼容](Compatibility.md) | 全版本支持、Folia 适配、BungeeCord 能力边界、平台指标差异 |
| [命令与权限](Commands.md) | `/probe` 各子命令用途、示意输出、权限节点表 |
| [指标说明](Metrics.md) | 逐类指标的含义、单位、采集方式、平台/版本差异 |
| [启动剖析指南](Startup-Profiling.md) | "开服慢"排查:启动画像解读、历史对比、慢启动根因 checklist |
| [数据呈现与对接](Data-Output.md) | 命令 / Prometheus+Grafana / Web 面板 / 本地文件 四通道 |
| [常见问题FAQ](FAQ.md) | 技术决策类问答(为何不用 Agent、Folia TPS 为何 N/A 等) |

---

## 兼容性矩阵摘要

| 维度 | 范围 | 说明 |
|---|---|---|
| MC 版本 | **1.8 – 1.21.11**(及 26.1) | 与 TabooLib `MinecraftVersion.supportedVersion` 一致 |
| 服务端类型 | CraftBukkit / Spigot / Paper / **Folia** / 其他 Bukkit 衍生 | Folia 是 Bukkit 运行期变体,非独立平台 |
| 代理端 | **BungeeCord** | Velocity 已在架构预留,后续低成本接入 |
| 运行 JRE | Java 8+ | 随服务端版本要求;探针核心 Java 8 字节码,所有 JRE 均可加载 |
| 分发形态 | **单 jar 多端** | 同一 jar 在 Bukkit 系与 BungeeCord 上各读各自描述符 |

完整矩阵与差异说明见 [版本与平台兼容](Compatibility.md)。

---

> 本页是 Wiki 首页。其余内容请从上方导航进入。
