# ServerProbe

面向 Minecraft 服务器的轻量级运维探针。使用同一个 jar 为 Bukkit、Spigot、Paper、Folia、BungeeCord 与 Velocity 3.1.1–4.x 提供启动分析、运行指标、网络取证、代理健康与 Prometheus 输出。

[![版本](https://img.shields.io/github/v/release/wcpe/ServerProbe?label=version&sort=semver)](https://github.com/wcpe/ServerProbe/releases)
[![构建](https://img.shields.io/github/actions/workflow/status/wcpe/ServerProbe/ci.yml?branch=master&label=build)](https://github.com/wcpe/ServerProbe/actions)
[![许可证](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-red)](#兼容性与验收)

当前正式版本：[v0.3.0](https://github.com/wcpe/ServerProbe/releases/tag/v0.3.0)。项目坚持只读采集与默认安全：可选增强默认关闭，失败时降级，不应影响服务器或其他插件启动。

## 能做什么

- **定位启动慢**：记录启动总时长、生命周期、世界加载和插件启用耗时，并可与上次启动画像对比。
- **采集运行指标**：JVM、GC、线程、CPU、TPS、MSPT、在线人数与世界负载统一采集、聚合和落盘。
- **正确观测 Folia**：全局 TPS/MSPT 明确为 N/A；对含玩家的真实 region 输出 TPS/MSPT 分位、世界汇总与过期状态。
- **网络取证**：Bukkit 系、BungeeCord 与 Velocity 全平台采集双向流量与包速率；本地 SQLite 保存重要包证据，Prometheus 只输出脱敏聚合，完整 IP/载荷仅经鉴权的 Web 面板与只读 API 可查。
- **观测代理网络**：BungeeCord 与 Velocity 提供子服在线、RTT、可达性、玩家路由和玩家 ping。
- **可选业务集成**：内置 MultiCurrencyEconomy 与 AllinInventorySync 对接模块；未安装对应插件时不加载、不影响探针。
- **事故诊断控制面（可选，默认关闭）**：手动开启后外部 MCP 客户端经 JSON-RPC 读取状态、执行服务器命令、查看线程/死锁，并驱动内嵌 Arthas Core（Java 8–16 用 3.1.1，Java 17+ 用 4.3.2）进行 watch/trace/类重定义等深度诊断。
- **多种查看方式**：游戏内 `/probe`、Prometheus `/metrics`、本地 JSON/JSONL、内置 Web 面板与告警。
- **按需精确归因**：支持启动期 `-javaagent` 与 Bukkit/Paper 的 Incision 方法级归因；两者均为可选能力。

## 快速开始

1. 从 [GitHub Releases](https://github.com/wcpe/ServerProbe/releases) 下载最新的 `ServerProbe-<版本>.jar`（当前 v0.3.0）。
2. 将 jar 放入服务端的 `plugins/` 目录（Velocity/BungeeCord 同样放入其插件目录）。
3. 重启服务器。首次启动会生成 `plugins/ServerProbe/config.yml`。
4. 在游戏内执行 `/probe health`，或按需启用 Prometheus 后访问 `/metrics`。

从源码构建时运行 `./gradlew build`；产物位于 `plugin/build/libs/`。

同一个 jar 可直接用于 Bukkit/Spigot/Paper/Folia、BungeeCord 或 Velocity 3.1.1–4.x。代理端只采集网络与子服健康，不采集世界、TPS 或 MSPT。

## 常用命令

| 命令 | 用途 |
|---|---|
| `/probe health` | 查看健康概览 |
| `/probe startup` | 查看启动画像与历史对比 |
| `/probe tps`、`/probe gc`、`/probe world` | 查看 TPS、GC 与世界指标；Folia 的 `/probe tps` 另含已观测 region 明细 |
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

### MCP 诊断控制面（默认关闭）

`mcp.enabled=true` 时开放 MCP Streamable HTTP（JSON-RPC 2.0）端点，供外部 MCP 客户端读取状态、执行服务器命令并调用内嵌 Arthas Core。安全边界：

- 默认监听 `127.0.0.1:9942` 且不启用；不提供 TLS，也不强制密钥——但**开启该控制面即等于授予 JVM 完整控制权限**（类重定义、任意 JVM 内代码求值），请仅在明确授权的事故场景开启。
- 监听非回环地址或使用空密钥时会打印醒目中文 WARN，审计不记录密钥与命令正文。
- 不提供 OS Shell；Arthas 上游 Telnet/HTTP/MCP 端口不监听。

## 兼容性与验收

| 环境 | 已验证范围 |
|---|---|
| Paper 1.20.1 + JDK 21 | 指标、命令、Prometheus、Web 面板、FR-08/FR-09/FR-10 自动化 E2E、网络取证、MCP 诊断 |
| Paper 1.21.11 + JDK 21 | Incision 采集、降级与性能 |
| Spigot 1.8.8 + Java 8 | 加载、JVM、TPS/MSPT、玩家与世界指标 |
| Spigot 1.20.1 | 网络取证、MCP 诊断（Arthas 3.1.1 路径见 Java 8 验收） |
| Folia 1.21.4 + JDK 21 | 加载、JVM/玩家/世界指标；全局 TPS 按设计为 N/A；已观测 region 明细（详见 [`docs/specs/folia-observed-regions.md`](docs/specs/folia-observed-regions.md)）、网络取证与 MCP 诊断 |
| BungeeCord 1.19-R0.1 #1700 + Java 8 | 加载、`/probe proxy` 与 Prometheus；另经验收网络取证与 MCP 诊断 |
| BungeeCord #2088 + 两个 Paper 后端 | 在线数、RTT/可达性、切服路由与玩家 ping |
| Velocity 3.1.1 / 3.5.1 / 4.1.0（JDK25） | 加载、指标、后端 RTT/可达性、切服路由、玩家 ping、网络取证（详见 [`docs/specs/velocity-platform.md`](docs/specs/velocity-platform.md)）；MCP 诊断 |

完整验收与能力边界见 [PRD](docs/PRD.md) 和 [更新日志](CHANGELOG.md)。

开发时可额外运行 [E2E 验收](e2e/README.md)：

```powershell
.\gradlew.bat e2eReadApi
.\gradlew.bat e2eStorageSpi
.\gradlew.bat e2eBridgeFixture
.\gradlew.bat e2eIntegrationsBoth        # 需设置 SERVERPROBE_E2E_CORELIB_JAR/MCE_JAR/AIS_JAR 真实插件路径
.\gradlew.bat e2eNetworkForensicsPaperWithBot
.\gradlew.bat e2eFoliaObservedRegionsWithBot
.\gradlew.bat e2eMcpDiagnosticsPaper
```

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
