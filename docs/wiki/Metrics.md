# 指标说明

本页逐类说明 ServerProbe 采集的指标：含义、单位、采集方式、平台/版本差异。

## 设计约束（贯穿所有指标）

- 主线程只做轻量取值（MSPT 仅 `nanoTime`）；聚合/遍历异步或限频。
- 调度一律走 TabooLib `submit`，严禁 `Bukkit.getScheduler()`。
- 采集周期可配（`collect-period-ticks`，默认 100 tick ≈ 5 秒）；严禁循环内 DB / 远程调用。

## JVM 指标（FR-02，全平台通用）

| 指标 | 说明 |
|---|---|
| 堆/非堆内存 | used / committed / max |
| 内存池 | 各池 used/max（label `pool`） |
| GC | young/old 的 count 与 time（counter），及速率差分 |
| 线程 | 总数/daemon/峰值/死锁数 |
| 类加载 | 已加载类数 |
| CPU | 进程与系统 CPU 负载 |
| 运行时长 | uptime |

## 服务器指标（Bukkit）

| 指标 | 说明 |
|---|---|
| TPS | 1/5/15 分钟；Paper 走 `Bukkit.getTPS()`，低版本 NMS 反射或自采样兜底；**Folia 全局 N/A** |
| MSPT | 均值/p95/p99；Paper 原生或自研 tick 时钟直方图兜底 |
| 在线人数 | 当前/最大 |
| 世界负载 | 各世界已加载区块数、实体数、方块实体数（限频采样） |

## 网络指标

| 指标 | 说明 |
|---|---|
| ping 分布 | 在线玩家 RTT 按固定区间桶（<50ms / 50-100 / 100-200 / 200-500 / 500ms+）；1.16.1+ 经 `Player#getPing()`，低版本 N/A |
| 流量/包速率 | 全平台双向 bytes/s、packets/s、包类型计数（FR-11） |
| 数据包取证 | 本地 SQLite 保存包元数据与白名单载荷（FR-11） |

## 代理端指标（BungeeCord / Velocity）

| 指标 | 说明 |
|---|---|
| 总在线 | 代理端全部在线玩家 |
| 后端子服在线 | 各后端在线数 |
| 子服 RTT/可达性 | 后台周期 ping 回调计时 |
| 玩家路由 | 玩家当前所在子服 |
| 每玩家 ping | 玩家到代理的延迟 |

## 插件运行期 CPU 归因（FR-02，默认关闭）

`ThreadMXBean` 周期采样全部线程栈，按插件 ClassLoader 归并，输出各插件样本计数与占比。默认关闭（`cpu.enabled=false`）。

> 各插件 CPU 火焰图不自研，建议并用 [spark](https://spark.lucko.me)。

## Prometheus 指标命名

统一前缀 `serverprobe_`，涵盖 JVM / 服务器 / 世界维度；取证相关指标只输出聚合速率与脱敏 IP Top100（IPv4 掩 /24、IPv6 掩 /64）。
