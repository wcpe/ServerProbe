# 启动剖析指南

> ⚠️ 本页为设计阶段文档,描述目标行为,功能尚未实现。

这是 ServerProbe 的**首要能力**(PRD G1 / FR1):当你觉得"开服越来越慢、又说不清慢在哪"时,用启动剖析把它**量化定位**到具体插件、世界、生命周期阶段,并能与历史对比。

> ⚠️ 本页所述能力均为 🚧 规划中,功能尚未实现;示意输出为占位。

---

## 一、为什么需要它

服务端启动是一条**串行链**:

```
JVM 启动 → 服务端 bootstrap → 各插件 onLoad/onEnable → 世界加载 → Done!
```

任一环节卡顿都会拖慢整体:某插件 enable 卡顿、世界 spawn-chunk 预加载、依赖在线下载、数据升级……但传统手段只能"感觉慢",无法量化。启动剖析就是把这条链拆开计时。

---

## 二、启动画像里有什么

一次启动会生成一份**启动画像(Startup Profile)**,通过 `/probe startup` 查看(见 [命令与权限](Commands.md))。它包含:

| 内容 | 对应需求 | 说明 |
|---|---|---|
| 端到端总时长 | FR1.1 | `ServerLoadEvent(STARTUP)` 时刻 − `RuntimeMXBean.getStartTime()` |
| 逐插件 onEnable 耗时榜(Top-N) | FR1.2 | 本插件走生命周期打点;全部插件解析 `logs/latest.log` 的 `Enabling X` 时间戳 |
| 逐世界加载 + spawn-chunk 预加载耗时 | FR1.3 | 世界事件 + 日志(`Preparing spawn area`) |
| 启动分段耗时 | FR1.4 | `@Awake(CONST/INIT/LOAD/ENABLE/ACTIVE)` 各阶段 `nanoTime` |
| 与上次 / 基线对比(每项 Δ) | FR1.5 | 落盘后对比,标注每项变化量 |

示意输出:

```
[ServerProbe] 启动剖析
 端到端总时长: 48.6s   (上次 31.2s, Δ +17.4s ↑)
 分段: CONST 0.1s | INIT 0.4s | LOAD 2.1s | ENABLE 39.8s | ACTIVE 6.2s
 慢插件 Top-5 (onEnable):
   1. SomePlugin     22.3s   (上次 5.1s, Δ +17.2s ↑)   ← 重点嫌疑
   2. AnotherPlugin   6.4s
 世界耗时:
   world        4.8s   (spawn-chunk 预加载 3.9s)
   world_nether 1.1s
```

---

## 三、怎么读

1. **先看总时长 + Δ**:确认这次到底比上次/基线慢多少。Δ 越大越值得排查。
2. **看分段**:哪个阶段吃掉了大部分时间?
   - `ENABLE` 大 → 多半是某个插件 enable 慢(看慢插件榜)。
   - `ACTIVE` 大 → 服务器完全就绪前的收尾(常含世界/收尾任务)。
   - `LOAD` 大 → onLoad 阶段(部分插件在此做重活)。
3. **看慢插件 Top-N 的 Δ**:总时长涨了 17s,而某插件正好涨了 17s —— 元凶基本锁定。
4. **看世界耗时**:spawn-chunk 预加载是否异常?某世界是否突然变慢?
5. **与历史对比**:这是关键。"绝对值大"不一定是问题,"比上次明显变大"才是信号。

---

## 四、常见慢启动根因 checklist 及应对

| 类别 | 典型表现 | 排查 / 应对 |
|---|---|---|
| **插件** | 某插件 onEnable 耗时榜居高、Δ 突增 | 看该插件配置 / 数据量;是否在 enable 做了重 IO、在线下载、全表加载;升级或反馈作者;考虑延迟初始化 |
| **世界** | 某世界加载 / spawn-chunk 预加载耗时高 | 调小 spawn 半径 / 预生成区块 / 检查异常区块;Folia 注意 per-region |
| **数据升级** | 大版本升级后首启特别慢 | MC 数据格式升级(DataFixerUpper)一次性开销,属 bootstrap 盲区,只能整体时长对比;升级前预生成/备份 |
| **JVM** | 总时长波动大、GC 频繁 | 看 `/probe gc` 与启动参数快照;堆是否过小导致启动期频繁 GC;检查 GC 参数 |
| **环境** | 时间散落在 IO / 下载 | 磁盘 IO、依赖在线下载、网络拉取;改用本地依赖 / 预热缓存 |

---

## 五、慢启动告警

可配置阈值:**启动总时长 > 基线 ×1.5** 触发慢启动告警(FR1.6 / FR5),输出到控制台 / 命令 /(可选)webhook。这样无需每次人工盯,显著变慢时自动提醒。

---

## 六、盲区说明(务必知悉)

服务端启动有一段**早于 ServerProbe 自身加载**的盲区,纯插件(纯 API)看不到:服务端 **bootstrap 阶段**(NMS 初始化、DataFixerUpper、注册表)、本插件 onEnable 之前加载的**其他插件**、插件**依赖在线下载**、以及完全**无日志输出的卡顿**。默认(纯插件模式)下:

- 探针**不做**逐方法级归因(那需要重型字节码织入,属非目标)。
- bootstrap 层只能通过**端到端总时长对比**来反映其变化(例如大版本升级导致 bootstrap 变慢,会体现在总时长上,但拆不到具体方法)。

**可补的部分(可选 premain agent)**:启用[第七章](#七启动期-agent可选增强补加载前盲区)的 premain agent 后,**本插件加载前**的盲区——逐插件精确耗时、库下载耗时、世界创建/配置加载/事件·命令注册耗时、多线程折叠栈采样(抓"无日志卡顿"),并据此用 `/probe flamegraph` 导出**启动火焰图 + 嵌套时间线**——可被补上;**代价是需手动加 `-javaagent` 启动参数**。仍补不到的是 bootstrap 内部的逐方法 CPU 归因。

如需 bootstrap 层更细的 CPU 归因,建议并用 [spark](FAQ.md)。

---

## 七、启动期 agent(可选增强,补加载前盲区)

> ✅ 1.21.4 Paper 单端真机验证;**可选增强,需手动启用**。其他端(1.8 / Folia / BungeeCord)尚未逐一真机。

前六章的剖析全部来自纯插件(纯 API),拿不到 ServerProbe 自己 `onEnable` **之前**发生的事。若你需要这段盲区,可启用 ServerProbe 自带的**启动期 premain agent**。

### 7.1 它能多看到什么

| 能力 | 说明 | 相对纯 API |
|---|---|---|
| 逐插件 load/enable **精确耗时** | 纳秒级,且覆盖本插件之前加载的插件 | 优于日志解析的秒级口径(真机:ServerProbe onEnable 精确 0.3s vs 日志 1.0s) |
| **库下载耗时** | 插件依赖在线下载(`LibraryLoader`,1.17+)的耗时 | 量化"依赖在线下载"这一常见慢启动根因 |
| **世界创建耗时** | 插桩 `CraftServer.createWorld`,逐世界精确计时 | 纯 API 拿不到逐世界精确耗时(`/probe startup` 世界耗时由此回填,不再恒为 0) |
| **配置 / 事件 / 命令注册耗时** | 插桩 `YamlConfiguration.loadConfiguration` / `registerEvents` / `register`,逐项计时 | 暴露"配置文件加载慢""监听器/命令注册慢"等细粒度根因 |
| **多线程折叠栈采样** | 10ms 周期抓**全部线程**的**完整调用栈**,以折叠栈聚合 | 抓启动期**无任何日志**的卡顿热点(含并行下载等非主线程),并保留调用层级供火焰图还原(见 7.5) |
| **HTTP/TCP 外呼监控(运行期常驻)** | 插桩 `HttpURLConnection.getInputStream` + `Socket.connect` | 哪个插件/代码发起对外请求、目标、耗时、响应码、(脱敏)参数;`/probe http` 回看(见 7.6) |

### 7.2 怎么启用

ServerProbe 是**二合一 jar**:`plugins/` 下的 `ServerProbe.jar` 同时可作 `-javaagent`。在**启动命令**里加一行指向同一个 jar:

```bash
java -javaagent:plugins/ServerProbe.jar -jar paper.jar
```

- **手动启用**:必须改启动命令/脚本;**不加这行 = 纯插件模式**,前六章能力照常工作。
- **为何不受 JEP 451**:这是**启动期命令行 premain**(JVM 在 `main` 之前加载),**不是被否决的运行时 self-attach**(后者在 Paper/JDK21+ 默认禁用)。Paper + JDK21/24 上零警告。
- **绝不成为事故源**:premain 顶层兜底,启用/插桩失败一律静默降级为"agent 未生效",不影响插件、不崩 JVM。

### 7.3 多线程折叠栈采样:抓"无日志卡顿"

启动期最难排查的是**既无日志、又拆不到插件**的卡顿——某段初始化在线程上闷头跑,日志静默、计时也只看到"这一段总耗时高"。采样器对 `Server thread` / `Netty Server IO` / `ServerMain` 等关键线程按 5ms 周期抓取**完整调用栈**,以折叠栈(`栈底;…;栈顶`)按出现次数聚合——既给出"这段时间各线程在执行什么"的热点榜,又**保留帧间父→子调用关系**,据此可还原真正的多层火焰图(见 7.5)。主线程扁平热点榜(`/probe startup` 的"主线程热点 Top-N")即由其派生。

### 7.4 范围与降级(诚实边界)

- **M5 先 Bukkit 端**;插件/世界/配置/事件/命令计时、库下载、折叠栈采样均走 Bukkit 路径。
- **Folia**:无单一主线程,主线程相关采样降级(此场景引导用 [spark](FAQ.md));其余计时复用 Bukkit 路径。
- **BungeeCord**:本阶段推迟。
- **真机覆盖**:仅 **1.21.4 Paper 单端**全通;其他端未逐一真机。
- **依赖**:引入唯一新依赖 ASM(已 relocate 隔离,不与服务端/其他插件冲突)。
- **不成事故源**:采集严格收敛在"启动窗口"内——插件就绪即关闭采集,被插桩方法在运行期的调用不再记录(杜绝内存泄漏);任何采集失败静默降级。

### 7.5 启动火焰图 + 嵌套时间线(`/probe flamegraph`)

挂载 agent 后,`/probe flamegraph` 把最近一次启动画像导出为**自包含 HTML**(CSS/JS 全内联,离线可看,无 CDN 依赖)到 `data/flamegraph/`,含两个视图:

- **火焰图**:由多线程折叠栈逐层并树而成的**真正多层火焰图**——纵轴=调用深度、条宽=该调用路径占该线程采样的比例;可在顶部**切换线程**,左键下钻、右键返回上层、搜索高亮帧名。用于回答"启动期 CPU 实际耗在哪条调用链上"。
- **时间线**:各 hook 事件按 `[start,end]` **区间包含关系分泳道**的嵌套视图——`enablePlugin` 区间在下层,其内部的 `registerEvents`/`loadConfiguration`/`register` 子区间叠在上层、按真实时间对齐;X 轴 0 点 = premain,如实反映"premain → 各阶段"的真实时间线。用于回答"谁套在谁里面、各占多少墙钟时间"。

> 定位:此火焰图**专注启动期**(premain 阶段一般性 profiler 难以介入);运行期 CPU 归因仍建议并用 [spark](FAQ.md),二者互补不冲突。无 agent 时该命令会提示先加 `-javaagent` 启用。

### 7.6 HTTP/TCP 对外网络外呼监控(运行期常驻)

很多"开服慢/卡顿"其实卡在**对外网络请求**——典型如 TabooLib/插件在加载阶段**在线下载依赖**(可卡数分钟,且日志稀少)。挂载 agent 后,本监控插桩 JDK 网络层,自动记录**每一次对外请求**:

- **覆盖**:`HttpURLConnection`(HTTP/HTTPS,含 TabooLib 下载)经 `getInputStream` 捕获完整语义;绕过它的客户端(OkHttp/Apache/数据库等)经 `Socket.connect` 兜底记录目标地址。
- **内容**:发起的**插件 + 代码栈**、请求方法、目标 URL、响应码、耗时;可选(默认关)记录请求头。
- **归因方式**:优先按调用栈各类的 **ClassLoader 身份**匹配插件——这样即便插件经连接池/驱动后台线程发起连接(栈上只有它打包的 mysql/h2/apache 等库类),也能据这些库类的 ClassLoader 归因到拥有它的插件;再以包名前缀兜底。若某库由服务端/共享类加载器提供(不属于任何插件),则标记为 `未知`(属预期)。
- **呈现**:① 实时中文日志(`外呼 [插件] GET 主机 (200, 137000ms) ← 触发处`);② 落盘 `data/http/http-<日期>.log`;③ `/probe http` 回看近期;④ 启动期外呼并入 `/probe flamegraph` 报告。
- **安全**(遵循安全准则):URL 查询串与请求头中的 `Authorization`/`Cookie`/`token`/`secret`/`password` 等敏感项自动打码为 `***`;**请求体不在捕获范围**(发起时已流式写出,无法事后读取)。
- **不成事故源**:注入极简、逻辑全程兜底(经真实 JDK 类 + ASM 校验合法,绝不破坏 JVM 网络);有界缓冲防泄漏;`config.yml` 的 `http-monitor` 段可调开关、控制台日志、落盘、请求头记录、缓冲容量与拉取周期。

> 这正是排查"开服卡在依赖下载"类隐形瓶颈的直接手段:在 `/probe http` 或启动报告里,卡最久的那条外呼会清楚标出是哪个插件、在向谁请求、耗时多少。

---

## 八、数据落盘与对比

每次启动画像作为**一份 JSON**异步落本地文件(`StartupProfile`:`schemaVersion, serverId, platform, mcVersion, jvmStartTimeMs, totalMs, phaseTimings, pluginTimings, worldTimings, jvmArgs, createdAtMs`),用于"与上次 / 基线对比"。挂载 agent 时还含**增强字段**(`agentAttached`/`premainNanos`/`agentPluginLoadTimings`/`agentPluginEnableTimings`/`libraryTimings`/`mainThreadHotspots`/`timelineEvents`/`threadStacks`/`configTimings`/`eventTimings`/`commandTimings`),均带默认值、向后兼容(`schemaVersion` M5 起为 3);未挂载时为默认/`null`。落盘通道详见 [数据呈现与对接](Data-Output.md)。

---

> 返回 [Wiki 首页](Home.md) · 相关:[命令与权限](Commands.md) · [指标说明](Metrics.md) · [数据呈现与对接](Data-Output.md)
