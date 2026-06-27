# ADR-0017：背包业务对接随 AllinInventorySync 2.0.0 写门面分区字节化的契约调整

## 状态

已接受（取代 [ADR-0016](0016-inventory-business-item-transport.md) 的决策 1 / 2）

## 背景

ADR-0016 设计 `inventory` 域物品过桥契约时，AllinInventorySync 写门面 `InventoryWriteApi.writeInventory/writeEnderChest` 的入参是**结构化物品**（`Map<Int, ItemDto>`，其 core 经 `ItemStackCodec.fromDto` 以 `nbtBase64` 全保真重建物品）。据此 ADR-0016 决定：探针把 `base/edited` 物品集编码为 `List<String>`（每串一件物品 JSON）下发，探针侧解码出 `slot + nbtBase64` 还原 `ItemDto`，与 AllinInventorySync 读门面对称、可 round-trip。

AllinInventorySync **2.0.0** 发生两处对探针有影响的破坏性变更：

1. **对外 api 纯 Java 化**：`api.model` 全部 DTO 由 Kotlin 改为 Java（Lombok `@Value`）。Kotlin 不允许对 Java 方法 / 构造器使用具名实参，探针侧构造这些 DTO 须改为按字段声明顺序传位置参。
2. **物品写门面退回不透明分区字节**：`InventoryWriteDto` 的入参由结构化 `Map<Int, ItemDto>` 退回为 `byte[] base/edited`（GZIP NBT 分区字节，与其内部落盘快照对称）。该分区字节是 AllinInventorySync 内部编码，其全保真 codec 在 core 而非 api，**外部集成（探针仅 `compileOnly` api）无法从结构化物品产出这些字节**；而读门面 `getPlayerInventory` 返回的是结构化 `InventoryViewDto`、精简快照视图 `PlayerSnapshotView` 明确不外泄分区字节，故也拿不到字节做 round-trip。

结果：ADR-0016 决策 1 / 2 赖以成立的「结构化物品写门面」在 2.0.0 已不存在，探针的物品写路径既编译不过、语义也走不通。需调整 `inventory` 域对接契约以适配 AllinInventorySync 2.0.0。

> 范围说明：本 ADR 只调整**探针侧对接契约**，不改 AllinInventorySync（其 2.0.0 为已审慎发布的版本，写门面的分区字节路径服务于其自身管理 GUI）。

## 决策

1. **物品写（`writeInventory` / `writeEnderChest`）暂不提供**：AllinInventorySync 2.0.0 物品写门面入参为不透明分区字节、外部不可消费，故这两个动作**不进 manifest**；`InventoryProvider.dispatch` 收到时经 `InventoryEnvelope.itemWriteUnsupported` 返回**明确降级失败**（原因点名「分区字节不可外部消费、待其导出可消费的结构化物品写门面再恢复」），而非静默或伪成功。

2. **读（`view`）保留结构化富视图**：`getPlayerInventory(uuid)` → `InventoryViewDto`（回源含离线），把每件物品的 UI 便利字段（material / amount / displayName / lore / enchantments）连同全保真 `nbtBase64` 编码出来供平台渲染；玩家无数据回 `exists=false`。读契约不变。

3. **基础属性写（`writeBasicAttrs`）保留**：其入参是定形 `BasicAttrsDto`（非分区字节），外部可构造，故保留。沿用 ADR-0016 决策 3 的幂等键模型——`taskId`（CP 注入）→ 写门面 `requestId` 持久去重、`base→edited` 净改动 delta、`player` 为 UUID、operator 透传（空回退 `JianManager`）；落盘回执 `WriteResult`（success / online / newDataVersion / errorCode）结构化透传。

4. **追踪事件上报不变**：ADR-0016 决策 4（`TrackedItemActionEvent` 只编 Bukkit-API 便利字段、去重键 `playerUuid:action:occurredAtMs:seq` 自合成）继续有效。

5. **AllinInventorySync DTO 构造改位置参**：因 2.0.0 api 纯 Java 化，探针 Kotlin 侧（`InventoryEnvelope` / `InventoryProvider` / 单测）构造 `ItemDto` / `BasicAttrsDto` / `InventoryViewDto`、调用 `WriteResult` 静态工厂等一律按字段声明顺序传位置参。

## 理由

- **忠实适配上游契约**：探针是 AllinInventorySync 的消费者，上游 2.0.0 把物品写门面收敛为分区字节是其既定决策；消费者据现状能力对接（读 + 属性写 + 事件可消费，物品写不可消费），不擅自要求上游回退设计。
- **明确降级胜于静默 / 伪装**：物品写不进 manifest（UI 不会提供一个必失败的入口），万一仍被下发则返回点名原因的失败回执，便于排障、不误导。
- **非永久放弃**：物品写是「待上游导出可外部消费的结构化物品写门面后恢复」的延后项，不是能力删除；契约以 ADR 记录，恢复时再写新 ADR。
- **覆盖 JBIS 背包域当前可消费能力**：读视图 + 基础属性写 + 重点物品流转事件，已能支撑平台侧背包查看、属性治理与物品流水汇聚（JianManager FR-125 / FR-126 / FR-127 的可落地子集）。

## 后果

- CP / web（JianManager FR-126 汇聚 / FR-127 定制页）背包**写**仅 `writeBasicAttrs`；**物品改写暂不可用**，UI 不应提供物品写入口（manifest 不含该动作，前端按 manifest 动态渲染即自然不出现）。
- 物品改写需求 → 待 AllinInventorySync 新增**可外部消费的结构化物品写门面**（其仓的 FR）后，另写新 ADR 恢复探针物品写路径与 manifest 项。
- 探针 `platform-bukkit` 依赖 `compileOnly allininventorysync-api:2.0.0`（纯 Java + Lombok，自包含单制品）。
- `InventoryEnvelope` 移除物品写解码（`itemFromParts`）、新增 `itemWriteUnsupported` 降级；`InventoryProvider` 移除 `writeItems` / `decodeItems` 物品写骨架；manifest 由「view + 三写」收敛为「view + writeBasicAttrs」。单测相应调整（manifest 二动作 / 物品写降级 / DTO 位置参构造）。

## 备选方案

- **扩 AllinInventorySync api 重新加结构化物品写门面**（即被 2.0.0 回退的对称写设计）：可让探针恢复完整物品读写，但属跨仓改动、且重新引入上游已审慎回退的设计。本次取「适配现状、不动上游」，该路径留作上游后续若有需要时的独立决策。
- **分区字节 round-trip（读出字节 → 改 → 写回字节）**：AllinInventorySync 读门面不暴露分区字节（精简视图明确不外泄），且结构化编辑无法重新编码回内部分区字节；走不通，否决。
- **物品写保留在 manifest、运行时再降级**：UI 会展示一个注定失败的动作误导运营者；改为不进 manifest + dispatch 明确降级，更诚实。
