# 安装与构建

本页面向两类读者：**服主**（只想把插件装上）和**开发者**（需要从源码构建）。

## 一、服主安装

ServerProbe 是**单 jar 多端**：同一个 jar 既能放进 Bukkit 系服务端，也能放进 BungeeCord / Velocity 代理端。

1. 从 [GitHub Releases](https://github.com/wcpe/ServerProbe/releases) 下载最新 `ServerProbe-<版本>.jar`。
2. 将 jar 放入服务端的 `plugins/` 目录（BungeeCord 放 `plugins/`、Velocity 放 `plugins/`）。
3. 重启服务器。首次启动会生成 `plugins/ServerProbe/config.yml`。
4. 游戏内执行 `/probe health` 验证，或按需启用 Prometheus 后访问 `/metrics`。

### 兼容性

| 环境 | 支持 |
|---|---|
| Bukkit 系 | CraftBukkit / Spigot / Paper / Folia，MC 1.8 – 1.21.11（及 26.1） |
| 代理端 | BungeeCord、Velocity 3.1.1–4.x |
| 运行 JRE | Java 8+（核心 Java 8 字节码，随服务端版本要求） |

> 代理端只采集网络与子服健康，不采集世界、TPS 或 MSPT。

## 二、开发者构建

前置：JDK 8 与 JDK 17+（Gradle toolchain 自动检测）。

```bash
# 全量构建（编译 + 单测 + detekt 静态检查）
./gradlew build

# 仅测试
./gradlew test
```

产物位于 `plugin/build/libs/ServerProbe-<版本>.jar`。

### E2E 验收（可选）

```powershell
.\gradlew.bat e2eReadApi
.\gradlew.bat e2eStorageSpi
.\gradlew.bat e2eBridgeFixture
.\gradlew.bat e2eIntegrationsBoth        # 需设置 SERVERPROBE_E2E_CORELIB_JAR/MCE_JAR/AIS_JAR 真实插件路径
.\gradlew.bat e2eNetworkForensicsPaperWithBot
.\gradlew.bat e2eFoliaObservedRegionsWithBot
.\gradlew.bat e2eMcpDiagnosticsPaper
```

E2E 基于 [mc-testkit](https://github.com/wcpe/mc-testkit)，在真实服务端上验证。

## 三、可选增强

### 启动期 agent

以启动参数启用额外的启动期精确采集（逐插件耗时、栈采样、火焰图、外呼监控）：

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

### MCP 诊断控制面

默认关闭。`mcp.enabled=true` 时开放 MCP Streamable HTTP（JSON-RPC 2.0）端点，详见 [数据呈现与对接](Data-Output)。
