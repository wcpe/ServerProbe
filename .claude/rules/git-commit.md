# Git 提交规范

> 适用于本仓库所有 `git commit` 操作。

## 1. 提交信息语言（强制）

- **标题（Description）与正文（Body）必须使用简体中文。** 禁止英文、日文等非中文。
- Conventional Commits 的 type 与 scope 仍用英文小写（`feat`/`fix`/`refactor`/`docs`/`chore`/`test`/`build`/`ci`/`perf`/`style`）。
- **禁止在提交信息中添加任何 AI 签名或尾注**，例如 `Generated with ...`、`Co-Authored-By: ...`。不要附加作者/工具/来源署名。

### 1.1 标题格式

```
<type>(<scope>): <中文描述>
```

- `<scope>`：英文小写模块/能力域，可选。按本仓库实际划分，如：`api`、`core`、`platform-bukkit`、`platform-bungee`、`plugin`、`agent`、`command`、`collector`、`sampler`、`startup`、`store`、`alert`、`prometheus`、`config`、`docs`、`build`。
- `<中文描述>`：简洁陈述本次做了什么，必须中文，结尾不加句号。

### 1.2 正文格式

- 用空行与标题分隔，中文撰写，可用 `-` 列要点。
- 说明"为什么改"与"改动要点"，不逐行复述 diff。

### 1.3 示例

✅ 正确
```
feat(prometheus): 新增 token + IP 白名单双重鉴权

- /metrics 端点默认关闭且仅绑定本机
- 鉴权失败返回 401，端口被占用时优雅降级 warn 不影响插件启用
```

❌ 错误（标题英文）
```
feat(prometheus): add token + ip auth
```

### 1.4 禁止阶段性词语（强制）

提交按**功能点**描述，不按**开发阶段**描述。commit message（标题与正文）**禁止**出现阶段 / 批次性词语：`M1` / `M2` / `M5`、`P0` / `P1` / `P2`、`MVP`、`Sprint`、`第一期` / `本次迭代` 等。它们说的是"项目走到哪一步"而非"这次改了什么"，会随时间失效、也无法追溯到具体改动。

✅ 正确（描述功能点）
```
feat(startup): 启动画像新增与上次对比的每项 Δ
```

❌ 错误（用阶段词代替功能描述）
```
feat: 完成 M2 核心功能
chore: P1 第三批的若干功能
```

> 注：本仓库历史提交曾用过 `M1`/`M2` 等里程碑词，自本规范起新提交按功能点描述，旧历史不追溯改写。

## 2. 文档入库边界（强制）

判据：**活文档（长期维护、是真源）入库；易朽稿（做完即弃）留 `.tmp/`。**

### 2.1 应当入库的耐久文档

- 产品 / 需求：`README.md`、`CHANGELOG.md`、`docs/PRD.md`（活文档，随需求变更同 PR 更新）。
- 架构：`docs/ARCHITECTURE.md`、`docs/adr/*.md`、`docs/API.md`。
- 运维 / 安全：`docs/OPERATIONS.md`、`SECURITY.md`。
- 协作治理：`docs/CONTRIBUTING.md`、`.claude/rules/*.md`、`docs/wiki/*`。

### 2.2 严禁入库的易朽过程稿（已由 `.gitignore` 排除 `/.tmp/`）

- 实施计划 / 里程碑 / 路线图：`实施计划.md`、`PLAN.md`、`roadmap.md` 等（项目级 README/CHANGELOG 中的简短规划除外）。
- 过程性报告：`IMPLEMENTATION.md`、`执行报告.md`、`分析.md`、`audit-*.md` 等。
- AI 助手过程性笔记、交流稿、思路记录。

> 例：PRD 是活的需求规格 → 入库 `docs/`；实施计划易朽 → 留 `.tmp/`。文档与代码的同步要求见 `docs/CONTRIBUTING.md` 与 `.claude/rules/doc-sync.md`。

## 3. 最小提交粒度（强制）

- **验证门通过才提交**：`git commit` 前，本次变更必须已过验证门（判据见 `testing-and-quality.md` §1）。**门未全绿、或完成被实测推翻时，不得提交**；涉及实机 / 真机维度的，待用户确认验收通过再提交，先继续修到门过再提交。
- **独立可编译**：每个 commit 落地后代码都能编译 / 构建通过，不留"半截"提交。
- **只做一件事**：一个 commit 只对应一个功能点 / 一个修复 / 一次重构，无关改动不混入。
- **不混类型**：不在同一 commit 里混 `feat` / `fix` / `refactor`——各自独立提交（顺手发现的 bug 单独 `fix`，重构单独 `refactor`）。

✅ 正确（拆成独立、各自可编译、各做一件事）
```
feat(world): 世界采集支持按实体类型分布统计
fix(prometheus): 修复端口被占用时未降级的问题
refactor(sampler): 提取分位计算为独立纯函数
```

❌ 错误（一个 commit 混了功能 + 修复 + 重构）
```
feat: 加世界采集，顺便修个端口 bug 并重构采样器
```

## 4. 开发分支允许频繁提交

- `feature/*`、`fix/*`、`refactor/*` 等短生命周期开发分支应频繁、及时提交安全检查点，不得为了追求表面上的历史整洁而长期堆积大量未提交变更。
- 优先直接形成可保留的逻辑提交。需要把后续修正归入既有提交时，可以临时使用 `fixup!` 或 `squash!`，但它们只能存在于尚未共享的本地开发分支，并且必须在发 PR 或合入主线前整理掉。
- 临时 `WIP`、提交信息拼写修补和同一实现的跟随式修复不得未经整理直接或长期进入 `main` / `master`。
- 临时检查点可以尚未满足最终提交的完整粒度与验证门，但不得包含凭据、隐私数据等禁止内容，也不得推送或交给协作者继续开发；整理后的最终提交必须满足下文全部要求。

## 5. 提交历史整理与合并策略（强制）

### 5.1 发 PR 或合入主线前整理全部提交

发 PR 或合入 `main` / `master` 前必须审查分支上的全部提交，而不只看最后一次提交或最终 diff，并按逻辑重排、归并或拆分历史：清理 `WIP`、`fixup!`、`squash!`，修正提交信息拼写，把同一实现的跟随式修复归入所属逻辑提交，并把混合无关意图、无关需求或不同 Conventional Commit 类型的过大提交拆开。整理只能在尚未共享的本地分支上进行。

整理完成后，每个提交都必须满足单一意图、可独立理解、可独立回滚、可构建和影响范围验证门通过。开发期临时检查点可以被改写或吸收；最终逻辑提交则是供评审、合并、发布和长期追溯的稳定历史。

### 5.2 主线与 PR 合并策略

- `main` 或 `master` 主线只保留最终逻辑提交，不保留 `WIP`、`fixup!`、`squash!`、拼写修补或纯跟随式修复提交。
- 合并时优先采用能够保留整理后逻辑提交的 rebase 后 fast-forward、受控 rebase 或其他保留清晰提交序列的方式；选择方式的目标是得到可审查、可定位、可回滚的主线历史。
- 单一意图的小型 PR 可以整理为一个逻辑提交；包含多个独立意图的 PR 必须保留为多个逻辑提交，不得为了"一 PR 一提交"强行压缩。
- 禁止使用 Squash and merge 把整个版本压成单个提交；禁止把大型或多意图 PR 压缩成一个提交。版本历史应由多个按能力、修复、重构和治理划分的逻辑提交组成。
- 禁止制造"完整版本一次提交"的大提交。版本号不是提交边界，发版也不能替代此前逻辑提交的整理。

### 5.3 发布提交与标签

正式版本由此前已经进入主线的多个 `feat`、`fix`、`refactor`、`test`、`docs`、`build` 等逻辑提交组成。发版时另建独立 release commit，并在该提交上创建独立 tag：

```
chore(release): 发布 X.Y.Z
vX.Y.Z
```

release commit 只包含版本号、CHANGELOG 定稿以及发布所必需的元数据，不夹带功能、修复、重构或大范围格式化。`vX.Y.Z` tag 与独立 release commit 共同标记发布点；不得把整个版本的实现压成一条 release 大提交。

### 5.4 公开历史禁止重写

- 已 push、已被协作者基于其继续开发，或已被正式版本标签引用的历史禁止重写；不得对这类提交执行 rebase、squash、amend 或 force push。
- 已发布版本发现问题时创建新的 `fix` 提交；需要撤销既有改动时优先使用 `git revert`，保留原提交与撤销原因。
- 只有尚未共享的本地开发分支可以通过 rebase、squash、amend 等方式重排、拆分或压缩临时提交；一旦共享，改用追加提交或 revert。
- 严禁对 `main` 或 `master` 执行 force push，包括 `git push --force` 与 `git push --force-with-lease`。

## 6. 其他约束

- 禁止跳过 hooks（`--no-verify`）。禁止对已 push 的提交 `--amend`。
- 提交前确认未包含凭据 / token / 大型二进制。
- **未经用户在当前对话明确指示，不得自动 `git commit` / `git push` / 用 git 恢复已修改文件**（遵全局协作规则）。
