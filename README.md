# ServerProbe

面向 Minecraft 服务器的轻量级运维探针。使用同一个 jar 为 Bukkit、Spigot、Paper、Folia 与 BungeeCord 提供启动分析、运行指标、代理健康与 Prometheus 输出。

[![版本](https://img.shields.io/github/v/tag/wcpe/ServerProbe?label=version&sort=semver)](https://github.com/wcpe/ServerProbe/tags)
[![构建](https://img.shields.io/github/actions/workflow/status/wcpe/ServerProbe/ci.yml?branch=master&label=build)](https://github.com/wcpe/ServerProbe/actions)
[![许可证](https://img.shields.io/github/license/wcpe/ServerProbe)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-red)](#兼容性与验收)

当前正式版本：[v0.2.0](https://github.com/wcpe/ServerProbe/releases/tag/v0.2.0)。项目坚持只读采集与默认安全：可选增强默认关闭，失败时降级，不应影响服务器或其他插件启动。

## 能做什么

- **定位启动慢**：记录启动总时长、生命周期、世界加载和插件启用耗时，并可与上次启动画像对比。
- **采集运行指标**：JVM、GC、线程、CPU、TPS、MSPT、在线人数与世界负载统一采集、聚合和落盘。
- **观测代理网络**：BungeeCord 提供子服在线、RTT、可达性、玩家路由和玩家 ping。
- **多种查看方式**：游戏内 `/probe`、Prometheus `/metrics`、本地 JSON/JSONL、内置 Web 面板与告警。
- **按需精确归因**：支持启动期 `-javaagent` 与 Bukkit/Paper 的 Incision 方法级归因；两者均为可选能力。

## 快速开始

1. 从 [GitHub Releases](https://github.com/wcpe/ServerProbe/releases) 下载 `ServerProbe-0.2.0.jar`。
2. 将 jar 放入服务端的 `plugins/` 目录。
3. 重启服务器。首次启动会生成 `plugins/ServerProbe/config.yml`。
4. 在游戏内执行 `/probe health`，或按需启用 Prometheus 后访问 `/metrics`。

从源码构建时运行 `./gradlew build`；产物位于 `plugin/build/libs/`。

同一个 jar 可直接用于 Bukkit/Spigot/Paper/Folia 或 BungeeCord。代理端只采集网络与子服健康，不采集世界、TPS 或 MSPT。

## 常用命令

| 命令 | 用途 |
|---|---|
| `/probe health` | 查看健康概览 |
| `/probe startup` | 查看启动画像与历史对比 |
| `/probe tps`、`/probe gc`、`/probe world` | 查看 TPS、GC 与世界指标 |
| `/probe ping`、`/probe proxy`、`/probe cpu` | 查看网络、代理与 CPU 归因 |
| `/probe flamegraph`、`/probe http` | 查看启动火焰图与近期外呼（需启动 agent） |

根权限为 `serverprobe.command`；子命令权限为 `serverprobe.command.<子命令>`。

## 可选增强

### 启动期 agent

以启动参数启用额外的启动期精确采集：

```bash
java -javaagent:plugins/ServerProbe.jar -jar paper.jar
```

不添加该参数时，普通插件功能不受影响。

### Incision 方法级归因

仅 Bukkit/Paper 支持，默认关闭。启用后重启服务端：

```yaml
incision:
  enabled: true
```

它会记录 `SimplePluginManager#enablePlugin` 的逐插件精确耗时。未命中有效切点时，ServerProbe 自动保留普通启动画像并继续运行。

## 兼容性与验收

| 环境 | 已验证范围 |
|---|---|
| Paper 1.20.1 + JDK 21 | 指标、命令、Prometheus 与 Web 面板 |
| Paper 1.21.11 + JDK 21 | Incision 采集、降级与性能 |
| Spigot 1.8.8 + Java 8 | 加载、JVM、TPS/MSPT、玩家与世界指标 |
| Folia 1.21.4 + JDK 21 | 加载、JVM/玩家/世界指标；全局 TPS 按设计为 N/A |
| BungeeCord 1.19-R0.1 #1700 + Java 8 | 加载、`/probe proxy` 与 Prometheus |
| BungeeCord #2088 + 两个 Paper 后端 | 在线数、RTT/可达性、切服路由与玩家 ping |

完整验收与能力边界见 [PRD](docs/PRD.md) 和 [更新日志](CHANGELOG.md)。

## 文档

- [安装与配置 Wiki](docs/wiki/Home.md)
- [指标说明](docs/wiki/Metrics.md)
- [启动剖析说明](docs/wiki/Startup-Profiling.md)
- [架构](docs/ARCHITECTURE.md)
- [开放 API](docs/API.md)
- [运维手册](docs/OPERATIONS.md)
- [安全政策](SECURITY.md)

## 参与贡献

请先阅读 [贡献与维护指南](docs/CONTRIBUTING.md)。提交前运行：

```powershell
.\gradlew.bat build
```

## 许可证

[MIT License](LICENSE)
