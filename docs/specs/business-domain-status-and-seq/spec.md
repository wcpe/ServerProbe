# ServerProbe FR-40 / FR-41 域状态上报与事件序号

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 优先级：P1

> 关联：JM FR-534（插件面板）、FR-522（事件透传）

---

# FR-40 已注册业务域可视化数据面

## 1. 背景

现有 `BusinessStatusRoute` 只返回 `domain → 是否可用`。JM 的业务插件面板需要更丰富的信息才能让运维一眼看出问题。

## 2. 目标

提供「本服务端探针注册了哪些业务域、各自健康状态、规格摘要、最近调用情况」的完整查询面，**不触发 Provider 初始化**（只读注册表快照）。

## 3. DomainStatus 扩展

```java
public record DomainStatus(
    String domain,
    boolean available,              // 是否已注册且健康
    String degradedReason,          // 不可用原因（null 表示健康）
    String manifestDigest,          // manifest 内容指纹（sha256 前 16 位）
    long   lastDispatchAt,          // 最近一次 dispatch 时间戳（0=从未）
    long   dispatchCount,           // 累计 dispatch 次数
    long   failureCount,            // 累计失败次数
    ProviderHealth health           // 现有健康结构
) {}
```

### 3.1 manifestDigest 的用途

避免每次查询都传完整 manifest（可能有几 KB × N 个域）。下游先拿 digest，只有变化时才拉全量。

计算方式：对规范化 JSON 序列化后取 sha256 前 16 位。

**规范化**：动作按名称排序、键按字典序、去除空白差异。

### 3.2 计数语义

| 字段 | 语义 |
|---|---|
| `dispatchCount` | 自探针启动以来的累计成功 + 失败次数 |
| `failureCount` | 其中失败的次数 |
| `lastDispatchAt` | 最近一次 dispatch 的完成时间 |

**重启后归零** —— 这是运行期指标，不是持久统计。

## 4. 接口

复用现有 `core.business_status` 命令，扩展返回内容：

```json
{
  "probeVersion": "1.5.0",
  "registeredAt": 1730000000,
  "domains": [
    {
      "domain": "activity",
      "available": true,
      "degradedReason": null,
      "manifestDigest": "a3f2b8c1d4e5f607",
      "lastDispatchAt": 1730001234,
      "dispatchCount": 1523,
      "failureCount": 7,
      "health": { "status": "UP", "detail": "" }
    },
    {
      "domain": "economy",
      "available": false,
      "degradedReason": "PLUGIN_DISABLED",
      "manifestDigest": null,
      "lastDispatchAt": 0,
      "dispatchCount": 0,
      "failureCount": 0,
      "health": { "status": "UNKNOWN", "detail": "" }
    }
  ]
}
```

## 5. 实现约束

| 约束 | 说明 |
|---|---|
| 只读快照 | 不调用 `Provider.manifest()`，只用注册时缓存的 digest |
| 不阻塞 | 在桥读线程外执行，有独立超时 |
| 无副作用 | 查询不改变任何状态 |
| 域为 0 | 返回空数组，不报错 |

## 6. 验收

1. 0 个域 → 返回空数组
2. N 个域 → 每个都有完整字段
3. 某域降级 → `available=false` + `degradedReason`
4. `manifestDigest` 在 manifest 变化后改变
5. 查询不触发 Provider 初始化（用「构造时抛异常的 Provider」验证）
6. 100 个域时响应 < 100ms

---

# FR-41 可靠事件传输层序号

## 1. 背景

业务台需要判断「事件有没有漏」。当前只有 `dedupKey`（去重），没有**位点**（定位）。

- `dedupKey` 解决「同一条事件重复到达」→ 幂等
- `seq` 解决「两条事件之间有没有少」→ 完整性

两者互补，不能互相替代。

## 2. 目标

为可靠事件提供单调递增的 `seq`，使下游能凭单一序号判断缺口。

## 3. 设计

### 3.1 接口扩展

```java
public interface BusinessEventSink {

    /** 可丢事件（不占 seq 序列）。 */
    boolean emit(String domain, String dedupKey, String topic,
                 Map<String, String> fields);

    /** 可靠事件（自动分配 seq）。 */
    boolean emitReliable(String domain, String dedupKey, String topic,
                         Map<String, String> fields);

    /** 可靠事件（调用方指定 seq，用于补发场景）。 */
    boolean emitReliable(String domain, String dedupKey, String topic,
                         Map<String, String> fields, long seq);
}
```

### 3.2 seq 分配规则

| 规则 | 说明 |
|---|---|
| 作用域 | **per-domain** 独立递增（不同域互不干扰） |
| 起始 | 1（或启动时从 `max(persisted) + 1` 续接） |
| 单调性 | 严格递增，不跳号 |
| 可丢事件 | **不占用 seq** |
| 补发 | 用旧 seq 重发，不重新分配 |

### 3.3 重启后的 seq 策略

这是必须明确的语义。两种选择：

| 方案 | 行为 | 优缺点 |
|---|---|---|
| **A. 重置** | 重启后 seq 从 1 开始 | 简单，但下游无法区分「重启」和「回退」 |
| **B. 续接** | 从持久化的最大值 +1 开始 | 下游可安心用序号，但需持久化 |

**推荐 B**，实现方式：

- 探针在 `EventSpillStore` 里持久化每个 domain 的 `maxSeq`
- 启动时读取并续接
- 若持久化丢失，从 `(当前时间戳毫秒 << 20)` 开始，保证不会与旧值冲突

**帧中必须标注**：`seqEpoch`（epoch 标识，重启时若走方案 A 则递增）。下游遇到 `seqEpoch` 变化时，知道需要触发对账。

### 3.4 帧格式

```
{
  "type": "event",
  "event": "activity_change",
  "domain": "activity",
  "topic": "activity.spring-2026",
  "dedupKey": "e-8891",
  "seq": 1024,
  "seqEpoch": 1,
  "payload": {...},
  "timestamp": 1730000000
}
```

## 4. 与快照的关系

业务台做对账时需要：

```
「快照时刻的 seq」 → 「当前 seq」
```

两者之间的所有 seq 都应该在事件流里出现过。若发现缺口：

- 若 `seqEpoch` 相同 → 真的漏了，需对账
- 若 `seqEpoch` 不同（探针重启过）→ 正常，需全量对账

**探针的职责**：保证 `seq` 与 `seqEpoch` 准确。
**探针不做**：不规定业务快照里放什么字段（那是业务台与插件的约定）。

## 5. 实现约束

| 约束 | 说明 |
|---|---|
| 分配不阻塞 | seq 分配在事件入队时完成，用 `AtomicLong` |
| 溢出保护 | long 溢出前（极不可能）需重置并递增 epoch |
| 补发保持原 seq | 从 spill 重发时用原 seq，不重新分配 |
| 可丢事件不占序列 | 避免可靠事件的 seq 出现"空洞" |

## 6. 验收

1. 连续 emit 100 条可靠事件 → seq 1..100 无跳号
2. 可丢事件混入 → 不影响可靠事件 seq 连续性
3. 探针重启 → seq 续接（方案 B）或 epoch 递增（方案 A）
4. 从 spill 补发 → 保持原 seq
5. 同一 dedupKey 重复 emit → 两条都有 seq（去重由下游做）

## 7. 待定

1. ~~**重启策略选 A 还是 B**~~ —— **已定：B（续接）**。`EventSeqAllocator` 从 `seq.state` 的
   `max + 1` 续接；**正常重启不递增 epoch**，只有「持久化丢失/损坏」与「long 溢出前重置」
   这两种"序号语义确实重置"的情形才递增。
2. ~~`seqEpoch` 是否必要，或可用 `probeBootId` 替代~~ —— **保留 `seqEpoch`**。语义比 bootId
   更精确：bootId 每次启动都变（方案 B 下会让下游误判"需要全量对账"），
   而 `seqEpoch` 只在序号真的不可比时才变——这正是下游需要的那个信号。
3. ~~补发时若原 seq 已被新事件覆盖，如何处理~~ —— **补发仍带原 seq，下游按 `dedupKey` 去重**。

---

## 8. 实现注记（实现期确定，spec 原文未覆盖）

1. **「首次启动」与「持久化丢失」必须分开**。spec §3.2 要求起始 1，§3.3 又要求丢失时从
   `时间戳 << 20` 起跳——二者是**不同**的情形。`EventSeqStore.LoadResult` 因此是三态
   （`Absent` / `Corrupt` / `Present`）：文件不存在 → 从 1 起；文件存在但不可解析 → 时间戳起跳 + epoch 递增。
   若合成一条兜底路径，全新服务器上 §6 验收 1「seq 1..100」永远不成立。
2. **损坏后不立刻落盘**。丢失时若把（当时还是空的）域表写进 `seq.state`，下次启动会被读成
   `Present` 且域表为空 → 各域从 1 起跳，与刚发出去的 `1.8e18` 号段正面冲突。
   保持损坏态、等正常停机再写真实域表，才是安全的。
3. **`emitReliable` 分配在「确认发布器可用」之后**。探针未就绪时一个号都不消耗，
   否则每次失败的早期上报都会在序列里留一个假空洞。
4. **通道饱和导致的序号空洞是刻意的**。分配在提交前完成（spec §5「入队时完成」），
   被拒时那个号确实没送达、调用方也拿到 `false`。回退它反而危险：并发下回退会让两条事件拿到同一个号。
5. **`DomainStatus` 保留旧 10 参构造器**并转调新构造器。外部实现若自己 `new DomainStatus(...)`，
   升级探针后不该编译不过。
6. **`emitReliable(..., seq)` 与 `BusinessEventPublisher.publish(..., position)` 都是 default/带默认值的**。
   前者是第三方可直接实现的接口（加抽象方法会让人 `AbstractMethodError`）；
   后者让既有调用点（含基准与单测）一行都不用改。
7. **旧契约（legacy 适配）不给 `manifestDigest`（null）**。其清单由适配器合成，
   算出的指纹没有可比性，给出来只会让面板误判「规格变了」。
8. **`degradedReason` 与 `manifestDigest` 在回执里恒存在，值为 `null`**（与 `lastFailure` 的「整字段缺席」不同）：
   它们是每域固定槽位，下游不必判"键存不存在"。
9. **落盘行格式扩为 8 列**（新增 `eventSeq` / `seqEpoch`），并兼容 FR-41 之前的 6 列旧行（位点为 0）。
