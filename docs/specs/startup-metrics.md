# 功能规格：启动画像指标进 Prometheus

> 状态：开发中　·　关联 PRD：FR-25　·　分支：feature/startup-metrics

## 1. 背景与目标

产品首要目标 G1 是"开服慢可量化"，但启动画像目前只在 `/probe startup` 命令、Web 面板与本地 JSON 文件三个出口可见，**Prometheus `/metrics` 完全没有启动维度**（全文件无任何 startup 指标）。接大盘的运维看不到"开服耗时趋势"，也无法配置"启动超基线"类告警——而这正是 PRD §2 运维角色"接 Grafana 看板与报警"的直接诉求。

本 FR 把**进程内已缓存的最近一次启动画像**补导出为 Prometheus 指标，属于"数据已采集、只补出口"的纯接线增强，不新增任何采集逻辑。

## 2. 需求（要什么）

- `/metrics` 新增**启动画像指标段**（仅当内存中存在最近一次启动画像时输出；缺失/无画像的平台整段跳过，不输出空指标）：
  - `serverprobe_startup_total_seconds`（gauge）：端到端启动总耗时（毫秒→秒，遵循 API.md §5.4 "时间一律秒"的规范）。
  - `serverprobe_startup_plugin_seconds{plugin}`（gauge）：逐插件 onEnable 耗时，label 为插件名；受画像自带 Top-N/抽稀上限约束，超出部分不输出。
  - `serverprobe_startup_world_seconds{world}`（gauge）：逐世界加载耗时，label 为世界名。
- 数据源：`StartupProfileHolder` 的**内存值**（经 `readApi` 或直接注入均可，实现时取与现有 `MetricsHttpHandler` 注入风格一致的一种）。
- 范围内：core 格式化器与 handler 注入、单测、文档同步。
- 不做（范围外）：
  - **不读盘**：绝不走 `lastStartupProfile()` 的落盘回退路径——`/metrics` 是 HTTP 请求线程，读盘违反"主线程/请求线程禁止阻塞 IO"红线；内存无画像时直接整段跳过。
  - 代理端（Bungee/Velocity）不输出本段（无启动画像生产者，非降级、是平台本无此概念）。
  - 不做启动历史时序（历史序列由既有 history JSONL 归档承担，不在 /metrics 重复）。
  - 不新增配置键。

## 3. 设计（怎么做）

- `core/prometheus/PrometheusTextFormatter` 新增 `appendStartup(profile)` 段函数，沿用既有"null/缺失整行跳过""gauge 语义、无 `_total` 后缀"约定；label 值经既有转义处理。
- `MetricsHttpHandler` 增加画像来源参数（`StartupProfileHolder` 或等价只读来源，可空注入以保代理端兼容），拼装进现有输出流。
- 指标命名/单位/label 规范与 `docs/API.md` §5.4 一致；`serverprobe_` 前缀由导出器统一处理。
- 无新 ADR（不改架构决策，仅扩呈现出口）；`api` 契约模块不动（Holder 非 `api` 模块类型，实现层注入）。

## 4. 任务拆分

- [ ] `PrometheusTextFormatter.appendStartup`：总耗时 / 逐插件 / 逐世界三组指标（画像为 null 时整段跳过）。
- [ ] `MetricsHttpHandler` 注入内存画像来源并接线（代理端无来源时保持现状输出）。
- [ ] 单测：有画像输出全部三组且数值=毫秒/1000；无画像零输出；插件/世界 label 转义；空列表不产生空标签行。
- [ ] 文档同步：`docs/API.md` §5.4 补服务器组启动指标条目、`docs/wiki/Metrics.md` 补说明、`CHANGELOG.md` 未发布段记录、PRD FR-25 状态流转。

## 5. 验收标准

- 单测全绿：格式化输出含 `serverprobe_startup_total_seconds`/`_plugin_seconds{plugin}`/`_world_seconds{world}`，数值与画像毫秒值换算一致；无画像时输出与现版本逐字节一致（零回归）。
- 真机（Paper）：启动后访问 `/metrics`（或 curl 带鉴权）可见三组真实数值，与 `/probe startup` 的总时长/慢插件榜一致；**重启前后的值随最新一次启动变化**。
- 真机（BungeeCord 或 Velocity）：`/metrics` 输出不含任何 `startup_` 行（平台本无画像，非降级）。
- `/metrics` 请求耗时无可测退化（不触发任何读盘——可用大画像目录下重复请求验证响应时间稳定）。

## 6. 风险 / 待定

- 画像的插件榜受 Top-N 抽稀限制，指标只含榜单内插件（数量级固定，无 label 爆炸风险）——文档需说明"仅 Top-N"口径。
- label（插件/世界名）含特殊字符时依赖既有转义；实现时补断言用例。
