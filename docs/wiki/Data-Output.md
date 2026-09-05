# 数据呈现与对接

ServerProbe 提供**多个呈现 / 对接通道**，同一份采集结果向多个出口共享，互不重复采集。

| 通道 | 状态 | 适用场景 |
|---|---|---|
| 游戏内命令 `/probe` | ✅ 已交付 | 即时巡检、单次定位"开服慢" |
| Prometheus `/metrics` | ✅ 已交付 | 长期监控、看板（Grafana）、报警 |
| 告警引擎（日志 / 游戏内 / Webhook） | ✅ 已交付 | 阈值越线主动推送 |
| Web 面板 | ✅ 已交付 | 总览 / 启动画像详情 / 历史趋势 / 取证查询 |
| 本地文件历史落盘 | ✅ 已交付 | 历史对比、事故回溯、数据沉淀（不依赖数据库） |
| 开放只读 API + 存储 SPI | ✅ 已交付 | 第三方插件读取数据或替换存储后端 |

## Prometheus `/metrics`

- Bukkit 与 BungeeCord 各一套，端口可配（`metrics.port`）
- 鉴权：token（`Authorization: Bearer`）+ IP 白名单双重校验，默认仅本机、默认关闭
- 指标前缀 `serverprobe_`，覆盖 JVM / 服务器 / 世界维度
- 取证指标只输出聚合速率、包类型计数与脱敏 IP Top100（IPv4 掩 /24、IPv6 掩 /64）

## Web 面板

- 三个只读页面：总览 `/`、启动画像 `/startup`、历史趋势 `/history`，另有网络取证查询页
- 自包含 HTML（内联 CSS、无 CDN）
- 鉴权：token + IP 白名单，默认仅本机、**默认关闭**（`web.enabled=false`）

## 告警引擎

- 内置阈值规则：TPS<18 警 / <15 重、MSPT p95>50ms、堆>90% 持续、Old GC 频繁/单次>200ms、死锁立即、启动超基线 ×1.5
- 防抖（持续 N 周期才触发）与恢复状态机
- 三通道：日志 / 游戏内 / Webhook（默认关闭 `alert.enabled=false`）

## 本地文件落盘

- 启动画像：每次一份 JSON（`data/startup/`）
- 指标历史：JSONL 行式追加、按日期滚动（`data/metrics/`），可配保留策略
- 落盘根对象含 `schemaVersion`，向后兼容读旧格式
- 网络取证：本地 SQLite（`serverprobe-store.sqlite`），默认保留 60 天、上限 4 GiB

## 开放接口（第三方插件）

- **只读 API**：第三方经 `ServerProbeApi` 读取最新快照 / 历史 / 启动画像（只读，不暴露写入/控制）
- **存储 SPI**：`ServerProbeStorageApi.install` 可安装第三方 `MetricStore`，关闭注册后原子回退默认本地文件实现
- 详细契约见 [开放 API 文档](https://github.com/wcpe/ServerProbe/blob/master/docs/API.md)

## MCP 深度诊断控制面（默认关闭）

`mcp.enabled=true` 时开放 MCP Streamable HTTP（JSON-RPC 2.0）端点（默认 `127.0.0.1:9942`）：

- 原生工具：服务器状态/指标、平台命令执行、线程 CPU Top/栈/死锁、`diagnostic_bundle`
- 内嵌 Arthas Core（Java 8–16 用 3.1.1、Java 17+ 用 4.3.2）：`watch/trace/monitor/tt/profiler` 等持续命令异步任务化，`arthas_execute` 兜底全部命令
- 产物统一写 `mcp-workspace/`（heap dump、火焰图、反编译源码、dump class），支持分块读写与清理
- 审计记录来源 IP/工具/任务/耗时/参数 SHA-256，不记录密钥与命令正文

> **安全边界**：开启该控制面即等于授予 JVM 完整控制权限（类重定义、任意 JVM 内代码求值），仅限明确授权的事故场景。不提供 OS Shell；Arthas 上游 Telnet/HTTP/MCP 端口不监听。
