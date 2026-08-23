# 功能规格：Incision 方法级精确归因 PoC（FR7）

> 状态：草拟（PoC 前）　·　关联 PRD：FR7 / §5.5 / ADR-2　·　分支：feature/incision-poc

## 1. 背景与目标

FR7（P2，可选）要求在目标 Paper + 目标 JDK 上验证 TabooLib **Incision** 织入能力后，
才将其用于"方法级精确归因"可选模块。本项目 `taboolib 本仓库零真实用例`，成熟度未验证，
因此**必须先 PoC 后启用**，默认关闭、失败静默降级。

本 spec 固化 PoC 的验证范围、判据与回滚预案，供真机（真实 Paper 服务端）执行。

## 2. 需求（要什么）

- **范围内**：
  - 验证 Incision 在目标环境的**织入能力**（能否改写目标方法字节码并生效）；
  - 验证织入**开销**（对 TPS/MSPT 的影响是否可接受）；
  - 验证**可回滚**（关闭开关后完全复原，无残留）。
- **不做（范围外）**：
  - 不做逐插件 enablePlugin 插桩的正式功能（PoC 仅验证可行性）；
  - 不引入裸 ASM 自研方案（ADR-2 已锁定 Incision）。

## 3. 设计（怎么做）

- **验证目标**：`enablePlugin`（`SimplePluginManager#enablePlugin`）方法级耗时插桩，
  作为"方法级精确归因"的代表性验证点（对应 PRD §5.4 的"可选/M4 Incision 插桩"）。
- **实现载体**：platform-bukkit 下 `incision` 包（或独立 `nms` 模块按需），
  `incision.enabled` 配置开关（默认 false）；启用时经 Incision 织入 `enablePlugin` 计时，
  结果并入启动画像；失败静默降级（不崩服、不影响插件启用）。
- **关闭路径**：开关为 false 时完全不加载 Incision 相关类（零织入、零开销）。

## 4. 任务拆分

- [ ] 引入 Incision 依赖（compileOnly，按需），确认 API 用法（自读 taboolib 源码/文档）
- [ ] PoC 代码：目标 Paper + 目标 JDK（1.21.x + JDK21）上织入 `enablePlugin` 计时
- [ ] 真机验证：1.21.4 Paper 启动，确认织入生效（逐插件耗时更精确）且无崩服/警告
- [ ] 开销测量：织入前后 TPS/MSPT 对比（p95 不劣化 >5%）
- [ ] 回滚验证：开关关闭后重启，确认无任何织入残留、行为与未引入前一致
- [ ] 验证通过 → 转正式功能（更新 ADR-2、PRD FR7 状态）；未通过 → 维持关闭并记录原因

## 5. 验收标准

- **真机（1.21.4 Paper + JDK21）**：
  - [ ] 织入后启动无异常，`enablePlugin` 计时数据产出且合理；
  - [ ] 织入前后 MSPT p95 劣化 ≤5%（可接受开销）；
  - [ ] 关闭开关重启后无残留、行为与未引入前一致（可回滚）；
  - [ ] 织入失败（如 Incision 不兼容）时插件照常启用、零崩服（静默降级）。
- 单元测试全绿不替代上述真机验收项。

## 6. 风险 / 待定

- **Incision 成熟度**：本仓库零用例，API 稳定性未知——PoC 即为此设;
- **JDK/Paper 版本差异**：目标 JDK21 的模块/反射限制可能影响织入——PoC 覆盖 1.21.x + JDK21；
- **后续多版本**：通过后可扩展验证 1.8 / Folia / 其他 JDK（另行评估）。
