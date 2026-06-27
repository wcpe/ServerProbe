# 更新日志(Changelog)

本项目所有重要变更均记录于此文件。

本文件格式遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [语义化版本(SemVer)](https://semver.org/lang/zh-CN/)。

> 首个版本 **0.1.0(2026-06-20)** 已发布(本地 tag `v0.1.0`,未推送、无公开下载产物)。`规划路线` 段列出后续计划版本(版本号为初步规划,以实际发布为准)。

---

## [未发布] (Unreleased)

### 新增
- **业务对接 agent 骨架(JBIS,ADR-0015)**:ServerProbe 演进为 JianManager 业务对接 agent 的探针侧骨架。`core/bridge` 新增 `BusinessProvider` 接口(业务域 / 动作 / 能力清单 / 派发)+ `BusinessHost`(域键路由 + **事故域隔离**:独立 daemon 业务线程池 + 有界超时 + 异常边界 + 合并 manifest);`BridgeCommand` 加 `domain`/`payload`,`BridgeClient` 收到带 domain 的业务 `command` 帧时路由到对应 Provider,治理命令走既有路径(业务 / 监控分流)。Provider 卡死 / 抛异常只降级该次回执,**监控采集与桥读线程不受影响**。core 平台无关(无 Bukkit 符号),业务 Provider 实现落 platform 层。`./gradlew build` 编译 + 单测(路由 / 未注册降级 / 抛异常隔离 / 卡死有界超时 / 注册)+ detekt 全绿;**业务 Provider 接入(经济等)与端到端真机待续**。
- **经济业务 Provider(对接 MultiCurrencyEconomy,JBIS)**:platform-bukkit 新增 `EconomyProvider`(`economy` 域),`compileOnly` MultiCurrencyEconomy 公开 api(运行期由目标服务端提供),经 `MultiCurrencyEconomyApi` 发现 + 就绪判定;首个动作只读 `economy.balance`(按 player + currency 查余额,金额以 BigDecimal 字符串承载防失真),mce 未就绪 / 参数缺失 / 查询异常一律降级失败。`@Service` + `@PlatformSide(BUKKIT)` + `@PostConstruct` 平台门 + 桥开关门自注册到 `BusinessHost`(沿用 `BukkitBridgeCommandHandler` 范式)。`./gradlew build` 全模块编译 + IOC 静态分析 + detekt 全绿;**真机(真 MultiCurrencyEconomy 服查真实余额)待端到端复验**。
- **业务能力清单桥查询(JBIS 元查询)**:`BridgeClient` 收到保留元命令(domain=`jbis` + action=`manifest`)时返回 `BusinessHost` 汇总的各业务 Provider 能力清单 JSON,供 JianManager 动态发现业务能力(不硬编码具体插件);该元命令不派发到任何业务 Provider。core 编译 + detekt 全绿。
- **经济变更事件上报(JBIS,对接 JM FR-122)**:platform-bukkit 新增 `BukkitEconomyEventListener`(`object` + `@SubscribeEvent`),订阅 MultiCurrencyEconomy `PlayerEconomyChangeEvent`(持久化投递流,覆盖 web 后台/跨服一切余额变更,下游唯一可靠源)与 `PlayerEconomyCatchupEvent`(玩家上线补发离线缺口),折算为 `economy` 业务**事件**经既有反向 WS 桥上报本机 Worker(→ JM CP 按 ledgerId 去重 + node→zone 聚合)。`core/bridge` 的 `BridgeClient` 新增 `emitBusinessEvent`(`event` 帧带顶层 `domain`/`dedupKey`,信封 data 携 currencyId→identifier、zoneId、signedAmount/balanceAfter 字符串、entryType、seq、occurredAt;空值不发、未连静默丢弃、绝不抛)。currencyId(Int 主键)经 `getActiveCurrencies()` 映射为全局稳定 identifier(跨服/跨区同币主键可能不同,按 identifier 聚合方不串味),映射缺失回退 Int 不丢事件;纯折算/映射逻辑抽 `EconomyEventEnvelope`(脱离 Bukkit 运行期、可单测、降 detekt 复杂度)。两事件均 mce 异步事件,监听器在非主线程触发且整体 runCatching 兜底(事故域隔离,绝不拖垮探针/mce)。`./gradlew :platform:platform-bukkit:build` 全模块编译 + IOC 静态分析(errors=0) + detekt + 单测(映射/回退/去重键/信封编码/大额防科学计数)全绿;**真机(web 后台/其他服改余额汇聚到 JM、跨区不混)待端到端复验**。
- **经济业务 Provider 扩写动作(JBIS,对接 MultiCurrencyEconomy)**:`EconomyProvider` 在只读 `balance` 基础上新增七个写动作——`deposit`(加)/`withdraw`(扣)/`adjust`(有符号差额校正)/`set`(无原生设值,经「查余额→算差额→adjust」实现、非原子)/`transfer`(玩家间转账)/`consume`(原子消费产流水号)/`refund`(按消费流水号退款),各经 mce `MultiCurrencyEconomyService` 子服务执行。守 mce 写契约:**幂等键** pluginName=`JianManager` + `IdempotencyMode.BusinessOrder(taskId)`(taskId 由 JianManager 侧生成,缺则拒绝;重试须复用同键防 `MCE-LEDGER-0001` 冲突);**金额 BigDecimal 字符串承载**(禁浮点);mce 业务失败(余额不足 / 账户冻结 / 金额非法…)以结构化结果回传(success/status/errorCode 透传),仅 Provider 级错误(未就绪 / 参数缺失 / 解析失败 / 调用抛异常)回 `BridgeCommandResult.fail`。纯解析 / 校验 / 结果编码逻辑抽到 `EconomyEnvelope`(脱离 Bukkit 运行期、降 detekt 复杂度)。`./gradlew build` 全模块编译 + IOC 静态分析 + detekt + 单测全绿;**真机(真 mce 加 / 扣 / 转账成功且幂等、余额不足等错误码正确)待端到端复验**。
- **背包业务 Provider(对接 AllinInventorySync 2.0.0,JBIS,对接 JM FR-125)**:platform-bukkit 新增 `InventoryProvider`(`inventory` 域),`compileOnly` AllinInventorySync 自包含 api(2.0.0 起纯 Java + Lombok,运行期由目标服务端提供),经 `AllinInventorySyncProvider.isAvailable/get` 发现 + 降级。只读 `view`(`getPlayerInventory(uuid)` 回源含离线 → 结构化视图:背包 / 末影箱物品数组 + 基础属性 + online + dataVersion,玩家无数据回 `exists=false`)+ 基础属性写 `writeBasicAttrs`(经写门面 `getInventoryWriteApi()`,落盘回执 `WriteResult` 透传 success/online/newDataVersion/errorCode)。守写契约:幂等键 `taskId`(CP 注入)→ 写门面 `requestId` 持久去重(缺则拒绝),`base + edited` 净改动 delta 透传,operator 透传(空回退 `JianManager`)。**物品写 `writeInventory`/`writeEnderChest` 暂不提供**——AllinInventorySync 2.0.0 物品写门面入参退回不透明分区字节(`byte[]`),外部集成无法从结构化物品构造,收到即明确降级、不进 manifest(见 [ADR-0017](docs/adr/0017-inventory-write-degraded-byte-facade.md) 取代 [ADR-0016](docs/adr/0016-inventory-business-item-transport.md) 决策 1/2,待其导出可外部消费的结构化物品写门面再恢复)。`@Service` + `@PlatformSide(BUKKIT)` + `@PostConstruct` 自注册到 `BusinessHost`;纯解析 / 校验 / 编码逻辑抽 `InventoryEnvelope`(可单测,encode 返纯 Map)。`./gradlew build` 全模块编译 + IOC(errors=0)+ detekt + 单测(manifest 二动作 / 物品写降级 / 读写校验 / 属性解码 / 视图 · 物品 · 回执编码)全绿;**真机(真 AllinInventorySync 服读真实背包 + 写属性、回执正确)待端到端复验**。
- **背包追踪事件上报(JBIS,对接 JM FR-126)**:platform-bukkit 新增 `BukkitInventoryEventListener`(`object` + `@SubscribeEvent(bind=FQCN)`,软依赖按名绑定避免漏注册,同经济监听器教训),订阅 AllinInventorySync `TrackedItemActionEvent`(重点物品流转:登录携带 / 丢出 / 拾取 / 移入容器),折算为 `inventory` 业务**事件**经反向 WS 桥上报本机 Worker。信封 data 携 playerName / playerUuid / action / ruleId / ruleDescription / material / amount / displayName / occurredAt;物品只编 Bukkit-API 便利字段(无 `nbtBase64`——全保真 codec 在 AllinInventorySync core 非 api,见 ADR-0016);瞬时观测无插件侧持久 ID,去重键 `playerUuid:action:occurredAtMs:seq`(会话单调 seq 去歧义)。`@SubscribeEvent` 收 `OptionalEvent`、`get<T>()` 取强类型(无 AllinInventorySync 时 bind 不触发,零副作用);整段 runCatching 兜底(事故域隔离,绝不拖垮探针)。纯折算逻辑抽 `InventoryEventEnvelope`(可单测,3 例);plugin softdepend `AllinInventorySync`。`./gradlew build` 全绿;**真机(重点物品流转汇聚到 JM)待端到端复验**。

### 修复
- **经济事件监听器按名绑定避免漏注册(对接 JM FR-122,真机修)**:`BukkitEconomyEventListener` 原用 `@SubscribeEvent` 直收 mce `PlayerEconomyChangeEvent`/`PlayerEconomyCatchupEvent`——探针 enable 时(即便 plugin.yml softdepend mce 保证后于 mce 加载)TabooLib 仍按方法反射参数类型解析事件类失败(`事件未能找到` WARN),致监听器**漏注册、经济事件零捕获**。改 `@SubscribeEvent(bind="事件全限定名")` 按名绑定(不在 enable 时解析类)+ 处理器收 `taboolib.common.platform.event.OptionalEvent`、`get<T>()` 取强类型(无 mce 时该 bind 自然不触发,零副作用);并在 `plugin/build.gradle.kts` 的 `description.dependencies` 加 `name("MultiCurrencyEconomy").optional(true)` softdepend 保序。真机(Paper1.20.1+mce)重启后 WARN 消失、监听器注册成功。
- **经济写动作透传操作者身份进 mce 流水(对接 JM FR-121,真机修)**:`EconomyProvider` 七个写动作原用默认 `OperationContexts.system()`,致 mce 审计流水操作者恒 `SYSTEM`、JM 管理员身份无法落到插件流水。改为从 payload 取 JianManager 注入的 `operator`/`nodeId`,经 `EconomyEnvelope.operationContext` 构造 `OperationContexts.of`(PLUGIN 类型、operator=管理员、sourceAction=`economy.<动作>`、nodeId 入 metadata 供追溯),operator 空回退 system()。真机验:CP 写路径下发后 mce 流水 `操作者=<管理员>`(非 SYSTEM)。补 operationContext 单测。

### 变更
- **JSON 编解码统一收口到可换适配器**(ADR-14):新增 `core/json` 的 `JsonCodec` 接口 + `Json` 门面 + 默认实现 `ConfigJsonCodec`(后端复用运行时 TabooLib `Configuration` / nightconfig,**零额外依赖**)。插件桥(删手写 `MiniJson` + `escapeJson`)、Webhook 告警、指标历史 / 启动画像落盘的 JSON 序列化与解析全部改走 `Json.encode` / `parse` / `decode`,换库(gson 等)只换实现、调用点不动。同时修正 ADR-10「优先用 gson」的事实偏差(gson 仅在构建期、运行期不携带)。火焰图查看器数据(递归树 / 瀑布数组、只构造不解析、绑定前端格式且性能敏感)为有据例外保留专用手拼。真机:Paper 1.21.1 插件桥连接握手 + 在线名册 + 踢人端到端通过;`./gradlew build` 编译 + 单测 + detekt 全绿。
- **`api` 模块改用纯 Java(Lombok 不可变模型)**(ADR-13):消除 Kotlin `@Metadata`,使任意 Kotlin(含 1.x)/ Java 版本第三方均可编译依赖 `serverprobe-api`。落盘字段名 JSON 不变(向后兼容既有 `data/`);内部对 api 模型的构造改用 builder。编译 + 单元测试 + detekt 已绿;**TabooLib 序列化落盘/读盘往返待 1.21.4 Paper 真机复验**。

## [0.1.0] - 2026-06-20

> 首个版本(本地 tag,未推送、无公开下载产物)。汇集启动剖析 + 运维指标 + 存储聚合 + 四通道呈现 + 告警 + 开放接口,以及可选启动期 premain agent 增强。`./gradlew build` 编译 + 单元测试 + detekt 全通过,并已在 **1.21.4 Paper 单端真机验证全通过**;其他端(1.8 / Folia / BungeeCord)构建通过、实验性未逐一真机。核心**零新增第三方依赖**,**仅可选启动 agent 引入唯一新依赖 ASM**(需手动 `-javaagent` 启用,relocate 隔离)。

### 新增
- 初始化项目骨架:`api` / `core`(`project:core`)/ `plugin` 三模块目录与基础构建配置(当前源码目录为空,从零开发)。
- 新增开源许可证 [`LICENSE`](LICENSE):**MIT License**。
- **M1 代码实现(P1–P9;编译 + 单测通过,真机验证待进行)**:
  - **多平台骨架**:Bukkit 系 + BungeeCord 单 jar 多端;env 按模块下沉;IOC(`@Service`/`@Inject`)装配。
  - **启动剖析(FR1)**:端到端总时长 + 各生命周期分段 + 逐插件启用间隔 + 启动画像 + 与上次对比。
  - **JVM 指标(FR2.1)**:堆/非堆/内存池/GC(明细 + young·old)/线程 + 死锁/类加载/CPU/uptime/jvmArgs。
  - **服务器 TPS/MSPT(FR2.2)**:多版本(Paper API / 低版本 NMS 反射 / 自采样)+ Folia 全局 N/A;MSPT p95/p99。
  - **`/probe` 命令(FR4.1)**:health/startup/tps/gc/world/proxy 六子命令 + 中英 i18n + 只读 API(FR8.1)。
  - **本地文件落盘(FR1.5)**:启动画像 JSON(TabooLib `Configuration` 序列化)+ `config.yml` 配置。
  - **代理端基础**:总在线 + 各子服在线(BungeeCord)。
  - 留 M2+:世界指标完整(FR2.3)、代理 ping/路由(FR2.5)、Prometheus(FR4.2)、Web(FR4.3)、告警(FR5)、Incision(FR7)。
- **M2 代码实现(M2-1~6;编译 + 单测 + 1.21.4 Paper 单端真机验证通过,零新增依赖)**:
  - **世界 / 实体采集(FR2.3)**:按世界统计已加载区块数、实体数、方块实体数,并可按实体类型给出分布;开销较高故独立限频采样(默认约 30 秒)、采样后缓存。Folia 走路线 1——仅给出区块数,实体 / 方块实体计数置 N/A。`/probe world` 由占位升级为真实数据。
  - **指标聚合(FR3.3)**:对近 N 份快照做跨快照统计——TPS 滑窗均值、MSPT 跨快照分位(p95 / p99)、GC 差分速率(年轻代 / 老年代的次数与耗时速率);`/probe tps` 增加"近期聚合"补充行。
  - **历史 JSONL 落盘(FR3.2)**:把每次采集的快照按实例分目录、按自然日滚动追加为 JSON Lines(`data/metrics/<实例>/metrics-<yyyyMMdd>.jsonl`);保留天数 + 总体积双闸清理,绝不删除当天文件。与内存环形缓冲、启动画像归档相互独立。
  - **开放接口扩面(FR8)**:`ProbeReadApi` 新增历史区间查询与聚合查询;`MetricStore` SPI 新增 `readStartupProfiles` / `readHistory` / 批量 `appendHistory`(均带默认实现,旧实现向后兼容);并提供 `ServerProbeApi` 静态门面便于第三方取用。
  - **Prometheus `/metrics`(FR4.2)**:基于 JDK 内置 `HttpServer` 零依赖实现;Bukkit 与 BungeeCord 各为独立进程、各起一个端点;鉴权采用 token(`Authorization: Bearer`)+ IP 白名单双重校验,安全默认为关闭且仅本机。指标统一前缀 `serverprobe_`,涵盖 JVM / 服务器 / 世界维度。
  - **告警引擎(FR5)**:内置枚举规则(TPS 偏低 / 过低、MSPT p95 过高、堆占用率过高、死锁)+ 防抖(持续 N 周期才触发)与恢复状态机;三通道输出——日志 / 游戏内 / Webhook(Webhook 走 JDK `HttpURLConnection`)。安全默认为关闭。
  - 坚持"探针不成事故源":各导出 / 落盘 / 告警通道全异步或限频、失败静默降级,真机印证 Prometheus 端口被占用时优雅降级、不影响插件启用。
  - 留 M3+:Web 面板(FR4.3)、CPU 采样归因(FR2.6)、Incision 方法级插桩(FR7)。
- **M5 启动期 agent 增强(可选,需手动 `-javaagent` 启用;1.21.4 Paper 单端真机验证通过)**:
  - **形态——二合一 jar**:同一个 `ServerProbe.jar` 既是 `plugins/` 插件、又可作 `-javaagent`;启动命令加 `-javaagent:plugins/ServerProbe.jar` 启用,**不加则纯插件模式照常工作**,功能完整。
  - **premain 注入服务器启动流程**:由 JVM 在 `main` 之前经标准 `premain` 入口加载,**不是被 ADR-1 否决的运行时 self-attach**,不受 JEP 451 限制(Paper + JDK21/24 零警告)。专补 ServerProbe 自身加载前的盲区(FR1.7)。
  - **逐插件精确耗时**:premain `ClassFileTransformer` 插桩 Bukkit `SimplePluginManager`,纳秒级 load/enable 计时,优于日志解析的秒级,且覆盖本插件之前加载的插件(真机:ServerProbe onEnable 精确 0.3s vs 日志 1.0s)。
  - **库下载耗时**:插桩 `LibraryLoader.createLoader`(1.17+),量化插件依赖在线下载耗时。
  - **扩展插桩点(世界/配置/事件/命令)**:新增 `CraftServer.createWorld`(世界创建,按 `/CraftServer` 后缀匹配兼容含/不含版本号包名)、`YamlConfiguration.loadConfiguration`(配置加载)、`SimplePluginManager.registerEvents`(事件注册)、`SimpleCommandMap.register`(命令注册)四个 hook,逐项纳秒级计时;`/probe startup` 与启动日志按 Top-N 呈现配置加载/事件注册/命令注册耗时,世界耗时由 agent 实测回填(不再恒为 0)。
  - **多线程折叠栈采样**:对 `Server thread` / `Netty Server IO` / `ServerMain` 等关键线程 5ms 周期抓**完整调用栈**并以折叠栈(folded stack)聚合,保留父→子调用关系;主线程扁平热点榜由其派生。
  - **启动火焰图 + 嵌套时间线(`/probe flamegraph`)**:由折叠栈生成**真正的多层、多线程火焰图**(宽度=调用路径采样占比、纵轴=调用深度,支持线程切换/缩放/搜索),并由逐事件时间线生成**按区间包含关系分泳道的嵌套时间线**(父区间含其内部 register/config 子区间);输出为**自包含 HTML**(CSS/JS 全内联、无 CDN 依赖)到 `data/flamegraph/`。此火焰图**专注启动期**(premain 阶段 spark 难以介入),与"运行期 CPU 归因建议并用 spark"的既定方向不冲突。
  - **更精确的统计 + 不成事故源**:时间线时刻取真实出口 `nanoTime`(纳秒级、相对 premain),不再用毫秒反推;`/probe startup` 慢插件榜在挂载 agent 时择优用 agent 实测 onEnable;引入**启动窗口**标志,插件就绪即关闭采集,杜绝被插桩方法在运行期持续追加导致的内存泄漏。
  - **HTTP/TCP 对外网络外呼监控(运行期常驻)**:插桩 `sun.net.www.protocol.http.HttpURLConnection.getInputStream`(覆盖 HTTP/HTTPS,含 TabooLib 依赖下载)与 `java.net.Socket.connect`(原始 TCP 兜底,去重),记录**哪个插件/哪段代码**发起了对外请求、目标 URL、响应码、耗时、(脱敏的)请求头与查询串;实时中文日志 + 落盘 `data/http/` + `/probe http` 回看 + 启动期外呼并入 `/probe flamegraph` 报告。**安全**:Authorization/Cookie/token 等敏感头与敏感查询参数由 agent 侧打码为 `***`,请求体不捕获;**不成事故源**:注入极简、逻辑全程 `try/catch` 兜底(经真实 JDK 类 + ASM `CheckClassAdapter` 校验合法),有界环形缓冲防泄漏,可配开关/限频。这正是定位"开服卡在依赖下载"类隐形瓶颈的利器(对应本轮发现的 TabooLib 下载卡顿)。
  - **唯一新增第三方依赖 = ASM**(relocate 到 `...agent.shadow.asm` 隔离);跨 ClassLoader 通道经 `appendToBootstrapClassLoaderSearch` 把极薄的 `ProbeAgentBridge` 放 bootstrap CL,供插桩字节码 / 插件反射 / 栈采样共享同一份数据;premain 顶层 `catch(Throwable)` 兜底,失败静默降级,绝不崩 JVM。
  - **范围(诚实)**:M5 先 Bukkit 端;Folia 主线程栈采样降级标 N/A(无单一主线程,引导用 spark),插件计时复用 Bukkit 路径;BungeeCord 推迟。**仅 1.21.4 Paper 单端真机验证,其他端(1.8 / Folia / BungeeCord)未逐一真机。**
  - 详见架构文档 ADR-11 / §13。

### 变更
- 确立技术选型与技术决策(详见架构文档 ADR-1 ~ ADR-11):
  - **探针主体 = 纯 API + JMX(`java.lang.management`)+ 平台原生 API + 采样**,主体不用 Java Agent、不裸写 ASM(只读优先,绝不成为事故源);被否决的是**运行时 self-attach**,而非启动期命令行 premain(后者作为 M5 可选增强补加载前盲区,见 ADR-11)。
  - **字节码插桩 / Incision 仅作架构预留**:首期不启用,引入前必须先 PoC 验证,默认关闭、失败静默降级。
  - **核心 Java 8 字节码**:保证产物可被 1.8–1.21.x 所有 JRE 加载;仅必须直接引用高版本 NMS 类型的胶水才独立抬 toolchain。
  - **多版本 + 多平台方案**:依赖 TabooLib `MinecraftVersion` / `nmsProxy` / `@PlatformSide` 三件套,覆盖 Bukkit 系 1.8–1.21.11(含 Folia)+ BungeeCord,**单 jar 多端**;遵循"先通用,跑不通再拆胶水"原则。
  - **Folia 适配**:调度统一走 TabooLib `submit`(零胶水);TPS/MSPT 抽象 `ServerTickSampler` 接口多实现;实体/区块用 `callRegion{}` 逐区域采集。
  - **存储**:本地文件落盘(JSON/JSONL,原子写入,可配滚动与保留),不依赖数据库;提供读取 API + 存储 SPI 开放接口。
  - 技术栈:Kotlin 2.1.0 / TabooLib 6.3.0 / taboolib-ioc 0.0.5 / 本地文件存储 + 开放接口;groupId `top.wcpe.mc.plugin.serverprobe`。

### 文档
- 新增产品需求文档 [`docs/PRD.md`](docs/PRD.md):明确背景与目标(开服慢可量化定位、统一运维探针、全版本多平台)、兼容性矩阵、功能需求(FR1–FR8)、非功能需求、数据模型与迭代规划(M1–M4)。初稿日期 2026-06-08。
- 新增架构文档 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md):明确分层架构(`plugin → platform-* / nms-* → core → api`)、多版本兼容机制、多平台架构、JDK/Toolchain 策略、Folia 适配、核心数据流、启动剖析机制、运行时装配与关键技术决策记录(ADR)。
- 新增本更新日志 `CHANGELOG.md`。
- **引入 SDD(规格驱动开发)治理脚手架**:新增 `.claude/rules/`(架构不变量 + 范围/决策/文档/质量/静态检查等防漂移规则)、`docs/adr/`(ADR-1~12 由 `ARCHITECTURE.md` 内联迁移为独立文件 + 索引,`ARCHITECTURE.md` §11 改为索引)、`docs/API.md`(对外接口契约)、`docs/OPERATIONS.md`、`SECURITY.md`、`docs/CONTRIBUTING.md`、`docs/specs/`、`.github/` 模板与 `.editorconfig`;`PRD.md` §7 增"功能交付状态总览"。**仅新增治理文档,未改动任何业务代码**;现有能力的交付状态见 [PRD §7.0](docs/PRD.md)。
- **关键决策敲定(同步至 Wiki 各页)**:将原"待定 / 开放问题 / 产品定义未定"的若干议题更新为明确结论。
  - **Folia TPS/MSPT 呈现**:采用 **per-region 明细 + 全局标 N/A**;分阶段实施 —— M1 先全局标 N/A,M2/M3 补 per-region 明细(原"标 N/A / 聚合 / per-region 待定")。
  - **依赖策略**:**允许按需引入轻量依赖,但每个新依赖需逐个确认**(原 Prometheus / Web 服务依赖"是否引入属开放问题")。
  - **CPU 热点 / 火焰图**:**不自研**,建议并用 [spark](https://spark.lucko.me);ServerProbe 专注指标监控 + 启动剖析(原"是否自研火焰图属开放问题")。
  - **代理端(BungeeCord)**:**各端独立采集与展示,暂不与后端联动汇总**(不引入 Porticus 等跨服汇总组件)。
  - **实例标识 `serverId`**:由实例**自动生成**,可在配置中以 **`server-name`** 覆盖为自定义值;本地文件**按实例分目录存放**(原"多服共存 serverId 标识规则属开放问题")。
  - **开源许可证**:选定 **MIT License**(原"待定")。

---

## 规划路线(Roadmap)

> 0.1.0 已发布(含启动剖析 + JVM/服务器/世界指标 + 存储聚合 + 命令/Prometheus/告警 + 开放接口 + 可选启动 agent)。以下为后续计划版本,与 PRD §10 迭代规划(M3–M4)对应;版本号为初步规划,实际发布时以最终实现为准。

### [0.2.0] - 计划中:M3 Web 面板 + 运行期 CPU 采样归因 + 多端真机补齐

- **Web 面板(FR4.3)**:启动画像详情、历史趋势、(可选)火焰图;需鉴权 + 绑定地址。
- **运行期 CPU 采样归因(FR2.6)**:`ThreadMXBean` 周期采样栈,按插件 ClassLoader 归并(spark 模式,无 agent),给出各插件运行期 CPU 占比。
- **多端真机补齐(FR6 / FR2.5)**:1.8 / Folia / BungeeCord 真机验证;Folia per-region TPS/MSPT + `callRegion{}` 实体采集;代理端 ping / 路由 / 每玩家 ping。

### [0.3.0] - 计划中(可选):M4 Incision PoC + 方法级归因

- **Incision PoC(§5.5)**:在目标 Paper + 目标 JDK 上验证 self-attach / JVMTI 兜底能否织入、开销与可回滚性。
- **方法级精确归因(FR7)**:验证通过后启用,用于 `enablePlugin` 精确插桩、特定事件/方法耗时;**默认关闭**,失败静默降级。

[未发布]: https://github.com/
