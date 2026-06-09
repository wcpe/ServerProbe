# 数据呈现与对接

> ⚠️ M1 + M2 已实现并在 1.21.4 Paper 单端真机验证通过,**待生产环境验证**;其他端未逐一真机。下方配置键名 / 路径 / 指标命名与当前代码一致,以最终实现为准。

ServerProbe 提供**多个呈现 / 对接通道**(对应 PRD FR4 / FR5)。同一份采集结果向多个出口共享,互不重复采集。

> 状态标注:✅ 已实现(待生产验证) / 🚧 规划中。游戏内命令、Prometheus `/metrics`、本地文件历史落盘、告警引擎均已实现;Web 面板仍为 🚧 规划中。

| 通道 | 优先级 | 状态 | 适用场景 |
|---|---|---|---|
| 游戏内命令 | P0 | ✅ 已实现(待生产验证) | 即时巡检、单次定位"开服慢" |
| Prometheus `/metrics` + Grafana | P1 | ✅ 已实现(待生产验证) | 长期监控、看板、报警 |
| 告警引擎(日志 / 游戏内 / Webhook) | P1 | ✅ 已实现(待生产验证) | 阈值越线主动推送 |
| Web 面板 | P2 | 🚧 规划中 | 启动画像详情、历史趋势、(可选)火焰图 |
| 本地文件历史落盘 | P0/P1 | ✅ 已实现(待生产验证) | 历史对比、事故回溯、数据沉淀(不依赖数据库) |

---

## 一、游戏内命令(P0)

最直接的出口,无需任何外部组件。入口 `/probe`,子命令 `health|startup|tps|gc|world|proxy`,权限受控。

适用场景:服主临时排查、运维快速确认当前状态、开发者看自己插件 enable 耗时。

完整命令、示意输出、权限节点见 [命令与权限](Commands.md)。

---

## 二、Prometheus `/metrics` + Grafana(P1)✅ 已实现(待生产验证)

把指标暴露为 Prometheus 抓取端点,接入 Grafana 做长期看板与告警。基于 **JDK 内置 `HttpServer` 实现,零第三方依赖**。

- 路径固定 `/metrics`,内容类型 `text/plain; version=0.0.4`。
- Bukkit 与 BungeeCord **各为独立进程、各起一个端点**;同机同时部署两端时需配置**不同端口**避免冲突。
- 安全默认:端点**默认关闭**,且默认仅本机(`127.0.0.1`)可访问;开放到外网前务必配置 token 与/或 IP 白名单。

配置(`config.yml`,键名与代码一致):

```yaml
metrics:
  enabled: false          # 是否开启 /metrics 端点(默认关闭)
  host: "127.0.0.1"       # 绑定地址(默认仅本机回环)
  port: 9940              # 监听端口(默认 9940)
  token: ""               # 鉴权 token;非空时请求需带 Authorization: Bearer <token>,否则 401
  allowed-ips:            # 来源 IP 白名单(与 token 叠加);仅精确 IP,不支持 CIDR
    - "127.0.0.1"
```

> 鉴权双重校验:token 非空时校验 `Authorization: Bearer <token>`(不匹配回 **401**);来源 IP 不在 `allowed-ips` 内回 **403**。token 为空表示不启用 token 鉴权,此时仅靠 IP 白名单兜底(默认仅本机,绝不裸奔)。拒绝响应不泄露任何配置 / token 信息。

Prometheus 抓取配置(示意):

```yaml
scrape_configs:
  - job_name: serverprobe
    static_configs:
      - targets: ['127.0.0.1:9940']
```

### 指标命名(`serverprobe_*`)

所有指标统一前缀 `serverprobe_`;每条时间序列附带公共 label `serverId`(实例标识)与 `platform`(`BUKKIT`/`BUNGEE`/`VELOCITY`)。时间一律以**秒**为单位(`*_seconds` / `*_seconds_total`)。不可用项(如 Folia 的 TPS/MSPT、JDK 不提供的 CPU、`-1` 哨兵)**不导出对应行**,而非给占位值。

| 指标(示例) | 类型 | 额外 label | 维度 |
|---|---|---|---|
| `serverprobe_heap_used_bytes` / `serverprobe_heap_max_bytes` | gauge | —— | JVM 堆 |
| `serverprobe_nonheap_used_bytes` 等 | gauge | —— | JVM 非堆 |
| `serverprobe_memory_pool_used_bytes` | gauge | `pool` | 各内存池 |
| `serverprobe_gc_count_total` / `serverprobe_gc_time_seconds_total` | counter | `gc` | GC 明细 |
| `serverprobe_gc_young_count_total` / `serverprobe_gc_old_count_total` 等 | counter | —— | GC young/old 派生 |
| `serverprobe_threads` / `serverprobe_threads_deadlocked` 等 | gauge | —— | 线程 |
| `serverprobe_classes_loaded` / `serverprobe_classes_loaded_total` | gauge / counter | —— | 类加载 |
| `serverprobe_process_cpu_load` / `serverprobe_system_cpu_load` | gauge | —— | CPU(0.0–1.0) |
| `serverprobe_uptime_seconds` | gauge | —— | JVM 运行时长 |
| `serverprobe_tps` | gauge | `window`(1m/5m/15m) | 服务器 TPS |
| `serverprobe_mspt_seconds` | gauge | `quantile`(avg/p95/p99) | 服务器 MSPT |
| `serverprobe_players_online` / `serverprobe_players_max` | gauge | —— | 在线 / 容量 |
| `serverprobe_server_uptime_seconds` | gauge | —— | 服务器运行时长 |
| `serverprobe_world_loaded_chunks` / `serverprobe_world_entities` / `serverprobe_world_tile_entities` | gauge | `world` | 各世界 |
| `serverprobe_proxy_players_online` / `serverprobe_proxy_backend_players_online` | gauge | `backend` | 代理端 |

> 每个指标名输出一行 `# TYPE`(为压缩体积略去 `# HELP`)。

适用场景:多服 / 长期监控、需要历史趋势图与阈值报警、已有 Grafana 体系。

> 依赖策略(已敲定):**允许按需引入轻量依赖,但每个新依赖需逐个确认**。Prometheus 导出最终以 **JDK 内置 `HttpServer` 零依赖实现**,未引入任何 Prometheus 客户端库。

---

## 三、Web 面板(P2)🚧 规划中

内置 Web 面板,用于查看启动画像详情、历史趋势,以及(可选)火焰图。

- 需**鉴权 + 绑定地址**(安全要求,不裸暴露)。
- 优先级 P2,排在命令 / Prometheus / 落盘之后。

配置占位(🚧 规划中):

```yaml
web:
  enabled: false
  bind: 127.0.0.1
  port: 8980
  auth: ""                # 访问凭据
```

适用场景:需要图形化查看启动画像细节与历史趋势,不想自建 Grafana。

> 依赖策略(已敲定):**允许按需引入轻量依赖,但每个新依赖需逐个确认**。内嵌 Web 服务依赖(如 Javalin / NanoHTTPD)据此评估,逐个确认后引入。

---

## 四、本地文件历史落盘 + 开放接口(P0/P1)✅ 已实现(待生产验证)

探针**不访问数据库**,把数据沉淀到**本地文件**,支撑"与上次 / 基线对比"和事故回溯。

落盘内容与路径(均位于插件数据目录下的 `data/`):
- **启动画像**:每份写为**一个 JSON 文件**(`StartupProfile`),归档于 `data/startup/`,保留份数由 `history-retention`(默认 30)控制。
- **指标历史**:每个采集周期的快照(`MetricSnapshot`)以 **JSONL 行式追加**,**按实例分目录、按自然日滚动**:

  ```
  data/metrics/<实例>/metrics-<yyyyMMdd>.jsonl
  ```

  每行一条完整快照(含 `serverId`、`platform`、`timestampMs`、`jvm`、`server`、`proxy`),写入频率受 `collect-period-ticks` 控制。

设计约束:
- **原子写入**:启动画像先写临时文件再 `rename`,避免半截文件。
- **异步 / 不阻塞主线程**:高频指标先进环形缓冲,落盘异步进行。
- **双闸清理**:历史 JSONL 同时受**保留天数**与**总体积上限**两道闸限制,**绝不删除当天文件**。
- **不依赖数据库**:无需任何外部数据源或连接配置。

配置(`config.yml`,键名与代码一致):

```yaml
# 内存近期历史保留份数(环形缓冲,不落盘)。默认 360。
history-capacity: 360
# 启动画像归档保留份数。默认 30。
history-retention: 30

# 历史指标 JSONL 落盘(与上面两项相互独立)
history-file:
  enabled: true           # 是否落盘(默认开启)
  retention-days: 7       # 保留天数:含当天保留最近 N 天(默认 7)
  max-total-mb: 200       # 单实例总体积上限 MB,超出从最旧清理至达标(默认 200;<=0 视为不限制)
```

> 三个保留概念相互独立、互不影响:`history-capacity`(内存环形缓冲,供只读 API 回看)、`history-retention`(启动画像归档份数,针对 `data/startup/`)、`history-file.*`(历史指标 JSONL,针对 `data/metrics/`)。

适用场景:需要历史对比定位"这次比上次慢多少"、长期数据沉淀与事故复盘。多服共存时:**`serverId` 由实例自动生成,可在配置中以 `server-name` 覆盖为自定义值,本地文件按实例分目录存放**,互不混淆(目录名经净化防止路径逃逸)。

数据模型见 [指标说明](Metrics.md) 与 [架构文档](../ARCHITECTURE.md)。

### 开放接口 ✅ 已实现(待生产验证)

探针对外开放三层接口,既供查询也预留扩展:

- **① 读取 API**:`api` 模块的 `ProbeReadApi` 提供**只读数据访问**契约(最新快照、近期历史、历史区间查询、聚合查询、启动画像),第三方插件经 TabooLib 服务获取;另提供 `ServerProbeApi` 静态门面便于取用。
- **② 存储 SPI**:`MetricStore` 抽象存储后端,**默认且唯一内置 = 本地文件**;M2 扩面新增 `readStartupProfiles` / `readHistory` / 批量 `appendHistory`(均带默认实现,旧实现向后兼容)。预留扩展点供第三方自接 DB / 远程,但插件本身不内置、不依赖。
- **③ 导出端点**:Prometheus `/metrics`(✅ 已实现,见第二节)与 Web 面板(🚧 规划中,见第三节)。

---

## 五、告警引擎(P1)✅ 已实现(待生产验证)

每个采集周期对照规则判定指标是否越线,越线经各通道主动推送;含**防抖**(持续 N 周期才触发)与**恢复**状态机,避免抖动刷屏。**安全 / 降噪默认:整体默认关闭**,需显式开启。

内置规则(枚举驱动,`alert.rules` 下逐条可配 `threshold` / `sustain-cycles` / `level`(WARN/CRITICAL)/ `enabled`):

| 规则键 | 含义 | 默认阈值 | 默认级别 |
|---|---|---|---|
| `tps-warn` | 1 分钟 TPS 偏低 | < 18 | WARN |
| `tps-critical` | 1 分钟 TPS 过低 | < 15 | CRITICAL |
| `mspt-p95` | MSPT p95 过高 | > 50 ms | WARN |
| `heap-usage` | 堆占用率过高 | > 90% | WARN |
| `deadlock` | 死锁线程数(事件型,持续 1 周期即触发) | > 0 | CRITICAL |

三个输出通道:
- **日志**:写控制台 / 日志文件(WARN / CRITICAL / 恢复 分级)。默认开启。
- **游戏内**:推送给在线 OP 或持 `serverprobe.alert` 权限的玩家(仅 Bukkit 端)。默认开启。
- **Webhook**:以 JSON `POST` 到外部地址(钉钉 / 飞书 / 企业微信 / 自建网关),基于 JDK `HttpURLConnection`,**无第三方依赖**。默认关闭;回调地址可能含 token,探针绝不写入日志。

配置(`config.yml`,节选):

```yaml
alert:
  enabled: false                 # 告警总开关(默认关闭)
  channels:
    log: true
    in-game: true
    webhook:
      enabled: false
      url: ""                    # 留空=不投递
      timeout-ms: 3000           # 连接/读取超时
  rules:
    tps-warn:    { threshold: 18.0, sustain-cycles: 3, level: WARN,     enabled: true }
    tps-critical:{ threshold: 15.0, sustain-cycles: 3, level: CRITICAL, enabled: true }
    mspt-p95:    { threshold: 50.0, sustain-cycles: 3, level: WARN,     enabled: true }
    heap-usage:  { threshold: 90.0, sustain-cycles: 3, level: WARN,     enabled: true }
    deadlock:    { threshold: 0.0,  sustain-cycles: 1, level: CRITICAL, enabled: true }
```

适用场景:无人值守时主动发现 TPS 跌落 / MSPT 飙高 / 堆吃满 / 死锁,并在恢复后收到回执。

---

## 通道与场景速查

| 你的需求 | 推荐通道 |
|---|---|
| 现在就想看一眼服务器状态 | 游戏内命令 `/probe health` |
| 这次开服为啥这么慢 | 游戏内命令 `/probe startup` + 历史文件对比 |
| 做长期看板 + 报警 | Prometheus + Grafana |
| 无人值守时主动收告警 | 告警引擎(日志 / 游戏内 / Webhook) |
| 图形化看启动画像细节 | Web 面板(🚧 规划中) |
| 事故回溯 / 历史趋势 | 本地文件历史落盘 |

---

> 返回 [Wiki 首页](Home.md) · 相关:[命令与权限](Commands.md) · [指标说明](Metrics.md) · [启动剖析指南](Startup-Profiling.md)
