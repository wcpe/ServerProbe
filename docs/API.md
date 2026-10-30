# 接口契约:ServerProbe

> 对外接口的单一真源。始终原地更新到当前契约。涵盖四类对外出口:① 跨插件只读 API ② 存储后端 SPI ③ Prometheus 导出端点 ④ 游戏内命令。
>
> 本文档忠于**当前代码现状**:方法签名、参数名、返回类型、权限节点、配置键均以源码与 `config.yml` 为准。PRD/README 中标注"规划中"而代码尚未实现的能力不构成对外契约；Web 面板与代理子服 ping/路由已在 v0.2.0 交付。

## 1. 通用约定

- **形态**:ServerProbe 是 Bukkit/BungeeCord **插件 + 库 API**,**不是 REST 服务**。只读 API 与存储 SPI 以 **Java** 接口形式发布在 `api` 模块（纯 Java + Lombok、零 Kotlin metadata，任意 Kotlin/Java 版本可编译依赖，ADR-13）,Java 8 可直接调用;数据出口另有 Prometheus 文本端点与游戏内命令。
- **获取方式**:跨插件消费方统一走 `api` 模块的静态门面 `top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI.read()`,无需了解容器内部细节。旧门面 `top.wcpe.mc.plugin.serverprobe.ServerProbeApi` 已废弃。
- **只读边界**:读取 API 仅暴露查询,不暴露运行控制能力；存储 SPI 的受控替换注册见第 4 节，不属于读取 API。
- **平台语义**:快照/画像携带 `platform` 字段(`BUKKIT` / `BUNGEE` / `VELOCITY`)。三端均已启用(`VELOCITY` 自 v0.3.0 起提供采集与 MCP 控制面,见 [velocity-platform](specs/velocity-platform.md))。代理端(`BUNGEE`/`VELOCITY`)无世界/TPS/MSPT 概念,对应字段为 null;服务端无代理拓扑,`proxy` 字段为 null。桥治理执行器(kick/list 类)按 ADR-016 只在 Bukkit 子服端与 Bungee 代理端二选一实现,Velocity 侧不提供,收到此类指令由 `BridgeRoutes` 返回明确的失败回执。
- **线程/调用时机**:
  - 调用方应在 ServerProbe 容器就绪后调用(如自身 `ENABLE`/`ACTIVE` 之后);过早调用得到 null 属正常,稍后重试即可。
  - 读内存快照/聚合的方法(`latestSnapshot`、`recentSnapshots`、`recentSnapshotsSince`、`aggregated`、`lastStartupProfile`、`lastStartupComparisonSummary`)为轻量内存操作,可在主线程安全调用,**不触发采集、不读盘**。
  - **可能读盘**的方法(`historyStartupProfiles`,以及 SPI 的 `readStartupProfiles` / `readHistory`)宜在异步上下文调用,避免阻塞主线程。
- **版本演进**:落盘对象自带 `schemaVersion`(`MetricSnapshot` 当前 = 1;`StartupProfile` 当前 = 4,FR7 = 4,M5 = 3,A3 = 2,M1 = 1),新增字段均带默认值,旧档反序列化降级为默认值,保持向后兼容。

## 2. 错误约定

- **门面容错**:`ServerProbeAPI.read()` **恒返回非 null 的稳定委派代理**(永不 null、可缓存为字段),内部逐方法 `runCatching` 兜底,**绝不向调用方抛出**。探针未就绪时各方法返回各自的空值(null / 空列表),调用方据此降级即可——**不要写 `?: return`**,那个分支永远不会成立。
- **查询方法的空语义**(逐方法详见第 3 节):
  - 返回**可空单值**的方法,在"尚无数据"时返回 null(如 `latestSnapshot()`、`lastStartupProfile()`、`lastStartupComparisonSummary()`)。
  - 返回**列表**的方法,在无数据或不支持时返回**空列表**,不返回 null。
  - `aggregated(windowSize)` **恒返回非空对象**;窗口内无快照时 `windowSampleCount == 0`,单项不可计算时对应字段为 null。
- **SPI 默认方法的向后兼容**:`MetricStore` 的 M2 扩面方法(`readStartupProfiles`、`readHistory`、批量 `appendHistory`)均为 Kotlin 接口默认方法,默认实现为"空读 / 批量退化为逐条",**旧实现无需改动即向后兼容**。
- **Prometheus 端点错误码**:见第 5 节(401/403/500)。

## 3. 只读 API:ProbeReadApi(FR-08)

接口:`top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi`
门面:`top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI`

### 3.1 获取实例(门面用法)

```kotlin
val api = ServerProbeAPI.read() // 恒非 null,可缓存为字段
val snapshot = api.latestSnapshot() ?: return // 尚无任何采样
```

`ServerProbeAPI.read(): ProbeReadApi` —— **恒返回**稳定委派代理,探针后加载也生效;未就绪时各方法返回空值而非抛异常。

### 3.2 方法清单

| 方法 | 返回 | 语义 / 边界 |
| --- | --- | --- |
| `latestSnapshot(): MetricSnapshot?` | 最新一份指标快照 | 尚无任何采样时为 null。内存操作,可在主线程调用。 |
| `recentSnapshots(limit: Int): List<MetricSnapshot>` | 最近若干份快照 | 无数据时为空列表。顺序(倒序/正序)由实现约定。 |
| `recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot>` | 内存近期缓冲中 `timestampMs >= sinceMs` 的快照 | 仅在内存缓冲上按时间下界筛选,**不采集、不读盘**;可回看跨度受缓冲容量限制(早于缓冲最旧一份的数据需经 SPI 读历史归档)。无满足项为空列表。 |
| `aggregated(windowSize: Int): AggregatedMetrics` | 对最近 `windowSize` 份快照的跨快照聚合 | **恒非空**。`windowSize` 非正按无数据处理;窗口内无快照时 `windowSampleCount == 0`;单项不可计算(无 TPS 样本、速率差分样本不足)时该字段为 null。内存操作,不读盘。 |
| `lastStartupProfile(): StartupProfile?` | 最近一次启动画像 | 无历史记录时为 null。 |
| `historyStartupProfiles(limit: Int): List<StartupProfile>` | 历史归档启动画像(由新到旧,至多 `limit` 份) | **可能读盘**(本地文件后端遍历归档目录逐份反序列化),**宜异步调用**;`limit` 非正、无可用后端或无归档时为空列表。 |
| `historySnapshots(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot>` | 历史归档指标快照(FR-26,闭区间时间范围,由新到旧,至多 `limit` 条) | **可能读盘**(本地文件后端定位日期范围内历史文件逐行解析),**宜异步调用**;默认实现空列表保兼容;`limit` 非正或无数据时为空列表。与 `recentSnapshotsSince` 区别:本方法读落盘历史(可超出内存缓冲跨度),后者仅筛内存缓冲、不读盘。 |
| `lastStartupComparisonSummary(): String?` | 最近一次启动相对上一次的对比摘要(单行人类可读) | 由启动监听器在就绪时算出并写入内存;首次启动或无上一份画像时为 null。 |

### 3.3 返回的数据模型(关键字段)

包路径:`top.wcpe.mc.plugin.serverprobe.api.model.{metrics,startup,player,diagnostics}.*`（按用途分为四个子包）。下表仅列契约消费方最常用的字段;完整字段与口径以各类 KDoc 为准。

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

**`ServerMetrics`**(仅 Bukkit 端):`tick: TickSample`、`onlinePlayers`、`maxPlayers`、`uptimeMs`、`worlds: List<WorldMetrics>?`(未采集到或历史档不含时为 null)、`pingDistribution: List<PingBucket>?`(在线玩家 ping 分桶,FR2.4,无人/不支持时 null)、`observedRegions: List<ObservedRegionMetrics>?`(Folia 已观测 region 明细,FR-12,非 Folia/无数据时 null)、`observedRegionWorlds: List<ObservedRegionWorldMetrics>?`(按世界汇总,FR-12)。

**`TickSample`**:`tps1m`/`tps5m`/`tps15m`、`msptAvg`/`msptP95`/`msptP99`(均 `Double?`,**null 表示 N/A**,如 Folia 无全局 TPS)、`source: TickSampleSource`。

**`WorldMetrics`**:`name`、`loadedChunks`、`entityCount`、`tileEntityCount`(后两者在 **Folia 受限时为 -1**,表示 N/A)、`entitiesByType: Map<String, Int>?`(未开启分类统计或 Folia 受限时为 null)。

**`ProxyMetrics`**(仅代理端):`totalOnline: Int`、`backends: List<BackendServer>`(`BackendServer` = `name` + `online`)。

> `web` 配置键(`web.enabled`/`host`/`port`/`token`/`allowed-ips`,默认关闭仅本机)与 `metrics.*` 同属端点类配置,详见 `config.yml` 注释与 [OPERATIONS](OPERATIONS.md);本文不重复。

**`AggregatedMetrics`**(聚合结果,字段除 `windowSampleCount` 外均可空,**null = 本窗口不可计算**):`windowSampleCount: Int`、`tpsAvg`、`msptP95`、`msptP99`、`gcYoungRatePerSec`、`gcOldRatePerSec`、`gcYoungTimeRatePerSec`、`gcOldTimeRatePerSec`(后六者均 `Double?`)。

**`StartupProfile`**(启动画像):基础字段 `schemaVersion`、`serverId`、`platform`、`mcVersion`、`jvmStartTimeMs`、`totalMs`、`phaseTimings`、`pluginTimings`、`worldTimings`、`jvmArgs`、`createdAtMs`。**Incision 可选字段**：`incisionEnabled` 表示本次启动是否请求采集，`incisionActive` 表示目标切点已实际执行，`incisionPluginEnableTimings` 仅在实际采集时提供逐插件精确启用耗时；默认关闭或未命中有效切点时 `incisionActive=false` 且列表为 null。**启动 agent 增强字段**(仅 `-javaagent:plugins/ServerProbe.jar` 挂载时有值,否则 `agentAttached = false` 且其余为 null):`agentAttached`、`premainNanos`、`agentPluginLoadTimings`、`agentPluginEnableTimings`、`libraryTimings`、`mainThreadHotspots`、`timelineEvents`、`threadStacks`、`configTimings`、`eventTimings`、`commandTimings`、`sampleIntervalMs`、`httpCalls`。

## 4. 存储 SPI:MetricStore(FR-08)

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

第三方对接 DB/远程后端时，先实现 `MetricStore`：三个抽象方法必须实现；三个带默认实现的 M2 扩面方法按需覆盖（覆盖 `readStartupProfiles`/`readHistory` 以提供历史回读能力，覆盖批量 `appendHistory` 以降低写入开销）。不覆盖即保持“空读 / 批量退化为逐条”语义，不破坏既有 SPI 与既有调用。

在 ServerProbe 已启用后，通过运行期插件 jar 中的 `top.wcpe.mc.plugin.serverprobe.ServerProbeStorageApi` 安装实例：

```kotlin
val registration = ServerProbeStorageApi.install(store)
// 插件禁用或不再需要替换时：
registration.close()
```

`install` 同一时刻只接受一个第三方存储；已有替换实例时抛出 `IllegalStateException`，不会静默覆盖。返回 `MetricStoreRegistration`，关闭当前注册后立即恢复内置 `LocalFileMetricStore`；陈旧注册句柄不会移除后来安装的实例。未安装第三方实例时，全部存储调用始终委派内置本地文件实现。

## 5. Prometheus 导出端点(FR-04)

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
- **启动画像**(FR-25,仅内存存在最近一次启动画像时导出;代理端无画像不导出):`startup_total_seconds`、`startup_plugin_seconds`(label `plugin`,**与 `/probe startup` 的慢插件榜同源同截**:来源经 `SlowPluginRanking` 择优——Incision 精确 > agent 实测 > 日志解析近似——并同样截断到 `startup.top-n` 条)、`startup_world_seconds`(label `world`)。
- **代理端**(仅代理端):`proxy_players_online`、`proxy_backend_players_online`(label `backend`)。

> 端点仅暴露**最新快照**的瞬时值;历史趋势由 Prometheus 抓取时间序列自身承载。
> Web 面板（FR-04 子能力，v0.4.0 起四页：总览 / 启动画像 / 历史趋势 / **网络取证**——后者为唯一展示完整 IP 与白名单载荷的页面，经鉴权 + IP 白名单访问），默认关闭，见 PRD FR-04。

## 6. 游戏内命令 /probe(FR-04)

实现:`top.wcpe.mc.plugin.serverprobe.command.ProbeCommand`

主命令 `/probe`,权限 `serverprobe.command`(无权限提示"你没有权限使用该命令")。全部子命令**只读**,取值来自 `ProbeReadApi` 的内存快照/聚合,无阻塞 IO。无参 `/probe` 输出帮助(列出全部子命令)。

| 子命令 | 作用 | 权限 | 备注 |
| --- | --- | --- | --- |
| `health` | 总体概览:TPS(1m)/MSPT(avg)/堆已用·最大/CPU/在线人数/运行时长。 | `serverprobe.command.health` | 服务器维度字段在代理端为 N/A。尚无采样时提示采集中。 |
| `startup` | 最近一次启动画像:总时长、慢插件 Top-N、各世界耗时、与上次对比;挂载 agent 后追加库下载/主线程热点/配置·事件·命令耗时 Top-N。 | `serverprobe.command.startup` | 无画像时提示"尚无启动画像"。Top-N 取 `startup-top-n`(默认 5)。agent 增强段需 `-javaagent`。 |
| `tps` | TPS(1/5/15 分钟)与 MSPT(avg/p95/p99),并附近 N 份快照的聚合补充行(FR-03)。 | `serverprobe.command.tps` | 字段 null(Folia/不可用)显示 N/A;代理端无此指标。聚合窗口取 `aggregation.window`(默认 12)。 |
| `gc` | GC(young/old 的 count/timeMs)+ 堆/非堆/各内存池。 | `serverprobe.command.gc` | JVM 指标全平台通用,代理端同样可用。 |
| `world` | 各世界:已加载区块数、实体数、方块实体数(FR-02)。 | `serverprobe.command.world` | 代理端无此指标;worlds 未采样时提示采集中;Folia 受限项(-1)显示 N/A。 |
| `proxy` | 代理端总在线 + 各子服在线明细、子服 RTT/可达性、玩家路由与每玩家 ping。 | `serverprobe.command.proxy` | 仅代理端有数据;在服务端提示"此为服务端,请在 BungeeCord 执行"。 |
| `proxy` | 代理端总在线、各子服在线与 ping/可达性、玩家路由、每玩家 ping(FR2.5)。 | `serverprobe.command.proxy` | 仅代理端有数据;在服务端提示"此为服务端,请在 BungeeCord 执行"。子服 ping 由后台周期任务异步采集并缓存(`collectPeriodTicks` 周期,单次超时 3s),`collect` 只读缓存不阻塞主线程;尚未测到时 `pingMs=-1`、`reachable=false`。BungeeCord 与 Velocity 两端均有实现。 |
| `flamegraph` | 由最近启动画像导出自包含 HTML(火焰图 + 时间线),输出到 `data/flamegraph/`。 | `serverprobe.command.flamegraph` | **需挂载启动 agent**(`-javaagent:plugins/ServerProbe.jar`);未挂载时提示启用方式。 |
| `http` | 回看最近的对外网络外呼(插件/方法/URL/响应码/耗时/触发处),倒序展示。 | `serverprobe.command.http` | **需挂载启动 agent**且外呼监控开启方有数据;展示条数固定 20(`HTTP_DISPLAY_LIMIT`)。缓冲为空时按 agent 是否挂载给出不同提示。 |

> 另有 `serverprobe.alert` 权限(非 `/probe` 子命令):游戏内告警通道据此向在线 OP 或持该权限的玩家推送告警(仅 Bukkit 端),见 `config.yml` 的 `alert` 段。

## 7. 业务桥 API:BusinessHost(FR-35 / FR-38 / FR-40)

接口:`top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost`
门面:`top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI.businessHost()`

第三方插件**自注册**业务域(FR-37 后探针不再内置任何业务实现)。门面返回**稳定代理**,可缓存为字段;探针未就绪时注册得到失效句柄、查询返回降级值,**绝不抛异常**。

```java
// onEnable:缓存一次,探针后加载也依然有效
BusinessHost host = ServerProbeAPI.businessHost();
Registration handle = host.register(new MyProvider()); // close() 幂等,按实例比对
```

### 7.1 方法清单

| 方法 | 返回 | 语义 / 边界 |
| --- | --- | --- |
| `register(BusinessProvider)` | `Registration` | 同域重复注册以最新为准,旧句柄立即失效(`REPLACED`)。provider 为 null / domain 格式非法(须匹配 `^[a-z0-9-]{1,32}$`) / 动作名重复时返回失效句柄(`INVALID`)并记 ERROR。 |
| `registerLegacy(LegacyBusinessProvider)` | `Registration` | 注册 **FR-37 形态的旧契约** Provider(裸 `Map` manifest + 双字符串 `dispatch`)。内部经 `LegacyBusinessProviderAdapter` 适配,该域在 manifest 里标 `specDeclared=false`。 |
| `unregister(BusinessProvider)` | `boolean` | **按实例比对**注销:仅当该域仍指向此实例才移除(插件热重载的经典坑)。经 `registerLegacy` 注册的域须用返回句柄 `close()`(包装实例无法从外部取得)。 |
| `domains()` | `Set<String>` | 当前已注册域,不可变、按字典序。 |
| `manifest()` | `Map<String, Object>` | 聚合信封 `{"apiVersion":1,"domains":{...}}`,见 §7.5。单域 `manifest()` 抛异常时该域呈现为 `{"error": "..."}`。 |
| `dispatch(ActionRequest)` | `ActionResult` | **强类型入口(推荐)**。带 `requestId` 与 `Principal`;`requestId` 为空时由探针补齐。**可能阻塞至多 `bridge.dispatch.timeout-ms`,禁止在主线程调用**。 |
| `dispatchAsync(ActionRequest)` | `CompletableFuture<ActionResult>` | 异步强类型派发;超时也以**失败结果**完成,而非异常完成。 |
| `dispatch(domain, action, payloadJson)` | `BridgeCommandResult` | **兼容入口**:等价于无 `requestId`/无 `Principal` 的请求,**错误码与诊断信息在这一步丢失**。 |
| `dispatchAsync(domain, action, payloadJson)` | `CompletableFuture<BridgeCommandResult>` | 兼容入口的异步版本。 |
| `healthOf(domain)` | `DomainStatus` | 该域健康状态(**只读探针缓存**)。不调用 Provider、不做 IO、不派发、不新建线程。未注册域返回 `registered=false` 的快照,不报错。 |

### 7.2 `BusinessProvider` 强类型契约(FR-38 / FR-39)

```java
public interface BusinessProvider {
    String domain();                    // 小写字母/数字/连字符，1-32
    DomainManifest manifest();          // 强类型能力清单
    ActionResult dispatch(ActionRequest request);
    default ProviderHealth health() { return ProviderHealth.unknown(); }
}
```

| 类型 | 关键字段 |
| --- | --- |
| `DomainManifest` | `apiVersion`(当前 = 1)、`domain`、`displayName`、`description`、`actions: List<ActionSpec>`、`attributes: Map<String,String>`(**逃生舱**,平台不解释、原样透传)。 |
| `ActionSpec` | `name`、`description`、`risk`(`READ`/`WRITE`/`DESTRUCTIVE`,未声明按 `WRITE`)、`idempotent`、`inputSchema`/`outputSchema`(JSON Schema 文本,可为空)、`errorCodes`(该动作可能返回的业务错误码)。 |
| `ActionRequest` | `domain`、`action`、`payloadJson`、`requestId`(**平台保证非空**)、`principal`。 |
| `Principal` | `operator`、`operatorId`、`nodeId`、`reason`(操作者上下文,探针原样透传,不解释)。 |
| `ActionResult` | `success`、`outputJson`、`errorCode`(**机器可读**,面板据此分流)、`errorMessage`(人可读)、`metadata`(诊断信息,不影响兼容)。 |

**`risk` 必须诚实声明**:标 `READ` 却写数据会让下游的风险控制(二次确认)彻底失效。

**注册期校验**(spec §7):

| 校验项 | 失败行为 |
| --- | --- |
| `domain` 格式(`^[a-z0-9-]{1,32}$`) | 拒绝注册(失效句柄,`INVALID`),记 ERROR |
| `apiVersion` 不支持 | **不拒绝**:降级为 legacy 规格(`specDeclared=false`),记 WARN |
| 动作名重复 | 拒绝注册(失效句柄,`INVALID`),记 ERROR |
| `risk` 未声明 | 按 `WRITE` 生效,记 WARN |
| `errorCodes` 为空 | 允许(表示未声明) |

**旧契约向后兼容**:FR-37 形态的 Provider 改 `implements LegacyBusinessProvider`(一词之差),用 `registerLegacy(...)` 注册即可继续工作;其域标 `specDeclared=false`,`risk` 一律 `WRITE`、`idempotent=false`、schema 与错误码为空。

### 7.3 `BusinessProvider.health()`(FR-35,默认方法)

`BusinessProvider` 新增 `default ProviderHealth health()` ,默认返回 `ProviderHealth.unknown()` —— **旧实现零改动即兼容**。

- 在探针的独立刷新线程(`serverprobe-health-probe`)上被调用,**不在命令线程上**;仍应快速返回,不要做网络/磁盘 IO。
- 探针只等 `bridge.health.timeout-ms`(默认 1000ms):超时记 `DEGRADED("health() 超时")` 并进入隔离窗口 `bridge.health.quarantine-ms`;抛异常记 `DEGRADED("health() 抛出异常：<类名>")`;返回 `null` 记 `DEGRADED("health() 返回 null")`。**任何分支都不影响其它域**。
- 等级:`AVAILABLE` / `UNAVAILABLE` / `INITIALIZING` / `DEGRADED` / `UNKNOWN`。要报告"自何时起如此"用 `ProviderHealth.of(status, reason, detailJson, sinceMs)`;简写工厂(`available()` 等)会把 `sinceMs` 填为构造时刻,`unknown()` 恒为 0。
- 探针**不替 Provider 推断** `sinceMs`;运维要"观测时间"读回执里的 `refreshedAtMs`(探针采集时刻)。

### 7.4 `DomainStatus`(FR-35 / FR-40)

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `domain` | `String` | 业务域。 |
| `registered` | `boolean` | 当前是否有 Provider 注册(`getRegistered()`,布尔访问器一律 `get` 前缀)。 |
| `health` | `ProviderHealth` | Provider 自报值;未刷新过为 `unknown()`。 |
| `reasonOverride` | `String` | 探针补充的原因(如 `Provider 未注册` / `Provider 已注销`),非空时优先呈现。 |
| `refreshedAtMs` | `long` | 探针采集 `health` 的时刻;未刷新为 0。 |
| `lastFailure` | `FailureInfo?` | 最近一次 dispatch 失败(已脱敏限长);从未失败为 null。 |
| `dispatchTotal` / `dispatchFailed` / `lastCostMs` | `long` | 派发总数 / 失败数 / 最近耗时。 |
| `quarantined` | `boolean` | 是否处于隔离窗口内(`getQuarantined()`)。 |
| `manifestDigest` | `String?` | **FR-40** 规格指纹(规范化 JSON 的 sha256 前 16 位十六进制小写);无指纹为 null。 |
| `lastDispatchAt` | `long` | **FR-40** 最近一次 dispatch 的**完成**时刻;从未派发为 0。 |
| `dispatchCount` / `failureCount` | `long` | **FR-40** 同 `dispatchTotal` / `dispatchFailed` 的另一读口(取值恒等,历史命名保留)。 |
| `degradedReason` | `String?` | **FR-40** 降级原因的**呈现口径**(探针补充优先、退而用自报);健康时为 null。 |
| `available` | `boolean` | **FR-40** 等价于 `registered && health == AVAILABLE`。 |

`manifestDigest` 的三个要点:

- **注册时算一次并缓存**,查询路径**绝不**再调 `Provider.manifest()`——那会占住命令槽,且让"只读快照"失效。
- **归一化后再取指纹**:动作按名称排序、对象键(`attributes`/`errorCodes`)按字典序、去除空白差异。因此"同一份语义内容"在任何声明顺序下都得到同一个值。
- **null 表示没有可比对指纹**:未注册域、或走旧契约适配的域(其清单由适配器合成,算出的指纹没有可比性)。**不是**空指纹。
- `dispatchCount` / `failureCount` / `lastDispatchAt` 是**运行期指标,重启归零**,不是持久统计。

### 7.5 `core.business_status`(FR-35 / FR-40,`order=110`,命令池)

只读健康缓存快照。payload 可选 `{"domain":"economy"}`(不传则全量:已注册域 + 保留窗口内的已注销域)。整段查询**不调 `dispatch`、不做 IO、不新建线程**,域数上限 `bridge.health.max-entries`,每域 `reason` ≤ `reason-max-chars`、`detailJson` ≤ `detail-max-chars`(**存储时即截断**)。

```json
{
  "domains": {
    "economy": {
      "registered": true, "health": "AVAILABLE", "reason": "", "detailJson": "",
      "sinceMs": 1730000000000, "refreshedAtMs": 1730000000500, "quarantined": false,
      "available": true, "degradedReason": null,
      "manifestDigest": "a3f2b8c1d4e5f607", "lastDispatchAt": 1730001234000,
      "dispatchCount": 1523, "failureCount": 7,
      "lastFailure": {"message": "外部插件未就绪", "timestampMs": 1729999990000, "consecutiveFailures": 2},
      "dispatch": {"total": 1523, "failed": 7, "lastCostMs": 3}
    }
  },
  "refresh": {"inFlight": false, "stalled": false, "quarantined": ["activity"], "lastSweepAtMs": 1730000001000, "skippedThisSweep": []},
  "timestamp": 1730000001000
}
```

- `lastFailure` 从未失败时**整个字段缺席**(不是空对象)。
- `degradedReason` / `manifestDigest` 为 null 时**字段存在、值为 `null`**(与 `lastFailure` 的"缺席"不同):它们是每域恒定存在的槽位。
- **0 个域 → `domains` 为空对象,整段仍 `success=true`**(不是错误)。
- `refresh.stalled=true` 表示 probe 线程被某个卡死的 `health()` 占住:其余域沿用上次缓存(查询仍成功)、本轮后续域列在 `refresh.skippedThisSweep`。这是已知取舍(Java 无法强杀线程),代价是"其它域的健康值不再新鲜",收益是线程数恒定、命令通道完全不受影响。
- 单域超时/异常/返回 null 只把该域记为 `DEGRADED`,**整段回执仍 `success=true`**。

相关配置(`bridge.health.*`,均单域有界):`refresh-interval-ms`(10000)、`timeout-ms`(1000)、`quarantine-ms`(60000)、`retain-after-unregister-ms`(300000)、`max-entries`(256)、`reason-max-chars`(512)、`detail-max-chars`(2048)。

### 7.6 `jbis/manifest`(FR-38,`order=90`)

保留元域 `jbis` + 动作 `manifest`:返回全部已注册业务域的能力清单。它**不派发到任何 Provider**,只汇总 manifest。

`output` 是如下信封(顶层 `apiVersion` 是信封协议版本,不是某个域的):

```json
{
  "apiVersion": 1,
  "domains": {
    "activity": {
      "displayName": "活动系统",
      "description": "...",
      "specDeclared": true,
      "actions": [
        {
          "name": "start",
          "description": "开启活动",
          "risk": "WRITE",
          "idempotent": true,
          "inputSchema": "{\"type\":\"object\"}",
          "outputSchema": null,
          "errorCodes": ["ACTIVITY_NOT_FOUND", "ALREADY_RUNNING"]
        }
      ],
      "attributes": {}
    }
  }
}
```

- `specDeclared=false` 表示该域规格不可信:来自旧契约适配,或 `apiVersion` 不受支持被降级。此时动作清单可能不完整,`risk`/`idempotent` 是探针的保守默认值(`WRITE`/`false`),**面板不应据此渲染精确表单**。
- `inputSchema`/`outputSchema` 未声明时为 `null`(不是空串);`errorCodes: []` 表示未声明。
- 单域 `manifest()` 抛异常时该域呈现为 `{"error": "..."}`,不影响其它域。
- 兼容性:旧消费方读 `domains` 仍然有效,新增的 `apiVersion`/`specDeclared` 是纯增量。

### 7.7 事件上报与序号(FR-41)

接口:`top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink`
门面:`ServerProbeAPI.eventSink()`(稳定代理,可缓存;探针未就绪时一律返回 `false`,`emitReliable` 的 `seq` 重载默认退化为忽略 `seq`)

| 方法 | 语义 |
| --- | --- |
| `emit(domain, dedupKey, topic, fields)` | **可丢**:队列满即丢并计数。**不占 seq 序列**。 |
| `emitReliable(domain, dedupKey, topic, fields)` | **不可丢**:探针自动分配 `seq`。`dedupKey` 必须非空,为空直接 `false` 且**不降级为可丢**。 |
| `emitReliable(domain, dedupKey, topic, fields, seq)` | **不可丢 + 调用方指定 `seq`**(补发专用)。`seq <= 0` 返回 `false`。 |

#### 强语义入口(推荐新代码使用)

`boolean` 只能回答「有没有受理」,而**不受理的原因决定了调用方该做什么**。需要区分时用下面两个方法,
它们返回 `EmitReceipt`(带原因、可重试标记、序号):

| 方法 | 语义 |
| --- | --- |
| `emitReceipt(domain, dedupKey, topic, fields)` | 同 `emit`,但返回 `EmitReceipt`。 |
| `emitReliableReceipt(domain, dedupKey, topic, fields)` | 同 `emitReliable`,并**回传探针分配的 `seq`/`seqEpoch`**,业务侧可拿它做本地水位线。 |

```java
EmitReceipt receipt = ServerProbeAPI.eventSink()
        .emitReliableReceipt("economy", "bill-8891", "economy.bill", fields);
if (!receipt.isAccepted()) {
    if (receipt.isRetryable()) {
        // 通道饱和:退避后重试,事件本身没丢
    } else {
        // 参数类失败或探针未就绪:重试无用,记日志并按业务策略处理
    }
}
long watermark = receipt.getSeq();   // 可靠通道才有值,> 0
```

`EmitReceipt.Reason` 的取值与含义:

| 原因 | 可重试 | 含义与建议处置 |
| --- | --- | --- |
| `ACCEPTED` | 否 | 已受理(含“已落盘待回放”)。 |
| `REJECTED_SATURATED` | **是** | 队列满且落盘也接不下。退避后重试。 |
| `PROBE_UNAVAILABLE` | 否 | 探针未就绪/已卸载。插件先于探针加载是常态,静默忽略即可。 |
| `INVALID_DOMAIN` | 否 | 域名空或格式错,调用方 bug。 |
| `MISSING_DEDUP_KEY` | 否 | 可靠事件缺 `dedupKey`,调用方 bug;**不会降级为可丢**。 |
| `INVALID_SEQUENCE` | 否 | 调用方指定的 `seq <= 0`。 |

**只有 `REJECTED_SATURATED` 可重试**。把永久失败当成可重试,回灌循环会陷入“退避→重试→再退避”的死循环。

兼容性:`emit` / `emitReliable` 的 `boolean` 形式**行为完全不变**;`emitReceipt` / `emitReliableReceipt`
是接口的 `default` 方法,第三方既有实现升级后无需改动。未覆写时它们从参数反推原因——只能分辨“空域”
与“缺去重键”两种必败情形,**其余一律保守归为 `REJECTED_SATURATED`**(宁可多试一次,也不误报永久失败)。

`seq` 的语义边界:

- **per-domain 独立递增**,不是全局单调;不同域互不干扰。
- **严格递增、不跳号**;起始 1,重启后从持久化的 `max + 1` **续接**(方案 B)。
- **可丢事件不占序列**:否则可丢事件一被丢弃,可靠序列里就会留下永远补不上的空洞。
- **补发保持原 seq**:落盘缓冲的行里连同位点一起写入(`type \t event \t domain \t topic \t requestId \t seq \t seqEpoch \t payload`),回放时原样发出。FR-41 之前的 6 字段旧行仍可读出(位点为 0)。
- **持久化丢失**时从 `(当前时间戳毫秒 << 20)` 起跳并递增 `seqEpoch`,保证不与旧值冲突。
- **调用方指定的 `seq` 不推进**探针的分配序列;若它大于当前上限则把上限抬高到 `seq + 1`(避免后续序号倒退)。

事件帧(payload)新增两个字段,**只在有值时出现**:

```json
{
  "type": "event", "event": "activity_change", "instance": "s1",
  "timestamp": 1730000000000, "domain": "activity", "dedupKey": "e-8891",
  "seq": 1024, "seqEpoch": 1,
  "data": {"...": "..."}
}
```

`seqEpoch` 递增只发生在序号语义确实重置时(持久化丢失、long 溢出前重置)。正常重启**不递增**
——下游看到同一 `seqEpoch` 就能确信「序号仍可跨重启比对」;`seqEpoch` 变了则需全量对账。

**探针只保证 `seq`/`seqEpoch` 准确,不规定业务载荷里放什么字段**(那是业务台与插件的约定)。

状态文件:落盘目录下的 `seq.state`(与 `spill.jsonl` 同目录,`bridge.line.spill-enabled=false` 时不存在):

```text
# ServerProbe 事件序号状态（制表符分隔，勿手工编辑）
epoch	1
activity	1024
economy	7
```

---

## 8. 配置重载 API:ConfigReloadRegistry(FR-32)

接口:`top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadRegistry`
门面:`top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI.configReloads()`

JM 经 `core.config_reload` 下发路径后,探针只**通知真正关心这份配置的插件**,并如实回传结果。
**探针不推断插件是否真的应用了新配置**(reload 成功 ≠ 配置生效);需要强保证请用重启。

```java
// onEnable:注册监听器(门面返回稳定代理,探针后加载也可用)
Registration handle = ServerProbeAPI.configReloads().register(new MyReloadListener());
// onDisable:注销(幂等;按实例比对,不会误删热重载后的新实例)
handle.close();
```

### 8.1 方法清单

| 方法 | 返回 | 语义 / 边界 |
| --- | --- | --- |
| `register(ConfigReloadListener)` | `Registration` | 注册即校验声明:非法条目(越界、含 NUL、空串)被丢弃并 WARN;全部非法 → 失效句柄 `INVALID` + `"路径越界"`;声明空集合 → 失效句柄 `INVALID` + `"未声明关注路径"`。**不静默成功**。探针未就绪 → 失效句柄 `PROBE_UNAVAILABLE`,不抛异常。 |

### 8.2 `ConfigReloadListener`

| 成员 | 语义 |
| --- | --- |
| `Collection<String> watchedPaths()` | 关心的路径:精确(`plugins/Foo/config.yml`)或目录前缀(**末尾斜杠**,`plugins/Foo/`)。路径用 `/` 分隔、相对工作目录、必须在 `plugins/` 子树内。**每次派发都会被调用**(可在运行期改关注项),必须轻量。 |
| `ConfigReloadResult onConfigReload(ConfigReloadEvent)` | 重载并返回结果。**绝不抛异常**;抛出的会被兜住并转为 `PLUGIN_THREW`(丢失可读原因);返回 `null` 视为失败。 |

线程约定:回调在 `serverprobe-reload-worker` 池的 daemon 线程上触发,**不在**主线程、**不在**桥读线程。
需要主线程的插件自行切回,但总耗时不得超过 `bridge.reload.timeout-ms`(默认 5000ms);
超时后探针只标记 `TIMEOUT`,**不强杀**你的任务(Java 无法强杀线程),该次重载自生自灭。

### 8.3 `ConfigReloadEvent` / `ConfigReloadResult` / `ConfigReloadErrorCode`

| 类型 | 要点 |
| --- | --- |
| `ConfigReloadEvent` | `getMatchedPaths()` 本次命中本监听器的路径(已排序);`getRequestedPaths()` 本次请求全部路径(全量时为空);`getFullReload()` 是否全量。静态工厂 `of(matched, requested)` / `full(matched)`。 |
| `ConfigReloadResult` | 工厂 `ok()` / `ok(detailJson)` / `failed(code, message[, detailJson])`。读口 `getOk()`/`getCode()`(成功时 null,本类唯一允许 null 的读口)/`getMessage()`/`getDetailJson()`;自报事实 `getAppliedMd5()`(`Optional<String>`)、`getAppliedVersion()`、`getAppliedCount()`(`OptionalInt`,`empty` 表示未上报、`0` 是合法事实),配套写口 `withAppliedMd5/Version/Count`。布尔访问器一律 `get` 前缀(ADR-13)。 |
| `ConfigReloadErrorCode` | `FILE_NOT_FOUND` / `PARSE_ERROR` / `PLUGIN_REJECTED` / `PLUGIN_THREW` / `TIMEOUT` / `PATH_ESCAPE` / `IN_FLIGHT` / `STUCK` / `INTERNAL_ERROR`。**`BEST_EFFORT` 不是错误码**——无人认领的路径计入回执的 `bestEffort`(算成功)。只有 `TIMEOUT` 触发熔断。 |

### 8.4 `core.config_reload`(FR-32,`order=100`,reload 协调池)

payload:`{"paths":["plugins/Foo/config.yml", "plugins/Bar/"]}`;**空数组或省略 `paths` = 全量**
(向所有已注册监听器各通知一次,`matchedPaths` 为其声明集合)。单次上限
`bridge.reload.max-paths-per-request`(默认 32),超限**整请求拒绝**(不静默截断)。

```json
{
  "success": true,
  "reloaded": ["plugins/Foo/config.yml"],
  "bestEffort": ["plugins/Bar/config.yml"],
  "failed": [
    {"path": "plugins/Baz/config.yml", "code": "PARSE_ERROR", "message": "第 12 行缩进错误"}
  ],
  "applied": {"plugins/Foo/config.yml": {"md5": "...", "version": "...", "count": 12}},
  "costMs": 37,
  "error": ""
}
```

- **整体成败判定式(唯一权威)**:`success = failed.isEmpty() && error.isEmpty()`。
  即使全部是 `bestEffort` 也算成功;任一失败则整体失败,但 `reloaded` 仍如实列出成功的部分。
- `reloaded` 至少一个监听器返回 `ok()` 的路径;`bestEffort` **没有任何监听器认领**的路径(算成功,单列);
  `failed` 任一监听器失败/超时/越界/在途/卡死。同一路径既有成功又有失败 → **归入 `failed`**(严格口径)。
- `applied` 为自报事实,**未上报的字段整个缺席**(不写 0);三个字段都没上报时该路径不出现在 `applied` 里。
- 请求级错误(路径数超限、payload 非法)不产生 `failed` 条目,只写 `error` + `success=false`。

> **失败时的传输层形态**:命令回执的信封是 `{success, output, error}`。
> **成功时** `output` 是本节的完整 JSON;**失败时**两者都有——`output` 仍是完整回执
> (含 `reloaded` / `bestEffort` / `failed` / `applied` 明细),`error` 是逐条带
> `path/code/message` 的可读摘要(并注明"已成功 N 项"),`success=false`。
> 这由 `BridgeCommandResult.ofFailure(output, error)` 承载——`ok(output)`/`fail(error)`
> 两个既有工厂互斥,单靠它们会让失败时丢掉机器可读明细。面板应优先读 `output`,
> `error` 仅供直接展示与告警文案。

相关配置(`bridge.reload.*`):`timeout-ms`(5000)、`max-paths-per-request`(32)、`worker-pool-size`(8)、
`stuck-after-ms`(30000)、`circuit-breaker.enabled`(true)/`failure-window-ms`(60000)/
`open-duration-ms`(60000)/`max-open-duration-ms`(600000)/`failure-threshold`(1)/`max-entries`(256)。
协调池容量**不在此段**:它是 `bridge.line.reload-pool-size`(4)。
