# ADR-0015：监控探针演进为 JianManager 业务对接 agent

## 状态

已接受

## 背景

ServerProbe 至今的定位是**只读监控探针**：采指标 / 启动剖析 + 经反向 WS 插件桥承接 JianManager（下称 JM）下发的**治理**指令（踢 / 封 / 白名单 / 在线列表 / 全状态查询，FR-067 / FR-076，JM ADR-016）。`architecture-invariants` 与 `scope-discipline` 锁定「探针主体只读、core/api 不碰平台 API、不引数据库」。

JM 要把它进化为运营总管——穿透到每个服务端的每个**业务插件**（经济 / 背包 / 领地…）读写数据并汇聚回平台（JM「JBIS 业务对接平台」，JM ADR-025/026/027）。落地形态经 JM 侧与运营者确认为**一个 ServerProbe 分模块**（对外单 agent / 单连接 / 单身份，对内监控层与业务对接层分层隔离），而非另起独立插件。这要求 ServerProbe 在保持监控主体只读纯净的前提下，长出一个**业务对接层**。本 ADR 记录这一身份级演进决策（对应 JM ADR-025 所指「ServerProbe 子模块仓自身演进 ADR」）。

## 决策

ServerProbe 演进为「**对外单 agent、对内监控 / 业务分层隔离**」的全能 agent：

1. **业务对接层落 platform 层**：core / api 保持只读纯净、不碰平台 API、不引数据库（既有红线不变）。业务对接基础设施（域路由 / 事故域隔离 / 能力清单）下沉 core 作平台无关装配点（与既有 `BridgeCommandRegistry` 同性质，纯 Kotlin 无 Bukkit 符号）；**真正调业务插件 API 的 Provider 实现落 platform-bukkit**（与 `BukkitBridgeCommandHandler` 同范式，TabooLib `@PlatformSide` + `@PostConstruct` 自注册）。
2. **复用既有桥，不新增通道 / 协议**：业务命令复用 `command` 帧、业务事件复用 `event` 帧，按 `domain` 命名空间与监控 / 治理分流（治理 `core.*` 既有；业务 `economy.*`/`inventory.*` 新增）。`BridgeCommand` 加性扩 `domain`/`payload`（JM 侧 proto 已加 FR-115）。
3. **事故域隔离（铁律）**：业务 Provider 在**独立线程池**执行、**独立异常边界**、有界超时；Provider 卡死 / 抛异常 / 插件缺失只降级该域命令回执，**监控采集与桥心跳完全不受影响**。守 `testing-and-quality` §2.6「主线程不阻塞」——业务执行不占桥读线程、不占主线程（需主线程的平台操作由 Provider 自行 `submit` 切回，同 `BukkitBridgeCommandHandler`）。
4. **能力清单（manifest）驱动**：每个业务 Provider 声明其域 / 动作 / 字段 schema；JM 经 manifest 动态发现能力，新增业务插件 = 写一个 Provider + manifest，core 链路零改（O(1) 接入）。
5. **数据所有权不变**：业务真源仍在各业务插件存储，ServerProbe 只做「透过插件 API 读 + 下指令改」的 agent，不引数据库、不复制业务数据（既有红线不变）。

## 理由

- **守住监控主体只读、绝不成为事故源**：分层 + 事故域隔离让业务写逻辑（改余额 / 改背包，有 bug 会刷钱 / 吞物品）与监控采集互不影响；监控层一行不改、保持纯净。
- **对外单 agent 契合运营总管定位**：一个插件 / 一条连接 / 一个身份，运营面最简（详见 JM ADR-025 的取舍：同 JVM 下独立插件不提供更强隔离）。
- **复用既有桥、零新协议**：业务命令 / 事件复用 `command`/`event` 帧按 domain 分流，core 改动最小（既有 `BridgeCommandRegistry` 范式直接镜像）。
- **core 仍平台无关**：业务路由 / 隔离 / manifest 是纯 Kotlin 基础设施（无 Bukkit），Provider 实现落 platform——分层依赖单向不破。

## 后果

- 新增 core 业务对接基础设施（`BusinessProvider` 接口 + `BusinessHost` 域路由 / 事故域隔离 / manifest）与 platform-bukkit 业务 Provider（首批经济，wrap MultiCurrencyEconomy）。`BridgeCommand` 加 `domain`/`payload`、`BridgeClient` 按 domain 分流业务 / 治理。
- ServerProbe 范围从「纯监控 + 治理」扩到「监控 + 治理 + 业务对接」；`scope-discipline` 须同步登记本线（PRD FR9）。这是经用户确认的身份级扩张，非镀金。
- 业务 Provider 须引入对应业务插件的 api 依赖（`compileOnly`，如经济 `MultiCurrencyEconomy` api；背包 `AllinInventorySync` api 须先扩其写门面，JM FR-124），落 platform 层不污染 core/api。
- 事故域隔离的业务线程池 / 超时须纳入 `testing-and-quality` §2 高风险区（并发 + 主线程不阻塞）穷举测试。

## 备选方案

- **另起独立 JianAgent 插件**（ServerProbe 守监控不动）：同 JVM 下两插件不提供更强运行时隔离（共享主线程 / 堆 / 进程），真正隔离来自线程池 + 异常边界（一个插件分模块即可达成），徒增双连接 / 双身份 / 双发版。经 JM 侧与运营者权衡否决（详见 JM ADR-025）。
- **业务逻辑直接塞进 core / 与监控共用线程**：违背事故域隔离铁律——业务插件 API 卡死 / 抛异常会拖垮监控与桥心跳，使 ServerProbe 成为事故源；否决。
- **让业务插件实现 JBIS SPI（依赖反转，范式 B）**：开放生态更彻底，但 SDK 契约未经真插件验证即固化风险高；首批用 platform 侧适配器 Provider（范式 A）+ manifest，SPI 留 future（详见 JM ADR-026）。
