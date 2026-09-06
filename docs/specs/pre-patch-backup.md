# 功能规格：MCP 补丁前自动备份原始字节码

> 状态：草拟　·　关联 PRD：FR-19　·　分支：feature/fr19-pre-patch-backup

## 1. 背景与目标

FR-14 已支持 `arthas_redefine` / `arthas_retransform` 用工作区内的字节码替换已加载类，并可用 `arthas_revert` 按条目回滚。但 `revert` 依赖 Arthas 内存中的 retransform 记录——**跨重启不持久**，且替换前原始字节码未自动落盘：若重启后才发现补丁有问题，无法恢复原始行为。本 FR 在每次类替换前自动备份原始字节码到工作区，形成可回滚/可对比的持久副本。

## 2. 需求（要什么）

- 在执行 `arthas_redefine` / `arthas_retransform` **之前**，自动把目标类的**当前已加载字节码**备份为工件：
  - 命名：`backup_<类名转义>_<原替换时间戳>.class`（扁平前缀，符合工作区白名单，见 §3）。
  - 内容：经 `Instrumentation` 读取的该类当前字节码（与 `retransform` 的"当前定义"一致）。
- 新增 MCP 工具 `artifact_backup_list`：列出所有备份工件（复用 `artifact_list` 的元数据，按名字前缀过滤 `backup_`）。
- 新增 MCP 工具 `artifact_backup_restore`：入参备份名，将备份字节码写回工作区为可重新替换的工件（**不自动触发 `redefine`**；由 agent 决定是否调用 `arthas_redefine` 恢复）。
- 备份与替换**提交绑定**：备份写入成功后才提交替换任务；备份执行失败则本次替换返回失败（不静默替换）。**替换任务本身的执行失败不影响备份留存**（备份是可重试证据）。
- 范围内：自动备份、列出、恢复（写回工作区）、与既有 `redefine`/`retransform`/`revert` 配合。
- 不做：自动还原（不监听类加载事件）、备份生命周期清理（沿用工作区保留策略）、跨重启自动恢复（重启后类已重置，备份仅作证据/对比）。

## 3. 设计（怎么做）

- **core** `ArthasControl` 接口扩面：新增 `dumpClassBytes(className): ByteArray?` **默认方法（默认返回 null 表示"能力未提供"）**，由 `diagnostics-arthas` 实现（经 `Instrumentation#getAllLoadedClasses` 匹配类 + `getBytecodes` 读取；JDK 6+ 标准 API，与 Arthas 版本无关）。扩面保持向后兼容（既有实现无需改动）。
  - **兼容语义（关键）**：默认 null = **备份能力缺失** → **跳过备份 + WARN + 继续替换**（保底兼容，不破坏既有 redefine/retransform）；仅当"实现已提供但读取失败"（异常/空字节）时 → **拒绝替换**。两种降级路径必须区分，避免旧实现未适配新方法时静默禁用替换能力。
  - **同名类歧义**：`getAllLoadedClasses` 对同一 FQCN 可能返回多个 ClassLoader 的定义；`dumpClassBytes(className)` 取**首个匹配**并记录 WARN（含 ClassLoader 提示），文档注明该局限。
- **实现注意**：`ArthasInstrumentationAccess` 现状只暴露 `acquire(agentJar)/retry(agentJar)` 且不持有稳定 `Instrumentation` 引用——本 FR 需为其增加 `instrumentation()` 访问器或改造持有（内部重构，不属接口扩面）。`NativeMcpToolProvider.arthasClassCommand`（`arthas_redefine`/`arthas_retransform` 的处理路径）在提交 Arthas 任务前：
  - 调用 `dumpClassBytes(className)` 获取当前已加载字节码。
  - 写入 `McpArtifactWorkspace`：**扁平命名** `backup_<类名转义>_<epochMillis>.class`（类名 `.` → `_`）。
  - 写入成功才继续提交替换任务；失败返回结构化错误（`backupFailed`）。
- **工作区白名单约束（关键）**：`McpArtifactWorkspace.resolve` 的 `FILE_NAME` 白名单（`[A-Za-z0-9._-]{1,128}`）**只允许根级文件名、不允许 `/` 子目录**。故备份命名**不用 `backup/` 子目录**，改用**扁平前缀** `backup_<转义类名>_<时间戳>.class`（`backup_` 前缀在 `[A-Za-z0-9._-]` 内合法）；`artifact_backup_list` 按名字前缀 `backup_` 过滤，`artifact_backup_restore` 目标用 `restored_<原名>`（同白名单）。**不改动现有安全正则**。
- **artifact_backup_list**：复用 `McpArtifactWorkspace.list()` 过滤 `backup_` 前缀；返回 `{backups:[{name,size,modifiedAtMillis}]}`。
- **artifact_backup_restore**：入参 `backupName`（必须为 `backup_` 前缀且存在），读字节码写回工作区根（`restored_<原名>`），返回新工件名（供 `arthas_redefine` 引用）。
- **并发**：备份写文件与替换提交在 MCP 请求线程顺序执行；`McpArtifactWorkspace` 已 `@Synchronized` 保证工作区内部互斥——**"备份成功才提交"限定为"备份写入成功才提交替换任务"**，不承诺替换任务本身的事务性（Arthas 替换是异步任务，可能因字节码校验失败）。
- **降级**：`dumpClassBytes` 失败（类已卸载/无权限）→ 本次替换失败并返回原因；不阻断其他工具。

## 4. 任务拆分

- [ ] `ArthasInstrumentationAccess` 暴露 `dumpClassBytes`（或复用既有字节码获取）+ 单测。
- [ ] `arthasClassCommand` 集成备份前置 + 单测（备份成功才替换、失败拒绝、命名转义）。
- [ ] `artifact_backup_list` / `artifact_backup_restore` + 单测（过滤、恢复写回、非法名拒绝）。
- [ ] MCP 接线 + E2E 真机：真实 `redefine` 前自动产生备份，`restore` 后重新替换可恢复原行为。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG。

## 5. 验收标准

- 单测：备份写入/命名转义/失败拒绝、**默认 null 的兼容路径（WARN+跳过+继续替换）**、**备份命名冲突消歧（同一类两次替换 → 时间戳不同）**、list 过滤、restore 写回、非法名拒绝全绿。
- 真机（Paper）：对真实类执行 `arthas_redefine` 前，workspace 出现 `backup_<类>_<时间戳>.class`；用该备份 `restore`（得到 `restored_` 工件）后**经 `arthas_redefine`（而非 `arthas_revert`）**恢复原始行为。
- 备份文件受工作区保留策略（默认 24h/10 GiB）约束，不额外常驻。

## 6. 风险 / 待定

- `getBytecodes` 在极端情况可能返回空（类正被重定义）→ 视为备份执行失败，拒绝替换。
- 备份文件命名转义后可能重名（不同类名转义相同，如 `a.b` 与 `a_b`）→ 时间戳后缀消歧。
- 同一 FQCN 多 ClassLoader 定义歧义：`dumpClassBytes` 取首个匹配并 WARN（见 §3）。
- 待定：是否需要"批量恢复全部备份"（YAGNI，先只做单备份恢复）。
