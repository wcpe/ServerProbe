# ADR-14：JSON 编解码经可换适配器（默认 nightconfig，零依赖）

## 状态

已接受

## 背景

探针的 JSON 处理此前散落且不统一：

- **手写区**：插件桥（`MiniJson` 手写解析 + `buildString` 手拼帧）、Webhook 告警（`toJson` + `escapeJson`）等，各自维护转义与拼接，易有边界坑、重复、难维护。
- **库区**：指标历史 / 启动画像落盘走 TabooLib `Configuration`（`Type.JSON_MINIMAL`，底层重定位的 nightconfig）。

二者并存，无统一抽象，想整体换 JSON 实现时要逐处改。

同时，ADR-10 的 JSON 指引「优先用 TabooLib 自带 gson」**与事实不符**：gson 只存在于**构建期** TabooLib gradle 插件，**运行时并不携带**；引入 gson 会成为一个新的运行期依赖（违背 ADR-10「优先复用、新依赖逐个确认」的本意）。运行时真正可用的 JSON 能力是 nightconfig（`Configuration`）。

## 决策

把全项目 JSON 编解码收口到 `core` 的一层**可替换适配器**：

- `JsonCodec` 接口：`encode(对象/Map/List → JSON 文本)` + `parse(JSON 文本 → 只读树 JsonObject)`。
- `Json` 静态门面：委托给可替换的 `Json.codec`；另含 reified `decode<T>`（JSON → 强类型对象的反射映射，落盘用）。
- 默认实现 `ConfigJsonCodec`：后端复用**运行时已有的 TabooLib `Configuration`（nightconfig）**，`Type.JSON_MINIMAL` 紧凑输出，**零额外依赖**。动态键值用 `Configuration.empty + set` 构造，数据类走 `serialize` 反射。

所有 JSON 调用点改走 `Json.encode/parse/decode`，不再手写、不直接耦合具体库。**换库（gson / jackson 等）只需新增一个 `JsonCodec` 实现并替换 `Json.codec`，调用点全部不动。**

### 与 ADR-10 的关系

本 ADR **细化** ADR-10 的 JSON 指引，不推翻其依赖策略原则：

- 纠正「优先用 gson」为「JSON 经可换适配器，默认复用运行时已有的 nightconfig（`Configuration`）」——既符合 ADR-10「优先复用 TabooLib 自带、避免新依赖」的本意，又消除了 gson「构建期有、运行期无」的事实偏差。
- 若将来确需 gson 等，按 ADR-10「逐个确认」评估后，新增一个 `JsonCodec` 实现替换默认后端即可，不再触碰调用点。

### 边界与例外

- **落盘对象映射**：启动画像 / 指标快照的 `JSON ↔ 强类型数据类` 反射映射经 `Json.decode<T>`（reified 内联）默认绑定 `Configuration` 反射，与 `Json.encode` 落盘序列化**成对、共同定义持久化格式**。换落盘后端须同步两侧并演进 `schemaVersion`，不在通用 `codec` 文本替换范围内。
- **火焰图查看器数据**（`FlamegraphExporter` 的 `threadTreeJson` 递归树 + 瀑布数组）：性能敏感的大型**递归结构**、绑定前端 JS 查看器的特定格式、且**只构造不解析**，保留专用高效手拼，**不经适配器**（justified exception）。

## 理由

- **统一 + 可换**：一处抽象、处处统一；换库只换实现，调用点零改动，满足「随时换」。
- **零额外依赖**：默认后端复用运行时已携带的 nightconfig，jar 不增重、不引版本冲突，最符合 ADR-10。
- **更健壮、更少代码**：以库替代手写转义 / 拼接 / 解析，消除边界坑与重复。

## 后果

- 正面：JSON 处理统一、可维护、可替换；删除手写 `MiniJson` / `escapeJson`。
- 约束：落盘对象映射与火焰图查看器数据为有据例外，须在代码与本 ADR 保持说明一致。
- 真机验证：插件桥 `hello`/`event`/`command_result` 经 `Json.encode` 构造、`command` 经 `Json.parse` 解析，1.21.4/1.21.1 Paper 上连接握手 + 在线名册 + 踢人端到端通过。

## 备选方案

- **引入 gson 作默认后端**：被否决——gson 不在运行时，引入是新依赖，与 ADR-10「优先复用自带」相悖；且适配器已使其可作为「随时可换」的备选实现，无需作为默认。
- **维持各处手写、仅补规则**：被否决——手写易有边界坑、重复、难换库，不满足「统一 + 可换」诉求。
