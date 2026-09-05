# ADR-0020：Folia 指标以已观测 region tick 事件采样

## 状态

已被 [ADR-0023](0023-folia-current-region-identity.md) 取代（原决策历史保留）

## 背景

Folia 没有全局主线程或全局 TPS；公共调度器只能把工作放到指定位置，不能代表 region tick 时延。FR12 要求每个有在线玩家的真实 region 产出 TPS/MSPT 的均值、p95、p99，并避免枚举所有无玩家 region。

## 决策

以在线玩家所在位置的 RegionScheduler 任务识别“已观测 region”，再在同一 region 的 `ServerTickStartEvent`/`ServerTickEndEvent` 中采集真实 tick cadence 与 duration；region id 和中心区块由唯一反射适配层读取。

## 理由

- Tick 事件在 Folia 的实际 region tick 线程触发，duration/cadence 可直接生成 MSPT/TPS 分位，避免把异步调度延迟伪装成服务端性能。
- 只维护含在线玩家的活跃表，成本随真实玩家区域增长，不进行全量 region 枚举。
- region id 能正确表达合并/拆分后的新统计序列，中心区块保留为排查坐标。

## 后果

- Folia 全局 TPS/MSPT 始终为 N/A；世界摘要只合并当前已观测 region 的样本。
- 反射失败时不发布虚假值，限频中文 WARN 并保留其它平台指标。
- 事件路径只做有界内存统计，文件、网络和聚合外送都在异步路径完成。

## 备选方案

- **GlobalRegionScheduler 周期采样**：不能代表任一 region tick，否决。
- **枚举所有 loaded region**：缺少稳定公共枚举 API且成本不可控，否决。
- **只取 Bukkit 平均 TPS/MSPT**：不能提供分位且会混淆 per-region 语义，否决。
