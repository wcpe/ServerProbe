# 功能规格：内置 MultiCurrencyEconomy 与 AllinInventorySync 集成模块

> 状态：已完成验收，待下次正式版本登记　·　关联 PRD：FR-10　·　分支：当前分支

## 1. 背景与目标

FR-09 已证明业务桥协议可独立验收，但其 Bukkit Provider 仍混在 `platform-bukkit`。本功能把两个可选业务对接拆成边界清晰的仓库内模块，并继续合入同一个 `ServerProbe.jar`；服主只部署一个探针 jar，未安装业务插件时不报错、不加载外部 API 类。

## 2. 需求（要什么）

- 新建完整命名模块 `integration-multicurrencyeconomy` 与 `integration-allininventorysync`。
- 两个模块分别 `compileOnly` 对接 MultiCurrencyEconomy `1.2.0` API 与 AllinInventorySync `2.1.0-SNAPSHOT` API，产物由 `plugin` 合入单 jar。
- MultiCurrencyEconomy 通过 Bukkit `ServicesManager` 在启用期/插件启用事件后发现服务；写操作只在异步执行器运行，并保留调用方提供的确定性幂等键。
- AllinInventorySync 通过其公开 API 完成读取、写入、事件与失败回执适配；不反射访问内部 Manager。
- 两个 Provider 独立注册、独立卸载、独立报告可用状态；任一外部插件不存在、未就绪、重载或故障时不得拖垮另一集成或 ServerProbe 主体。
- 真实验收必须注入并运行 `D:\Projects\MultiCurrencyEconomy` 与 `D:\Projects\AllinInventorySync` 构建出的真实插件，不以假实现替代。
- 范围内：迁移现有经济/背包 Provider、服务生命周期、FR-09 业务命令与业务事件 E2E。
- 不做：让 MCE/AIS 反向依赖 ServerProbe、增加第三个业务集成、把集成拆成服主需另装的插件。

## 3. 设计（怎么做）

- 依赖方向固定为 `api <- project:core <- integration:* <- plugin`；集成模块可以依赖 Bukkit API，但 `project:core` 不得引用 MCE/AIS 类型。
- `plugin` 只负责把两个模块的编译输出合入发行 jar；外部 API 保持 `compileOnly`，不得被重复打包。
- 每个模块提供一个 `BusinessProviderFactory`。工厂先用插件存在性与类存在性双重检查，再在外部插件就绪后创建 Provider；卸载事件撤销注册并释放监听器。
- MCE 操作沿用公开 Service 契约：查询建议异步，所有写操作强制异步；结果按 `success/errorCode` 映射为 FR-09 回执，不以异常表达业务失败。
- 真实插件路径只作为 mc-testkit 的本机输入，公共 DSL、配置和仓库文档不写死绝对路径。
- 架构决策在规格获批后写入 ADR-0018。

## 4. 任务拆分

- [x] 先补模块边界、缺失插件、动态启停、线程与回执单元测试。
- [x] 新建两个集成模块并迁移现有 Provider，删除迁移后孤立代码。
- [x] 调整单 jar 组装并加入外部 API 未打包检查。
- [x] 构建真实 MCE/AIS 插件并通过 mc-testkit 注入 Paper/Folia 场景。
- [x] 验证经济写入幂等、背包读写、事件回传、失败/恢复和卸载。
- [x] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG。

## 5. 验收标准

- `ServerProbe.jar` 内含两个集成模块实现，但不含 MCE/AIS API 类；无两个业务插件时 ServerProbe 正常启用且 Provider 状态为不可用。
- 只安装其中一个插件时只注册对应 Provider；插件禁用/重启后注册状态正确收敛。
- 真实 MCE `1.2.0` 场景至少覆盖查询、成功写入、重复幂等命中、业务错误与恢复；写操作不在 Bukkit/Folia region 主执行线程阻塞。
- 真实 AIS `2.1.0-SNAPSHOT` 场景至少覆盖读取、写入、事件回执、冲突/失败与恢复。
- FR-09 RFC 6455 fixture 经业务桥调用两个真实 Provider，结果文件为 PASS；不运行 JianManager。
- 既有 FR-08/FR-09 自动化、全量单元测试、IoC 门禁和单 jar 检查全部通过。

## 6. 风险 / 待定

- 外部插件快照 API 可能变化；实现时以两个本地源码仓库的公开 API 与实际发布坐标为准，并把版本集中在构建变量中。
- Folia 下回调 Bukkit 对象必须切回对象所属 region；仅纯数据转换和外部存储调用可在异步线程执行。
- 验收过程落实的两项外部源码修复：MCE `BalanceOperationResult` 公开透传幂等与审计字段（探针读取 `idempotentHit`）；AIS 新玩家先做零差异公开写入建立权威快照、追踪事件经 AIS 实际 classloader 注册。E2E 余额比较使用 `BigDecimal.compareTo` 消除 scale 误判。

## 7. 验收记录（2026-08-28）

- 四组 mc-testkit 真实场景全部 PASS：`integrations-both`（真实 MCE 1.2.0 + AIS 2.1.0-SNAPSHOT 注入）、`integrations-mce-only`、`integrations-ais-only`、`integrations-none`；结果文件位于 `build/mc-testkit/results/`。
- 运行期强制 HTTP/HTTPS 代理 `127.0.0.1:9` 断网；TabooLib 运行时来自 616 项离线闭包，日志无仓库下载。
- 全量单测、IoC 门禁与发行 jar 外部 API 未打包检查通过；逐条证据见 `.tmp/acceptance-phase-M5-2026-08-28.md`。

