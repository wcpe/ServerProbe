# 功能规格：MCP GC/JFR 详诊

> 状态：草拟　·　关联 PRD：FR-22　·　分支：feature/fr22-gc-jfr-detail

## 1. 背景与目标

现有指标只采集 GC 计数/耗时聚合（`gcYoungCount/TimeMs`、`gcOldCount/TimeMs`、`gcCollectors`），无法回答"GC 卡顿在哪、对象分配压力、老年代碎片、频繁 Full GC 根因"。本 FR 为 MCP 补充 GC 详诊入口（GC 事件明细、JFR 采集），辅助定位内存/GC 类事故。优先级 P3，低优先。

## 2. 需求（要什么）

- MCP 新增工具 `gc_events`：返回**自上次调用以来**（core 保存上次样本，请求驱动差分窗口）的 GC 事件增量（`GarbageCollectorMXBean` 的 countDelta/timeDeltaMs）。
- MCP 新增工具 `gc_stats`：返回当前堆/非堆使用、各内存池详情、GC **累计值**（`gcTotalCount`/`gcTotalTimeMs`）与上次采样差分（`gcCountDelta`/`gcTimeDeltaMs`）。
- MCP 新增工具 `jfr_start` / `jfr_stop`：经 Arthas `jfr` 命令（`jfr start` / `jfr stop`）采集 JFR 到工作区，产物供 FR-18 二进制回传。
- 优先复用**内嵌 Arthas** 的 `jfr` 命令；`vmoption`（Arthas）作为可选补充：查询 GC 相关 JVM 参数（如 `-XX:+UseG1GC`、`MaxHeapSize`）辅助判断 GC 配置，经 `arthas_execute {"command":"vmoption <选项名>"}` 兜底，不作为独立工具。
- 范围内：GC 事件/统计、JFR 启停、与 FR-18 产物回传联动。
- 不做：GC 日志文件解析、堆栈分析、自动调优建议、长期 JFR 留存（沿用工作区保留策略）。

## 3. 设计（怎么做）

- **core** `NativeMcpToolProvider` 扩展：
  - `GC_EVENTS` / `GC_STATS`：经 `ManagementFactory.getGarbageCollectorMXBeans()` 做**单调计数两点差分**（countDelta/timeDeltaMs；**参考** core 既有 `MetricAggregator.computeGcRates` 的差分口径，但数据源与语义不同——既有是基于 MetricSnapshot 聚合字段算速率（次/秒），本 FR 是基于 MXBean 原始 collector 算绝对值差，故新建公共 `GcDiff` 纯函数，不强行复用私有方法），MCP 请求线程同步执行，core 保存上次样本供差分。
  - `JFR_START` / `JFR_STOP`：封装 Arthas `jfr start [options]` / `jfr stop --filename <path>` 原始命令，提交到 `arthasControl.submit`（异步任务模型），文件名经 `workspace.outputPath` 白名单校验；`jfr_stop` 支持默认命名 `jfr-<epochMillis>.jfr`（与 FR-21 一致，减少 agent 负担）。
- **数据模型**：`gc_events` 返回 `{events:[{collector,countDelta,timeDeltaMs,heapAfterGcKb?}]}`（`heapAfterGcKb` 经 `GarbageCollectorMXBean.getLastGcInfo()` 获取，**多数 JVM 默认返回 null**，仅 JVM 支持时填充，验收不依赖它）；`gc_stats` 返回 `{heapUsedKb,heapCommittedKb,heapMaxKb,nonHeapUsedKb,pools:[{name,usedKb,committedKb,maxKb,type}],gcTotalCount,gcTotalTimeMs,gcCountDelta,gcTimeDeltaMs}`。
- **依赖**：JFR 产物回传依赖 FR-18；GC 事件/统计不依赖。
- **降级**：JFR 在无 Arthas attach 或平台不支持时返回结构化失败；GC 工具始终可用（JMX）。
- **数据模型**：`gc_events` 返回 `{events:[{collector,countDelta,timeDeltaMs,heapAfterGcKb?}]}`；`gc_stats` 返回 `{heapUsedKb,heapCommittedKb,heapMaxKb,nonHeapUsedKb,pools:[{name,usedKb,committedKb,maxKb,type}],gcTotalCount,gcTotalTimeMs}`。
- **并发**：GC 读为 JMX 只读；JFR 启停走 Arthas 任务线程。

## 4. 任务拆分

- [ ] 抽公共 `GcDiff`（core 复用）+ `gc_events`/`gc_stats` 工具 + 单测（差分、字段、空采样）。
- [ ] `jfr_start`/`jfr_stop` 薄封装（Arthas jfr 命令、产物路径校验）+ 单测（命令构造、非法名）。
- [ ] 依赖 FR-18 联调 JFR 产物回传。
- [ ] E2E 真机：受控负载下 `gc_events` 有非零增量；`jfr_start/stop` 产出 JFR 且可回传。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG。

## 5. 验收标准

- 单测：GC 差分正确（首采/无变化/并发采样）、gc_stats 字段完整（含累计值+delta）、jfr 命令构造与非法名拒绝全绿。
- 真机（Paper）：`gc_events` 在 GC 活动后返回非零增量；`jfr_start` 成功、`jfr_stop` 产物在工作区且经二进制回传字节一致。
- 降级：无 Arthas attach 时 `jfr_*` 明确失败；`gc_*` 不受影响（JMX 始终可用）。
- `heapAfterGcKb` 通常为 null（依赖 JVM GC 详情支持），不作为验收判据。

## 6. 风险 / 待定

- JFR 仅在 OpenJDK 系（含 JFR 功能）可用；Oracle 旧版或精简 JRE 无 jfr 模块 → 结构化失败提示。
- `jfr start` 可能影响性能（采样开销）→ 文档提示"短时采样"，默认 `jfr start --duration` 由调用方控制。
- 待定：是否把 `gc_events` 合并进 `diagnostic_bundle`（建议合并，见 FR-23 工具组织）。
