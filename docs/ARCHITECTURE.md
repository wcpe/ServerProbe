# ServerProbe 架构文档

> 本文是 ServerProbe 的技术架构说明,与[产品需求文档(PRD)](PRD.md)配套。PRD 回答 *What/Why*,本文回答 *How*。

## 1. 概述

ServerProbe 是一个 **Minecraft 服务器运维探针**,核心能力为"开服慢剖析"与"运维指标采集分析",目标覆盖 **Bukkit 系 1.8–1.21.11 全版本(含 Folia) + BungeeCord 代理端**,单 jar 多端运行。

### 设计原则
1. **先通用,跑不通再拆胶水**:绝大多数指标走 Bukkit API + JMX(全版本+全平台通用),只有当通用 API 不支持某版本/平台功能时,才编写最小胶水。严禁一上来就建一堆空胶水模块。
2. **只读优先,绝不成为事故源**:主体只读采集,不改运行时;字节码插桩等危险手段默认关闭、失败静默降级。
3. **面向接口 + 运行时装配**:通用层定义接口,平台/版本实现运行时探测后装配,核心编译期不依赖任何具体实现。
4. **核心 Java 8 字节码**:保证产物可被 1.8–1.21.x 所有 JRE 加载;仅极少数必须直接引用高版本类型的胶水才抬 toolchain。

---

## 2. 总体分层

```
                       ┌─────────────────────────────┐
                       │           plugin             │  壳 + env install + 单 jar 打包
                       └──────────────┬──────────────┘
            ┌─────────────────────────┼─────────────────────────┐
   ┌────────┴────────┐      ┌─────────┴─────────┐      ┌─────────┴─────────┐
   │ platform-bukkit │      │  platform-bungee  │      │  nms-vXXX(可选)   │
   │ Bukkit/Folia采集│      │   代理端采集       │      │ 仅直引NMS类型时    │
   └────────┬────────┘      └─────────┬─────────┘      └─────────┬─────────┘
            └─────────────────────────┼─────────────────────────┘
                              ┌────────┴────────┐
                              │      core       │  通用采集编排/JMX/聚合/告警/呈现/本地存储+开放接口
                              └────────┬────────┘
                              ┌────────┴────────┐
                              │       api       │  契约:采集器接口 + 指标/启动画像模型
                              └─────────────────┘
```

依赖方向**单向向下**:`plugin → platform-* / nms-* → core → api`。`core` 与 `api` **不依赖任何平台 API**;`platform-*` 实现 `api` 的接口;`core` 通过运行时装配拿到实现。

---

## 3. 模块结构

| 模块 | 职责 | JDK/target | 平台 | env install | compileOnly 依赖 |
|---|---|---|---|---|---|
| **api** | 契约层(**纯 Java + Lombok 不可变模型**,ADR-13):指标模型、采集器接口 `*Collector`、只读 API/存储 SPI、公共枚举。零平台 API、**零 Kotlin metadata**,任意 Kotlin(含 1.x)/Java 版本可编译依赖。 | Java 8 | 通用 | `Basic` | `lombok`(仅编译期) |
| **core**(现 `project:core`) | 通用核心:采集编排/调度、JMX 采集(`java.lang.management.*`)、聚合/滑窗/分位、阈值告警、呈现格式化、**本地文件存储 + 开放接口**。面向 api 接口编程。 | Java 8 | 通用 | `Basic`+`I18n` | `project(":api")` |
| **platform-bukkit** | Bukkit/Paper/**Folia** 采集:在线人数、世界/区块/实体计数、TPS/MSPT、插件列表。Folia 分流(`Folia.isFolia` + `callRegion{}`)写在此。 | Java 8 | Bukkit(含 Folia) | `Basic`+`Bukkit`+`BukkitUtil`+`I18n` | `project(":api")` + `ink.ptms.core:*:universal` + `io.paper:folia-api` |
| **platform-bungee** | 代理端采集:子服在线/总人数、子服 ping/路由、`ProxyServer` 指标。标 `@PlatformSide(Platform.BUNGEE)`。 | Java 8 | BungeeCord | `Basic`+`BungeeCord` | `project(":api")` + `net.md-5:bungeecord-chat` |
| **nms-vXXX**(可选,**按需才建**) | 仅当某指标必须**直接继承/引用** NMS 专有类型(无法反射绕过)时才建,如低版本读 `MinecraftServer.recentTps`。沿用 `nmsProxy {name}Impl$versionId` 多 Impl 范式。 | Java 8(全反射)/ 高 toolchain(直接 extends 高版本类) | Bukkit | — | `project(":api")`+`project(":platform-bukkit")` + 对应版本服务端 |
| **plugin** | 壳 + 打包:TabooLib 描述、IOC 自动接管、合并各模块产物进单 jar。 | Java 8 | 多端 | 全量 | `taboo(project(...))` |

> 演进:现有 `api`/`project:core`/`plugin` 保留;`platform-bukkit`、`platform-bungee` 为新增 `include`(需改 `settings.gradle.kts`,属关键文件,实施时确认)。

---

## 4. 多版本兼容机制(MC 1.8 – 1.21.11)

依赖 TabooLib 的版本抽象,核心三件套:

### 4.1 版本探测:`MinecraftVersion`
- 取版本:`runningVersion`("1.21.4")、`major`/`minor`(supportedVersion 索引)、`versionId`(数字,如 `12101`)。
- 特征位:`isUniversal`(≥1.17,走 Mojang/universal 映射)、`isUnobfuscated`(≥26.1,不混淆)、`isUniversalCraftBukkit`(Paper 1.20.6+)。
- 比较:`isHigherOrEqual(V1_13)`/`isIn(min,max)` 等。

### 4.2 NMS 多版本抽象:`nmsProxy`
写一套 **Mojang 映射的抽象类 + Impl**,`nmsProxy<T>()` 在运行期用 ASM 把 Impl 字节码重映射到当前服务端的实际混淆名再实例化(结果缓存)。业务代码只见抽象类,版本差异收敛到 Impl 内部。

### 4.3 版本特定代码三手法
1. **运行期版本分支**(最常用):`if (MinecraftVersion.isUniversal) {...} else {...}`。
2. **`nmsProxy` + abstract/Impl**:封装跨版本 NMS 字段/方法差异;可配合 `bind="{name}Impl$versionId"` 按版本加载不同 Impl(`""`/`"17"`/`"26"`)。
3. **`@PlatformSide`**:平台级(Bukkit/Bungee)门控,非 MC 版本级。

### 4.4 ServerProbe 的版本策略
- **JVM/在线数/世界列表**:纯通用,零版本处理。
- **TPS/MSPT**:唯一需版本分支的关键点(见 §7.3)。
- **NMS 深度采集**:能反射就反射(`nmsClass`/`getProperty`),避免被高版本类型"传染"toolchain。

---

## 5. 多平台架构(Bukkit + BungeeCord)

- **接口在 common,实现按 `@PlatformSide` 隔离**:`api` 定义采集器接口;`platform-bukkit`/`platform-bungee` 各自实现并标注 `@PlatformSide(Platform.BUKKIT|BUNGEE)`,运行时只激活当前平台对应实现。
- **生命周期**:`@Awake(LifeCycle.X)` —— CONST→INIT→LOAD→ENABLE→**ACTIVE(服务器完全启动)**→DISABLE。Bukkit/Bungee 一致。
- **单 jar 多端**:同一 jar 内写入 `plugin.yml`+`bungee.yml`,各端读各自描述符。
- **代理端能力边界**:`ProxyPlayer` 多数字段在代理端 `Unsupported`(无世界/坐标);代理端只采网络拓扑类数据(总在线、各子服在线+ping、玩家路由、JVM)。

---

## 6. JDK / Toolchain 策略

**核心结论:几乎全部 Java 8 编译即可,包括 Folia。**

| 场景 | 是否需抬 toolchain | 原因 |
|---|---|---|
| 核心逻辑、JMX 采集 | ❌ Java 8 | 纯 JDK API |
| Bukkit API 采集(全版本) | ❌ Java 8 | Bukkit API 向后兼容 |
| Folia 调度/采集 | ❌ Java 8 | `compileOnly folia-api` + 反射 + `Class.forName` 探测 |
| `compileOnly` 高版本服务端但**走反射** | ❌ Java 8 | javac 不加载未被直接引用的 class |
| **直接 `extends`/typed-reference 高版本 NMS 类** | ✅ 高 toolchain(独立模块) | javac 编译子类必须加载验证父类 class,JDK8 读不了 major≥53 |

要点:
- `compileOnly` 一个高字节码依赖**本身不强制**抬 toolchain(TabooLib `bukkit-nms-stable` 即 `compileOnly` 26.1 服务端却仍 Java 8,全程反射)。
- 真正"传染"的是**直接出现在源码符号里**的高版本类型(继承、字段类型、方法签名)。
- 因此 ServerProbe **优先反射**,把"必须直接引用高版本类型"的代码隔离到独立 `nms-vXXX` 模块单独抬 toolchain,绝不与 Java 8 核心混编。
- 跨 toolchain 依赖方向:高版本模块可 `compileOnly` Java 8 模块(安全);Java 8 核心**绝不**反向依赖高版本模块产物,只通过接口 + 运行时反射装配。

---

## 7. Folia 适配

### 7.1 识别
`Folia.isFolia` = `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` 成功与否。Folia 是 `Platform.BUKKIT` 的运行期变体,非独立平台。

### 7.2 调度(零胶水)
TabooLib `submit()/submitAsync()` 已适配:Folia 上自动走 `GlobalRegionScheduler`(同步)/`AsyncScheduler`(异步),**不碰已被移除的 `Bukkit.getScheduler()`**。
**规则:探针所有定时采集一律走 TabooLib `submit`,严禁直接 `Bukkit.getScheduler()`。**

### 7.3 TPS/MSPT(唯一需自研的关键点)
TabooLib **完全不封装** TPS/MSPT。ServerProbe 抽象 `ServerTickSampler` 接口,多实现:

| 环境 | TPS 实现 | MSPT 实现 |
|---|---|---|
| Paper(支持 `getTPS()`) | `Bukkit.getTPS()` | `Bukkit.getAverageTickTime()` + 每 tick `nanoTime` 直方图算分位 |
| 老版本/纯 CraftBukkit(无 `getTPS()`) | `nmsProxy` 读 `MinecraftServer.recentTps[]`,或自建 tick 任务采样 | 自建 tick 采样 |
| **Folia** | 无全局 TPS(per-region)→ **per-region 明细 + 全局标 N/A**(M1 先全局 N/A,M2/M3 补 per-region 明细) | 同上,per-region |

### 7.4 实体/区块采集
- 非 Folia:`submit{}`(同步)遍历 `world.getEntities()`/`getLoadedChunks()`。
- Folia:数据归属各 region 线程,跨线程读会抛异常 → 用 TabooLib 现成的 `Location.callRegion{}` / `Entity.callRegion{}` 逐区域采集后汇总。

---

## 8. 核心组件与数据流

```
[采集器 Collector]  ──submit 定时/事件──▶  [指标快照 / 启动画像]
   (平台实现)                                      │
                                ┌─────────────────┴─────────────────┐
                          [环形缓冲(实时)]                    [异步落盘(本地文件)]
                                │                                    │
                          [聚合:滑窗/分位/GC差分]                [历史/启动画像]
                                │
        ┌───────────────┬───────┴───────┬───────────────┐
   [游戏内命令]   [Prometheus /metrics]  [Web 面板]   [告警引擎]
```

- **采集器(api 接口,platform 实现)**:`MetricCollector#collect(): MetricSnapshot`,按平台/版本提供实现。
- **采集编排(core)**:用 `submit(period)` 定时驱动各采集器;主线程只取轻量值,聚合/遍历异步或限频。
- **存储**:高频指标进定容环形缓冲;启动画像与聚合后指标**异步落本地文件**(JSON/JSONL,原子写入,可配滚动与保留),**不依赖数据库**;经统一存储 SPI 写入,默认实现为本地文件。
- **聚合**:TPS 滑窗、MSPT 分位直方图(p50/p95/p99)、GC 累计值差分。
- **呈现**:命令/Prometheus/Web 三出口共享同一聚合结果;告警引擎按阈值判定。
- **开放接口**:① 读取 API(`api` 暴露只读数据访问,第三方经 TabooLib 服务获取)② 存储 SPI(默认且唯一内置=本地文件,预留第三方扩展)③ 导出端点(Prometheus/Web)。详见 PRD FR8。

---

## 9. 启动剖析机制

| 指标 | 手段 | 覆盖范围 |
|---|---|---|
| 端到端总时长 | `ServerLoadEvent(STARTUP)` − `RuntimeMXBean.getStartTime()` | JVM 启动→服务就绪 |
| 本插件分段 | `@Awake(CONST/INIT/LOAD/ENABLE/ACTIVE)` 各阶段 `nanoTime` | 本插件视角 |
| 全部插件 onEnable | 解析 `logs/latest.log` 的 `Enabling X` 时间戳 | 所有插件(粗粒度) |
| 世界加载 | 世界事件 + 日志(`Preparing spawn area`) | 各世界 |
| (可选,M5)逐插件精确耗时 | premain agent 经 `ClassFileTransformer` 插桩 `SimplePluginManager` | 每个插件 load/enable(纳秒级) |
| (可选,M5)库下载耗时 | premain agent 插桩 `LibraryLoader.createLoader`(1.17+) | 各插件依赖在线下载 |
| (可选,M5)世界/配置/事件/命令耗时 | premain agent 插桩 `CraftServer.createWorld` / `YamlConfiguration.loadConfiguration` / `registerEvents` / `register` | 逐世界创建、逐配置加载、逐事件/命令注册(纳秒级) |
| (可选,M5)多线程折叠栈采样 | premain agent 对 `Server thread` / `Netty Server IO` / `ServerMain` 5ms 抓**完整调用栈**、折叠聚合 | 启动期"无日志卡顿"热点 + 调用层级(火焰图数据源) |
| (可选,M5)启动火焰图 + 嵌套时间线 | `/probe flamegraph` 由折叠栈/时间线生成自包含 HTML | 多层多线程火焰图 + 区间嵌套时间线 |
| (可选,M5)HTTP/TCP 外呼监控(运行期常驻) | premain agent 插桩 `HttpURLConnection.getInputStream` + `Socket.connect` | 哪个插件/代码发起对外请求、目标、耗时、响应码、(脱敏)参数 |
| (可选,M4)精确插桩 | Incision 织入 `enablePlugin` | 方法级 |

**盲区(及其补法)**:服务端 bootstrap(DFU/注册表)早于任何插件加载,普通插件不可见,只能整体时长对比。**ServerProbe 自身加载前的盲区**(早于本插件 onEnable 的插件计时、库下载、无日志卡顿)可由**可选的 premain Java Agent**补上(命令行 `-javaagent` 手动启用,见 §13);代价是需手动加启动参数,不加则纯插件模式正常工作。

---

## 10. 运行时加载与装配

四层门控,叠加使用,核心思想"编译期 `compileOnly` + 运行期探测后再触碰":

1. **`@PlatformSide`**:平台级,IOC 扫描只在匹配平台实例化。
2. **`MinecraftVersion` + `nmsProxy` 版本后缀**:版本级,只反射加载当前版本的 Impl,其余 Impl 类存在但永不链接。
3. **`Class.forName` 探测**:特性级(Folia 即用此法),专有类型仅在探测为真的分支被触碰。
4. **面向接口 + 运行时装配**:契约在 `api`/`core`,实现在 `platform-*`,通过 `nmsProxy`/服务发现/反射在运行时绑定;核心编译期不依赖实现。

---

## 11. 关键技术决策记录(ADR)

> 决策正文已迁移为独立文件,见 [`adr/`](adr/)(**单一真源**)。下表仅作索引(决策 + 状态 + 链接);新决策递增追加。

| # | 决策 | 状态 | 链接 |
|---|---|---|---|
| ADR-1 | 探针主体用纯 API+JMX+采样,不用 Java Agent(否决运行时 self-attach) | 已接受 | [0001](adr/0001-probe-core-no-java-agent.md) |
| ADR-2 | 方法级插桩(如需)选 Incision 而非裸 ASM,默认关闭+先 PoC | 已接受 | [0002](adr/0002-method-instrumentation-incision-not-asm.md) |
| ADR-3 | 核心 Java 8 字节码,胶水按需抬 toolchain | 已接受 | [0003](adr/0003-core-java8-bytecode.md) |
| ADR-4 | Folia 调度直接用 TabooLib `submit` | 已接受 | [0004](adr/0004-folia-scheduling-via-taboolib-submit.md) |
| ADR-5 | TPS/MSPT 自研 `ServerTickSampler` 抽象 | 已接受 | [0005](adr/0005-tps-mspt-self-built-sampler.md) |
| ADR-6 | 单 jar 多端分发 | 已接受 | [0006](adr/0006-single-jar-multi-platform.md) |
| ADR-7 | 存储用本地文件 + 开放接口,不用数据库 | 已接受 | [0007](adr/0007-local-file-storage-no-database.md) |
| ADR-8 | 运行期火焰图不自研(并用 spark),启动期火焰图自研 | 已接受 | [0008](adr/0008-startup-flamegraph-self-built-runtime-spark.md) |
| ADR-9 | 代理端暂不联动汇总,各端独立 | 已接受 | [0009](adr/0009-proxy-independent-no-cross-server-aggregation.md) |
| ADR-10 | 依赖策略:允许按需引入轻量依赖,逐个确认 | 已接受 | [0010](adr/0010-dependency-policy-lightweight-on-demand.md) |
| ADR-11 | 为补加载前盲区做可选的 premain Java Agent 增强(默认不启用) | 已接受 | [0011](adr/0011-optional-premain-startup-agent.md) |
| ADR-12 | HTTP/TCP 外呼监控插桩 JDK 层,而非逐个 HTTP 客户端库 | 已接受 | [0012](adr/0012-outbound-http-tcp-monitor-jdk-instrumentation.md) |
| ADR-13 | api 模块改用纯 Java(Lombok),支持任意 Kotlin/Java 消费方 | 已接受 | [0013](adr/0013-api-pure-java-for-broad-consumer-compat.md) |
| ADR-14 | JSON 编解码经可换适配器(`core/json` 的 `JsonCodec`/`Json`),默认后端 nightconfig 零依赖;细化 ADR-10 的「用 gson」 | 已接受 | [0014](adr/0014-json-codec-adapter.md) |
| ADR-15 | 监控探针演进为 JianManager 业务对接 agent(对外单 agent、对内监控/业务分层、事故域隔离) | 已接受 | [0015](adr/0015-business-integration-agent.md) |
| ADR-16 | 背包业务对接的物品传输契约(结构化物品过桥、读富写以 nbtBase64 为准、JSON 门面承载) | 决策 1/2 被 ADR-17 取代 | [0016](adr/0016-inventory-business-item-transport.md) |
| ADR-17 | 背包对接随 AllinInventorySync 2.0.0 写门面分区字节化调整(物品写暂不提供并明确降级、读 / 属性写 / 事件保留、DTO 位置参构造) | 已接受 | [0017](adr/0017-inventory-write-degraded-byte-facade.md) |

> ADR-11 的详细**技术论证**见下文 §13(架构叙述视角);其**决策记录**见 [ADR-11](adr/0011-optional-premain-startup-agent.md)。

---

## 12. 构建与打包

- **单 jar**:沿用现有 `plugin/build.gradle.kts` 的 `taboo(project(...))` + `from(sourceSets.output)` 合并模式;新增模块同步追加两行。
- **多 toolchain 混装**:不同 major version 的 class 可共存于一个 jar;按需加载保证低版本 JVM 永不触碰高版本 class,不会 `UnsupportedClassVersionError`。
- **不额外 shadowJar relocate**:TabooLib 插件已接管 relocate/IOC-takeover。
- **构建命令**:`./gradlew build`(发行,不含 TabooLib 本体)、`./gradlew taboolibBuildApi -PDeleteCode`(开发,含本体)。

---

## 13. 启动期 Java Agent(可选增强,ADR-11 详述)

> **定位**:与"丢进 `plugins/` 即用的纯 API 主体"**并存**的一个**可选、需手动 `-javaagent` 启用的高精度增强**,专补 ServerProbe 自身加载前的盲区。默认不启用;启用失败一律静默降级,绝不影响插件正常工作,绝不崩 JVM。**当前仅 M5 Bukkit 端、仅 1.21.4 Paper 单端真机验证。**

### 13.1 为何要它,以及它与 §5.1 / ADR-1 的调和

§5.1 与 ADR-1 的结论是"**探针主体**不引入 Java Agent",否决的是**裸 ASM + 运行时 self-attach**——那种方式在 Paper/JDK21+ 默认禁用(`-XX:-EnableDynamicAgentLoading` 拦截、JEP 451 对自挂载发警告/后续将拒绝),脆弱且高危。

本 agent **不属于被否决的对象**:它是**命令行 premain agent**,由 JVM 在**启动期**(`main` 之前)经 `-javaagent:plugins/ServerProbe.jar` 加载,走的是标准 `premain` 入口,**不是 self-attach**。premain 路径不受 JEP 451 约束,在 Paper + JDK21/24 上**零警告**。因此:
- **主体仍是纯 API**——不加 `-javaagent` 时,ServerProbe 是一个普通插件,所有既有能力照常工作。
- **agent 是叠加在主体之上的可选增强**,只在用户主动加启动参数时生效,用来拿主体(纯 API)**拿不到**的数据:ServerProbe 自己 onEnable 之前发生的事。

它补的三类盲区(均为本插件加载前、纯 API 不可见):

| 能力 | 手段 | 相对纯 API 的优势 | 1.21.4 真机 |
|---|---|---|---|
| 逐插件 load/enable 精确耗时 | premain `ClassFileTransformer` 插桩 Bukkit `SimplePluginManager` | 纳秒级,优于日志解析的秒级,且覆盖本插件之前加载的插件 | ServerProbe onEnable 实测 0.3s,日志解析口径为 1.0s |
| 库下载耗时 | 插桩 `LibraryLoader.createLoader`(1.17+) | 量化"依赖在线下载"这一常见慢启动根因 | 通过 |
| 主线程栈采样 | 按线程名 `"Server thread"` 周期抓栈 | 抓启动期"无日志卡顿"的热点,日志/计时都看不到 | 首位热点 `ClassLoader.loadClass`(455 次) |

### 13.2 形态:二合一 jar

`ServerProbe.jar` **同一个产物**既是 `plugins/` 下的插件,又是 `-javaagent` 的 agent(清单含 `Premain-Class`)。启用方式 = 启动命令加 `-javaagent:plugins/ServerProbe.jar`;**不加则纯插件模式**,功能完整。无需第二个 jar、无需改插件部署位置。

### 13.3 技术要点:ASM 依赖与跨 ClassLoader 通道

- **唯一新依赖 ASM**:premain 期需在字节码层面插桩,引入 ASM;**relocate 到 `...agent.shadow.asm`**,与服务端/其他插件隔离,避免版本冲突。这是本增强带来的**唯一一个新的第三方依赖**。
- **跨 ClassLoader 通道——`ProbeAgentBridge` 放 bootstrap ClassLoader**:插桩进去的字节码运行在被插桩类的 CL(如服务端 CL),而插件逻辑在 TabooLib 的插件 CL,两者天然隔离。为让"插桩字节码 + 插件反射读取 + 栈采样"共享**同一份**计时数据,premain 经 `Instrumentation#appendToBootstrapClassLoaderSearch` 把一个极薄的 `ProbeAgentBridge` 放到 **bootstrap CL**——它对所有 CL 全局可见,三方都引用同一份。
- **顶层 `catch(Throwable)` 兜底**:`premain` 全程被顶层 `try/catch(Throwable)` 包裹,任何异常只静默降级为"agent 未生效",**绝不向上抛、绝不崩 JVM**。

### 13.4 诚实记两个 bootstrap CL 真机坑(宝贵教训)

bootstrap CL 通道的边界很窄,真机踩过两个坑,记录以备后人:

1. **插桩字节码直接引用 Bridge → `NoClassDefFound`**:最初让被插桩类里的字节码直接 `import`/引用 `ProbeAgentBridge`,但 Bridge 尚未对那个 CL 可见,链接时 `NoClassDefFoundError`。**根因**:被插桩类的 CL 看不到放在别处的 Bridge。
2. **把整个 agent 子树 append 到 bootstrap → app↔bootstrap `IllegalAccessError`,崩 JVM**:为"解决"上一个坑,一度把整个 agent 包子树都 `appendToBootstrapClassLoaderSearch`。结果 bootstrap CL 里的类去访问仍在 app CL 的类(双向跨界),触发 `IllegalAccessError` 直接**崩 JVM**——违背"绝不成为事故源"。

**终解**:**只** append 极薄的 `ProbeAgentBridge`(纯数据中转,不反向依赖 app CL 任何东西)到 bootstrap CL,插桩字节码只与这个 Bridge 交互;再叠加 premain 顶层 `catch(Throwable)` 兜底。两坑的共同教训是:**bootstrap CL 上只放"零反向依赖、对所有 CL 安全可见"的最小桥接类,多放一寸都可能把单向隔离破坏成双向越界。**

### 13.5 范围与降级

- **M5 先 Bukkit 端**。插件计时、库下载、主线程栈采样均走 Bukkit 路径。
- **Folia**:无单一主线程,**主线程栈采样降级标 N/A**(此场景引导用 spark);插件计时复用 Bukkit 路径。
- **BungeeCord**:推迟,本阶段不做。
- **真机覆盖(诚实边界)**:仅 **1.21.4 Paper 单端**全通——`Done` 正常、逐插件精确耗时正确、栈采样正常、无 `IllegalAccessError`/`NoClassDefFound`、不破坏启动;**其他端(1.8 / Folia / BungeeCord)未逐一真机**。
