# ADR-0023：Folia 已观测 region 从真实 tick 线程直接识别

## 状态

已接受，取代 [ADR-0020](0020-folia-observed-region-tick-events.md)

## 背景

ADR-0020 把在线玩家位置提交到 `RegionScheduler` 作为已观测 region 的识别入口。真实 Folia 运行验证表明，`ServerTickStartEvent` / `ServerTickEndEvent` 已在正在执行的 region tick 线程触发，且该线程可直接取得当前 `TickRegionScheduler` region 及其 `RegionStats#getPlayerCount()`。额外调度既不能增强 tick 身份的准确性，还会增加一次跨时点的归属判断。

## 决策

在每次真实 tick 事件的当前线程中，反射读取 `TickRegionScheduler#getCurrentRegion()`、`ThreadedRegion` 的 id 与中心区块，以及 `TickRegionData` 的世界和 `RegionStats#getPlayerCount()`。仅当玩家数大于零时，把真实 tick 的开始间隔和结束时长写入有界窗口。

一个观测序列以运行期 region id、世界名和中心区块共同识别；任一组成变化都会创建新的 `regionSequence`，旧窗口按保留期自然过期。反射仅允许放在唯一适配器中；解析失败时不发布伪造数据。

## 理由

- 取值与 tick 事件在同一真实 region 线程，MSPT 与 TPS 均来自实际 tick，而非调度延迟。
- `RegionStats` 的玩家数使采集成本随当前含玩家 region 增长，无需枚举 Folia 未公开的全部 region。
- 原始 Folia region id 对外可见，`regionSequence` 则让同 id 的合并、拆分或中心坐标变化不会串接统计。

## 后果

- Folia 全局 TPS/MSPT 继续为 N/A；只发布“已观测 region”明细和按实际样本合并的世界汇总。
- 对 Folia 内部成员的兼容面收敛为一个反射适配器，升级 Folia 时须由真实 Folia 场景回归；不兼容时仅关闭该指标并以中文 WARN 说明。
- 事件路径只进行有界内存写入；不在其中枚举玩家、调度任务、读盘或网络输出。

## 备选方案

- **玩家位置 `RegionScheduler` 识别**：与真实 tick 的当前身份不是同一时点，且增加调度开销，否决。
- **GlobalRegionScheduler 周期采样**：不能代表任一 region tick，否决。
- **枚举所有 loaded region**：没有稳定公共枚举 API，且成本不可控，否决。
