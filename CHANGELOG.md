# 更新日志(Changelog)

本项目所有重要变更均记录于此文件。

本文件格式遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [语义化版本(SemVer)](https://semver.org/lang/zh-CN/)。

> 最新版本 **0.7.0(2026-09-22)** 已正式发布；推送版本标签后由 GitHub Actions 构建并附加发行 jar。

---

## [未发布]

### 修复

- **FR-29 告警历史从未落盘（真机验收推翻发布说明）**：`AlertHistoryChannel` 交付时漏了自注册——`AlertChannelRegistry` 不做类型扫描，各通道须在 `@PostConstruct` 里 `registry.register(this)`，日志 / Webhook / 游戏内三通道都有、唯独它没有；编译 + 单测 + CI 全绿，但真机上事件永远到不了历史通道，`data/alerts/` 永不生成、`/probe alerts` 恒回"暂无告警历史"。修复：补齐 `@Inject AlertChannelRegistry` 与 `@PostConstruct register()`，并新增 `AlertHistoryChannelTest`（锁定"每个内置通道都有自注册入口" + 注入真实注册中心后确实进入广播清单），用例经"摘掉注解即红、恢复即绿"验证有效性。
- **FR-29 Old GC 频繁规则恒不触发（真机验收推翻）**：差分基准 `previousSnapshot` 原在逐条规则判定内前移，首条规则判完基准即被写成当前快照，其后每条速率类规则的时间差恒为 0 → 差分恒 N/A。生产规则序里 `gc-old-high` 排第 6，真机上老年代 GC 速率持续约 0.48 次/秒（阈值 0.05）达 12 个采集周期仍静默不触发；单测漏网是因它把差分规则放在列表首条。修复：基准在整轮判定期间恒为上一次采集快照、全部规则判定完才整体前移；`AlertEngineTest` 新增"差分规则排在首条之后仍按真实差分触发"回归用例（旧行为下转红）。
- **`/probe alerts` 阈值显示失真（真机验收发现）**：数值格式化原为"非整数一律保留 1 位小数"，把 Old GC 默认阈值 0.05 显示成 0.1（2 倍失真），而这一列正是运维据此调参的依据。改为按至多 3 位小数四舍五入后去尾随零（0.05 → 0.05、0.20036064… → 0.2、整数仍省小数）；原单测把 0.05 → 0.1 断言成了预期，等于把缺陷固化为契约，一并改为如实呈现。

## [0.7.0] - 2026-09-22

### 新增

- **FR-29 告警规则扩面与告警历史**：`AlertType` 新增 `GC_OLD_HIGH`（老年代 GC 次数速率，跨采集周期差分、与聚合器 GC 速率同口径；首采/计数回绕期间 N/A 不判定）与 `STARTUP_SLOW`（最近一次启动总时长超阈值即告警，数据源为内存画像、代理端无画像恒 N/A；绝对秒数阈值取代 wiki 曾宣称的"×1.5 倍数"——首启场景无基线集合可参照，绝对秒数语义更直接）。**告警历史落盘**：新增 `AlertHistoryChannel`（以既有 `AlertChannel` 广播链接入，零额外线程；事件 JSONL 按日落盘 `data/alerts/`，保留天数沿用 `history-file.retention-days`、当天文件绝不删，异步写不阻塞采集）；新增 `/probe alerts` 子命令（由新到旧至多 10 条，含时间/级别/动作/类型/值/阈值）。wiki 曾宣称的"Old GC 频繁/启动超基线"两条规则由本 FR 做成真（#27 纠偏后回填）。单测 6 条新增（差分/回绕/时间倒退/换算/引擎差分+防抖组合）。issue #25。规格见 [alert-extended](docs/specs/alert-extended.md)。
- **FR-28 代理端 MCP 日志检索**：新增 `BungeeLogPathProvider` 与 `VelocityLogPathProvider`，补齐 FR-16 代理端欠条——MCP `log_tail`/`log_search` 此前在 BungeeCord/Velocity 恒返回"平台不支持"，而代理端无 TPS/世界指标、日志是其最主要排障线索。BungeeCord 日志为工作目录根下 `proxy.log`，Velocity 为 `logs/latest.log`（e2e 实测运行目录实证；3.1.1–4.x 共享源码无差异）；字符集取 `file.encoding`（Windows 低版本常为 GBK）；路径经 core 既有规范化前缀校验与文件缺失结构化降级。单测 4 条（两平台路径解析与字符集）。issue #24。规格见 [proxy-log-tail](docs/specs/proxy-log-tail.md)。

## [0.6.0] - 2026-09-22

### 新增

- **FR-27 Grafana 看板与 Prometheus 告警规则随发行提供**：新增 `grafana/` 目录（随仓库/Release 分发，**不进发行 jar**）——`dashboard.json`（24 面板：总览四指标卡、JVM 内存/GC/线程死锁、TPS/MSPT/世界/ping 分布、运行期 CPU 归因 Top、启动画像（FR-25）、代理端与脱敏网络流量；数据源与实例变量化）+ `alerts.yml`（5 条规则模板，口径与探针内置 FR-05 告警一致：TPS<18/<15、MSPT p95>50ms、堆>90%、死锁≥1）。指标名以 API.md §5.4 为唯一真源（脚本校验 26 个引用指标全部在册）；`docs/OPERATIONS.md` 新增 §5.1「接 Grafana」。纯产物零代码变更。issue #23。规格见 [grafana-pack](docs/specs/grafana-pack.md)。
- **FR-26 只读 API 暴露历史指标回读**：`ProbeReadApi` 新增 `historySnapshots(sinceMs, untilMs, limit)`——从历史后端（本地文件/第三方存储 SPI）按闭区间时间范围读取落盘快照，由新到旧至多 `limit` 条。实现委派既有 `MetricStore.readHistory`（零新增存储逻辑）；接口以 **default 方法**落地（默认空列表），既有第三方实现零改动、二进制兼容（与 `queryNetworkPackets` 同款范式）。Javadoc 明确"**可能读盘、调用方宜在异步上下文调用**"。issue #22。规格见 [readapi-history](docs/specs/readapi-history.md)。
- **FR-25 启动画像指标进 Prometheus**：`/metrics` 新增启动画像区块——`serverprobe_startup_total_seconds`（端到端启动总耗时）、`serverprobe_startup_plugin_seconds{plugin}`（逐插件 onEnable 耗时，画像慢插件榜口径）、`serverprobe_startup_world_seconds{world}`（逐世界加载耗时），时间毫秒→秒换算。数据源为进程内最近一次启动画像的**内存值**（`StartupProfileHolder`），刻意不走落盘回退——`/metrics` 跑在请求线程上，读盘违反"请求线程禁止阻塞 IO"红线；画像未产出或代理端（无画像生产者）时整区块跳过、输出与既往版本逐字节一致。issue #21。规格见 [startup-metrics](docs/specs/startup-metrics.md)。

### 修复

- **`historySnapshots` 排序契约与实现不符（code review 发现，0.6.0 未发布前修正）**：公开 API 承诺"由新到旧"，但底层 `MetricStore.readHistory`（本地文件实现）按时间升序遍历并随收随截，实际返回**由旧到新且保留最旧的 limit 条**——第三方按文档消费会拿最旧数据当"最新趋势"。修复：`MetricStore` 新增带排序语义的 `readHistoryLatestFirst` default 方法（默认反转兜底，既有第三方实现零改动），本地文件实现**覆盖**该方法（从最新日期文件倒序遍历、单文件内取尾部命中行，兑现"保留最新的 limit 条"），公开 API 改委派该方法。同步修正测试错位：委派层测试改用真实 `ProbeReadApiImpl` 实例（此前用测试内复刻桩，生产实现零覆盖）。

### 文档

- **文档与实现漂移修正（#27）**：`API.md` `/probe proxy` 由"子服 ping/路由规划中未实现"改为已交付（v0.3.0 起）、Web 面板"只读三页"改为四页（补唯一展示完整 IP/载荷的网络取证页及其敏感数据提示）、`ServerMetrics` 字段表补 `pingDistribution`/`observedRegions`/`observedRegionWorlds`（与 api 模型实查一致）、补 `web.*` 配置键指引；wiki `Data-Output.md` 告警规则由宣称的 6 类纠正为实际的 4 类（"Old GC 频繁/启动超基线"两项将由 FR-29 做成真后再回填），补 Grafana 告警模板指引；`built-in-integrations.md` 移除代码中不存在的 `BusinessProviderFactory` 表述，改为实际的 `@Service + @PlatformSide + @PostConstruct/@PreDestroy` 发现注册机制；**`config.yml` 补 6 个代码实际读取但此前未出现在默认配置的键**（`history-file.archive-days`、`agent-stack-max-samples`、`http-monitor.file-retention-days`/`file-archive-days`、`mcp.audit-max-file-mb`/`audit-retention-days`），键名经脚本与 `ProbeConfig` 常量逐一核对一致，注释含用途/取值/默认值/影响（config-files 规范）。

## [0.5.1] - 2026-09-21

### 安全

- **`/probe mcp` 的“仅控制台”门可被命令方块绕过**：门原先只拒绝玩家（`ProxyPlayer`），一切非玩家发送者都被放行——命令方块 `CraftBlockCommandSender` 的 `isOp()` 在 1.16.5 上恒真，等于把 JVM 完整控制权（类重定义、任意代码求值）交给红石信号。改为**控制台类型白名单**（Bukkit 控制台/RCON、Bungee 控制台、Velocity 控制台，按底层发送者类型匹配），白名单外一律拒绝（失败关闭）。真机 e2e 新增“命令方块替身必须被拒绝”断言（修复前 FAIL、修复后 PASS）。issue #20。

### 修复

- **背包基础属性写入缺字段静默回退默认值**（#14）：`decodeBasicAttrs` 对 `base`/`edited` 中缺失或类型不符的字段静默取 0.0/0/`SURVIVAL`，下游按净改动落盘即把在线玩家属性写坏（有事故先例）。现要求六项属性齐全且可解析（兼容嵌套/扁平与字符串/数值两种承载），缺失即拒绝并点名；`gameMode` 不再回退 `SURVIVAL`。
- **JDK 9+ 上进程 CPU 指标恒为不可用**（#15）：`JmxSupport` 原先从 MXBean 实现类（JDK 9+ 位于未导出包 `com.sun.management.internal.*`）取方法并 `setAccessible`，被模块系统以 `InaccessibleObjectException` 拒绝后静默返回哨兵 -1.0（JDK 21 实机复现），`/probe health` 与 Prometheus `process_cpu_load` 均按“JDK 不提供”降级。改为经**导出接口** `com.sun.management.OperatingSystemMXBean` 取方法（不放开访问），异常兜底与 -1.0 语义保持不变。
- **CPU 归因占比随运行时间单调衰减**（#16）：窗口滑动淘汰最旧一轮时只回退各插件计数、累计总样本数只增不减，占比被全生命周期分母稀释（默认窗口 60 轮，运行 1 小时后约压缩 60 倍）。现淘汰时同步回退分母，`/probe cpu`、Prometheus `serverprobe_plugin_cpu_percent`、MCP `plugin_cpu` 三处出口恢复“窗口内占比”口径。
- **插件类归属把 JDK/服务端类误归给插件**（#13）：`PluginClassLoaderRegistry` 原按“能加载即归属”判定，而插件加载器是父委派——`java.*` 等服务端帧被稳定归给迭代顺序靠前的插件。现要求类由该加载器（或其子加载器）**定义**才算归属，`plugin_threads`/`plugin_cpu` 的归属数据从“按哈希顺序分配”的噪声恢复为真实归属。
- **启动画像抽稀丢整线程组、主线程热点取错线程**（#17）：`decimateStacks` 原按等距下标抽取**线程组**（默认上限 10，线程数超限即整组丢弃，主线程常被丢），且热点榜用抽稀后数据计算、可能取到 worker 线程。现抽稀下沉到线程内折叠栈（每线程至少保留 1 条、线程组不丢），热点榜改用抽稀前全量样本。
- **Arthas 运行时启动期在主线程同步加载**（#18）：`mcp.enabled=true` 时 `@PostEnable` 同步执行运行包解包与 Instrumentation 附加（attach helper 子进程等待最长 30s），违反“主线程禁止阻塞磁盘 IO / 外部进程”红线（命令路径早已异步化，启动路径漏修）。现改经 `submitAsync` 异步加载；卸载后置位标志拒绝加载，避免异步任务晚于卸载被调度而残留线程阻止 JVM 退出。
- **MCP 日志工具输出被静默截断为 256 条**（#19）：`log_tail`/`log_search` 声称上限 2000/1000，但响应写入器对任意数组一律截断到 256 且不产生标记，超出部分静默消失；`log_search` 的 `nextOffset` 还会越过被丢弃的命中行，后续分页再也取不回。现把两者上限钳到与写入器一致（256）并同步工具描述与规格——钳到同一上限后恒不触发截断。

### 真机验收（0.5.1）

本机复跑真机矩阵 **31 个场景全部 PASS**（唯一判据为 `build/mc-testkit/results/<场景>.properties` 的 `status=PASS`）：

- **安全项由红转绿**：Paper 1.20.1（Java 21）`mcp-diagnostics-paper-java21` 新增的“命令方块替身必须被拒绝”断言修复前 FAIL、修复后 PASS。
- **平台矩阵（11）**：Paper 1.8.8 / 1.12.2 / 1.16.5 / 1.17.1 / 1.18.2 / 1.19.4 / 1.20.4 / 1.21.1 / 26.2（MC 新版本号方案，JDK 25）；Spigot 1.8.8 / 1.16.5。
- **MCP 诊断（8）**：Paper、Paper(Java 21)、Spigot、Folia、Java 8（Paper 1.16.5 + 瘦 agent）、BungeeCord（经代理）、Velocity（经代理）、Velocity(Java 25，经代理）。
- **网络取证（5）**：Bukkit、Paper、Folia 直连与经代理的 BungeeCord / Velocity。
- **其它（7）**：read-api、storage-spi、bridge-fixture、folia-observed-regions、velocity-matrix v3.1 / v3.5 / v4.1。
- **JDK 21 实测** `JmxSupport.processCpuLoad()` 恢复真实取值（修复前恒为哨兵 -1.0）。
- `./gradlew build`（构建 + 单元测试 + detekt；IoC 静态诊断无 error）全绿。

未纳入本轮复跑：4 个 `integrations-*` 场景需三个真实业务插件构件（`SERVERPROBE_E2E_{CORELIB,MCE,AIS}_JAR`，本机缺 AllinInventorySync 构件）——按仓库既有口径它们属“本地验收”、不进 CI；缺陷 #14 的回归由单元测试覆盖。

## [0.5.0] - 2026-09-21

### 新增

- **FR-24 MCP 控制面运行期两级开关**：新增控制台命令 `/probe mcp <on|off|status>` 运行期起停 MCP 端点、`/probe mcp arthas <on|off>` 运行期加载/卸载内嵌 Arthas 运行时。端点级开关起来后原生工具（状态/命令执行/线程转储/日志检索/插件与玩家诊断）立即可用、不付 Arthas 代价；Arthas 级开关才执行闭包解包与 Instrumentation 附加。**仅控制台/RCON 可执行**（开启 MCP 等于授予 JVM 完整控制权，游戏内即使持有权限节点也拒绝，防游戏内管理员提权）；开关只作用于当前运行期、不写回 `config.yml`，重启回到配置声明的姿态。`mcp.enabled` 的启动期语义保持不变（`true` 时仍自动开端点并加载 Arthas）。见 [ADR-0028](docs/adr/0028-mcp-runtime-toggle.md) 与 [spec](docs/specs/mcp-runtime-toggle.md)。
- 新增 core 契约 `ArthasRuntime`（运行期启停）与装配点 `ArthasRuntimeRegistry`，与既有 `ArthasControl`（已加载运行时内的任务执行）职责分离；诊断模块经内部转发实现自注册，`core` 编译期不依赖任何 Arthas 类型（维持 ADR-0025 边界）。
- **CI 质量门禁补齐**：新增字节码门禁（校验各业务模块与发行 jar 内 class 的 major version 不超过 52，保护“编译为 Java 8 字节码”这一关键不变量，此前完全无自动化保护）、OSV-Scanner 依赖漏洞扫描、Dependabot 周更新、CodeQL 代码扫描，以及 Windows runner 交叉验证 job（历史上出现过平台相关缺陷在 Windows 上被掩盖）。依赖扫描起步设为 continue-on-error 以先收集基线；CI 与字节码门禁另支持手动触发。
- **覆盖率统计**：在 `serverprobe.base` 约定插件中统一应用 JaCoCo，一处生效覆盖全部业务模块；CI 新增独立 coverage job 只产出报告、不设任何阈值（先积累真实基线再讨论阈值）。当前基线：行覆盖 50.4%、分支覆盖 36.1%。
- **E2E 真机验收基建**：新增独立的 E2E 真机验收工作流（`.github/workflows/e2e.yml`），手动触发（`scope=all|batch1`）与推送 `v*` 标签时运行，在 ubuntu runner 上由 mc-testkit 拉起真实 Paper/Spigot/BungeeCord/Velocity 服务端并断言场景结果文件（以 `build/mc-testkit/results/<场景>.properties` 的 `status=PASS` 为判据——gradle 退出码不能证明服务端内的断言通过），覆盖 8 个无 bot、不依赖外部闭源插件的场景；依赖 bot 与真实业务插件的场景仍留本地验收。场景闭包（reflex、TabooLib）改为**构建期解析**并按需预置离线包，不再读本机 Gradle 模块缓存 / 插件缓存，干净机器与 CI 结果一致；引入 mc-testkit 0.10.0、改用其运行目录公开契约并固化 `javaVersion` 矩阵实测。同期消除两处偶发失败（Arthas `watch`/`trace` 与 Arthas 懒初始化赛跑、`ArthasTaskManager` 终态断言对线程池调度的时序依赖）、补上 `prepareE2e*` 缺失的 network-bukkit harness 构建依赖、恢复被误判为场景缺陷而移除的 Spigot 场景。

### 变更

- **构建与依赖维护**：Gradle wrapper 8.9 → 9.7.1；随发行 jar 内嵌的 SQLite JDBC 驱动 3.53.2.1 → 3.53.4.0（版本抽到 `gradle.properties` 的 `sqlite.jdbc.version` 集中管理）与 agent 插桩闭包 ASM 9.7.1 → 9.10.1（relocate 隔离）；构建期 Lombok 1.18.34 → 1.18.48；测试侧 JUnit 5.11.4 → 5.14.4（按 JUnit 5.12+ 要求显式声明 `junit-platform-launcher`，否则 Gradle Test Executor 起不来）与 netty-transport 4.2.17.Final → 4.2.18.Final。移除未使用的 KSP 插件声明；Dependabot 忽略规则收敛到需人工判断的坐标（NMS 版本锁定 / 私有 fork / 服务端 API / JUnit 与 Kotlin 插件主版本 / detekt），并移除曾加的 Gradle 主版本忽略以接收 wrapper 9.x。均为维护性升级，发行 jar 行为不变。

### 修复

- **MCP 控制面关闭时的残留守卫**：`McpControlPlane` 的停止逻辑原先依赖 `httpServer != null` 判断，启动中途失败（端口占用等）时 `artifacts` 已创建并注册但守卫会跳过清理，留下幽灵工作区注册；改为无条件走停止路径。同时为启停路径加锁（`start`/`stop`/`enable`/`disable`），避免运行期开关引入的命令线程与生命周期线程并发访问四个无保护句柄字段。
- **运行期开关阻塞主线程（真机发现）**：四个启停动作都含阻塞操作——端点级要建目录并按保留期/容量清理工件（最多扫 10 GiB）；Arthas 级要解包约 20 MB 闭包，且 Instrumentation 附加可能 spawn helper 子进程并等待其退出（最长 30 秒）。若在命令主线程执行会冻结服务器（违反项目红线）。已改为经 `submitAsync` 异步执行后回执；纯内存读取的 `status` 保持同步以便立即回显。
- **并发下的开关幂等误报（真机发现）**：命令层"先查状态再调用"的写法在多条命令排队时失效——都在预检时看到未加载/已加载，导致重复 `arthas on` 复述上一次 attach 结果、连续 `arthas off` 把空操作回报成"卸载成功"、并发 `mcp on` 把已监听的端点在无人使用的情况下重启。已把幂等判定收敛到实现内部（`ArthasRuntime.startRuntime()` 返回"无需重复开启"说明、`stopRuntime()` 返回是否确实卸载、`McpControlPlane.enable()` 内部判 `running`），命令层不再做竞态预检。
- **增强类命令后卸载导致服务器崩溃（Java 8 真机发现）**：执行 `watch`/`trace` 等会 retransform 目标方法的命令后再 `arthas off`，若未还原字节码，被插桩方法仍回调已释放的 Arthas 类，下一 tick NPE 崩服务器（崩溃点即被插桩方法，Java 8 + Arthas 3.1.1 实测 4/4 必现）。修复：`ArthasMemoryShellRunner.close()` 在拆除 bootstrap 与隔离加载器**之前**执行 Arthas `reset` 还原全部增强类；复位失败只告警并继续释放（否则线程与加载器永不释放、JVM 无法退出）。真机复验：7 次 `watch → off` 全部存活，0 次 NPE，`crash-reports/` 未生成，复位 WARN 0 次。
- **attach 失败时状态误报“已加载”（Java 8 真机发现）**：Java 8 纯 `-jar` 启动下 `com.sun.tools.attach.VirtualMachine` 不可见（位于 `lib/tools.jar`），两条 attacher 链均失败，但 `status` 仍报“已加载”，而 `arthas_execute` 实际 FAILED。原因是 `runtimeReady` 只判断“控制器已注册”（attach 失败也会注册）。修复：改为“控制器已注册 **且** Instrumentation 可用”；同时让重试可用——attach 失败时回收残留控制器，避免运维陷入“显示未加载、却被幂等拦截”而无法重试。

### 真机验收（FR-24）

Paper 1.20.1（Windows、Java 17）以 `mcp.enabled=false` 启动后**全程不重启服务器**：状态查询 → RCON 放行 → 端点开启（`tools/list` 42 工具零重复、`server_status` 无需 Arthas 即返回真实数据）→ Arthas 加载（helper 子进程注入成功，`version`/`thread` 真实输出）→ 卸载（工具降级、端点工具不受影响）→ **二次加载成功**（原设计主风险点，无需启用 ADR 记录的降级方案）→ 端口释放与重开 → 重复开关幂等。附加确认：5 轮 `arthas on/off` 后 `arthas-*` 线程全部清理、`ServerProbe-*` 线程恒定各 1 个；`mcp.enabled=true` 时启动期行为与既有版本一致。

---

## [0.4.0] - 2026-09-07

### 新增（FR-15~23 MCP 深度诊断能力扩展，同一分支并行开发）

- **FR-23 MCP 工具描述增强（接线地基）**：`McpJsonRpcDispatcher` 支持多 provider 组合（`List<McpToolProvider>` 按工具名路由），新增 `McpToolProviderRegistry` 供扩展 provider 注册；`McpTool` 增加 `usageExample`/`outputFields`/`workflow` 元数据，`tools/list` 为每个工具下发结构化中文描述（用途/参数/示例/异步工作流/输出字段/限制），外部 agent 仅凭描述即可正确调用。
- **FR-15 MCP 插件维度诊断入口**：`plugin_list`（已加载插件清单/元数据）、`plugin_classes`（按插件枚举可解析类，经 `JavaPlugin.file` 实例解析禁止路径拼接）、`plugin_threads`（按 `PluginClassLoaderRegistry.ownerOf` 过滤归属线程栈，≤128×64 有界）、`plugin_cpu`（复用 `CpuAttributionSampler` 窗口占比，未启用/零样本降级）。
- **FR-16 MCP 日志流式检索**：`log_tail`/`log_search`（尾部 N 行/关键字搜索 + 字节游标分页；路径规范化前缀校验防穿越；平台字符集解码；超长行截断且游标必前进；文件缺失结构化降级）。
- **FR-17 MCP 区块/实体明细**：`server_status` 的 worlds 输出增强 `entityTypeCounts`（复用 `BukkitWorldCollector` 缓存，不重复遍历）与 `regionStats`（Folia 透传 FR-12 `ObservedRegionMetrics`）；provider 缺失降级保持向后兼容。
- **FR-18 MCP 二进制产物安全回传**：`McpArtifactWorkspace.readBinaryChunk` + `artifact_read_binary`（Base64 编码二进制分块，默认 64 KiB/上限 1 MiB；与文本 `artifact_read_chunk` 并存；名称白名单防穿越）。
- **FR-19 MCP 补丁前自动备份**：`ArthasControl.dumpClassBytes` 接口扩面（默认 null=能力缺失→WARN+跳过+继续，读取失败→拒绝替换）；`arthas_redefine`/`arthas_retransform` 替换前自动备份 `backup_<类名转义>_<时间戳>.class`（扁平命名符合工作区白名单）；`artifact_backup_list`/`artifact_backup_restore`（恢复写回 `restored_` 供重新替换）。
- **FR-20 MCP 在线玩家诊断明细**：`player_lookup`（位置/血量/背包摘要无 NBT/区块/最近事件时间线；主线程周期缓存防阻塞，单周期上限 200；`BoundedEventRing` 有界事件缓冲）；**隐私红线**：默认开启随 MCP 开关、鉴权+审计参数 SHA-256、不落盘/不进 Prometheus/Web/历史；代理端结构化降级。
- **FR-21 MCP 运行期 CPU 火焰图**：`flamegraph_start`/`flamegraph_stop`（薄封装 `arthas_profiler`，默认命名 `flamegraph-<epochMillis>.html` 消歧）+ `flamegraph_view`（按扩展名分流：`.html` 文本分块 / `.jfr` 元信息提示走 `artifact_read_binary`）；**不推翻 ADR-8**（不自研采样器）。
- **FR-22 MCP GC/JFR 详诊**：`gc_events`/`gc_stats`（`GcDiff` 纯函数差分，计数/耗时回退钳制归零；请求驱动差分窗口）+ `jfr_start`/`jfr_stop`（经内嵌 Arthas，默认命名 `jfr-<epochMillis>.jfr`，产物配合 FR-18 回传）。

> 全部 9 条 FR 均完成**实现 + 单元测试 + detekt + IoC 静态分析（errors=0）**，`./gradlew build` 全绿；**真机验收**：Paper 1.20.1（Windows）与 WSL Linux（Paper 1.20.1 + Temurin 17）双平台真机通过，详见下方"修复"与验收记录。

### 修复（真机验收发现）

- **MCP 工具列表重复爆炸（真机）**：`McpJsonRpcDispatcher` 多 provider 组合下 `providers.values` 含同一实例的多个引用，`flatMap` 展开导致 `tools/list` 返回 256 个重复工具（26 工具 × ~10 次）。修复：`tools/list` 按 provider 实例 `distinct()` + 同名工具 `groupBy.last()` 去重，现 42 个工具零重复；补"同实例多工具不重复展开"回归测试。
- **MCP 工件工作区未注册（真机）**：`McpControlPlane.startServer` 创建 `McpArtifactWorkspace` 后未注册进 `McpArtifactWorkspaceRegistry`，导致 `artifact_read_binary`/`flamegraph_*`/`jfr_*`/备份等扩展工具返回"当前未启用 MCP 工件工作区"。修复：`startServer` 补 `register`、`stopServer` 补 `unregister`。
- **扩展 provider 构造参数注入失效（真机）**：`BinaryArtifactToolProvider`/`FlamegraphToolProvider`/`PrePatchBackupToolProvider` 以构造参数注入 `McpArtifactWorkspaceRegistry`/`ArthasControl`，真机 IOC 装配下与 `McpControlPlane` 注入的 registry 非同一实例。修复：统一改为**字段注入 + 构造参数仅测试注入**（与 `GcJfrToolProvider` 同款）。
- **MCP HTTP 服务 Linux backlog=0 连接挂起（真机）**：`McpHttpServer` 的 `BACKLOG=0` 在 Linux 上 accept 队列为空、连接超时（Windows 有默认值掩盖）。修复：`BACKLOG=64` 兼容两平台。
- **Arthas 闭包缺失 async-profiler 原生库（真机）**：FR-14 构建脚本仅从官方 bin zip 提取 4 个 jar，漏了 `async-profiler/libasyncProfiler-*.so`，Linux 上 `flamegraph_*` 报 "Can not find libasyncProfiler so"。修复：构建脚本提取 3 平台原生库入闭包；运行期提取补 `async-profiler/` 子目录布局（`ProfilerCommand` 按 core jar codeSource 相对定位）+ 无后缀 `libasyncProfiler.so`（`AsyncProfiler.loadLibrary` 的 `one.profiler.libraryPath` 属性，`ArthasMemoryShellRunner` 初始化 Bootstrap 前显式 `System.setProperty`）。WSL Linux 真机验证：`flamegraph_start/stop` 真实产出火焰图 HTML，`flamegraph_view` 分块取回完整。
- **WSL2 mirrored 网络模式破坏 loopback（环境结论）**：mirrored 下 WSL 内连 `127.0.0.1` 被 Windows loopback 劫持导致 MCP 不可达（非代码缺陷）；回退 NAT 模式 + MCP 监听 `0.0.0.0` 后 WSL 内正常。

### 修复（代码审查发现，sdd-review-code 556660f）

- **`plugin_threads` 生产恒返回空列表（blocking）**：`threadSource` 默认实现把 dump 的 `stack` 丢弃（`stack=emptyList()`），`filterOwned` 只数 stack 命中帧 → 生产调用恒 0 命中。修复：`NativeThreadDiagnostics` 新增 `threadSamples()` 保留原始 `StackTraceElement`（含 className），默认 `threadSource` 改用它；补"默认采样源保留真实栈帧"回归测试。
- **`ArthasControlRegistry` 未转发 `dumpClassBytes`（blocking）**：FR-19 自动备份经 Registry 委托链恒返回 null（"能力缺失→跳过备份"），生产 100% 不生效。修复：Registry 显式转发；`ArthasMemoryShellRunner` 新增 `dumpClassBytes`（经 Instrumentation `getBytecodes`），`ArthasTaskManager` 经 `bytecodeReader` 参数接入；补委托链回归测试。
- **`GcJfrToolProvider.arthasControl` 构造参数未改字段注入（blocking）**：生产 IOC 下 `jfr_start/stop` 恒 FAILED。修复：与 Flamegraph/PrePatch 一致改 `@Inject lateinit var arthasControlRegistry` + 构造参数仅测试注入。
- **`BukkitWorldDetailProvider` 对可选注入无条件解引用（blocking）**：`foliaObservedRegionService` 标 `@Inject(required=false)` 但直接调用，注入缺失时 NPE 全量降级。修复：判空 `?.snapshot()?.regions ?: emptyMap()`。
- **日志路径回退工作目录架空前缀校验（major）**：`BukkitLogPathProvider` 取不到根目录时回退进程工作目录，使 core 的 `LogPathGuard.withinRoot` 前缀校验形同虚设。修复：回退改为 null（工具降级"平台未提供日志文件"）；KDoc 修正"世界容器目录"语义。
- **玩家事件监听注册依赖插件加载时序且不重试（major）**：`BukkitPlayerDiagnosticsProvider` 的 `getPlugin("ServerProbe")` 失败被吞且不重试，`recentActivity` 可能永久为空。修复：`registerListenerWithRetry` 按 20 tick 间隔重试（最多 10 次）。
- **`BukkitPluginMetadataProvider` 空工具面占位（major）**：`tools()=emptyList()` + `call()` 抛错，core provider 未就绪时 FR-15 工具全挂。修复：兜底返回与 core 同名工具目录 + `call()` 结构化降级。
- **`McpControlPlane.startServer` 注册早于 HttpServer.start（major）**：端口占用启动失败时留下工作区幽灵注册与审计线程泄漏。修复：`register` 移到 `server.start()` 成功之后。
- **`arthas_ognl` 描述误标"同步"（major）**：补 `ASYNC_WORKFLOW`，对齐其他异步工具描述。
- **`BoundedEventRing` 并发非精确有界（major）**：`ConcurrentLinkedDeque.size()` O(n) 弱一致，瞬时可能超 capacity。修复：改用 `LinkedBlockingDeque`（`offerLast` 满则先 `pollFirst`，严格有界）。
- **`backupRestore` 先解析后校验前缀（minor）**：调为前缀校验先行（最小特权），测试断言同步。
- **`artifact_read_binary` maxBytes 负值/零语义不明（minor）**：入参层与 offset 同款钳制（非正回退默认 64 KiB）。

### 修复（真机复验 + 技术债，sdd-fix-bug 762f481 后续）

- **真机复验 B-1/B-2/B-3（WSL Paper 1.20.1）**：`plugin_threads` 返回真实命中线程（`ServerProbe-MCP` hitFrames=20）、Arthas `version` 4.3.2 可用、`jfr_start` 提交成功——三条默认注入路径全部修复生效。
- **真机暴露 M-3 修复缺陷并纠正**：`BukkitPluginMetadataProvider` 兜底注册同名工具面会按 IOC 注册序覆盖 core 的 `PluginScopedMcpToolProvider` 路由，导致 `plugin_*` 全部路由到降级实现（"插件维度诊断工具未就绪"）。修复：本类**不再实现 `McpToolProvider`、不注册工具面**（工具面由 core 统一提供），契约测试同步更新。
- **技术债：抽取 `McpExtensionToolBase` 基类**：统一 `currentWorkspace`/`arthas()`/`taskSnapshot`/`timeoutMillis`/`arthasPath` 五件套，消除 `BinaryArtifactToolProvider`/`GcJfrToolProvider`/`FlamegraphToolProvider`/`PrePatchBackupToolProvider` 四处的复制粘贴；`PrePatchBackupToolProvider.timeoutMillis` 保留 30 秒收紧上限（override 基类）。

### 技术债清理（安全/边界/可维护性/性能）

- **F5 `.so` 入 checksum manifest**：构建脚本把 3 平台 async-profiler 原生库纳入 `sha256.properties`（存在才写，3.1.1 无则跳过）；运行期 `isVerified`/`copyResources` 对 .so 做哈希校验（旧闭包无哈希键时存在性兜底），堵住原生库被替换的供应链攻击面。
- **F1 Mac 平台 dylib 扩展名修复**：Mac 不再生成 `libasyncProfiler.so` 无后缀副本（`.so` 后缀的 Mach-O 无法被 Darwin 加载器识别）；`one.profiler.libraryPath` 按平台选库名（Mac 用 `.dylib` 原名），`isVerified` 同步按平台校验。
- **F-06 log_tail 行号脱节提示**：大日志（>64MiB）无法精确统计起点前换行数时，`TailResult` 新增 `lineNumbersExact` 字段透传，工具响应带 `lineNumbersExact:false` 提示行号从窗口重计。
- **F-07 8KiB 恰好行误标截断修复**：`readLine` 读满 8KiB 后探测下一字节，仍有内容才算截断（文件末行恰好 8192 字节无换行不再误标）。
- **F-11 flamegraph_stop 扩展名校验**：显式命名必须 `.html`/`.jfr`，避免产物无法经 view 取回。
- **F-12 CLASS_NAME 正则收紧**：按 Java 标识符规则（段间单点、禁止连续点/开头点/结尾点）。
- **F-14 uuid 解析规范化**：前置标准格式校验（8-4-4-4-12 正则）再 `UUID.fromString`，替换晦涩的 `runCatching{}.getOrNull() ?: throw`。
- **m-10 inputSchema 补 required**：Native 工具的关键参数（command/className/methodName/name/artifactName/taskId/action/expression/entryId）标注必填，工具描述显示"必填"。
- **m-9 描述截断按码点**：`ToolDescriptionBuilder` 截断改用 `codePoints().limit()`，避免切断 UTF-16 代理对产生孤立代理项。
- **m-4 `McpBinaryChunk` 重命名 `ChunkReadResult`**：内部读取结果类型不再带 "Binary" 误导（文本/二进制共用）。
- **m-5 分片读取免全量列目录**：`McpArtifactWorkspace.metadata(name)` 单文件元数据，`artifact_read_binary` 分片不再 `list().firstOrNull` O(n)。
- **m-8 注册表保注册序**：`McpToolProviderRegistry` 改同步 `LinkedHashMap`，同名工具覆盖/去重的路由归属确定（不再依赖 CHM 随机迭代序）。
- **m-7 抽取任务输出字段常量**：`TASK_OUTPUT_FIELDS` 消除 11 处 taskId/state/message 三元组复制粘贴（行为零变化）。
- **m-13 server_status 截断提示**：新增 `worldsTruncated` 字段（worlds ≥ writer 的 256 上限时 true），`McpJsonWriter.MAX_ITEMS` 改 internal 供读取。
- **真机回归发现并修复两个问题（WSL Paper）**：
  - `BukkitLogPathProvider` 日志路径 lazy 固化 null + 无工作目录回退导致 `log_tail` 永久降级：改为**每次实时解析** + 回退进程工作目录绝对路径（服务器 chdir 到服务端根启动，与 `StartupLoadListener` 同源）。
  - `McpExtensionToolBase` 父类 `@Inject` 字段不被 TabooLib IOC 注入，导致扩展 provider 生产 `lateinit` 未初始化：改为**子类各自注入 + 抽象属性 getter 委托**（`workspaceRegistry`/`arthasControlRegistry`）。

## [0.3.0] - 2026-09-05

### 新增
- **FR-08 存储扩展注册**：新增 `ServerProbeStorageApi.install` 与 `MetricStoreRegistration`。运行期最多启用一个第三方 `MetricStore`，关闭注册后原子回退内置本地文件实现；重复安装和陈旧句柄均有明确保护。
- **FR-08/FR-09 自动化验收**：接入 mc-testkit，在真实 Paper 1.20.1 中验收第三方读取 API、存储 SPI 替换，以及不依赖 JianManager 的本地 RFC 6455 Worker 协议夹具。FR-09 覆盖桥握手、manifest、命令成功/失败/超时回执、业务事件和超时后的恢复。
- **内置业务集成模块化（FR-10）**：新增 `integration-multicurrencyeconomy` 与 `integration-allininventorysync` 仓库内模块并合入单 jar；未安装对应插件时不加载其类型、不影响探针。mc-testkit 注入真实 MultiCurrencyEconomy 1.2.0 与 AllinInventorySync 2.1.0-SNAPSHOT 完成读写动作、幂等命中、业务事件与失败/超时恢复验收，另验证双缺失/单缺失场景；运行期断网（HTTP/HTTPS 代理 `127.0.0.1:9`）下由 616 项离线闭包支撑。
- **全平台网络取证（FR-11）**：Bukkit/Spigot/Paper/Folia/BungeeCord/Velocity 全平台采集双向流量、包速率与包类型计数；本地 SQLite 保存包元数据与白名单载荷（单包默认 64 KiB、前缀截断保留原始长度与完整 SHA-256），默认保留 60 天、上限 4 GiB；Prometheus 仅输出聚合速率、包类型计数与脱敏 IP Top100，完整 IP/载荷仅经鉴权 Web 面板与 FR-08 只读 API 可查；驱动失败仅关闭取证并打印中文 WARN。六平台真实协议 E2E 全部 PASS。SQLite JDBC 现为发行 jar 内嵌闭包。
- **Folia 已观测 region 指标（FR-12）**：真实 ticking region 的 TPS/MSPT avg/p95/p99 明细与按世界汇总，region 离开玩家 60 秒后过期；全局 TPS/MSPT 保持 N/A。双协议 bot 真机验证隔离 region 与受控负载隔离。
- **Velocity 3.1.1–4.x 平台支持（FR-13）**：新增 `platform-velocity`，同一 jar 覆盖 Velocity 3.1.1 至 4.1.0；能力与 BungeeCord 对齐（总在线、后端 RTT/可达性、玩家路由、玩家 ping）并接入网络取证；共同源码经 API 3.1.1 与 4.1.0 双 `compileOnly` 编译门，发行类保持 Java 8。3.1.1（legacy forwarding，唯一允许 legacy 的版本）/3.5.1/4.1.0（JDK25）三组真机矩阵 PASS。
- **存储治理：磁盘占用大幅下降 + 通用数据库命名**：取证库更名 `network-forensics.sqlite` → `serverprobe-store.sqlite`（未来所有结构化数据的统一库，旧库启动时自动迁移）；启动画像 agent 栈样本按 `agent-stack-max-samples`（默认 10）均匀抽稀，单份画像从 4.64MB 降至 KB 级；指标 jsonl 过期后 gzip 归档（实测 14.6MB→0.9MB），归档保留期 `history-file.archive-days` 默认 30 天，历史查询自动透明解压；外呼日志（`http-monitor.file-retention-days`/`file-archive-days`）与 MCP 审计（`mcp.audit-max-file-mb`/`mcp.audit-retention-days`）同步支持过期归档与轮转。
- **旧版本兼容性修复（多版本矩阵真机发现）**：修复 JDK 8 上 IoC 注入链因 `@PlatformSide` 枚举数组注解解析异常而整体中断的问题（1.8–1.16 上组件 `@Inject` 字段全部未注入）；1.8–1.11 服务端改用首帧兜底装配启动画像（该平台无 `ServerLoadEvent`）；1.16 之前 Paper 缺原生 MSPT API 时改由自研 tick 时钟直方图兜底估算（TPS 仍走官方 API）。多版本矩阵（Paper 1.8.8–1.21.1 + Spigot 1.8.8/1.16.5，Java 8/17/21）十场景真机全部 PASS。
- **Arthas 获取链三级化（FR-14 增强）**：Instrumentation 获取按序自动尝试 premain → self-attach → helper 子进程外置注入（spawn 一次性进程对目标 PID attach + loadAgent，跨进程 attach 不受 JDK 9+ self-attach 限制），自动选择首个成功来源；未挂 `-javaagent`、未加 `-Djdk.attach.allowAttachSelf=true` 的服务器现在无需任何启动参数即可启用 Arthas 深度诊断。
- **外部 MCP 深度诊断控制（FR-14）**：默认关闭的 MCP Streamable HTTP（JSON-RPC 2.0）控制面，覆盖状态/指标、平台命令、线程 CPU Top/栈/死锁与诊断 bundle；内嵌 Arthas Core（Java 8–16 用 3.1.1、Java 17+ 用 4.3.2，构建期原子提取、SHA-256 校验、隔离 ClassLoader 引导），`arthas_execute` 兜底全部命令，持续命令异步任务化（并发/超时/输出/保留均可配）；产物统一写 `mcp-workspace/` 并支持分块读写与清理；审计记录来源 IP/工具/任务/耗时/参数 SHA-256。七组真机场景（Java 8/21/25 × Bukkit/Paper/Folia/BungeeCord/Velocity/Spigot）全部 PASS，Arthas 上游 Telnet/HTTP/MCP 端口不监听，非回环或空密钥时打印醒目中文 WARN。

### 修复
- **CI 依赖解析修复**：`integration-allininventorysync` 的 `allininventorysync-api` 依赖由 `2.1.0-SNAPSHOT` 降级为远程已发布的 `2.0.0`——`2.1.0-SNAPSHOT` 仅存在于本地 Maven 缓存、从未发布到 `maven.wcpe.top` 等公共仓库，导致 GitHub Actions 全新环境（空 mavenLocal）解析 `compileClasspath` 失败。已核对 `2.1.0-SNAPSHOT` 相对 `2.0.0` 仅新增 `PlayerBackup` 与 `listPlayerBackups`/`executePlayerRollback`/带 reason 物品写重载（探针代码均为反射调用、未使用新 API），降级兼容无破坏；模块 18 项单测全绿。
- **TabooLib 仓库收口 + 升级至 `6.3.0-wcpe.1`**：`repoTabooLib` 由聚合仓库 `maven.wcpe.top/repository/maven-public`（混有 test2 等版本）切到仅含 release 的 `maven-tabooproject-release` 镜像，消除版本解析噪音；运行库同步升级至 `6.3.0-wcpe.1`（含 `incision` 在内的全模块闭包均解析自新镜像）。产物 `META-INF/taboolib/version.properties` 确认 `taboolib=6.3.0-wcpe.1`，`env.properties` 的 `repo-taboolib` 指向 `maven-tabooproject-release`；本地 `./gradlew build` 全绿。

### 文档
- **PRD 结构规范化（realign）**：按 SDD 模板重构 `docs/PRD.md` 为 §1~§8 结构——FR 统一 `FR-NN` 零填充编号（FR-01~FR-14，子能力收进正文）、优先级收敛为 P1/P2/P3、状态列采用模板枚举（`已交付@vX.Y.Z` / `开发中`，FR-10~FR-14 待发版登记）、新增 §2 角色 / §3 用户故事 / §6 期级验收清单 / §7 分期主题；移除 §10 里程碑表与过程性验收日志（并入 CHANGELOG / specs）。
- **specs 文件语义化重命名**：`fr8-fr9-e2e-acceptance.md` → `open-api-bridge-e2e.md`、`incision-poc.md` → `method-incision.md`、`fr10-built-in-integrations.md` → `built-in-integrations.md`、`fr11-network-forensics.md` → `network-forensics.md`、`fr12-folia-observed-regions.md` → `folia-observed-regions.md`、`fr13-velocity-platform.md` → `velocity-platform.md`、`fr14-mcp-diagnostics.md` → `mcp-diagnostics.md`；PRD / README / ARCHITECTURE / specs 内部引用同步更新。
- **过期注记清理**：`CONTRIBUTING.md` / `doc-sync.md` / `adr/README.md` 的 ADR 编号指引更新至 ADR-27；`scope-discipline.md` 活跃范围更新为 v0.2.0 已交付 + FR-10~FR-14 待登记；`API.md` Web 面板描述由"规划中"改为"已交付"；`specs/README.md` / `_template.md` 的 M3/M4 过时措辞改为 P1/P2/P3。

## [0.2.0] - 2026-08-24

### 变更
- **标签发布自动化**：推送 `v*` 标签后，GitHub Actions 执行完整构建、上传 `ServerProbe-*.jar`，并创建或更新对应 GitHub Release。

### 新增
- **Incision 方法级精确归因(FR7)**：Bukkit/Paper 端以 VanillaModify 同款 `@Surgeon`、`@Lead`、`@Trail` 接入 `SimplePluginManager#enablePlugin`；TabooLib Gradle 插件升级为 `2.0.37-fix`、运行库升级为 `6.3.0-5b6fe60`，运行期模块改用可用镜像。`incision.enabled` 默认 `false`，关闭时不写精确数据；无效切点保持普通启动画像。Paper `1.21.11-132` + JDK `21.0.4` 真机验证已记录 `IncisionTarget` 精确耗时；受控 5ms/tick 负载下 MSPT p95 为 `6.7194ms → 6.6765ms`（`-0.64%`）。
- **在线玩家 ping 分布(FR2.4)**:服务端按固定区间桶(`<50ms`/`50-100ms`/`100-200ms`/`200-500ms`/`500ms+`)统计在线玩家 RTT 分布,1.16.1+ 经 `Player#getPing()`(内存读取、开销小),低版本无该方法时降级 N/A(绝不成为事故源)。纯分桶逻辑抽 `PingBuckets`(可单测,5 例边界/非法/空列表)。新增 `/probe ping` 子命令查看分布;Prometheus 导出 `serverprobe_players_ping_bucket{range}`。api 模型 `ServerMetrics` 加 `pingDistribution`(向后兼容,默认 null)。`./gradlew build` 全模块编译 + detekt + 单测全绿;真机各版本口径待端到端复验。
- **代理端子服 ping / 可达性 / 玩家路由 / 每玩家 ping(FR2.5)**:platform-bungee 后台调用 `ServerInfo#ping(Callback)` 计时 RTT并缓存，采集线程只读结果；同时采集玩家所在子服与玩家 ping。BungeeCord #2088 + 两个 Paper 1.20.1 后端 + 两名玩家真机已验证总在线、后端在线、RTT/可达性、切服路由与玩家 ping；BungeeCord `1.19-R0.1 #1700` + Java 8 另已验证单 jar、命令与 `/metrics`。
- **多端兼容性验收(FR6)**：同一发行 jar 已在 Spigot 1.8.8 + Java 8、Paper 1.21.x + JDK21、Folia 1.21.4 + JDK21 与 BungeeCord 真机加载并采集对应指标；Folia 全局 TPS 按设计降级为 N/A。
- **运行期 CPU 采样归因(FR2.6,M3,P2)**:core 新增 `CpuAttributionSampler`(`ThreadMXBean.dumpAllThreads` 周期采样全部线程栈,每线程至多 40 帧)+ `PluginClassLoaderRegistry`(插件 ClassLoader 注册表 + 类名→插件解析缓存,避免每帧遍历 CL)。窗口滑动聚合(默认 60 轮 ≈ 1 分钟),输出各插件样本计数与占比。platform-bukkit 新增 `BukkitPluginClassLoaderRegistrar`(反射取 `JavaPlugin#getClassLoader`,兼容各版本可见性)。新增 `/probe cpu` 子命令;Prometheus 导出 `serverprobe_plugin_cpu_samples_total{plugin}` / `serverprobe_plugin_cpu_percent{plugin}`。**默认关闭**(`cpu.enabled=false`,P2 增强)。`./gradlew build` 全模块编译 + IOC(errors=0) + detekt + 单测(注册表归并/缓存/注销)全绿;真机(Paper 多插件负载)待端到端复验。
- **Web 面板(FR4.3,M3,P2)**:core 新增 `WebPanelServer`(JDK 内置 `HttpServer` 零依赖,daemon 线程池)+ `WebPanelHtml`(无状态纯渲染)。三个只读页面:总览 `/`(最新快照 JVM/TPS/在线/内存)、启动画像 `/startup`(总时长/慢插件 Top-N/世界耗时)、历史趋势 `/history`(最近 120 份快照 TPS/MSPT/在线时序表);自包含 HTML(内联 CSS、无 CDN、文本 HTML 转义防注入)。安全:token(`Authorization: Bearer`)+ IP 白名单双重校验,默认仅本机、**默认关闭**(`web.enabled=false`),起服失败/单请求异常静默降级(探针不成事故源)。`./gradlew build` 全模块编译 + IOC(errors=0) + detekt + 单测(页面占位/转义)全绿;真机(浏览器访问三页)待端到端复验。

### 修复
- **BungeeCord 单 jar 平台隔离**：移除未使用的 `BukkitUI` 环境模块，避免 Bungee 启动加载 `bukkit-nms`；以项目内兼容扫描器在反射前按 `@PlatformSide` 过滤 IoC 组件，避免解析 Bukkit 方法签名。BungeeCord Java 8 与 #2088 多子服环境均已验收。
- **业务对接 agent 骨架(JBIS,ADR-0015)**:ServerProbe 演进为 JianManager 业务对接 agent 的探针侧骨架。`core/bridge` 新增 `BusinessProvider` 接口(业务域 / 动作 / 能力清单 / 派发)+ `BusinessHost`(域键路由 + **事故域隔离**:独立 daemon 业务线程池 + 有界超时 + 异常边界 + 合并 manifest);`BridgeCommand` 加 `domain`/`payload`,`BridgeClient` 收到带 domain 的业务 `command` 帧时路由到对应 Provider,治理命令走既有路径(业务 / 监控分流)。Provider 卡死 / 抛异常只降级该次回执,**监控采集与桥读线程不受影响**。core 平台无关(无 Bukkit 符号),业务 Provider 实现落 platform 层。`./gradlew build` 编译 + 单测(路由 / 未注册降级 / 抛异常隔离 / 卡死有界超时 / 注册)+ detekt 全绿;**业务 Provider 接入(经济等)与端到端真机待续**。
- **经济业务 Provider(对接 MultiCurrencyEconomy,JBIS)**:platform-bukkit 新增 `EconomyProvider`(`economy` 域),`compileOnly` MultiCurrencyEconomy 公开 api(运行期由目标服务端提供),经 `MultiCurrencyEconomyApi` 发现 + 就绪判定;首个动作只读 `economy.balance`(按 player + currency 查余额,金额以 BigDecimal 字符串承载防失真),mce 未就绪 / 参数缺失 / 查询异常一律降级失败。`@Service` + `@PlatformSide(BUKKIT)` + `@PostConstruct` 平台门 + 桥开关门自注册到 `BusinessHost`(沿用 `BukkitBridgeCommandHandler` 范式)。`./gradlew build` 全模块编译 + IOC 静态分析 + detekt 全绿;**真机(真 MultiCurrencyEconomy 服查真实余额)待端到端复验**。
- **业务能力清单桥查询(JBIS 元查询)**:`BridgeClient` 收到保留元命令(domain=`jbis` + action=`manifest`)时返回 `BusinessHost` 汇总的各业务 Provider 能力清单 JSON,供 JianManager 动态发现业务能力(不硬编码具体插件);该元命令不派发到任何业务 Provider。core 编译 + detekt 全绿。
- **经济变更事件上报(JBIS,对接 JM FR-122)**:platform-bukkit 新增 `BukkitEconomyEventListener`(`object` + `@SubscribeEvent`),订阅 MultiCurrencyEconomy `PlayerEconomyChangeEvent`(持久化投递流,覆盖 web 后台/跨服一切余额变更,下游唯一可靠源)与 `PlayerEconomyCatchupEvent`(玩家上线补发离线缺口),折算为 `economy` 业务**事件**经既有反向 WS 桥上报本机 Worker(→ JM CP 按 ledgerId 去重 + node→zone 聚合)。`core/bridge` 的 `BridgeClient` 新增 `emitBusinessEvent`(`event` 帧带顶层 `domain`/`dedupKey`,信封 data 携 currencyId→identifier、zoneId、signedAmount/balanceAfter 字符串、entryType、seq、occurredAt;空值不发、未连静默丢弃、绝不抛)。currencyId(Int 主键)经 `getActiveCurrencies()` 映射为全局稳定 identifier(跨服/跨区同币主键可能不同,按 identifier 聚合方不串味),映射缺失回退 Int 不丢事件;纯折算/映射逻辑抽 `EconomyEventEnvelope`(脱离 Bukkit 运行期、可单测、降 detekt 复杂度)。两事件均 mce 异步事件,监听器在非主线程触发且整体 runCatching 兜底(事故域隔离,绝不拖垮探针/mce)。`./gradlew :platform:platform-bukkit:build` 全模块编译 + IOC 静态分析(errors=0) + detekt + 单测(映射/回退/去重键/信封编码/大额防科学计数)全绿;**真机(web 后台/其他服改余额汇聚到 JM、跨区不混)待端到端复验**。
- **经济业务 Provider 扩写动作(JBIS,对接 MultiCurrencyEconomy)**:`EconomyProvider` 在只读 `balance` 基础上新增七个写动作——`deposit`(加)/`withdraw`(扣)/`adjust`(有符号差额校正)/`set`(无原生设值,经「查余额→算差额→adjust」实现、非原子)/`transfer`(玩家间转账)/`consume`(原子消费产流水号)/`refund`(按消费流水号退款),各经 mce `MultiCurrencyEconomyService` 子服务执行。守 mce 写契约:**幂等键** pluginName=`JianManager` + `IdempotencyMode.BusinessOrder(taskId)`(taskId 由 JianManager 侧生成,缺则拒绝;重试须复用同键防 `MCE-LEDGER-0001` 冲突);**金额 BigDecimal 字符串承载**(禁浮点);mce 业务失败(余额不足 / 账户冻结 / 金额非法…)以结构化结果回传(success/status/errorCode 透传),仅 Provider 级错误(未就绪 / 参数缺失 / 解析失败 / 调用抛异常)回 `BridgeCommandResult.fail`。纯解析 / 校验 / 结果编码逻辑抽到 `EconomyEnvelope`(脱离 Bukkit 运行期、降 detekt 复杂度)。`./gradlew build` 全模块编译 + IOC 静态分析 + detekt + 单测全绿;**真机(真 mce 加 / 扣 / 转账成功且幂等、余额不足等错误码正确)待端到端复验**。
- **背包业务 Provider(对接 AllinInventorySync 2.0.0,JBIS,对接 JM FR-125)**:platform-bukkit 新增 `InventoryProvider`(`inventory` 域),`compileOnly` AllinInventorySync 自包含 api(2.0.0 起纯 Java + Lombok,运行期由目标服务端提供),经 `AllinInventorySyncProvider.isAvailable/get` 发现 + 降级。只读 `view`(`getPlayerInventory(uuid)` 回源含离线 → 结构化视图:背包 / 末影箱物品数组 + 基础属性 + online + dataVersion,玩家无数据回 `exists=false`)+ 基础属性写 `writeBasicAttrs`(经写门面 `getInventoryWriteApi()`,落盘回执 `WriteResult` 透传 success/online/newDataVersion/errorCode)。守写契约:幂等键 `taskId`(CP 注入)→ 写门面 `requestId` 持久去重(缺则拒绝),`base + edited` 净改动 delta 透传,operator 透传(空回退 `JianManager`)。**物品写 `writeInventory`/`writeEnderChest` 暂不提供**——AllinInventorySync 2.0.0 物品写门面入参退回不透明分区字节(`byte[]`),外部集成无法从结构化物品构造,收到即明确降级、不进 manifest(见 [ADR-0017](docs/adr/0017-inventory-write-degraded-byte-facade.md) 取代 [ADR-0016](docs/adr/0016-inventory-business-item-transport.md) 决策 1/2,待其导出可外部消费的结构化物品写门面再恢复)。`@Service` + `@PlatformSide(BUKKIT)` + `@PostConstruct` 自注册到 `BusinessHost`;纯解析 / 校验 / 编码逻辑抽 `InventoryEnvelope`(可单测,encode 返纯 Map)。`./gradlew build` 全模块编译 + IOC(errors=0)+ detekt + 单测(manifest 二动作 / 物品写降级 / 读写校验 / 属性解码 / 视图 · 物品 · 回执编码)全绿;**真机(真 AllinInventorySync 服读真实背包 + 写属性、回执正确)待端到端复验**。
- **背包追踪事件上报(JBIS,对接 JM FR-126)**:platform-bukkit 新增 `BukkitInventoryEventListener`(`object` + `@SubscribeEvent(bind=FQCN)`,软依赖按名绑定避免漏注册,同经济监听器教训),订阅 AllinInventorySync `TrackedItemActionEvent`(重点物品流转:登录携带 / 丢出 / 拾取 / 移入容器),折算为 `inventory` 业务**事件**经反向 WS 桥上报本机 Worker。信封 data 携 playerName / playerUuid / action / ruleId / ruleDescription / material / amount / displayName / occurredAt;物品只编 Bukkit-API 便利字段(无 `nbtBase64`——全保真 codec 在 AllinInventorySync core 非 api,见 ADR-0016);瞬时观测无插件侧持久 ID,去重键 `playerUuid:action:occurredAtMs:seq`(会话单调 seq 去歧义)。`@SubscribeEvent` 收 `OptionalEvent`、`get<T>()` 取强类型(无 AllinInventorySync 时 bind 不触发,零副作用);整段 runCatching 兜底(事故域隔离,绝不拖垮探针)。纯折算逻辑抽 `InventoryEventEnvelope`(可单测,3 例);plugin softdepend `AllinInventorySync`。`./gradlew build` 全绿;**真机(重点物品流转汇聚到 JM)待端到端复验**。

### 修复
- **BungeeCord 类型隔离与新版 ping API 兼容**：采集器不再让 `ServerPing`/`ProxiedPlayer` 进入可被 Bukkit IOC 反射的方法签名；子服延迟改为反射查找 `ping(Callback)` 并以动态代理接收回调，失败或超时统一降级为不可达。成功、失败、超时单测与 BungeeCord #2088 多后端真机均通过。
- **BukkitPluginClassLoaderRegistrar 反射取 classLoader 兼容性(真机修,FR2.6)**:原用 `getMethod("getClassLoader")`,Paper 1.20.1 的 `JavaPlugin.classLoader` 为 private 且无公开 getter,致真机**注册 0 个插件 ClassLoader、CPU 归因归并失败**。改 `getDeclaredField("classLoader")` + `setAccessible(true)` 读取(兼容各版本可见性)。真机复验:注册 4 个插件(ServerProbe/CoreLib/AllinInventorySync/MultiCurrencyEconomy),`/probe cpu` 与 `serverprobe_plugin_cpu_*` 正常输出。
- **背包基础属性写解码对齐 JianManager 契约(FR-125/126/127,真机修)**:`InventoryProvider.writeBasicAttrs` 的 base/edited 解码原直读容器顶层字段且仅按字符串解析,而 JianManager 前端与 CP 审计的既定契约为 `{dataVersion,basicAttrs:{health,foodLevel,xpLevel,xpProgress,xpTotal,gameMode}}` 嵌套容器、数值字段为 JSON 数值——致契约 payload 全字段回退默认(血量 0.0/饱食 0/经验 0)写入 AllinInventorySync,**在线玩家被直接写死、离线玩家上线即死**(真机 Paper 1.20.1 + AllinInventorySync 2.1.0 复现)。解码逻辑下沉 `InventoryEnvelope.decodeBasicAttrs`(可单测):容器先取 `basicAttrs` 嵌套、缺失回退扁平直发;数值逐项先按字符串解析、再经新增 `JsonObject.getDouble`/`getInt` 按数值节点兜底;manifest note 同步为嵌套契约。真机复验:契约格式在线/离线写属性精确落库生效(xp 20→9、hp 20→15.5)、物品与其余属性不动、幂等重发 dv 不前移、玩家存活。补嵌套数值容器 + 扁平字符串兼容两单测(先红后绿)。
- **经济事件监听器按名绑定避免漏注册(对接 JM FR-122,真机修)**:`BukkitEconomyEventListener` 原用 `@SubscribeEvent` 直收 mce `PlayerEconomyChangeEvent`/`PlayerEconomyCatchupEvent`——探针 enable 时(即便 plugin.yml softdepend mce 保证后于 mce 加载)TabooLib 仍按方法反射参数类型解析事件类失败(`事件未能找到` WARN),致监听器**漏注册、经济事件零捕获**。改 `@SubscribeEvent(bind="事件全限定名")` 按名绑定(不在 enable 时解析类)+ 处理器收 `taboolib.common.platform.event.OptionalEvent`、`get<T>()` 取强类型(无 mce 时该 bind 自然不触发,零副作用);并在 `plugin/build.gradle.kts` 的 `description.dependencies` 加 `name("MultiCurrencyEconomy").optional(true)` softdepend 保序。真机(Paper1.20.1+mce)重启后 WARN 消失、监听器注册成功。
- **经济写动作透传操作者身份进 mce 流水(对接 JM FR-121,真机修)**:`EconomyProvider` 七个写动作原用默认 `OperationContexts.system()`,致 mce 审计流水操作者恒 `SYSTEM`、JM 管理员身份无法落到插件流水。改为从 payload 取 JianManager 注入的 `operator`/`nodeId`,经 `EconomyEnvelope.operationContext` 构造 `OperationContexts.of`(PLUGIN 类型、operator=管理员、sourceAction=`economy.<动作>`、nodeId 入 metadata 供追溯),operator 空回退 system()。真机验:CP 写路径下发后 mce 流水 `操作者=<管理员>`(非 SYSTEM)。补 operationContext 单测。

### 变更
- **JSON 编解码统一收口到可换适配器**(ADR-14):新增 `core/json` 的 `JsonCodec` 接口 + `Json` 门面 + 默认实现 `ConfigJsonCodec`(后端复用运行时 TabooLib `Configuration` / nightconfig,**零额外依赖**)。插件桥(删手写 `MiniJson` + `escapeJson`)、Webhook 告警、指标历史 / 启动画像落盘的 JSON 序列化与解析全部改走 `Json.encode` / `parse` / `decode`,换库(gson 等)只换实现、调用点不动。同时修正 ADR-10「优先用 gson」的事实偏差(gson 仅在构建期、运行期不携带)。火焰图查看器数据(递归树 / 瀑布数组、只构造不解析、绑定前端格式且性能敏感)为有据例外保留专用手拼。真机:Paper 1.21.1 插件桥连接握手 + 在线名册 + 踢人端到端通过;`./gradlew build` 编译 + 单测 + detekt 全绿。
- **`api` 模块改用纯 Java(Lombok 不可变模型)**(ADR-13):消除 Kotlin `@Metadata`,使任意 Kotlin(含 1.x)/ Java 版本第三方均可编译依赖 `serverprobe-api`。落盘字段名 JSON 不变(向后兼容既有 `data/`);内部对 api 模型的构造改用 builder。编译 + 单元测试 + detekt 已绿;**TabooLib 序列化落盘/读盘往返待 1.21.4 Paper 真机复验**。

## [0.1.0] - 2026-06-20

> 首个版本(tag 已于 2026-09-21 随 v0.5.0 发版流程补推,无公开下载产物)。汇集启动剖析 + 运维指标 + 存储聚合 + 四通道呈现 + 告警 + 开放接口,以及可选启动期 premain agent 增强。`./gradlew build` 编译 + 单元测试 + detekt 全通过,并已在 **1.21.4 Paper 单端真机验证全通过**;其他端(1.8 / Folia / BungeeCord)构建通过、实验性未逐一真机。核心**零新增第三方依赖**,**仅可选启动 agent 引入唯一新依赖 ASM**(需手动 `-javaagent` 启用,relocate 隔离)。

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

[未发布]: https://github.com/
[0.2.0]: https://github.com/
