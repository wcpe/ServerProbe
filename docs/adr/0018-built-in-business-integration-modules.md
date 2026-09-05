# ADR-0018：内置业务集成使用独立 Gradle 模块

## 状态

已接受（取代 [ADR-0015](0015-business-integration-agent.md) 决策 1 中“Provider 实现落 platform-bukkit”的部分；其余决策继续有效）

## 背景

ADR-0015 把首批经济与背包 Provider 放入 `platform-bukkit`，当时能以最小改动验证 FR9。但 MultiCurrencyEconomy 与 AllinInventorySync 都是可选外部插件，和通用 Bukkit 指标采集没有共同生命周期；继续同置会使外部 API、业务异常边界与采集代码耦合，也无法独立做真实插件验收。

## 决策

将两个 Provider 分别迁入仓库内独立模块 `integration-multicurrencyeconomy` 与 `integration-allininventorysync`，两者随 `plugin` 合入同一 `ServerProbe.jar`，外部 API 一律保持 `compileOnly`。

## 理由

- 业务插件可选且独立失败，模块边界让缺失、禁用、更新与故障仅影响自身 Provider。
- `core` 的业务路由与隔离继续平台无关；集成模块单向依赖 `core`/`api` 与 Bukkit API，不把 MCE/AIS 类型带入核心。
- 单 jar 保留现有部署体验，不要求服主额外安装 ServerProbe 扩展插件。

## 后果

- 依赖方向扩展为 `plugin → platform-* / integration-* / diagnostics-* → core → api`，不得反向依赖。
- 两个模块各自发现外部服务、注册/撤销 Provider，并有独立真实插件 E2E。
- MCE 写操作仍需异步和确定性幂等键；AIS 仅使用公开 API。

## 备选方案

- **维持在 platform-bukkit**：文件少，但可选集成与通用采集耦合，测试和卸载边界不清晰，否决。
- **拆成独立可安装插件**：隔离更强，但增加部署和版本组合负担，与单 jar 目标冲突，否决。

