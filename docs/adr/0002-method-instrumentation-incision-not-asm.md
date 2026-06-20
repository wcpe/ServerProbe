# ADR-2：方法级插桩（如需）选 TabooLib Incision 而非裸 ASM，默认关闭并先 PoC

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11 补记为独立 ADR。

主体方案（ADR-1）为纯 API + JMX + 采样，但仍存在"方法级精确归因"这类可选需求（如对 `enablePlugin`、特定事件/方法耗时做精确插桩，见 PRD FR7）。若将来确需方法级插桩，需要在两种字节码织入方式中取舍：裸 ASM，或 TabooLib 生态内封装好的 Incision。Incision 封装了 self-attach/JVMTI/ASM，处于 TabooLib 生态内、与本项目技术栈一致；但本仓库目前对 Incision 零真实用例，成熟度有待验证。

## 决策
方法级插桩（如需）选用 TabooLib Incision 而非裸 ASM，且默认关闭，引入前必须先做 PoC 验证。

## 理由
- Incision 封装了 self-attach/JVMTI/ASM，位于 TabooLib 生态内，与项目技术栈契合，无需自行直面裸 ASM 的复杂与脆弱。
- 但本仓库零用例、成熟度待验证，因此默认关闭、需先 PoC（验证目标 Paper + 目标 JDK 上 self-attach/JVMTI 兜底能否织入、开销、可回滚），验证通过后仅用于"方法级精确归因"可选模块，失败静默降级（见 PRD §5.5）。

## 后果
- 正面：保留了方法级精确归因的演进路径，又不让主体承担其风险；选择生态内的 Incision 降低了维护成本。
- 负面/约束：首期不启用字节码插桩 / Incision，仅预留架构 + PoC 验证；启用前必须完成 PoC，默认关闭并在失败时静默降级。

## 备选方案
- **裸 ASM**：需自行直面字节码织入的复杂与脆弱，不在 TabooLib 生态封装之内。否决。
