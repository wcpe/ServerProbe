# ADR-6：单 jar 多端分发

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11 补记为独立 ADR。

ServerProbe 需同时支持 Bukkit 系（含 Folia）与 BungeeCord 代理端。分发形态上可选"单 jar 多端"或"分平台多 jar"。TabooLib 支持单 jar 内同时写入 `plugin.yml` + `bungee.yml`，各端读各自描述符并只激活当前平台对应实现（按 `@PlatformSide` 隔离）；同时不同 major version 的 class 可共存于一个 jar，按需加载保证低版本 JVM 永不触碰高版本 class（见 ARCHITECTURE.md §5 / §12）。

## 决策
采用单 jar 多端分发：同一个 jar 同时部署到 Bukkit 端与 BungeeCord 端，各端读各自描述符并激活对应平台实现。

## 理由
- TabooLib 原生支持单 jar 多端（同 jar 内 `plugin.yml` + `bungee.yml`，各端读各自描述符）。
- 单一产物部署简单，用户无需为不同平台分别取用不同 jar。

## 后果
- 正面：部署简单，一个产物覆盖所有目标端；配合按需加载，多 toolchain 的 class 可共存于同一 jar 而不触发 `UnsupportedClassVersionError`。
- 负面/约束：需保证平台实现按 `@PlatformSide` 严格隔离、运行时只激活当前平台对应实现，并依赖按需加载机制确保各端只触碰自己需要的 class。

## 备选方案
- **分平台多 jar**：在 TabooLib 已支持单 jar 多端的前提下，分平台打包会增加分发与部署的复杂度。否决。
