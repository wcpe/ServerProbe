# 演进与维护指南

> 本文规定文档如何随代码演进、ADR 如何迭代、新需求如何落地。**目标：防止文档腐朽、变味、漂移。**

## 1. 黄金法则：文档即代码（docs-as-code）

- 文档与代码在**同一仓库、同一次变更**里一起改。
- **完成定义（DoD）**：一个变更没有把受影响的文档改到一致，就**不算完成**。
- **单一真源**：同一事实只在一处权威描述，别复制散落到多处各说各话；要复用就引用。

## 2. 文档地图（谁管什么 · 何时更新 · 入库否）

| 文档 | 管什么 | 何时更新 | 入库 |
|---|---|---|---|
| `docs/PRD.md` | 需求（WHAT/WHY）：目标、角色、功能需求（FR1–FR8）、验收、迭代规划 | 需求增删改时 | ✓ 活文档 |
| `docs/specs/<feature>.md` | 非平凡功能的开发期工作规格（需求/设计/任务/验收） | 开发该功能时 | ✓ 留作记录 |
| `docs/ARCHITECTURE.md` | 系统设计（HOW）：模块、数据模型、机制、多版本/多平台、Folia | 结构/机制/依赖变化时 | ✓ |
| `docs/adr/*` | 重大决策的"为什么"（当前 ADR-1~12） | 做出/推翻架构决策时（见 §3） | ✓ |
| `docs/API.md` | 对外接口契约（只读 API / 存储 SPI / Prometheus 端点 / `/probe` 命令） | 接口变更时 | ✓ |
| `docs/OPERATIONS.md` | 部署 / 升级 / 备份 / 回滚 / 排障 | 运维方式变化时 | ✓ |
| `SECURITY.md` | 信任模型、敏感数据、漏洞报告 | 安全模型变化时 | ✓ |
| `docs/wiki/*` | 面向用户的安装/命令/指标/启动剖析指南 | 对应能力变化时 | ✓ |
| `CHANGELOG.md` | 变更史 | 每个用户可见变更 | ✓ |
| `README.md` | 入口与导航 | 总览变化时 | ✓ |
| `.tmp/*` | 里程碑、勾选、探索笔记 | 随便改，做完即弃 | ✗ 易朽 |

**判据：活文档（长期维护、是真源）入库；易朽稿（做完即弃）留 `.tmp/`。**

## 3. ADR 生命周期（不可变 + 取代）

- ADR 一旦"已接受"就**不可变**：**不编辑**旧 ADR 的决策正文。它是**决策史，永久保留、只增不删、编号永不复用**。
- 决策变了 → 写一条**新 ADR 取代旧的**：
  - 新 ADR 在背景里写"取代 ADR-NNNN"。
  - 旧 ADR 只把**状态行**改为"已被 ADR-MMMM 取代"并加链接（**不动正文**）。
- 状态：`提议中 → 已接受 → 已弃用 / 已被取代`。
- **何时写 ADR**：引入新技术、采用或推翻一个架构模式、做有长期影响且有争议的取舍。小决定不用写。

### 3.1 ADR 实操（维护期）

- **编号**：= 现有最大编号 + 1，永不复用、不补洞。现有最大看 `docs/adr/` 目录（当前到 ADR-12 → 下一个 **ADR-13**），别硬记。
- **写不写**：日常加功能若落在既有决策内，不写；只有上面"何时写"的情形才写。
- **取代怎么做**：① 新建 ADR（取下一个空号），背景写"取代 ADR-NNNN"；② 把被取代旧 ADR 状态行改为"已被该新 ADR 取代" + 链接，**正文一字不动**；③ 同步改受影响的 `.claude/rules/*`（如撤掉对应不变量红线）、`ARCHITECTURE.md` §11 索引、相关 wiki。

**规模与导航（别慌通读）**：ADR 有意稀少。理解系统看 `ARCHITECTURE.md`（永远是现状综合），ADR 只在查"当初为什么"时按需翻；**当前架构 = 未被取代的活跃集**，不必通读所有 ADR。

## 4. 变更工作流（新需求 / 新功能如何落地）

```
1. 改 PRD（docs/PRD.md）         增/改需求，标 P0/P1/P2 + 状态（计划/开发中/已交付）
2. 影响架构？→ 写新 ADR          引入/推翻架构决策时（必要时取代旧 ADR）
3. 改 ARCHITECTURE.md            反映新模块 / 数据模型 / 机制 / 依赖
4. 改 API.md                    对外接口契约变更
5. 实现 + 测试                  过验证门（./gradlew build 全绿 + 涉及真机维度的真机复验）
6. 记 CHANGELOG（未发布段）       用户可见变更，引用具体模块/能力
7. 发版                         按提交定 SemVer、定稿 CHANGELOG 本版本段、打 tag
```

> 一次只做一件事；破坏性变更必须在 CHANGELOG + 相关文档写清迁移（落盘对象用 `schemaVersion` 容错）。

## 5. 防漂移检查清单（每次变更自检）

- [ ] 代码改了，PRD / ARCHITECTURE / API 是否还一致？
- [ ] 新增或推翻了架构决策，是否有对应 ADR（且旧 ADR 已标记取代）？
- [ ] 破坏性变更，CHANGELOG 与迁移说明是否写了？
- [ ] 文档里的版本号 / 坐标 / 路径 / 端点 / 权限节点是否过时？
- [ ] 同一事实是否只有一个真源？

## 6. 定期复检

- 每个里程碑 / 发版前，过一遍 §2 文档地图，确认无漂移。
- 发现"代码这样、文档那样" → **当 bug 修**，不积压。

## 7. 与 AI 协作

本仓库常与 AI 代理协作。文档同步要求已固化为 `.claude/rules/doc-sync.md`，未来任何会话改代码都会被要求同步文档，避免跨会话漂移。

## 8. 分支模型与发布渠道（现状 + 推荐路径）

**现状（单人开发）**：当前仓库**单 `master` 分支、无 remote、无 CI**，提交直推 `master`。提交须中文 Conventional Commits、无 AI 署名、过验证门（见 `.claude/rules/git-commit.md`）。

**推荐路径（开始协作 / 公开发布时启用）**：采用 GitHub Flow——

- **主干**（`master`）：始终可发布；多人协作后改动经 PR 合入（PR 模板含防漂移自检，见 `.github/PULL_REQUEST_TEMPLATE.md`）。
- **`feature/*`、`fix/*`、`refactor/*`、`hotfix/*`**：短生命周期分支，做完发 PR 回主干。
- **稳定发布**：打 `vX.Y.Z` tag（`sdd-release-version` 技能）。
- **回滚**优先 `git revert`，不重写已 push 历史（`sdd-rollback-change` 技能）。
- **CI**：建立公开仓库后，把"构建 + 测试 + lint"设为合并前门禁（见 `.claude/rules/static-analysis.md` §2，属待引入）。

**版本号当前权威来源 = 根 `gradle.properties` 的 `version` 字段**（Gradle 构建原生读取并注入各模块产物）。SDD 约定的根 `VERSION` 单一来源文件**本项目暂未引入**（避免与 `gradle.properties` 产生双源）；是否引入并接入构建，待维护者定夺。
> 注：当前版本口径存在不一致（`gradle.properties`=`1.0.0-SNAPSHOT`、路线图首版=`0.1.0`、README/PRD 标 `v0.2-draft`），统一口径为待办项。

## 9. 文档如何长期演进（本次会话之后）

| 文档 | 演进方式 |
|---|---|
| `docs/PRD.md` | **增量 + 状态流转**：加需求即加一行 FR（`计划`→`开发中`→`已交付@vX.Y.Z`），已交付的保留并标版本、不删——它是活的路线图 |
| `docs/ARCHITECTURE.md` | **原地更新**：始终反映当前系统真貌；结构 / 机制变了就改它 |
| `docs/adr/*` | **只追加 + 取代**：决策变了写新 ADR 取代旧的，旧的不删（§3） |
| `docs/API.md` | **原地更新**：始终是当前契约 |
| `CHANGELOG.md` | **累积 + 发版分段**：变更先进未发布段，发版时切成 `## X.Y.Z` 段 |

**文档冷热分层**：

| 冷热 | 文档 | 多久动一次 |
|---|---|---|
| 🔥 高频 | `CHANGELOG.md` | 每个用户可见变更 |
| 🔥 高频 | `docs/PRD.md` | 每个新需求 / 交付（加行 / 改状态） |
| 🌡 中频 | `docs/ARCHITECTURE.md`、`docs/API.md`、`docs/wiki/*` | 结构 / 机制 / 接口 / 能力变更时 |
| 🌡 中频 | `docs/OPERATIONS.md`、`docs/specs/<feature>.md` | 部署变化时 / 功能开发期 |
| ❄ 低频 | `docs/adr/*`、`README.md`、`SECURITY.md` | 架构决策时追加 / 总览或安全模型变化时 |
| 🧊 近乎不变 | `.claude/rules/*`（尤其 `architecture-invariants`）、`.editorconfig`、`.gitignore`、`gradle.properties` 版本（仅发版动） | 极少；改不变量 / 红线要慎重并配 ADR |

把"改不变量 / 红线"当大事——真要改先走 ADR，别随手动。

## 10. 维护迭代周期（稳态操作手册）

**每个工作项的标准循环**：

1. **识别工作项**，选对应技能（路由见下表）。
2. **（协作期）开分支**：`feature/*` / `fix/*` / `refactor/*` / `hotfix/*`（§8）；单人期可直推 `master`。
3. **按技能走**：读相关 PRD / ARCHITECTURE / ADR → 测试先行 → 实现（守不变量、简单优先）→ 过验证门 → `doc-sync` 同步文档。
4. **提交 / 发 PR**：填防漂移自检 → 评审（协作期）→ 合入主干。
5. **攒够一批 → 发版**（`sdd-release-version`：CHANGELOG 分段、定 SemVer、打 `vX.Y.Z`）。
6. **生产事故** → `sdd-hotfix` 旁路：从发布 tag 切分支最小修 → 出补丁版 → 回流主干。

**工作项 → 技能 路由**：

| 来了什么 | 用哪个技能 |
|---|---|
| 新需求 / 新能力 | `sdd-develop-feature` |
| bug / 报错 / 行为不对 | `sdd-fix-bug` |
| 代码太乱 / 拆分 / 消除重复 | `sdd-refactor-code` |
| 撤掉某功能 / 回退 | `sdd-rollback-change` |
| 升级第三方依赖 | `sdd-bump-dependencies` |
| 纯文档工作（写 ADR / 改架构说明 / 修文档漂移 / 整理文档） | `sdd-update-docs` |
| 出快照 / 给人试用 | `sdd-publish-snapshot` |
| 正式发版 | `sdd-release-version` |
| 生产紧急修 | `sdd-hotfix` |
| 外部 / 计划外提交进来需对齐文档 | `sdd-reconcile-external-commits` |
| sdd-skills 更新了治理模板，要同步进本项目 | `sdd-sync-governance` |

### 10.1 一次变更各动哪些（速查）

| 来了什么 | 要动 | 不用动 |
|---|---|---|
| **feat 新功能** | PRD §7 加一行 FR（贴优先级 + 状态 `计划`）· 非平凡写 `docs/specs/<f>.md` · 结构变更动 `ARCHITECTURE` · 接口变更动 `API` · `CHANGELOG` +1 行 · 加测试 | `gradle.properties` 版本（发版才动） |
| **fix 修 bug** | `CHANGELOG` +1 行 · 复现 + 回归测试 | PRD · 版本 · ADR · API |
| **refactor 重构** | 结构变才动 `ARCHITECTURE` · 测试前后同样全绿 | PRD · API · 行为 |
| **rollback 回滚** | FR 状态回退 · 取代相关 ADR · `CHANGELOG` +1（移除） | —— |
| **依赖升级** | 有感知影响才记 `CHANGELOG` · 全测试绿 | PRD · ADR |
| **架构决策** | **ADR +1 条（或取代旧的，编号 = 现有最大 +1）** · 更新 `ARCHITECTURE` + §11 索引 | 版本 |
| **发版 release** | **`gradle.properties` 版本改（按提交定 SemVer）** · `CHANGELOG` 未发布段 → `## X.Y.Z` · 交付的 FR 翻 `已交付@vX.Y.Z` · 打 tag | —— |

**谁常动 / 谁不动**：
- 🔥 高频：`CHANGELOG`（几乎每次）、PRD FR 表（每个 feat 加行 / 发版翻状态）、`gradle.properties` 版本（每次发版）。
- ❄ 低频：ADR（只在架构决策时 +1 或取代）。
- 🧊 几乎不动：`.claude/rules`、`architecture-invariants`（动它 = 动根基，要配 ADR）。
