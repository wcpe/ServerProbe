# 架构决策记录（ADR）

记录本项目的重大架构决策：背景、决策、理由、后果与被否的备选。每条决策一页，便于后来者理解"为什么是这样"。

> 本目录的 ADR-1~12 是**现状追认**——这些决策早于本次 SDD 逆向即已成立，原内联于 [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §11/§13，逆向时迁移为独立文件。`ARCHITECTURE.md` §11 现仅作索引链接，决策正文以本目录为单一真源。

| 编号 | 决策 | 状态 | 文件 |
|---|---|---|---|
| ADR-1 | 探针主体用纯 API + JMX + 采样，不引入 Java Agent | 已接受 | [0001](0001-probe-core-no-java-agent.md) |
| ADR-2 | 方法级插桩（如需）选 TabooLib Incision 而非裸 ASM，默认关闭并先 PoC | 已接受 | [0002](0002-method-instrumentation-incision-not-asm.md) |
| ADR-3 | 核心 Java 8 字节码，胶水按需抬 toolchain | 已接受 | [0003](0003-core-java8-bytecode.md) |
| ADR-4 | Folia 调度直接用 TabooLib `submit` | 已接受 | [0004](0004-folia-scheduling-via-taboolib-submit.md) |
| ADR-5 | TPS/MSPT 自研 `ServerTickSampler` 抽象 | 已接受 | [0005](0005-tps-mspt-self-built-sampler.md) |
| ADR-6 | 单 jar 多端分发 | 已接受 | [0006](0006-single-jar-multi-platform.md) |
| ADR-7 | 存储用本地文件 + 开放接口，不用数据库 | 已接受 | [0007](0007-local-file-storage-no-database.md) |
| ADR-8 | 运行期火焰图不自研（并用 spark），启动期火焰图自研 | 已接受 | [0008](0008-startup-flamegraph-self-built-runtime-spark.md) |
| ADR-9 | 代理端暂不联动汇总，各端独立 | 已接受 | [0009](0009-proxy-independent-no-cross-server-aggregation.md) |
| ADR-10 | 依赖策略：允许按需引入轻量依赖，逐个确认 | 已接受 | [0010](0010-dependency-policy-lightweight-on-demand.md) |
| ADR-11 | 为补加载前盲区做可选的 premain Java Agent 增强（默认不启用） | 已接受 | [0011](0011-optional-premain-startup-agent.md) |
| ADR-12 | HTTP/TCP 外呼监控插桩 JDK 层，而非逐个 HTTP 客户端库 | 已接受 | [0012](0012-outbound-http-tcp-monitor-jdk-instrumentation.md) |
| ADR-13 | api 模块改用纯 Java（Lombok），支持任意 Kotlin/Java 消费方 | 已接受 | [0013](0013-api-pure-java-for-broad-consumer-compat.md) |
| ADR-14 | JSON 编解码经可换适配器，默认 nightconfig 零依赖（细化 ADR-10） | 已接受 | [0014](0014-json-codec-adapter.md) |
| ADR-15 | 监控探针演进为 JianManager 业务对接 agent | 已接受 | [0015](0015-business-integration-agent.md) |
| ADR-16 | 背包业务对接的物品传输契约 | 决策 1/2 被 ADR-17 取代 | [0016](0016-inventory-business-item-transport.md) |
| ADR-17 | 背包物品写随 AllinInventorySync 2.0.0 分区字节化调整 | 已接受 | [0017](0017-inventory-write-degraded-byte-facade.md) |
| ADR-18 | 内置业务集成使用独立 Gradle 模块 | 已接受 | [0018](0018-built-in-business-integration-modules.md) |
| ADR-19 | 数据包取证使用受限 SQLite 例外 | 已接受；驱动分发被 ADR-27 细化 | [0019](0019-network-forensics-sqlite-exception.md) |
| ADR-20 | Folia 指标以已观测 region tick 事件采样 | 已被 ADR-23 取代 | [0020](0020-folia-observed-region-tick-events.md) |
| ADR-21 | Velocity 采用双 API 兼容源集与 Java 8 入口 | 已被 ADR-26 取代 | [0021](0021-velocity-dual-api-compatibility.md) |
| ADR-22 | ServerProbe MCP 控制面内嵌最小 Arthas 运行闭包 | 已被 ADR-24 取代 | [0022](0022-mcp-embedded-arthas-control-plane.md) |
| ADR-23 | Folia 已观测 region 从真实 tick 线程直接识别 | 已接受 | [0023](0023-folia-current-region-identity.md) |
| ADR-24 | 内嵌 Arthas 运行闭包改用官方 3.1.1 | 已被 ADR-25 取代 | [0024](0024-arthas-311-runtime-closure.md) |
| ADR-25 | Arthas 按 JVM 选择双运行时闭包 | 已接受 | [0025](0025-arthas-dual-runtime-compatibility.md) |
| ADR-26 | Velocity 采用共享源码与双实际兼容编译门 | 已接受 | [0026](0026-velocity-shared-source-dual-compile-gates.md) |
| ADR-27 | SQLite JDBC 驱动改为发行 jar 内嵌闭包 | 已接受 | [0027](0027-sqlite-jdbc-embedded-runtime-closure.md) |

> 下一个 ADR 取号 **ADR-28**（编号 = 现有最大 + 1，永不复用、不补洞；现有最大看本目录，别硬记某个数）。新建复制 [`_template.md`](_template.md)。

> **别慌通读**：ADR 有意稀少（只为重大决策写），理解现状看 [`../ARCHITECTURE.md`](../ARCHITECTURE.md)，ADR 只按需查"为什么"；被取代的归档不打扰，当前架构 = 未取代的活跃集。增长过快是滥写信号——日常变更归 PRD 状态列 + CHANGELOG。
