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

## 4. 其他约束

- 禁止跳过 hooks（`--no-verify`）。禁止对已 push 的提交 `--amend`。
- 提交前确认未包含凭据 / token / 大型二进制。
- **未经用户在当前对话明确指示，不得自动 `git commit` / `git push` / 用 git 恢复已修改文件**（遵全局协作规则）。
