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

服务端 **bootstrap 阶段**(NMS 初始化、DataFixerUpper、注册表)早于任何插件加载,**普通插件不可见**。因此对这一层:

- 探针**不做**逐方法级归因(那需要重型字节码织入,属非目标)。
- 只能通过**端到端总时长对比**来反映其变化(例如大版本升级导致 bootstrap 变慢,会体现在总时长上,但拆不到具体方法)。

如需 bootstrap 层更细的 CPU 归因,建议并用 [spark](FAQ.md)。

---

## 七、数据落盘与对比

每次启动画像作为**一份 JSON**异步落本地文件(`StartupProfile`:`schemaVersion, serverId, platform, mcVersion, jvmStartTimeMs, totalMs, phaseTimings, pluginTimings, worldTimings, jvmArgs, createdAtMs`),用于"与上次 / 基线对比"。落盘通道详见 [数据呈现与对接](Data-Output.md)。

---

> 返回 [Wiki 首页](Home.md) · 相关:[命令与权限](Commands.md) · [指标说明](Metrics.md) · [数据呈现与对接](Data-Output.md)
