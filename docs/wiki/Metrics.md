# 指标说明

> ⚠️ M1 + M2 已实现并在 1.21.4 Paper 单端真机验证通过,**待生产环境验证**;其他端(1.8 / Folia / BungeeCord)仅编译通过、未逐一真机。

本页逐类说明 ServerProbe 采集的指标:含义、单位、采集方式、平台/版本差异。指标设计与 PRD FR2 一致,**不发明新指标**。

> 状态标注:✅ 已实现(待生产验证) / 🚧 规划中。JVM、服务器、世界指标与指标聚合(FR2.1/2.2/2.3/3.3)均已实现;网络 ping 分布、代理端 ping/路由、插件运行时归因(P2)仍为 🚧 规划中。

设计约束(贯穿所有指标):
- 主线程只做轻量取值(MSPT 仅 `nanoTime`);聚合 / 遍历异步或限频。
- 调度一律走 TabooLib `submit`,严禁 `Bukkit.getScheduler()`。
- 采集周期可配;**严禁循环内 DB / 远程调用**。

---

## 一、JVM 指标(P0,全版本 + 全平台通用)

来源:`java.lang.management.*` 全套 MXBean(最稳,无版本/平台差异)。Bukkit 与 BungeeCord **一致**。

| 指标 | 含义 | 单位 | 采集方式 |
|---|---|---|---|
| 堆内存 used/max | 堆内存使用 / 上限 | 字节(展示用 MB/GB) | `MemoryMXBean.getHeapMemoryUsage()` |
| 非堆内存 | Metaspace 等非堆使用 | 字节 | `MemoryMXBean.getNonHeapMemoryUsage()` |
| 各内存池 | Eden/Survivor/Old/Metaspace 等分池用量 | 字节 | `MemoryPoolMXBean` |
| GC 次数(young/old) | 各代 GC 累计次数 | 次 | `GarbageCollectorMXBean.getCollectionCount()` |
| GC 耗时(young/old) | 各代 GC 累计耗时 | 毫秒 | `GarbageCollectorMXBean.getCollectionTime()`(聚合时做**差分**) |
| 线程数 | 活动线程数 | 个 | `ThreadMXBean.getThreadCount()` |
| 死锁 | 是否存在死锁线程 | 布尔 / 线程列表 | `ThreadMXBean.findDeadlockedThreads()` |
| 类加载 | 已加载 / 已卸载类数 | 个 | `ClassLoadingMXBean` |
| 进程 CPU | 探针进程 CPU 占用 | % | `com.sun.management.OperatingSystemMXBean`(不同 JDK 差异需容错) |
| 系统 CPU | 系统总 CPU 占用 | % | 同上 |
| uptime | JVM 运行时长 | 毫秒 | `RuntimeMXBean.getUptime()` |
| 启动参数 | JVM 启动参数快照 | 文本 | `RuntimeMXBean.getInputArguments()` |

> 注:`com.sun.management` 系列在不同 JDK 实现上可能缺失或行为不一,采集需容错降级。

---

## 二、服务器指标(P0,Bukkit)

| 指标 | 含义 | 单位 | 采集方式 |
|---|---|---|---|
| TPS(1/5/15min) | 每秒 tick 数,理想 20 | tick/s | 见下"TPS 兼容" |
| MSPT(均值 + p95/p99) | 单 tick 耗时,>50ms 即掉 tick | 毫秒 | 见下"MSPT 兼容" |
| 在线人数 | 当前在线玩家 | 人 | Bukkit API |
| 运行时长 | 服务器运行时间 | 时间 | JVM uptime / 启动时刻 |

### TPS 的多版本与 Folia 差异(关键)
TabooLib **不封装** TPS。探针抽象 `ServerTickSampler`,按环境选实现:

| 环境 | TPS 实现 |
|---|---|
| Paper(有 `getTPS()`) | `Bukkit.getTPS()` |
| 老版本 / 纯 CraftBukkit(无 `getTPS()`) | `nmsProxy` 读 `MinecraftServer.recentTps[]`,或自建 tick 采样器 |
| **Folia** | **无全局 TPS(per-region)**;呈现方式已敲定:**per-region 明细 + 全局标 N/A**(M1 先全局 N/A,M2/M3 补 per-region 明细) |

### MSPT 的多版本与 Folia 差异
| 环境 | MSPT 实现 |
|---|---|
| Paper | `Bukkit.getAverageTickTime()` + 每 tick `nanoTime` 入直方图算 p95/p99 |
| 老版本 / 纯 CraftBukkit | 自建 tick 采样,`nanoTime` 直方图算分位 |
| **Folia** | per-region 明细,全局标 N/A(M1 先全局 N/A,M2/M3 补 per-region 明细) |

> 主线程对 MSPT 只取 `nanoTime`,分位计算放聚合层。Folia 上"全服单值 TPS/MSPT"语义不成立,已敲定按 **per-region 明细 + 全局标 N/A** 呈现,详见 [常见问题FAQ](FAQ.md)。

---

## 三、世界指标(P1,Bukkit)✅ 已实现(待生产验证)

经 `/probe world` 与 Prometheus `serverprobe_world_*` 呈现,聚合于 `ServerMetrics.worlds`。

| 指标 | 含义 | 单位 | 采集方式 |
|---|---|---|---|
| 区块数 | 各世界已加载区块 | 个 | Bukkit API,限频 |
| 实体数(按类型) | 各世界实体计数,可分类型 | 个 | Bukkit API,限频 |
| 方块实体数 | 各世界 tile entity 计数 | 个 | Bukkit API,限频 |

采样与限频:
- 世界采集需遍历所有世界与已加载区块,开销高于轻量指标,故**独立限频采样**(配置 `world.sample-period-ticks`,默认 600 tick ≈ 30 秒),采样后缓存,不随每个主采集周期重扫。
- 是否按类型统计实体分布由 `world.entity-types`(默认开启)控制;关闭后仅给出实体总数。

平台差异:
- **非 Folia**:同步遍历 `world.getEntities()` / `getLoadedChunks()` 取全量计数。
- **Folia(当前为路线 1)**:实体 / 方块实体归各 region 线程,跨线程直读会抛异常;当前实现**仅给出已加载区块数**,实体数与方块实体数置 `-1`(呈现为 N/A),按类型分布置 null。逐区域 `callRegion{}` 完整统计留后续迭代。

---

## 四、网络指标(P1)

| 指标 | 含义 | 单位 | 采集方式 |
|---|---|---|---|
| 在线人数 | 当前在线 | 人 | Bukkit / Proxy API |
| ping 分布 | 玩家延迟分布 | 毫秒 | 玩家 ping 聚合 |
| 流量 / 数据包速率 | 网络吞吐 | —— | **需 Netty 注入,P2**,首期不做 |

---

## 五、代理端指标(P1,BungeeCord)

代理端定位 = 网络与子服健康监控,**无世界 / TPS / MSPT**。

| 指标 | 含义 | 单位 | 采集方式 | 状态 |
|---|---|---|---|---|
| 总在线 | 代理端总在线人数 | 人 | `ProxyServer.getPlayers()` | ✅ 已实现(待生产验证) |
| 各子服在线数 | 每个后端子服在线 | 人 | `ProxyServer.getServers()` | ✅ 已实现(待生产验证) |
| 子服 ping / 可达性 | 后端探活与延迟 | 毫秒 / 布尔 | `ServerInfo.ping()` | 🚧 规划中 |
| 玩家路由 | 玩家当前所在子服 | —— | 玩家路由信息 | 🚧 规划中 |
| 每玩家 ping | 单玩家延迟 | 毫秒 | Proxy API | 🚧 规划中 |
| JVM 全套 | 同第一节 JVM 指标 | —— | `java.lang.management.*` | ✅ 已实现(待生产验证) |

---

## 六、插件运行时归因(P2,增强,默认不做)

| 指标 | 含义 | 采集方式 |
|---|---|---|
| 事件 / 任务耗时 | 各事件、调度任务耗时 | 采样 |
| 各插件 CPU 占比 | 按插件归并的 CPU 占用 | `ThreadMXBean` 周期采样栈,按插件 ClassLoader 归并(spark 模式,**无 Java Agent**) |

> 方法级**精确**归因(FR7)需 Incision 字节码插桩,**默认关闭、需先 PoC 验证**,见 [常见问题FAQ](FAQ.md)。

---

## 指标聚合与存储(简述)✅ 已实现(待生产验证)

- 高频指标进**定容环形缓冲**(`history-capacity`,默认 360 份,避免磁盘 IO 压主线程)。
- **聚合(FR3.3)**:对**近 N 份快照**(窗口 `aggregation.window`,默认 12 份 ≈ 1 分钟)做跨快照统计,经 `/probe tps` 的"近期聚合"补充行呈现:
  - `tpsAvg`:窗口内各份 1 分钟 TPS 的算术平均;
  - `msptP95` / `msptP99`:对窗口内各份**已算好的周期代表分位**再取分位(即"分位的分位",原始逐 tick 样本不跨快照保留);
  - `gcYoung/OldRatePerSec`、`gcYoung/OldTimeRatePerSec`:取窗口两端 GC 累计量差分 ÷ 时间跨度(秒),得次数速率与耗时速率;窗口跨进程重启致累计量回绕时按不可计算返回 null。
  - 任一项无可用样本或不可计算时返回 null,呈现层降级为 N/A。
- 聚合后的指标历史异步落本地文件 JSONL(按实例分目录、按自然日滚动,可配保留天数与体积上限)。详见 [数据呈现与对接](Data-Output.md)。

数据模型(本地文件记录):
- **MetricHistory**(JSONL 记录,每行一条):为一份完整 `MetricSnapshot` 的序列化(含 `serverId`、`platform`、`timestampMs`、`jvm`、`server`、`proxy` 等),按实例分目录、按自然日滚动追加到 `data/metrics/<实例>/metrics-<yyyyMMdd>.jsonl`(`serverId` 由实例自动生成、可在配置中以 `server-name` 覆盖为自定义值;写入频率受 `collect-period-ticks` 控制)。

> **实例标识(已敲定)**:`serverId` 为**自动生成的实例 ID**,可通过配置项 `server-name` 覆盖为自定义名称;本地文件**按实例分目录存放**,多实例共存时互不混淆。

---

> 返回 [Wiki 首页](Home.md) · 相关:[版本与平台兼容](Compatibility.md) · [启动剖析指南](Startup-Profiling.md) · [数据呈现与对接](Data-Output.md)
