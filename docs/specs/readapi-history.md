# 功能规格：只读 API 暴露历史指标回读（readHistory）

> 状态：草拟　·　关联 PRD：FR-26　·　分支：feature/readapi-history

## 1. 背景与目标

`ProbeReadApi`（FR-08 只读开放接口）目前只能读内存环形缓冲（约 30 分钟）；磁盘历史 JSONL 已保留 7 天、归档 30 天，但**公开只读接口没有暴露这份数据**——第三方插件要做趋势分析只能自己去实现存储 SPI（受控替换注册面，并非只读消费面）。本 FR 在公开 API 上补一个历史回读方法，底层能力（`MetricStore.readHistory`，带默认实现的接口方法）已存在，属于纯暴露增强。

## 2. 需求（要什么）

- `ProbeReadApi` 新增 `historySnapshots(long sinceMs, long untilMs, int limit)`：
  - 语义：按时间范围从**历史后端**（本地文件/第三方存储 SPI）读取指标快照，由新到旧返回至多 `limit` 份。
  - 契约：**可能读盘**——Javadoc 必须显式写明"调用方宜在异步上下文调用"（与 `historyStartupProfiles` 同款措辞），与"主线程禁阻塞 IO"红线对齐。
- `ProbeReadApiImpl` 实现委派既有 `MetricStore.readHistory(sinceMs, untilMs, limit)`。
- 范围内：`api` 契约 + core 实现 + 单测 + 文档同步。
- 不做（范围外）：
  - 不改 `MetricStore` SPI（`readHistory` 已是带默认实现的方法，零改动）。
  - 不做分页游标/排序选项（YAGNI：limit + 时间范围已覆盖趋势回读场景；确需分页时另行增强）。
  - 不做内存缓冲与历史的合并去重（调用方可用 `recentSnapshotsSince` 自行拼接）。

## 3. 设计（怎么做）

- `api/ProbeReadApi.java` 新增 **default 方法**（默认返回空列表），严格沿用 `queryNetworkPackets`/`networkForensicsStatus` 的向后兼容范式（[ProbeReadApi.java:97-110](../api/src/main/java/top/wcpe/mc/plugin/serverprobe/api/ProbeReadApi.java)）——既有第三方实现零改动、二进制兼容。
- `core/api/ProbeReadApiImpl` override 委派 `store.readHistory(...)`；后端不支持/无数据返回空列表（与 `MetricStore` 默认实现语义一致）。
- 参数校验沿用 store 层既有行为（limit 非正返回空列表）；core 侧不做二次裁剪。

## 4. 任务拆分

- [ ] `ProbeReadApi.historySnapshots` default 方法 + Javadoc（时间范围语义、读盘警示、兼容性声明）。
- [ ] `ProbeReadApiImpl` override 委派实现。
- [ ] 单测：本地文件后端下范围命中/越界/limit 裁剪/无数据空列表；第三方 stub 实现（不改）零回归。
- [ ] 文档同步：`docs/API.md` §3 方法表补条目、`CHANGELOG.md` 未发布段、PRD FR-26 状态流转。

## 5. 验收标准

- 单测全绿：时间范围过滤正确、limit 生效、无数据返回空列表；对只实现旧接口的第三方 stub 编译通过且调用 `historySnapshots` 得空列表（兼容性回归）。
- 真机 E2E（read-api 场景扩展）：第三方夹具写入历史 → 经 `ServerProbeApi` 读回数据一致；主线程调用该方法的用例**不进** E2E（红线：文档明示异步调用即可）。
- `api` 模块编译产物对既有消费方二进制兼容（default 方法新增不破坏链接）。

## 6. 风险 / 待定

- 大时间范围 + 大 limit 的读盘量：实现按 store 层既有上限执行（与 `/history` Web 页同一数据源、同一裁剪），文档写明"返回条数受存储层上限约束"。
