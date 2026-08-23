# ServerProbe

Minecraft 服务器运维探针：定位开服瓶颈，采集运行指标，并通过命令、Prometheus、本地文件和 Web 面板输出数据。

[![Version](https://img.shields.io/badge/version-0.1.0-blue)](CHANGELOG.md)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-red)](#构建)
[![TabooLib](https://img.shields.io/badge/TabooLib-6.3.0-9cf)](https://github.com/TabooLib/taboolib)

> **版本状态**：`v0.1.0` 已发布；当前工作区的修复尚未发布。正式版本以 Git tag 与发行产物为准。

## 功能

| 范围 | 已实现能力 |
|---|---|
| 启动分析 | 启动总时长、插件/世界/阶段耗时、历史对比、慢启动告警 |
| JVM 与服务端指标 | 内存、GC、线程、CPU、TPS、MSPT、在线人数与世界指标 |
| 网络与代理 | 玩家 ping 分布、子服健康、路由与每玩家 ping |
| 存储与展示 | 内存缓冲、JSON/JSONL、`/probe`、Prometheus `/metrics`、Web 面板 |
| 可选增强 | `-javaagent` 启动分析、火焰图、时间线、HTTP/TCP 外呼监控、运行期 CPU 归因 |
| 扩展 | 只读 API、存储 SPI、JianManager 业务桥接基础设施 |

## 验证范围

| 环境 | 状态 |
|---|---|
| Paper 1.21.4 + JDK21 | 启动 agent 已真机验证 |
| Paper 1.20.1 + JDK21 | 指标、命令、Prometheus、Web 与部分业务 Provider 已真机验证 |
| Paper 1.21.11 + JDK21 | 当前发行构建已真机加载并启用 |
| BungeeCord + Java 8 | 单 jar 已真机加载并启用，代理采集器已启动 |
| 1.8 / Spigot / Folia | 构建通过，尚未完成逐端真机验收 |

Incision（FR7）已确认 Paper + JDK21 的织入链路，但性能、回滚与失败降级尚未验收；FR9 已移出本期验收范围。完整状态见 [PRD](docs/PRD.md) 与 [更新日志](CHANGELOG.md)。

## 构建

```powershell
.\gradlew.bat build
```

构建产物：`plugin/build/libs/ServerProbe-0.1.0.jar`。

将该 jar 放入 Bukkit/Paper/Folia 或 BungeeCord 的 `plugins/` 目录后重启。首次启动会生成 `plugins/ServerProbe/config.yml`。

## 命令

| 命令 | 作用 |
|---|---|
| `/probe health` | 健康概览 |
| `/probe startup` | 启动画像与历史对比 |
| `/probe tps` / `/probe gc` / `/probe world` | TPS、GC 与世界指标 |
| `/probe ping` / `/probe proxy` / `/probe cpu` | ping、代理与 CPU 归因 |
| `/probe flamegraph` / `/probe http` | 启动火焰图和近期外呼（需启动 agent） |

权限节点：`serverprobe.command`；子命令权限为 `serverprobe.command.<子命令>`。

## 启动 agent（可选）

同一个 jar 可作为启动 agent 使用：

```bash
java -javaagent:plugins/ServerProbe.jar -jar paper.jar
```

不添加 `-javaagent` 时，普通插件功能不受影响。

## 文档

- [产品需求文档](docs/PRD.md)
- [更新日志](CHANGELOG.md)
- [安装与配置 Wiki](docs/wiki/Home.md)
- [架构](docs/ARCHITECTURE.md)
- [开放 API](docs/API.md)
- [运维手册](docs/OPERATIONS.md)

## 许可

[MIT License](LICENSE)
