# ADR-5：TPS/MSPT 自研 `ServerTickSampler` 抽象

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11 补记为独立 ADR。

TPS/MSPT 是运维探针的核心服务器指标，但 TabooLib 完全不封装 TPS/MSPT，且其获取方式在不同环境差异很大：Paper 有 `Bukkit.getTPS()`/`Bukkit.getAverageTickTime()`，老版本/纯 CraftBukkit 无 `getTPS()`（需 `nmsProxy` 读 `MinecraftServer.recentTps[]` 或自建 tick 采样），而 Folia 无全局 TPS（per-region 语义）。因此需要一层能同时覆盖多版本与 Folia 的统一抽象（见 ARCHITECTURE.md §7.3、PRD §5.4 / §11 / §12）。

## 决策
自研 `ServerTickSampler` 抽象接口，按环境提供多实现（Paper 用原生 API、低版本 `nmsProxy`/自建采样兜底、Folia per-region / 全局标 N/A）。

## 理由
- TabooLib 不封装 TPS/MSPT，无现成能力可直接复用。
- TPS/MSPT 的获取需同时满足多版本兼容与 Folia per-region 语义，自研抽象接口 + 多实现是收敛这些差异的清晰方式；各实现还可统一以 JMX 兜底。

## 后果
- 正面：TPS/MSPT 的版本与平台差异收敛在 `ServerTickSampler` 多实现内部，业务侧面向统一接口；Folia 明确 per-region 语义、全局标 N/A（M1 先全局 N/A，M2/M3 补 per-region 明细）。
- 负面/约束：需自行维护多实现并保证各版本/平台正确性，TPS/MSPT 是唯一需版本分支的关键采集点。

## 备选方案
- **依赖第三方**：现成第三方方案难以同时满足本项目的多版本 + Folia 兼容需求，且会引入额外依赖面。否决。
