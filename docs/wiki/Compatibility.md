# 版本与平台兼容

> ⚠️ 本页为设计阶段文档,描述目标行为,功能尚未实现。

ServerProbe 的核心设计目标之一(G3)是:**单 jar 覆盖 Bukkit 系 1.8–1.21.11 全版本(含 Folia)+ BungeeCord**,核心 Java 8 字节码通用,版本/平台差异以**最小胶水**隔离。

> 本页所述能力均为目标行为,标注 🚧 规划中,功能尚未实现。

---

## 一、全版本支持(MC 1.8 – 1.21.11)

| 维度 | 范围 | 说明 |
|---|---|---|
| MC 版本 | **1.8 – 1.21.11**(及 26.1) | 与 TabooLib `MinecraftVersion.supportedVersion` 一致;被 `!` 跳过的紧急修复版(如 1.20.3 / 1.21 / 1.21.2)按 TabooLib 行为处理 |
| 服务端类型 | CraftBukkit / Spigot / Paper / Folia / 其他 Bukkit 衍生 | 均视为 Bukkit 平台 |
| 编译目标 | 核心 **Java 8** | 仅"直接继承高版本 NMS 类"的个别胶水模块才抬 toolchain |

### 多版本是怎么做到的
设计依赖 TabooLib 的版本抽象,核心三件套:

1. **版本探测 `MinecraftVersion`**:`runningVersion`("1.21.4")、`major`/`minor`、`versionId`(数字,如 `12101`);特征位 `isUniversal`(≥1.17)、`isUnobfuscated`(≥26.1)、`isUniversalCraftBukkit`(Paper 1.20.6+);比较 `isHigherOrEqual(...)` / `isIn(min,max)`。
2. **NMS 多版本抽象 `nmsProxy`**:写一套 Mojang 映射的抽象类 + Impl,运行期用 ASM 把字节码重映射到当前服务端的实际混淆名。业务代码只见抽象类。
3. **版本特定代码三手法**:运行期分支(`if (MinecraftVersion.isUniversal)`)、`nmsProxy` + abstract/Impl(可按 `versionId` 加载不同 Impl)、`@PlatformSide`(平台级门控)。

### 版本策略一览

| 指标 | 版本处理 |
|---|---|
| JVM / 在线数 / 世界列表 | **纯通用,零版本处理** |
| TPS / MSPT | **唯一需版本分支的关键点**(见下文) |
| NMS 深度采集 | 优先反射(`nmsClass` / `getProperty`),避免被高版本类型"传染"toolchain |

> 设计原则:**先通用,跑不通再拆胶水。** 绝大多数指标走 Bukkit API + JMX 全版本通用,严禁一上来就建一堆空胶水模块。

---

## 二、Folia 适配

Folia 被识别为 **`Platform.BUKKIT` 的运行期变体**(`Folia.isFolia` = `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` 成功与否),**不是独立平台**。

### 1. 调度零胶水
TabooLib 的 `submit()` / `submitAsync()` 已原生适配 Folia:在 Folia 上自动走 `GlobalRegionScheduler`(同步)/ `AsyncScheduler`(异步),不碰已被移除的 `Bukkit.getScheduler()`。

> **规则:探针所有定时采集一律走 TabooLib `submit`,严禁直接 `Bukkit.getScheduler()`。**

### 2. TPS / MSPT 的 per-region 语义(关键)
TabooLib **完全不封装** TPS/MSPT。探针抽象 `ServerTickSampler` 接口,多实现:

| 环境 | TPS | MSPT |
|---|---|---|
| Paper(支持 `getTPS()`) | `Bukkit.getTPS()` | `Bukkit.getAverageTickTime()` + 每 tick `nanoTime` 直方图算分位 |
| 老版本 / 纯 CraftBukkit(无 `getTPS()`) | `nmsProxy` 读 `MinecraftServer.recentTps[]`,或自建 tick 采样器 | 自建 tick 采样 |
| **Folia** | **无全局 TPS(per-region)** → **per-region 明细 + 全局标 N/A**(M1 先全局 N/A,M2/M3 补 per-region 明细) | 同上,per-region |

**Folia 没有"全局 TPS/MSPT"这一概念**:每个 region 跑在自己的线程上,各有各的 tick 节奏。因此一个统一的全服 TPS 数值在 Folia 上语义不成立。最终呈现方式已敲定:**全局值标 N/A + 给出 per-region 明细**;分阶段实施 —— **M1 先全局标 N/A,M2/M3 补 per-region 明细**。详见 [常见问题FAQ](FAQ.md)。

### 3. 实体 / 区块采集
- **非 Folia**:`submit{}`(同步)遍历 `world.getEntities()` / `getLoadedChunks()`。
- **Folia**:数据归属各 region 线程,跨线程读会抛异常 → 用 TabooLib 现成的 `Location.callRegion{}` / `Entity.callRegion{}` 逐区域采集后汇总。

---

## 三、BungeeCord 代理端能力边界

代理端的**产品定位 = 网络与子服健康监控**。代理端没有世界、没有 tick,因此**不采世界 / 区块 / 实体 / TPS / MSPT**。

> **多端关系(已敲定):各端独立采集与展示,暂不与后端联动汇总**(不引入 Porticus 等跨服汇总组件)。代理端与各 Bukkit 后端分别独立运行探针,各自采集、各自呈现,不做跨服聚合。

`ProxyPlayer` 的多数字段在代理端为 `Unsupported`(无世界 / 坐标),代理端只采网络拓扑类数据。

| 能力 | 代理端(BungeeCord) | Bukkit 后端 |
|---|---|---|
| JVM 全套(内存/GC/线程/CPU…) | ✅ 能采 | ✅ 能采 |
| 总在线人数 | ✅ 能采 | ✅(本服) |
| 各后端子服在线数 | ✅ 能采 | ✅(本服) |
| 子服 ping / 可达性 | ✅ 能采 | — |
| 玩家路由(在哪台子服) | ✅ 能采 | — |
| 每玩家 ping | ✅ 能采 | ✅(本服玩家) |
| TPS / MSPT | ❌ **无该概念** | ✅ |
| 世界 / 区块 / 实体 | ❌ **无该概念** | ✅ |
| 启动剖析 | 仅 JVM 启动 + 自身生命周期 | ✅ 完整(插件/世界耗时) |

> 实现上以 `@PlatformSide(Platform.BUNGEE)` 隔离:代理端只激活代理实现,后端只激活 Bukkit 实现。Velocity 已在架构层抽象预留,后续可低成本接入。

---

## 四、各平台指标差异速查

| 指标类别 | Bukkit/Paper | Folia | BungeeCord |
|---|---|---|---|
| JVM(内存/GC/线程/CPU) | ✅ 通用 | ✅ 通用 | ✅ 通用 |
| 在线人数 | ✅ | ✅ | ✅(总在线) |
| TPS | ✅(API/兜底) | ⚠️ per-region 明细 + 全局 N/A | ❌ |
| MSPT(p95/p99) | ✅ | ⚠️ per-region 明细 + 全局 N/A | ❌ |
| 世界 / 区块 / 实体 | ✅(`submit`) | ✅(`callRegion`) | ❌ |
| 子服 ping / 路由 | — | — | ✅ |
| 启动剖析 | ✅ 完整 | ✅ 完整 | ⚠️ 仅 JVM + 自身 |

---

## 五、JDK / Toolchain 与"会不会崩"

核心结论:**几乎全部 Java 8 编译即可,包括 Folia。**

- `compileOnly` 一个高字节码依赖**本身不强制**抬 toolchain(走反射时 javac 不加载未被直接引用的 class)。
- 真正"传染"toolchain 的,是**直接出现在源码符号里**的高版本类型(继承、字段类型、方法签名)。
- 因此探针**优先反射**,把极少数"必须直接引用高版本类型"的代码隔离进独立 `nms-vXXX` 模块单独抬 toolchain,绝不与 Java 8 核心混编。
- 多 toolchain 的 class 共存于一个 jar,**按需加载**保证低版本 JVM 永不触碰高版本 class,不会 `UnsupportedClassVersionError`。

---

> 返回 [Wiki 首页](Home.md) · 相关:[指标说明](Metrics.md) · [常见问题FAQ](FAQ.md)
