# ADR-0016：背包业务对接的物品传输契约

## 状态

已接受

## 背景

JBIS 业务对接(ADR-0015)新增 `inventory` 域——经 platform-bukkit 的 `InventoryProvider` wrap **AllinInventorySync** 公开 api,读写玩家背包 / 末影箱 / 基础属性,并订阅其 `TrackedItemActionEvent` 上报物品流转(对应 JianManager FR-125 / FR-126)。

`inventory` 域沿用 ADR-0015 的 Provider / manifest / 事故域隔离范式,但与经济域(载荷只是 player / currency / amount 等标量字符串)不同,背包要把**结构化物品**(不透明 NBT 字节 + 槽位结构)与**基础属性**过桥,且写门面带 delta 语义与落盘回执。这引出经济域未遇到的传输契约问题,而探针的 JSON 门面([JsonObject],ADR-14)有意做成最小取值面(`getString` / `getInt` / `getStringList` / `getObject` / `contains`),**无数组对象迭代、无动态键迭代、无 `getDouble`**。本 ADR 记录 `inventory` 域物品 / 属性的过桥编码契约。

## 决策

1. **读富、写以 `nbtBase64` 为准、读写不对称**:`view` 把每件物品的全部 UI 便利字段(material / amount / displayName / lore / enchantments)连同 `nbtBase64` 编码出来供平台渲染;**写**只需 `slot + nbtBase64`——`nbtBase64` 是 AllinInventorySync `ItemDto` 的全保真往返真源,其写门面经 `ItemStackCodec.fromDto` 一概忽略 UI 字段。故写解码只取这两项还原 `ItemDto`(UI 字段留痕、不可信)。

2. **`base/edited` 经 JSON 门面以 `List<String>`(物品 JSON 串)承载**:每件物品是一个 JSON 对象串(`{"slot":N,"nbtBase64":"…",…}`),整组用门面 `getStringList` 取字符串列表、再逐串 `Json.parse` 解析。这是在不扩 JSON 门面(守 ADR-14、零 core 改动)的前提下承载结构化物品集的唯一可行编码。`writeBasicAttrs` 的 `base/edited` 是**单个定形对象**,直接用门面 `getObject` 取嵌套对象;因门面无 `getDouble`,其数值字段(health / xpProgress 等)以字符串承载、探针侧解析。

3. **幂等键 `taskId`(CP 注入)→ `InventoryWriteDto.requestId`**:与经济域(JianManager FR-121)同款业务幂等;AllinInventorySync 据 `requestId` 持久去重,同键二次写返回首次回执、不重新施加(防重发刷物品)。`player` 入参为玩家 **UUID**(AllinInventorySync 读写以 UUID 寻址,区别于经济域的玩家名)。

4. **追踪事件只编 Bukkit-API 物品字段(无 `nbtBase64`)、去重键自合成**:AllinInventorySync 的全保真 `ItemStackCodec` 在其 **core** 而非 `api`,探针(仅 `compileOnly` api)拿不到 `nbtBase64`;且 `TrackedItemActionEvent` 是**瞬时观测**,信封只携 Bukkit-API 可得的便利字段(material / amount / displayName)即足够 CP 记录「谁、何动作、什么物品」。该事件无插件侧持久单调 ID(不同于经济 `ledgerId`)、无重投,去重键合成为 `playerUuid:action:occurredAtMs:seq`(seq 为探针会话内单调序号,同毫秒同动作去歧义)。

## 理由

- **守 ADR-14 的最小 JSON 门面、零 core 改动**:用现成 `getStringList` + 逐串 `Json.parse` 承载物品集,不为一个域去扩共享 JSON 门面(避免改动面扩散到全项目)。
- **`nbtBase64` 是唯一可信往返载体**:UI 字段仅供展示,写入据其重建会失真;只认 `nbtBase64` 与 AllinInventorySync 读写门面的对称设计(其 ADR-0013)一致,「读出 → 改 → 回写」round-trip 不丢 NBT。
- **观测事件无需全保真**:追踪是观测性质,Bukkit-API 字段够用;强求 `nbtBase64` 需把 AllinInventorySync 内部 codec 暴露到 api,得不偿失。
- **与经济域一致的幂等模型**:`taskId` 幂等键复用 FR-121 横切,CP 侧写路径统一。

## 后果

- CP / web(JianManager FR-126 汇聚 / FR-127 定制页)须遵此线格式:写 `base/edited` 的物品集发 JSON 串列表、属性发对象、`player` 发 UUID;读视图按物品数组 + 基础属性渲染。
- 依赖 AllinInventorySync 的 api 为**自包含单制品**(其 ADR-0014 把对外 DTO 归并入 api 模块),探针 `compileOnly allininventorysync-api:1.2.0` 即拿到全部对外类型。
- 新增 platform-bukkit:`InventoryProvider`(域路由 + 真实 API 调用 + future 有界阻塞)、`InventoryEnvelope`(纯逻辑:manifest / 校验 / 解码 / 编码,可单测)、`BukkitInventoryEventListener`(`object` + `@SubscribeEvent(bind=FQCN)`,软依赖按名绑定)、`InventoryEventEnvelope`(纯逻辑)。plugin 软依赖 `AllinInventorySync`。
- 物品 `nbtBase64` 在写路径透传不解,探针不承担物品语义校验(交 AllinInventorySync 写门面)。

## 备选方案

- **扩 JSON 门面加 `getObjectList`、`base/edited` 走自然 JSON 数组对象**:线格式更自然,但要改共享 core JSON 门面(ADR-14)、影响面扩散到全项目;否决,用现成 `getStringList`。
- **`base/edited` 用 slot 为键的对象**:门面无动态键迭代能力,解码走不通;否决。
- **追踪事件携 `nbtBase64` 全保真**:须把 AllinInventorySync 内部 `ItemStackCodec` 暴露到 api,且观测语义不需要;否决。
- **事件去重键用内容哈希**:同玩家重复同动作会被 CP 误并为一条;改用带会话单调 seq 的合成键保证每条各异。
