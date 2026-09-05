# 功能规格：Folia 已观测 region TPS/MSPT

> 状态：已完成验收，待下次正式版本登记　·　关联 PRD：FR-12　·　分支：当前分支

## 1. 背景与目标

Folia 没有全局主线程或全局 TPS。把 Paper 的单值指标套到 Folia 会产生错误结论。本功能只监测含在线玩家的真实 ticking region，明确标为“已观测 region”，输出 region 明细和按世界汇总。

## 2. 需求（要什么）

- 已观测 region 定义为当前或保留期内包含至少一名在线玩家、且已成功采样真实 tick 的 Folia region。
- 每条明细包含世界名、Folia region id、中心区块坐标、在线玩家数、TPS 与 MSPT 的均值/p95/p99、样本数和最后观测时间。
- 每世界输出当前已观测 region 的样本加权汇总；Folia 全局 TPS/MSPT 始终为 N/A。
- 默认 60 秒无新样本后淘汰 region，可配置；region 合并/拆分后以新的 region id 建新序列，旧序列自然过期。
- 指标必须来自实际 region tick，不得把 GlobalRegionScheduler 周期或普通异步任务时延伪装成 region TPS/MSPT。
- 范围内：Folia 分流、region 身份、滚动统计、命令/Prometheus/Web/FR-08 展示、真机验收。
- 不做：无在线玩家 region 的全量枚举、永久 region 历史、把多个 region 合成伪全局 TPS。

## 3. 设计（怎么做）

- Folia 每个 region 都触发 Paper `ServerTickStartEvent/ServerTickEndEvent`。监听器在当前 region tick 线程直接反射 `TickRegionScheduler#getCurrentRegion()`，再读取 `ThreadedRegion` id、中心区块、`TickRegionData` 世界和 `RegionStats#getPlayerCount()`；不提交 `RegionScheduler` 任务。
- MSPT 直接使用 EndEvent 的真实 tick duration；TPS 由同一 region 相邻 TickStart 时刻间隔换算并封顶 20。只有当前玩家数大于零的 region 才写入有界滚动窗口。
- 窗口键为运行期 region id、世界与中心区块；任一组成变化都建立新的单调 `regionSequence`，旧窗口按保留期自然过期。事件路径只做常量时间内存更新，不调度 IO、不遍历玩家。
- 每个窗口保存足以计算 avg/p95/p99 的有界样本；世界汇总按窗口样本数合并，空样本不产生数值。
- 反射集中在 `FoliaInternalRegionIdentityAdapter`，探测失败时该 region 不发布虚假指标并输出中文 WARN。对外模型显式包含 `foliaRegionId`、`regionSequence` 与 `observed=true`。
- 该方案依据 Folia 官方 region 调度与 per-region tick 事件语义，不依赖全局线程，也不枚举无玩家 region。
- 架构决策见 [ADR-0023](../adr/0023-folia-current-region-identity.md)，其取代保留历史的 ADR-0020。

## 4. 任务拆分

- [x] 完成滚动统计、region 变更隔离、过期、世界汇总和全局 N/A 的单元测试。
- [x] 实现 Folia 真实 tick 线程内的 region 身份适配与玩家数门控。
- [x] 接入 per-region TickStart/TickEnd 样本，事件路径只做有界内存更新。
- [x] 扩展指标模型、FR-08、`/probe tps`、Prometheus 与 Web。
- [x] 用 mc-testkit Folia 场景让两个协议 bot 进入隔离 region，在一个 region 施加受控负载并验证隔离。
- [x] 同步 ADR、PRD、ARCHITECTURE、README 与配置说明；API 数据模型 KDoc 为字段权威来源。

## 5. 验收标准

- **已通过（2026-08-27）**：`e2eFoliaObservedRegionsWithBot` 在 Folia 1.21.4 + JDK 21 启动同一 ServerProbe jar，以两个真实协议 bot 形成 target/control 两个 region。当前进程结果为 `targetRegionId=0`、`controlRegionId=8`、`targetP95Delta=10.938ms`、`controlP95Delta=-2.8858ms`、`loadRuns=118`、`expired=true`、`status=PASS`。
- **FR-08 真实读取**：E2E 通过 `ServerProbeApi.read().latestSnapshot()` 读取 `observedRegions` 与 `observedRegionWorlds`，要求两条稳定的 `observed=true` 明细、世界汇总及 Folia 全局 tick 字段全为 null；不会把 Paper 全局值回填。
- **命令**：同一真实 Folia 场景执行 `probe tps` 烟测成功；命令将全局 TPS/MSPT 显示为 N/A，并追加世界汇总及每个 region 的真实 id、序号、坐标和分位值。
- **Prometheus**：`PrometheusTextFormatterTest` 断言不导出全局 `tps`/`mspt_seconds`，同时导出 `serverprobe_folia_observed_region_*` 与 `serverprobe_folia_observed_world_*`。
- **Web**：`WebPanelHtmlTest` 断言总览渲染全局 N/A、世界汇总、region 明细、真实 id 与 HTML 转义。
- **统计与生命周期**：`ObservedRegionTrackerTest` 覆盖无玩家排除、avg/p95/p99、按真实样本的世界加权、过期，以及中心区块变化后的新序列。

可复验命令：

```powershell
.\gradlew.bat :platform:platform-bukkit:test --tests '*ObservedRegionTrackerTest' --no-daemon --console=plain
.\gradlew.bat :project:core:test --tests '*PrometheusTextFormatterTest' --tests '*WebPanelHtmlTest' --no-daemon --console=plain
.\gradlew.bat e2eFoliaObservedRegionsWithBot --no-daemon --console=plain
```

## 6. 风险 / 待定

- Folia 内部 region 身份不是稳定公共 API；只允许最小反射层承受版本差异，反射失败不得伪造坐标或统计。Folia 新版本须重跑真实 E2E，不是当前 FR-12 的未完成项。
- region id 只在当前服务器进程内有效，中心区块会随合并/拆分变化；对外排查应同时使用 `server instance + world + foliaRegionId + regionSequence`，中心区块仅作辅助定位。
- FR-12 功能与验收没有剩余项；**待完成的是发布登记，以及 FR-10、FR-11、FR-13、FR-14 的独立交付**，它们不因本规格通过而改变状态。
