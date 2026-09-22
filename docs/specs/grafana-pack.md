# 功能规格：Grafana 看板与 Prometheus 告警规则随发行提供

> 状态：草拟　·　关联 PRD：FR-27（免 spec，按流程仍建档）　·　分支：feature/grafana-pack

## 1. 背景与目标

`/metrics` 指标已齐全且命名/label/单位规范冻结（API.md §5.4），但仓库内**没有任何 Grafana dashboard 或告警规则产物**（全仓检索无 dashboard JSON）——用户接大盘要自己从零搭。本 FR 随发行提供"导入即用"的看板与规则模板，兑现 PRD §2 运维角色"接 Grafana 看板与报警"的直接诉求。纯产物 + 文档，不改任何业务代码。

## 2. 需求（要什么）

- 新增随发行分发的产物目录（如 `grafana/`，不进发行 jar，随仓库/Release 分发）：
  - **Dashboard JSON**（1 份）：JVM 概览（堆/线程/GC 速率）、TPS/MSPT、在线人数、代理端（子服可达性/RTT）、CPU 归因 Top、**启动耗时（依赖 FR-25 的 `startup_total_seconds` 先落地）**、网络取证速率与脱敏 Top-N。
  - **Prometheus 告警规则模板**（1 份 YAML）：TPS 低、MSPT p95 高、堆占用高、死锁（对齐 FR-05 现有 4 种 AlertType 口径；仅模板非探针内置）。
- `docs/OPERATIONS.md` 增"接 Grafana"一节：导入步骤、数据源指向、指标与 label 对照表链接（API.md §5.4）。
- 范围内：产物文件 + 文档 + CHANGELOG。
- 不做（范围外）：
  - 不改任何业务代码与指标定义（指标名以 API.md §5.4 为唯一真源）。
  - 不做看板变量级的多实例聚合（ADR-9 各端独立，模板单实例视角即可）。
  - 不做 recording rules 的服务端聚合（YAGNI）。

## 3. 设计（怎么做）

- Dashboard 用 Grafana 通用 schema（尽量低版本兼容的写法，不追花式），数据源变量化。
- 每个面板标注对应指标名/label（与 API.md §5.4 一一对应，防双源漂移——规则表已冻结，模板只引用不发明）。
- 告警规则阈值给默认建议值并注释"按服调整"；`for`/防抖时长与探针 FR-05 防抖口径一致。

## 4. 任务拆分

- [ ] `grafana/dashboard.json`（含启动耗时面板；FR-25 未合入前先留空面板占位并注释）。
- [ ] `grafana/alerts.yml`（4 条规则模板 + 注释）。
- [ ] `docs/OPERATIONS.md` 增"接 Grafana"节；`README` 文档区链接；`CHANGELOG.md` 未发布段。
- [ ] 自检：JSON 可被 Grafana 导入（schema 校验/本地 Grafana 实测其一即可）；YAML 语法校验。

## 5. 验收标准

- 产物自检通过：dashboard JSON 结构合法、alerts YAML 可被 promtool 或语法器接受。
- 文档对照：面板/规则引用的每个指标名与 API.md §5.4 完全一致（人工比对清单）。
- 真机（Paper）：启动后按 OPERATIONS 步骤导入，JVM/TPS/MSPT/在线面板出数；启动面板在 FR-25 合入后出数。

## 6. 风险 / 待定

- Grafana 版本兼容：以"最近 LTS 可导入"为底线，不声明全版本兼容。
- 指标名后续演进时模板需跟随——在 OPERATIONS 节注明"模板与 API.md §5.4 同步维护"。
