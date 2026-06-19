# 命令与权限

> ⚠️ 本页为设计阶段文档,描述目标行为,功能尚未实现。

游戏内命令是 ServerProbe 四个呈现通道之一(对应 FR4.1)。统一入口为 `/probe`,各子命令权限受控。

> ⚠️ 以下所有命令、输出、权限节点均为 🚧 规划中,功能尚未实现;示意输出仅用于说明形态,非真实数据。

---

## 命令总览

| 命令 | 用途 | 适用平台 |
|---|---|---|
| `/probe health` | 健康总览:关键指标一屏速览(TPS/MSPT/内存/在线等) | Bukkit / Bungee |
| `/probe startup` | 启动剖析:总时长、慢插件 Top-N、各世界耗时、与上次对比 | Bukkit(Bungee 仅 JVM+自身) |
| `/probe tps` | TPS / MSPT 详情(含 p95/p99) | Bukkit |
| `/probe gc` | GC 详情:young/old 次数与耗时、堆/内存池 | Bukkit / Bungee |
| `/probe world` | 各世界负载:区块 / 实体(按类型)/ 方块实体 | Bukkit |
| `/probe proxy` | 代理端总览:总在线、各子服在线 / ping / 路由 | Bungee |
| `/probe flamegraph` | 导出启动火焰图 + 嵌套时间线自包含 HTML(需 `-javaagent` 启动 agent) | Bukkit |
| `/probe http` | 查看近期对外 HTTP/TCP 外呼:哪个插件/代码触发、目标、响应码、耗时(需 `-javaagent`) | Bukkit |

> 平台差异:代理端无世界 / TPS / MSPT 概念,`tps` / `world` 在代理端不可用;`proxy` 仅代理端可用。详见 [版本与平台兼容](Compatibility.md)。

---

## 各命令详解(示意输出)

> 下列输出为**形态示意**,数值为占位,不代表真实采集结果。

### `/probe health`
用途:一屏看懂当前服务器健康状态,作为日常巡检入口。

```
[ServerProbe] 健康总览  (Paper 1.21.4 / 运行 3h12m)
 TPS  : 19.8 / 19.9 / 20.0   (1m/5m/15m)
 MSPT : 均值 8.3ms  p95 14.1ms  p99 22.7ms
 堆内存: 4.1G / 8.0G (51%)    线程: 142  死锁: 无
 在线  : 37 人
 状态  : 正常
```

### `/probe startup`
用途:排查"开服慢",查看本次启动画像并与上次对比。详细解读见 [启动剖析指南](Startup-Profiling.md)。

```
[ServerProbe] 启动剖析
 端到端总时长: 48.6s   (上次 31.2s, Δ +17.4s ↑)
 分段: CONST 0.1s | INIT 0.4s | LOAD 2.1s | ENABLE 39.8s | ACTIVE 6.2s
 慢插件 Top-5 (onEnable):
   1. SomePlugin     22.3s   (上次 5.1s, Δ +17.2s ↑)
   2. AnotherPlugin   6.4s
   ...
 世界耗时:
   world        4.8s   (spawn-chunk 预加载 3.9s)
   world_nether 1.1s
```

### `/probe tps`
用途:查看 TPS 与 MSPT 分位详情。

```
[ServerProbe] TPS / MSPT
 TPS : 19.8 / 19.9 / 20.0   (1m/5m/15m)
 MSPT: 均值 8.3ms  p50 6.9ms  p95 14.1ms  p99 22.7ms
```

> 在 Folia 上 TPS/MSPT 为 per-region 语义:已敲定按 **per-region 明细 + 全局标 N/A** 呈现(M1 先全局 N/A,M2/M3 补 per-region 明细),见 [常见问题FAQ](FAQ.md)。

### `/probe gc`
用途:查看 GC 与内存详情,辅助判断停顿与内存压力。

```
[ServerProbe] GC / 内存
 Young GC: 1284 次 / 累计 31.2s
 Old   GC: 6 次 / 累计 1.8s  (单次最大 410ms)
 堆: 4.1G / 8.0G (51%)   非堆: 312M
```

### `/probe world`
用途:查看各世界负载,定位高实体 / 高区块世界。

```
[ServerProbe] 世界负载
 world        区块 1420  实体 3105 (僧侣 2100, 物品 480, ...)  方块实体 612
 world_nether 区块 210   实体 88                                方块实体 33
```

> Folia 下世界数据按 region 汇总(`callRegion`),采集限频。

### `/probe proxy`
用途(仅代理端):查看网络拓扑与子服健康。

> 多端关系(已敲定):**各端独立采集与展示,暂不与后端联动汇总**(不引入 Porticus)。本命令仅展示代理端自身视角的网络拓扑与子服探活,不聚合各后端的服务器指标。

```
[ServerProbe] 代理端总览  (BungeeCord)
 总在线: 213 人
 子服:
   lobby   在线 80   ping 2ms    可达
   survival在线 95   ping 4ms    可达
   creative在线 38   ping 7ms    可达
   minigame在线 0    ping  --    不可达
```

### `/probe flamegraph`
用途:把最近一次启动画像导出为**自包含 HTML**(CSS/JS 全内联,离线可看),含两个视图:
- **火焰图**:由多线程折叠栈生成的真正多层火焰图,宽度=调用路径采样占比、纵轴=调用深度,可切换线程、缩放(左键下钻/右键返回)、搜索帧名;
- **时间线**:各 hook 事件按区间包含关系分泳道渲染的嵌套时间线(`enable` 区间内含其 `registerEvents`/`loadConfiguration`/`register` 子区间),X 轴为相对 premain 的真实时间。

```
[ServerProbe] 已生成火焰图: /path/to/server/plugins/ServerProbe/flamegraph/flamegraph-20260617_103200.html
```

> 前置条件:需在启动命令加 `-javaagent:plugins/ServerProbe.jar` 挂载启动 agent(否则无折叠栈/时间线数据,命令会提示启用方式)。详见 [启动剖析指南](Startup-Profiling.md)。

### `/probe http`
用途:回看最近的**对外网络外呼**(HTTP/HTTPS 经 `HttpURLConnection`,以及绕过它的原始 TCP 连接),定位"哪个插件、哪段代码在请求外部、目标是谁、耗时多少、响应如何"。运行期常驻监控,每条外呼亦实时打印日志并落盘 `data/http/`。

```
[ServerProbe] 近期外呼 (最近 6 条)
 - [CoreLib] GET https://maven.aliyun.com/.../bcutil.xml (200, 137000ms) ← corelib.taboolib...RuntimeEnv#loadDependency
 - [SomePlugin] POST https://api.example.com/report (200, 42ms) ← com.some.Reporter#flush
```

> 前置条件:需 `-javaagent:plugins/ServerProbe.jar`。**安全**:URL 查询串与请求头中的 Authorization/Cookie/token 等敏感项自动打码为 `***`;请求体不在捕获范围。可在 `config.yml` 的 `http-monitor` 段调整开关/控制台日志/落盘/请求头记录等。

---

## 权限节点

> 节点命名为 🚧 规划中草案,最终以实现为准。

| 权限节点 | 授予能力 | 建议授予对象 |
|---|---|---|
| `serverprobe.command` | 使用 `/probe` 入口 | 运维 / 管理 |
| `serverprobe.command.health` | `/probe health` | 运维 / 管理 |
| `serverprobe.command.startup` | `/probe startup` | 运维 / 管理 |
| `serverprobe.command.tps` | `/probe tps` | 运维 / 管理 |
| `serverprobe.command.gc` | `/probe gc` | 运维 / 管理 |
| `serverprobe.command.world` | `/probe world` | 运维 / 管理 |
| `serverprobe.command.proxy` | `/probe proxy`(代理端) | 代理端管理 |
| `serverprobe.command.flamegraph` | `/probe flamegraph`(导出启动火焰图 HTML) | 运维 / 管理 |
| `serverprobe.command.http` | `/probe http`(查看近期对外外呼) | 运维 / 管理 |
| `serverprobe.admin` | 管理操作(配置 / 维护类,如有) | 仅管理员 |

要点:
- 管理类操作会记**审计日志**(全中文),便于事故回溯。
- 控制台默认可执行全部命令。
- 输出不泄露服务器路径 / token 等敏感信息(安全要求)。

---

> 返回 [Wiki 首页](Home.md) · 相关:[指标说明](Metrics.md) · [启动剖析指南](Startup-Profiling.md)
