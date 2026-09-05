# 版本与平台兼容

ServerProbe 的核心设计目标（G3）：**单 jar 覆盖 Bukkit 系 1.8–1.21.11 全版本（含 Folia）+ BungeeCord + Velocity 3.1.1–4.x**，核心 Java 8 字节码通用，版本/平台差异以**最小胶水**隔离。

## 一、全版本支持（MC 1.8 – 1.21.11）

| 维度 | 范围 | 说明 |
|---|---|---|
| MC 版本 | **1.8 – 1.21.11**（及 26.1） | 与 TabooLib `MinecraftVersion.supportedVersion` 一致 |
| 服务端类型 | CraftBukkit / Spigot / Paper / **Folia** / 其他 Bukkit 衍生 | Folia 识别为 Bukkit 变体，非独立平台 |
| 代理端 | **BungeeCord + Velocity 3.1.1–4.x** | 两端独立采集与展示，不做跨服务器聚合 |
| 运行 JRE | Java 8+ | 随服务端版本要求；核心 Java 8 字节码 |
| 编译 | 核心 Java 8 target | 仅直接继承高版本 NMS 类的个别胶水模块抬 toolchain |

## 二、多版本兼容机制

- **版本判断**：`MinecraftVersion`（`major`/`minor`/`isUniversal`/`isHigherOrEqual`）
- **NMS 抽象**：`nmsProxy<T>()` 运行期 ASM 重映射；优先反射访问，避免直接 `extends` 高版本类型
- **原则**：先通用，跑不通再拆胶水；绝大多数指标走 Bukkit API + JMX 全版本通用

## 三、平台差异处理

| 能力 | Bukkit 系 | Folia | 代理端 |
|---|---|---|---|
| TPS | Paper API / NMS 反射 / 自采样 | **全局 N/A** + 已观测 region 明细 | 无此概念 |
| MSPT | Paper / 自研直方图兜底 | per-region 明细 | 无此概念 |
| 调度 | TabooLib `submit` | TabooLib `submit`（原生适配） | 各自调度 |
| 世界/实体 | Bukkit API | `callRegion{}` 逐区域 | 不采集 |
| 网络取证 | ✅ | ✅ | ✅ |
| MCP 诊断 | ✅ | ✅ | ✅ |

## 四、真机验收矩阵（v0.3.0）

| 环境 | 已验证范围 |
|---|---|
| Paper 1.20.1 + JDK 21 | 指标、命令、Prometheus、Web、开放接口/集成/取证/诊断 E2E |
| Paper 1.21.11 + JDK 21 | Incision 采集、降级与性能 |
| Spigot 1.8.8 + Java 8 | 加载、JVM、TPS/MSPT、玩家与世界指标 |
| Spigot 1.20.1 | 网络取证、MCP 诊断 |
| Folia 1.21.4 + JDK 21 | 加载、指标、已观测 region 明细、网络取证、MCP 诊断 |
| BungeeCord #2088 + 两个 Paper 后端 | 在线数、RTT/可达性、切服路由、玩家 ping |
| BungeeCord 1.19-R0.1 #1700 + Java 8 | 加载、`/probe proxy`、Prometheus |
| Velocity 3.1.1 / 3.5.1 / 4.1.0（JDK25） | 加载、指标、后端 RTT/可达性、切服路由、玩家 ping、网络取证、MCP 诊断 |

多版本矩阵（Paper 1.8.8–1.21.1 + Spigot 1.8.8/1.16.5，Java 8/17/21）十场景真机全部 PASS。
