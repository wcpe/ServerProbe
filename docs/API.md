# 接口契约:ServerProbe

> 对外接口的单一真源。始终原地更新到当前契约。涵盖四类对外出口:① 跨插件只读 API ② 存储后端 SPI ③ Prometheus 导出端点 ④ 游戏内命令。
>
> 本文档忠于**当前代码现状**:方法签名、参数名、返回类型、权限节点、配置键均以源码与 `config.yml` 为准。PRD/README 中标注"规划中"而代码尚未实现的能力(如 Web/HTTP 只读面板、代理子服 ping/路由),不构成对外契约,文内若提及会明确标注"规划中,未实现"。

## 1. 通用约定

- **形态**:ServerProbe 是 Bukkit/BungeeCord **插件 + 库 API**,**不是 REST 服务**。只读 API 与存储 SPI 以 **Java** 接口形式发布在 `api` 模块（纯 Java + Lombok、零 Kotlin metadata，任意 Kotlin/Java 版本可编译依赖，ADR-13）,Java 8 可直接调用;数据出口另有 Prometheus 文本端点与游戏内命令。
- **获取方式**:跨插件消费方经 TabooLib IOC 服务获取只读接口,统一走静态门面 `top.wcpe.mc.plugin.serverprobe.ServerProbeApi.read()`,无需了解容器内部细节。
- **只读**:对外接口仅暴露查询,不暴露任何写入/控制能力,外部消费方无法影响探针运行。
- **平台语义**:快照/画像携带 `platform` 字段(`BUKKIT` / `BUNGEE` / `VELOCITY`)。`VELOCITY` 为预留,尚未启用。代理端(`BUNGEE`)无世界/TPS/MSPT 概念,对应字段为 null;服务端无代理拓扑,`proxy` 字段为 null。
- **线程/调用时机**:
  - 调用方应在 ServerProbe 容器就绪后调用(如自身 `ENABLE`/`ACTIVE` 之后);过早调用得到 null 属正常,稍后重试即可。
  - 读内存快照/聚合的方法(`latestSnapshot`、`recentSnapshots`、`recentSnapshotsSince`、`aggregated`、`lastStartupProfile`、`lastStartupComparisonSummary`)为轻量内存操作,可在主线程安全调用,**不触发采集、不读盘**。
  - **可能读盘**的方法(`historyStartupProfiles`,以及 SPI 的 `readStartupProfiles` / `readHistory`)宜在异步上下文调用,避免阻塞主线程。
- **版本演进**:落盘对象自带 `schemaVersion`(`MetricSnapshot` 当前 = 1;`StartupProfile` M5 起 = 3,A3 = 2,M1 = 1),新增字段均带默认值,旧档反序列化降级为默认值,保持向后兼容。

## 2. 错误约定

- **门面容错**:`ServerProbeApi.read()` 在容器未初始化、bean 尚未注册或解析过程抛任何异常时,**统一返回 null**(内部 `runCatching` 兜底),绝不向调用方抛出。调用方据 null 自行降级。
- **查询方法的空语义**(逐方法详见第 3 节):
  - 返回**可空单值**的方法,在"尚无数据"时返回 null(如 `latestSnapshot()`、`lastStartupProfile()`、`lastStartupComparisonSummary()`)。
  - 返回**列表**的方法,在无数据或不支持时返回**空列表**,不返回 null。
  - `aggregated(windowSize)` **恒返回非空对象**;窗口内无快照时 `windowSampleCount == 0`,单项不可计算时对应字段为 null。
- **SPI 默认方法的向后兼容**:`MetricStore` 的 M2 扩面方法(`readStartupProfiles`、`readHistory`、批量 `appendHistory`)均为 Kotlin 接口默认方法,默认实现为"空读 / 批量退化为逐条",**旧实现无需改动即向后兼容**。
- **Prometheus 端点错误码**:见第 5 节(401/403/500)。

## 3. 只读 API:ProbeReadApi(FR8.1)

接口:`top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi`
门面:`top.wcpe.mc.plugin.serverprobe.ServerProbeApi`

### 3.1 获取实例(门面用法)

```kotlin
val api = ServerProbeApi.read() ?: return // 容器未就绪或未安装 ServerProbe
val snapshot = api.latestSnapshot() ?: return // 尚无任何采样
```

`ServerProbeApi.read(): ProbeReadApi?` —— 经 IOC 容器按接口类型解析 `ProbeReadApi`;容器未就绪、未安装 ServerProbe 或解析异常时返回 null。

### 3.2 方法清单

| 方法 | 返回 | 语义 / 边界 |
| --- | --- | --- |
| `latestSnapshot(): MetricSnapshot?` | 最新一份指标快照 | 尚无任何采样时为 null。内存操作,可在主线程调用。 |
| `recentSnapshots(limit: Int): List<MetricSnapshot>` | 最近若干份快照 | 无数据时为空列表。顺序(倒序/正序)由实现约定。 |
| `recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot>` | 内存近期缓冲中 `timestampMs >= sinceMs` 的快照 | 仅在内存缓冲上按时间下界筛选,**不采集、不读盘**;可回看跨度受缓冲容量限制(早于缓冲最旧一份的数据需经 SPI 读历史归档)。无满足项为空列表。 |
| `aggregated(windowSize: Int): AggregatedMetrics` | 对最近 `windowSize` 份快照的跨快照聚合 | **恒非空**。`windowSize` 非正按无数据处理;窗口内无快照时 `windowSampleCount == 0`;单项不可计算(无 TPS 样本、速率差分样本不足)时该字段为 null。内存操作,不读盘。 |
| `lastStartupProfile(): StartupProfile?` | 最近一次启动画像 | 无历史记录时为 null。 |
| `historyStartupProfiles(limit: Int): List<StartupProfile>` | 历史归档启动画像(由新到旧,至多 `limit` 份) | **可能读盘**(本地文件后端遍历归档目录逐份反序列化),**宜异步调用**;`limit` 非正、无可用后端或无归档时为空列表。 |
| `lastStartupComparisonSummary(): String?` | 最近一次启动相对上一次的对比摘要(单行人类可读) | 由启动监听器在就绪时算出并写入内存;首次启动或无上一份画像时为 null。 |

### 3.3 返回的数据模型(关键字段)

包路径:`top.wcpe.mc.plugin.serverprobe.api.model.*`。下表仅列契约消费方最常用的字段;完整字段与口径以各类 KDoc 为准。

**`MetricSnapshot`**(快照核心单元)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schemaVersion` | `Int` | 落盘格式版本(当前 = 1)。 |
| `timestampMs` | `Long` | 采样时刻(epoch 毫秒)。 |
| `serverId` | `String` | 实例标识(配置 `server-name` 或自动生成),恒有值。 |
| `platform` | `ProbePlatform` | `BUKKIT` / `BUNGEE` / `VELOCITY`。 |
| `jvm` | `JvmMetrics` | JVM 指标,**恒非空**。 |
| `server` | `ServerMetrics?` | 服务器指标;代理端为 null。 |
| `proxy` | `ProxyMetrics?` | 代理端指标;服务端为 null(默认 null)。 |

**`JvmMetrics`**(全平台通用):`heapUsedBytes`/`heapCommittedBytes`/`heapMaxBytes`(无上限为 -1)、`nonHeap*` 同款、`memoryPools: List<MemoryPoolMetric>`、`gcYoungCount`/`gcYoungTimeMs`/`gcOldCount`/`gcOldTimeMs`、`gcCollectors: List<GcCollectorMetric>`、`threadCount`/`daemonThreadCount`/`peakThreadCount`/`deadlockedThreadCount`、`loadedClassCount`/`totalLoadedClassCount`、`processCpuLoad`/`systemCpuLoad`(0.0–1.0,**-1.0 表示当前 JDK 不提供**)、`uptimeMs`/`startTimeMs`、`jvmArgs: List<String>`。

**`ServerMetrics`**(仅 Bukkit 端):`tick: TickSample`、`onlinePlayers`、`maxPlayers`、`uptimeMs`、`worlds: List<WorldMetrics>?`(未采集到或历史档不含时为 null)。

**`TickSample`**:`tps1m`/`tps5m`/`tps15m`、`msptAvg`/`msptP95`/`msptP99`(均 `Double?`,**null 表示 N/A**,如 Folia 无全局 TPS)、`source: TickSampleSource`。

**`WorldMetrics`**:`name`、`loadedChunks`、`entityCount`、`tileEntityCount`(后两者在 **Folia 受限时为 -1**,表示 N/A)、`entitiesByType: Map<String, Int>?`(未开启分类统计或 Folia 受限时为 null)。

**`ProxyMetrics`**(仅代理端):`totalOnline: Int`、`backends: List<BackendServer>`(`BackendServer` = `name` + `online`)。

**`AggregatedMetrics`**(聚合结果,字段除 `windowSampleCount` 外均可空,**null = 本窗口不可计算**):`windowSampleCount: Int`、`tpsAvg`、`msptP95`、`msptP99`、`gcYoungRatePerSec`、`gcOldRatePerSec`、`gcYoungTimeRatePerSec`、`gcOldTimeRatePerSec`(后六者均 `Double?`)。

**`StartupProfile`**(启动画像):基础字段 `schemaVersion`、`serverId`、`platform`、`mcVersion`、`jvmStartTimeMs`、`totalMs`、`phaseTimings`、`pluginTimings`、`worldTimings`、`jvmArgs`、`createdAtMs`。**启动 agent 增强字段**(仅 `-javaagent:plugins/ServerProbe.jar` 挂载时有值,否则 `agentAttached = false` 且其余为 null):`agentAttached`、`premainNanos`、`agentPluginLoadTimings`、`agentPluginEnableTimings`、`libraryTimings`、`mainThreadHotspots`、`timelineEvents`、`threadStacks`、`configTimings`、`eventTimings`、`commandTimings`、`sampleIntervalMs`、`httpCalls`。

## 4. 存储 SPI:MetricStore(FR8.2)

接口:`top.wcpe.mc.plugin.serverprobe.api.store.MetricStore`

定义启动画像与指标历史的持久化契约。**默认且唯一内置实现 = 本地文件**(JSON / JSONL,原子写入、可配滚动与保留);探针自身不内置、不依赖任何数据库。本接口作为扩展点预留,第三方可实现 DB/远程等后端进行替换。

### 4.1 方法清单

| 方法 | 语义 | 默认实现 / 兼容 |
| --- | --- | --- |
| `saveStartupProfile(profile: StartupProfile)` | 保存一份启动画像(每次启动一份)。 | 抽象,必须实现。 |
| `lastStartupProfile(): StartupProfile?` | 读取最近一次启动画像(用于与本次对比);无历史时为 null。 | 抽象,必须实现。 |
| `appendHistory(snapshot: MetricSnapshot)` | 追加一条指标历史(聚合后行式追加)。 | 抽象,必须实现。 |
| `readStartupProfiles(limit: Int): List<StartupProfile>` | 读历史归档启动画像(由新到旧,至多 `limit` 份);`limit` 非正返回空。可能读盘,宜异步。 | **默认返回空列表**(M2 扩面),旧实现无需覆盖即兼容。 |
| `readHistory(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot>` | 读 `timestampMs ∈ [sinceMs, untilMs]`(闭区间)的历史快照,至多 `limit` 条;`limit` 非正返回空。可能读盘,宜异步。 | **默认返回空列表**(M2 扩面),旧实现无需覆盖即兼容。 |
| `appendHistory(snapshots: List<MetricSnapshot>)` | 批量追加指标历史。 | **默认逐条调用 `appendHistory(it)`**(M2 扩面);DB/远程后端可覆盖为单事务/单请求批量写入。 |

### 4.2 第三方替换

第三方对接 DB/远程后端时,实现 `MetricStore` 接口:三个抽象方法必须实现;三个带默认实现的 M2 扩面方法按需覆盖(覆盖 `readStartupProfiles`/`readHistory` 以提供历史回读能力,覆盖批量 `appendHistory` 以降低写入开销)。不覆盖即保持"空读 / 批量退化为逐条"语义,不破坏既有 SPI 与既有调用。

## 5. Prometheus 导出端点(FR4.2)

实现:`top.wcpe.mc.plugin.serverprobe.core.prometheus.PrometheusExporter` / `MetricsHttpHandler` / `PrometheusTextFormatter`

基于 JDK 自带 `com.sun.net.httpserver.HttpServer`(零第三方依赖)起轻量 HTTP 服务,在 `/metrics` 路径以 Prometheus exposition format(`text/plain; version=0.0.4; charset=utf-8`)暴露**最新一份指标快照**。

### 5.1 端点与平台

- **路径**:`/metrics`。
- **双端独立**:Bukkit 与 BungeeCord 各为独立进程,**各起一个端点**,各自按本端 `config.yml` 配置。同机部署两端须配置不同 `port`。
- 端点默认**关闭**(`metrics.enabled = false`)。

### 5.2 配置键(`config.yml` 的 `metrics` 段)

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `metrics.enabled` | `false` | 是否开启 `/metrics` 端点。 |
| `metrics.host` | `127.0.0.1` | 绑定地址。默认仅本机回环;远程抓取改 `0.0.0.0`,但务必同时设 token 或白名单。 |
| `metrics.port` | `9940` | 监听端口。 |
| `metrics.token` | `""`(空) | 鉴权 token。空 = 不启用 token 鉴权(仅靠 IP 白名单)。 |
| `metrics.allowed-ips` | `[127.0.0.1]` | 允许访问的来源 IP 白名单(与 token **叠加**生效)。**仅精确 IP 匹配,不支持 CIDR**;IPv6 回环需另填 `::1`。 |

### 5.3 鉴权(默认仅本机、不裸奔)

请求需**同时**通过两道校验,任一不过即拒绝且不泄露细节:

1. **token**:`metrics.token` 非空时,要求请求头 `Authorization: Bearer <token>` 完全匹配,否则 **401 Unauthorized**;token 为空时跳过此校验,仅靠 IP 白名单兜底。
2. **IP 白名单**:来源 IP 不在 `metrics.allowed-ips` 内则 **403 Forbidden**。

服务端处理异常兜底返回 **500 Internal Server Error**。鉴权拒绝/错误响应体仅含极简文案,不含任何配置/路径/token 信息。

请求示例:

```
GET /metrics HTTP/1.1
Authorization: Bearer <token>
```

### 5.4 指标命名与维度

- **统一前缀** `serverprobe_`。
- 每条时间序列附带两个公共 label:`serverId`(实例标识)、`platform`(`BUKKIT`/`BUNGEE`/`VELOCITY`)。
- 时间单位一律转换为**秒**(`*_seconds` / `*_seconds_total`)。
- **不可用字段不导出时间序列**:数值哨兵 -1(如 `heapMaxBytes`、世界各计数)、-1.0(CPU 占用率)、可空字段 null(如 Folia 下 TPS/MSPT)对应行**整行跳过**,而非导出占位值。

覆盖维度(去前缀短名,实际名带 `serverprobe_` 前缀):

- **JVM**:`heap_used_bytes`、`heap_committed_bytes`、`heap_max_bytes`、`nonheap_used_bytes`、`nonheap_committed_bytes`、`nonheap_max_bytes`、`memory_pool_used_bytes`/`memory_pool_max_bytes`(label `pool`)、`gc_count_total`/`gc_time_seconds_total`(label `gc`,counter)、`gc_young_count_total`/`gc_young_time_seconds_total`/`gc_old_count_total`/`gc_old_time_seconds_total`(counter)、`threads`/`threads_daemon`/`threads_peak`/`threads_deadlocked`、`classes_loaded`(gauge)/`classes_loaded_total`(counter)、`process_cpu_load`/`system_cpu_load`、`uptime_seconds`。
- **服务器**(仅服务端):`tps`(label `window` = 1m/5m/15m)、`mspt_seconds`(label `quantile` = avg/p95/p99)、`players_online`、`players_max`、`server_uptime_seconds`、`world_loaded_chunks`/`world_entities`/`world_tile_entities`(label `world`)。
- **代理端**(仅代理端):`proxy_players_online`、`proxy_backend_players_online`(label `backend`)。

> 端点仅暴露**最新快照**的瞬时值;历史趋势由 Prometheus 抓取时间序列自身承载。
> PRD 中的 FR4.3 Web 面板 / FR8.3 的"Web/HTTP 只读 API"为**规划中,未实现**,当前对外 HTTP 出口仅 `/metrics` 一个。

## 6. 游戏内命令 /probe(FR4.1)

实现:`top.wcpe.mc.plugin.serverprobe.command.ProbeCommand`

主命令 `/probe`,权限 `serverprobe.command`(无权限提示"你没有权限使用该命令")。全部子命令**只读**,取值来自 `ProbeReadApi` 的内存快照/聚合,无阻塞 IO。无参 `/probe` 输出帮助(列出全部子命令)。

| 子命令 | 作用 | 权限 | 备注 |
| --- | --- | --- | --- |
| `health` | 总体概览:TPS(1m)/MSPT(avg)/堆已用·最大/CPU/在线人数/运行时长。 | `serverprobe.command.health` | 服务器维度字段在代理端为 N/A。尚无采样时提示采集中。 |
| `startup` | 最近一次启动画像:总时长、慢插件 Top-N、各世界耗时、与上次对比;挂载 agent 后追加库下载/主线程热点/配置·事件·命令耗时 Top-N。 | `serverprobe.command.startup` | 无画像时提示"尚无启动画像"。Top-N 取 `startup-top-n`(默认 5)。agent 增强段需 `-javaagent`。 |
| `tps` | TPS(1/5/15 分钟)与 MSPT(avg/p95/p99),并附近 N 份快照的聚合补充行(FR3.3)。 | `serverprobe.command.tps` | 字段 null(Folia/不可用)显示 N/A;代理端无此指标。聚合窗口取 `aggregation.window`(默认 12)。 |
| `gc` | GC(young/old 的 count/timeMs)+ 堆/非堆/各内存池。 | `serverprobe.command.gc` | JVM 指标全平台通用,代理端同样可用。 |
| `world` | 各世界:已加载区块数、实体数、方块实体数(FR2.3)。 | `serverprobe.command.world` | 代理端无此指标;worlds 未采样时提示采集中;Folia 受限项(-1)显示 N/A。 |
| `proxy` | 代理端总在线 + 各子服在线明细。 | `serverprobe.command.proxy` | 仅代理端有数据;在服务端提示"此为服务端,请在 BungeeCord 执行"。子服 ping/路由为**规划中,未实现**。 |
| `flamegraph` | 由最近启动画像导出自包含 HTML(火焰图 + 时间线),输出到 `data/flamegraph/`。 | `serverprobe.command.flamegraph` | **需挂载启动 agent**(`-javaagent:plugins/ServerProbe.jar`);未挂载时提示启用方式。 |
| `http` | 回看最近的对外网络外呼(插件/方法/URL/响应码/耗时/触发处),倒序展示。 | `serverprobe.command.http` | **需挂载启动 agent**且外呼监控开启方有数据;展示条数固定 20(`HTTP_DISPLAY_LIMIT`)。缓冲为空时按 agent 是否挂载给出不同提示。 |

> 另有 `serverprobe.alert` 权限(非 `/probe` 子命令):游戏内告警通道据此向在线 OP 或持该权限的玩家推送告警(仅 Bukkit 端),见 `config.yml` 的 `alert` 段。
