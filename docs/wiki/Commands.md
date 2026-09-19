# 命令与权限

游戏内命令是 ServerProbe 呈现通道之一。统一入口为 `/probe`，各子命令权限受控，全部**只读**。

根权限：`serverprobe.command`（无权限提示"你没有权限使用该命令"）。
子命令权限：`serverprobe.command.<子命令>`。

## 命令总览

| 命令 | 用途 | 适用平台 |
|---|---|---|
| `/probe health` | 健康总览：TPS/MSPT、堆已用·最大、在线人数、运行时长 | Bukkit / 代理端 |
| `/probe startup` | 最近一次启动画像：总时长、慢插件 Top-N、世界耗时、与上次对比 | Bukkit |
| `/probe tps` | TPS（1/5/15 分钟）与 MSPT（avg/p95/p99）；Folia 全局 N/A + 已观测 region 明细 | Bukkit（Folia 有 region 明细） |
| `/probe gc` | GC（young/old 的 count/timeMs）与堆/非堆/关键内存池 | 通用 |
| `/probe world` | 各世界指标：已加载区块数、实体数、方块实体数 | Bukkit |
| `/probe ping` | 在线玩家 ping 分布（固定区间桶） | Bukkit（1.16.1+） |
| `/probe cpu` | 各插件运行期 CPU 样本占比（默认关闭 `cpu.enabled`） | Bukkit |
| `/probe proxy` | 代理端子服在线、RTT、可达性、玩家路由 | BungeeCord / Velocity |
| `/probe flamegraph` | 导出最近启动画像为自包含 HTML 火焰图 + 时间线（需启动 agent） | Bukkit |
| `/probe http` | 回看近期对外网络调用（需启动 agent） | Bukkit |
| `/probe mcp <on\|off\|status>` | 运行期起停 MCP 诊断控制面端点（**仅控制台**） | 通用 |
| `/probe mcp arthas <on\|off>` | 运行期加载/卸载内嵌 Arthas 运行时（**仅控制台**） | 通用 |

## 权限节点

| 权限 | 说明 |
|---|---|
| `serverprobe.command` | 根权限（无参 `/probe` 显示帮助） |
| `serverprobe.command.health` | `/probe health` |
| `serverprobe.command.startup` | `/probe startup` |
| `serverprobe.command.tps` | `/probe tps` |
| `serverprobe.command.gc` | `/probe gc` |
| `serverprobe.command.world` | `/probe world` |
| `serverprobe.command.ping` | `/probe ping` |
| `serverprobe.command.cpu` | `/probe cpu` |
| `serverprobe.command.proxy` | `/probe proxy` |
| `serverprobe.command.flamegraph` | `/probe flamegraph` |
| `serverprobe.command.http` | `/probe http` |
| `serverprobe.command.mcp` | `/probe mcp`（仅控制台生效，游戏内即使持有该权限也被拒） |

## 备注

- 命令输出均为内存快照/聚合结果，无阻塞 IO。
- Folia 下 `/probe tps` 的全局值显示 N/A，并额外输出已观测 region 的 TPS/MSPT 明细。
- `/probe flamegraph` / `/probe http` 依赖启动期 agent 采集的数据，未挂 `-javaagent` 时显示不可用。
- `/probe mcp` 是唯一的**写操作**类命令（运行期开关控制面，见 [ADR-0028](../adr/0028-mcp-runtime-toggle.md)）：仅控制台/RCON 可执行（开启 MCP 等于授予 JVM 完整控制权，防游戏内权限提权）；开关只作用于当前运行期，不写回 `config.yml`，重启回到配置声明的姿态。典型用法是"平时按默认关闭运行，线上出问题时在控制台 `mcp on` 取得原生诊断，需要 `watch`/火焰图等深度手段再 `mcp arthas on`，处理完 `mcp off`"。
