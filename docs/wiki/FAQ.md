# 常见问题 FAQ

> ⚠️ 本页为设计阶段文档,描述目标行为,功能尚未实现。

本页回答关于 ServerProbe 技术决策的常见疑问,答案均依据 [PRD](../PRD.md) 与 [架构文档](../ARCHITECTURE.md) 的设计决策。

> ⚠️ 项目当前处于设计阶段、为空骨架。以下回答描述的是**设计意图**,相关功能 🚧 规划中,尚未实现。

---

### Q1:为什么不用 Java Agent / 裸 ASM 做插桩?

因为对 Minecraft 探针来说,代价高、风险大、可用性差,而收益绝大部分用现成 API 就能拿到。三方案对比(ADR-1):

| 维度 | 裸 ASM + Java Agent | TabooLib Incision | **纯 API + JMX + 采样(本项目主体)** |
|---|---|---|---|
| 实现成本 | 最高 | 中 | **最低** |
| MC 上可用性 | ❌ self-attach 在 Paper/JDK21+ 默认失效 | ⚠️ 有 JVMTI 兜底,本仓库零用例 | ✅ 稳定 |
| 崩服风险 | 最高 | 高 | **最低(只读)** |
| 全版本 + 多平台 | ⚠️ 各端各 JDK 行为不一 | ⚠️ 代理端需自适配 | ✅ 最佳 |
| 运行开销 | 高 | 高 | **极低** |

关键事实:**探针 90%+ 的指标用现成稳定 API 即可,连 spark 都不用 Java Agent。** 因此主体采用纯 API + JMX(`java.lang.management`)+ 平台原生 API + 采样。设计原则之一就是"只读优先,绝不成为事故源"。

方法级**精确**插桩作为**可选增强**保留:若启用,采用 **TabooLib Incision**(而非裸 ASM),且**默认关闭、需先 PoC 验证**(本仓库零真实用例,成熟度待验证),失败静默降级。

---

### Q2:为什么 Folia 上 TPS 可能显示 N/A?

因为 **Folia 没有"全局 TPS"这个概念**。Folia 把世界划分为多个 region,每个 region 跑在自己的线程上、各有各的 tick 节奏,因此不存在一个统一的全服 tick 速率。

此外,TabooLib **完全不封装** TPS/MSPT。本项目用 `ServerTickSampler` 抽象按环境取值:Paper 走 `Bukkit.getTPS()`,老版本/纯 CraftBukkit 用 `nmsProxy` 读 `MinecraftServer.recentTps[]` 或自建采样,而 **Folia 是 per-region**。

Folia 上的呈现方式**已敲定**:**给出 per-region 明细,同时把全局值标为 N/A**(因为全局单值本身语义不成立)。分阶段实施 —— **M1 先全局标 N/A**(诚实且语义安全),**M2/M3 再补 per-region 明细**。详见 [版本与平台兼容](Compatibility.md)。

---

### Q3:很老的版本(比如 1.8)能用吗?

能(按设计目标)。兼容范围是 **1.8 – 1.21.11 全版本**,与 TabooLib `MinecraftVersion.supportedVersion` 一致。做法:

- 探针核心是 **Java 8 字节码**,可被 1.8–1.21.x 全部 JRE 加载。
- JVM / 在线数 / 世界列表等绝大多数指标是**纯通用**的,零版本处理。
- 唯一需要版本分支的关键点是 TPS/MSPT:无 `getTPS()` 的老版本/纯 CraftBukkit 会用 `nmsProxy` 读 `MinecraftServer.recentTps[]` 或自建 tick 采样器兜底。

设计原则"先通用,跑不通再拆胶水"保证不会为低版本堆砌大量空胶水。

---

### Q4:代理端(BungeeCord)为什么没有 TPS?

因为**代理端没有 tick,也没有世界** —— TPS/MSPT/世界/区块/实体这些概念在代理端根本不成立。代理端的产品定位是**网络与子服健康监控**。

代理端能采:JVM 全套、总在线、各子服在线数、子服 ping/可达性、玩家路由、每玩家 ping。
代理端不采:TPS、MSPT、世界、区块、实体。

实现上以 `@PlatformSide(Platform.BUNGEE)` 隔离,`ProxyPlayer` 多数字段在代理端为 `Unsupported`(无世界/坐标)。完整边界见 [版本与平台兼容](Compatibility.md)。

---

### Q5:ServerProbe 会和 spark 冲突吗?要不要二选一?

不冲突,设计上**互补**而非替代。本项目明确**不自研重型 CPU 采样分析器去替代 spark**(非目标)。

- ServerProbe 主体只读、零 Java Agent,擅长:启动剖析、运维指标采集/聚合/告警/多通道呈现、历史对比。
- 深度 CPU 火焰图建议**并用 spark**;本项目的 CPU 采样归因(FR2.6,P2)仅作轻量增强(`ThreadMXBean` 周期采样,无 agent)。
- 服务端 bootstrap 层(DFU/注册表)是普通插件的盲区,若要更细的 CPU 归因,spark 是合适的补充。

是否自研火焰图已敲定:**不自研,建议并用 spark**;ServerProbe 专注指标监控 + 启动剖析。详见下一条。

---

### 会做 CPU 火焰图吗?

**不自研火焰图。** ServerProbe **不做**重型 CPU 热点分析 / 火焰图,这一点已敲定为非目标。需要深度 CPU 采样与火焰图时,建议**并用 [spark](https://spark.lucko.me)**:它基于采样、**零 Java Agent**、是社区公认的标准 profiler,与本项目"只读优先、绝不成为事故源"的定位天然契合。

ServerProbe 自身专注于**指标监控 + 启动剖析**:JVM/服务器指标采集聚合、多通道呈现、告警、历史对比,以及开服慢的量化定位。本项目的 CPU 采样归因(FR2.6,P2)仅作轻量增强(`ThreadMXBean` 周期采样、无 agent),不替代 spark 的火焰图能力。

---

### Q6:探针自己会不会拖慢服务器?

设计目标是**自身运行开销 < 2%**,且把"绝不成为事故源"作为硬约束:

- 主线程只做轻量取值(MSPT 仅取 `nanoTime`);聚合 / 落盘 / 采样**全异步**。
- 周期任务限频;高频指标进**定容环形缓冲**,避免磁盘 IO 压主线程。
- **严禁循环内 DB / 远程调用**(防 N+1)。
- 主体只读,不改运行时;可选插桩默认关闭、失败静默降级。

---

### Q7:它能精确告诉我某个插件的某个方法慢在哪吗?

**首期不能**,只能到"逐插件 onEnable 耗时"粒度(通过生命周期打点 + 解析 `logs/latest.log`)。

方法级**精确**归因(FR7)需要 Incision 字节码插桩,属 P2 可选项,**默认关闭,且引入前必须先 PoC 验证**(目标 Paper + 目标 JDK 上能否织入、开销、可回滚)。验证通过后才会用于 `enablePlugin` 精确插桩等场景。

---

### Q8:为什么是"单 jar 多端",一个包怎么同时给 Bukkit 和 BungeeCord 用?

TabooLib 支持单 jar 多端:同一 jar 内同时写入 `plugin.yml`(Bukkit)和 `bungee.yml`(BungeeCord),各端启动时读各自的描述符。平台相关实现用 `@PlatformSide` 隔离,运行时只激活当前平台对应的采集器(ADR-6)。部署简单,无需分平台多 jar。

---

### Q9:Folia 上采集会不会因为跨线程读数据崩掉?

不会(按设计)。Folia 下:

- **调度**:一律走 TabooLib `submit` / `submitAsync`(自动走 `GlobalRegionScheduler` / `AsyncScheduler`),严禁 `Bukkit.getScheduler()`(已被移除)。
- **读实体 / 区块**:数据归各 region 线程,跨线程直读会抛异常 → 用 TabooLib 现成的 `Location.callRegion{}` / `Entity.callRegion{}` 逐区域采集后汇总。

详见 [版本与平台兼容](Compatibility.md)。

---

### Q10:现在能下载使用了吗?

**还不能。** 项目当前是空骨架、零功能代码、处于设计阶段。本 Wiki 是面向未来用户的使用手册草案,描述的是目标行为。迭代规划(里程碑)见 [PRD](../PRD.md):M1 先做多版本多平台骨架 + 启动剖析 + JVM/服务器基础指标 + 游戏内命令。

---

### 探针为什么不用数据库?

因为探针应当**轻量自包含、零部署依赖、绝不成为事故源**。引入数据库会增加部署复杂度与故障面,与这一定位相悖。

因此探针只落**本地文件**:启动画像写为 JSON,指标历史以 JSONL 行式追加。同时通过**存储 SPI** 预留扩展点 —— 需要 DB / 远程存储的用户可自行实现存储后端,但插件本身**不内置、不依赖**(对应架构 ADR-7)。

---

> 返回 [Wiki 首页](Home.md) · 相关:[版本与平台兼容](Compatibility.md) · [指标说明](Metrics.md) · [启动剖析指南](Startup-Profiling.md)
