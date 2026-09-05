# 常见问题 FAQ

## Q1：为什么不用 Java Agent / 裸 ASM 做插桩？

对 Minecraft 探针来说，代价高、风险大、可用性差，而收益绝大部分用现成 API 就能拿到。探针主体 = 纯 API + JMX + 采样，**90%+ 指标用现成稳定 API**。被否决的是**运行时 self-attach**；启动期命令行 premain agent（可选、需手动 `-javaagent` 启用）不受此限，作为补加载前盲区的增强存在。

## Q2：探针会影响服务器性能吗？

设计目标是运行期自身开销 <2%：主线程只做轻量取值（MSPT 仅 `nanoTime`），聚合/落盘/采样全异步或限频，文件写入异步且原子。可选增强（agent / Incision / CPU 采样 / MCP）默认关闭，失败静默降级，绝不影响服务器或其他插件启动。

## Q3：Folia 的 TPS/MSPT 怎么处理？

Folia 没有全局主线程或全局 TPS。全局值**明确为 N/A**；对含在线玩家的真实 ticking region 输出 TPS/MSPT avg/p95/p99 明细、按世界汇总与过期状态（`/probe tps` 查看）。

## Q4：数据存在哪里？用数据库吗？

默认**本地文件**：启动画像 JSON、指标历史 JSONL（按日期滚动、可配保留）、网络取证本地 SQLite（`serverprobe-store.sqlite`，60 天 / 4 GiB 双上限）。不依赖外部数据库；第三方可经**存储 SPI** 替换为自建后端。

## Q5：Prometheus 怎么对接？

`metrics.enabled=true` 后，Bukkit / BungeeCord 各起一个 `/metrics` 端点（端口可配），token + IP 白名单鉴权，默认仅本机。指标前缀 `serverprobe_`，可直接接入 Grafana。

## Q6：为什么要有 MCP 诊断控制面？安全吗？

普通指标只能说明"哪里慢"，事故中需要线程、死锁、方法调用、类重定义等证据。MCP 控制面（默认关闭）提供 JSON-RPC 2.0 接口，内嵌 Arthas Core 支持深度诊断。**开启即等于授予 JVM 完整控制权限**，仅限明确授权的事故场景；默认回环监听、可配 Bearer、审计不记录密钥与命令正文、不提供 OS Shell。

## Q7：Velocity 支持到哪些版本？

Velocity **3.1.1 至最新 4.x**（代表矩阵 3.1.1 / 3.5.1 / 4.1.0-JDK25），能力与 BungeeCord 对齐（总在线、后端 RTT/可达性、玩家路由、玩家 ping）并接入网络取证。

## Q8：如何定位"开服慢"？

`/probe startup` 查看启动画像：总时长、慢插件 Top-N、世界耗时、生命周期分段、与上次对比。想更精确就挂 `-javaagent`（纳秒级逐插件耗时 + 火焰图 + 外呼监控），或启用 Incision 方法级归因。

## Q9：网络取证会泄露玩家隐私吗？

取证记录包元数据与白名单载荷（单包默认 64 KiB、前缀截断保留原始长度与完整 SHA-256）。完整 IP/载荷**仅经鉴权的 Web 面板与只读 API 可查**；Prometheus 只输出脱敏聚合（IPv4 掩 /24、IPv6 掩 /64），每采样期只保留 Top100 前缀。

## Q10：为什么不用跨服务器聚合？

各端独立采集与展示，不做跨服务器聚合（ADR-9）；跨服聚合由外部系统经开放接口完成。这样避免引入重型组件、保持探针轻量。

---

> 更多技术决策见 [架构文档](https://github.com/wcpe/ServerProbe/blob/master/docs/ARCHITECTURE.md) 与 [ADR 记录](https://github.com/wcpe/ServerProbe/blob/master/docs/adr/README.md)。
