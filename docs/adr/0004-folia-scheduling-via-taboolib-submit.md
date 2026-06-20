# ADR-4：Folia 调度直接用 TabooLib `submit`

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11 补记为独立 ADR。

Folia 移除了传统的 `Bukkit.getScheduler()`，改为区域化调度（`GlobalRegionScheduler`/`AsyncScheduler`），因此跨 Folia 与非 Folia 的统一调度需要处理这一差异。TabooLib 的 `submit()/submitAsync()` 已原生适配 Folia：在 Folia 上自动走 `GlobalRegionScheduler`（同步）/`AsyncScheduler`（异步），不碰已被移除的 `Bukkit.getScheduler()`（见 ARCHITECTURE.md §7.2、PRD §5.3）。

## 决策
Folia 下的调度直接使用 TabooLib 的 `submit`，探针所有定时采集一律走 `submit`，严禁直接 `Bukkit.getScheduler()`。

## 理由
- TabooLib 已原生适配 Folia 调度，自动在 Folia 与非 Folia 间分流，零胶水即可统一调度。
- 直接复用现成适配避免了自写区域调度适配器的成本与维护面。

## 后果
- 正面：调度层零额外胶水，Folia 与非 Folia 统一走同一入口，部署与维护简单。
- 负面/约束：形成硬性规则——探针所有定时采集必须走 TabooLib `submit`，严禁直接调用 `Bukkit.getScheduler()`（否则在 Folia 上会因该 API 已被移除而失败）。

## 备选方案
- **自写 RegionScheduler 适配**：在 TabooLib 已原生适配的情况下重复造轮子，增加实现与维护成本。否决。
