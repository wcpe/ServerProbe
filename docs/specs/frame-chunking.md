# 功能规格：大数据帧分片

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-34　·　分支：feature/frame-chunking


## 1. 背景与目标

桥是单 WS 连接。当业务回执或事件携带大数据（结算明细、批量背包快照、大型 JSON）时，单个帧可能达到数百 KiB 乃至 MiB 级：

- 撞 64 MiB 的 unary 上限风险；
- 一个大数据帧会长时间占用写锁，把 5s 超时的命令回执挤在后面。

本功能把超阈值帧自动切片，并保证命令回执的通道优先权。

## 2. 需求

### 范围内

- 单帧**整帧序列化上限**默认 256 KiB，超限自动分片。
- 分片帧形态：`chunk` 子类型 + `groupId` / `seq` / `total` / `ofType` / `data`（**base64 切片**）。
- 分片是 `FrameSender` 职责，位置固定在**滤镜之后、容量判定之前**。
- **同组原子入队**：整组一起提交或整组拒绝，绝不发出前缀片。
- 超限/拒绝经 `SubmitOutcome` 回报，命令侧据此回写失败回执。
- 向后兼容：不假设对端已支持分片；对端忽略未知子类型不应报错。
- **不新增 `OutboundFrame` 字段**：字段集见 [bridge-line-hardening.md §3.3](bridge-line-hardening.md)。

### 范围外

- 不做压缩（JM 侧可自行处理）。
- 不做分片级 ack / 重传（连接中断导致的半组由对端"新 `hello` 清空分组"兜底，见 §3.5）。
- 不做跨连接续传。
- 不做"对端是否支持分片"的版本协商。

## 3. 设计

### 3.1 分片帧形态

```json
// 首片
{
  "type": "event",
  "event": "chunk",
  "instance": "s1",
  "timestamp": 1730000000000,
  "groupId": "3f2b8c1e-9a44-4d6f-b0a7-1c5e8d2f7a10",
  "seq": 0,
  "total": 3,
  "ofType": "command_result",
  "requestId": "req-7",
  "data": "eyJvayI6dHJ1ZSwiZGV0YWlsIjoi..."
}
```

| 字段 | 规则 |
|---|---|
| `event=chunk` | 与既有 `event` 帧同构，对端按 `event` 字段识别 |
| `groupId` | **完整 `UUID.randomUUID().toString()`**（36 字符）。不接受任何"实例名 + 每连接递增计数"式的编号：重连后计数归零，会与上一连接尚未清理的残留分组撞号，对端把两组片拼到一起 |
| `seq` | 从 0 开始，连续递增 |
| `total` | **切片完成后**才算得出，随后写入**每一片**（含首片）；不预估、不预留 |
| `ofType` | 原始帧的 `event` 子类型（`command_result` / `<domain>_change`），供对端重组后还原 |
| `requestId` | 原帧有则透传（便于对端在重组前就能关联，也让超限失败回执能找回 `requestId`） |
| `data` | **原始载荷 UTF-8 字节的 base64 切片**（见下） |

**为什么必须 base64**（早期"直接切原始文本"的方案已废弃）：

- payload 本身就是 JSON 文本，里面的 `"` 与 `\` 在拼进 `data` 时会被**二次转义**，于是"按 seq 拼接后字节完全相同"落空；`overhead ≈ 512 B` 也严重低估转义膨胀（一段全引号的 JSON 可膨胀到 2 倍）。
- 改为 base64 后：`data` 只含 `A–Z a–z 0–9 + / =`，**没有任何需要 JSON 转义的字符**，切片与拼接都是纯文本操作，`overhead` 可精确计算。
- 代价是 4/3 膨胀（1 MiB 载荷 → 约 1.37 MiB base64）。这是可接受的，因为分片本就为"超阈值才触发"。

**重组（对端）**：按 `seq` 升序拼接全部 `data` → `Base64.getDecoder().decode(...)` → `new String(bytes, UTF_8)` → 得到原帧完整 JSON 文本 → 按 `ofType` 解析。因为每片长度取 4 的整数倍（§3.2），也可逐片解码再拼接字节，两种方式结果必须一致。

### 3.2 阈值、切分与单组上限

**阈值按最终序列化字节计**：`OutboundFrame.payload` 已经是完整帧文本（含 `type`/`event`/`instance`/`timestamp` 等信封字段，写出时不再包一层），因此"整帧 JSON 的 UTF-8 字节数"就是 `payload.toByteArray(UTF_8).size`，`max-frame-bytes` 对**这个值**生效，而不是对"载荷"生效。

```kotlin
// core/bridge/chunk/FrameChunker.kt
class FrameChunker(private val settings: ChunkSettings) {

    /**
     * 若整帧序列化字节超过阈值则分片；否则返回单元素列表（原帧，逐字节不改写）。
     *
     * 前置条件：帧已过滤镜链（被滤镜丢弃的帧根本不会到这里）。
     */
    fun split(frame: OutboundFrame): ChunkResult
}

/** 分片结果：要么整组（1 或 N 片），要么明确的失败原因。 */
sealed class ChunkResult {
    class Whole(val frames: List<OutboundFrame>) : ChunkResult()
    /** 片数超上限，或调小 data 预算重试后仍无法压到阈值内。 */
    class TooLarge(val reason: String) : ChunkResult()
}
```

**算法（顺序不可变）**：

```
① bytes = frame.payload.toByteArray(UTF_8)
   if (bytes.size <= maxFrameBytes) → Whole(listOf(frame))        // 原样，不重写

② b64 = Base64.getEncoder().encodeToString(bytes)

③ 量信封开销：用 data="" 的探针片序列化一次
   overhead = probe.toByteArray(UTF_8).size
   （data 只含 base64 字母，对转义零贡献 → 该值对每片都成立）

④ dataBudget = maxFrameBytes - overhead - 8      // 留 8 字节给 seq / total 的位数变化
   chunkDataLen = dataBudget - (dataBudget % 4)   // 取 4 的整数倍：每片可独立解码
   若 chunkDataLen <= 0 → TooLarge

⑤ total = ceil(b64.length / chunkDataLen)，至少 1
   若 total > maxChunksPerGroup → TooLarge

⑥ 逐片构造 seq=0..total-1、data=b64.substring(i*chunkDataLen, min(...))、total 写入每片
   逐片校验：chunk.payload.toByteArray(UTF_8).size <= maxFrameBytes
   若某片超限（信封里有超长 requestId 等）→ chunkDataLen -= 4 重算，最多重试 4 次
   仍超限 → TooLarge
```

- `total` 在切片完成后计算并写入每一片，因此对端不必猜测"还有没有下一片"。
- 片数上限按 `max-chunks-per-group`（默认 64）判定。默认阈值下单片约 255 KiB，即单组约 **16 MiB base64 ≈ 12 MiB 原始载荷**。
- 阈值和上限都经 `BridgeConfigSource.chunk()` 读取（接缝见 [bridge-line-hardening.md §6.2](bridge-line-hardening.md)），单测可注入改值，避免"读默认值"的假绿。
- `enabled: false` 时**不分片也不放行超大帧**：整帧字节超过 `max-frame-bytes` 一律 `TooLarge`（宁可明确失败，也不要发一个会长时间占写锁、可能撞对端上限的巨帧）。

### 3.3 与发送器集成：原子入队与超限回执

分片是**发送器职责**，不是滤镜职责：`OutboundFilter` 的签名是「一帧进、一帧出」，无法表达「一帧进、多帧出」；若让 filter 做分片，就必须返回特殊标记再由发送器二次展开，等于把逻辑拆到两处。与 [bridge-line-hardening.md §3.4](bridge-line-hardening.md) 一致：**分片在 `FrameSender` 内、滤镜之后、容量判定之前**。

```
FrameSender.submit / submitReceipt(frame)
  → ① 滤镜链（可能丢弃）
  → ② FrameChunker.split(frame) → 1 或 N 片
  → ③ 容量判定：按 total 一次性原子预留
  → ④ 全部成功 → 整组提交；否则整组放弃（不提交任何一片）
  返回 SubmitOutcome
```

**同组原子入队（关键）**：逐片 `offer` 的做法已被否决——队列只剩几个空位时它会发出 `seq 0..2 / total 64`，对端于是永久等待其余 61 片，重组缓冲泄漏，且没有任何超时机制能可靠回收它。下面改为**按 `total` 一次性预留**：要么整组进队，要么一片都不进。

```kotlin
// 发送器内部的容量预留（帧数单位，与 bridge.line.*-capacity 语义一致）
private val reserved = AtomicInteger(0)

/** 一次性预留 n 个槽位；失败则一个都不占。 */
private fun tryReserve(n: Int): Boolean {
    while (true) {
        val cur = reserved.get()
        if (cur + n > capacity) return false
        if (reserved.compareAndSet(cur, cur + n)) return true
    }
}

/** 整组提交成功后把预留转为真实入队；提交失败则 abort 归还。 */
private fun commitGroup(group: List<OutboundFrame>): Boolean
private fun abortReservation(n: Int)
```

- 入队的是**一个组**（`FrameGroup`），发送线程取的单位是组而不是单帧。
- 预留失败 → 整组拒绝，队列深度**不变**（不会留半组），返回：
  - 可丢事件 → `DROPPED`；
  - 不可丢事件/回执 → `REJECTED`；
  - 片数超上限 → `TOO_LARGE`（与可丢性无关，这是硬错误）。
- 由于容量以**帧**计，一个 64 片组需要 64 个连续空位。这是刻意的：宁可整组明确失败，也不要半组成功（半组是对端无法回收的状态）。

**超限处置必须落在拿得到 `requestId` 的层**：切分发生在 `FrameSender.submit` 内，那里只有已序列化的 payload，拿不到 `BridgeCommandResult`，所以**不能在那里把回执"改成"失败**——只能如实返回 `TOO_LARGE`，由命令池重写（见下）。

```kotlin
// 命令池线程：仍持有 BridgeCommand（含 requestId）与 BridgeCommandResult
val outcome = sender.submitReceipt(receiptFrame)
when (outcome) {
    SubmitOutcome.TOO_LARGE -> sender.submitReceipt(
        FrameSender.failureReceipt(
            requestId = command.requestId,              // 关键：从命令带过来
            reason = "回执过大无法分片传输",
        )
    )
    SubmitOutcome.REJECTED -> sender.submitReceipt(
        FrameSender.failureReceipt(
            requestId = command.requestId,
            reason = "回执通道饱和",
        )
    )
    else -> Unit
}
```

链路写清如下：

1. `FrameSender.submit` / `submitReceipt` 只负责返回 `SubmitOutcome.TOO_LARGE`，并计数（`bridge_status` 侧，见风险 3）。
2. **命令池**据 `frame.requestId`（或 `command.requestId`）重新构造一条 `BridgeCommandResult.fail("回执过大无法分片传输")` 的回执，经 `submitReceipt` 送出。该失败回执只有几十字节，不会再触发分片，因此**不会递归**。
3. 若这条失败回执自身也被拒（`REJECTED`）→ 记 WARN + `commandResultRejected` 计数，**不再重试**（禁止"为失败回执再生成失败回执"的循环）。
4. `requestId` 为空（纯事件路径，没有回执可写）→ 只计数 + WARN 一次（去重），不重试。

### 3.4 同组连续性与代价

- **片继承原帧的 `Priority`**：命令回执的片仍是 `COMMAND_RESULT`，可靠事件仍是 `RELIABLE_EVENT`。**不新设"分片队列"**——分片不另开通道，仍走原优先级队列（[bridge-line-hardening.md §3.4](bridge-line-hardening.md) 的三条队列 + 控制旁路），因此不会与回执争抢、也不需要额外的公平性策略。
- **同组是不可抢占的发送单元**：发送线程取出一个组后连续写出全部片，期间**普通帧排队**（不穿插）。否则对端要在内存里长期持有半成品分组。
- **心跳/ping 不受影响**：`Priority.CONTROL` 走旁路（[bridge-line-hardening.md §3.4](bridge-line-hardening.md)），不入队、直接写单帧，因此**分片组不会拖死心跳**，JM 不会因大组被判失联。代价是 ping 可能插入某个组的中间；对端按 `groupId`/`seq` 重组，不依赖连续性。
- **分片组是一段临界区**：最长占用约 = `max-chunks-per-group × max-frame-bytes / 有效带宽`。默认 64 × 256 KiB ≈ 16 MiB，在 1 Mbps 上约 134 s、10 Mbps 上约 13 s、100 Mbps 上约 1.3 s。因此：
  - 单组上限不能随意调大（配置项要有注释说明这个换算式）；
  - 回执组与事件组按 `Priority` 排队，回执组优先取出；
  - 一个长组排队期间，后续回执仍按优先级排在其后 —— 这是已知代价，靠"回执通常很小、大回执是少数"来承受。

### 3.5 对端分组状态清理

**唯一清理时机：收到新的 `hello` 即清空全部分组状态**（`groupId → (total, 已收 seq 集合, 累计字节, 首片时间)`）。

- 为什么只有这一个时机：一条连接内未完成的分组只可能来自探针侧中断、整组拒绝或写超时截断；重连后探针**不续传**（范围外），旧分组一律无意义。因此"元数据保留多久"的答案是**到这条连接结束为止**——重连后首帧 `hello` 一到，全部丢弃。
- 不接受 TTL/超时作为主清理机制：任何 TTL 都要么太短（正常慢链路被误清）要么太长（泄漏），且无法回答"该留多久"。
- 孤儿片（收到某 `groupId` 的片但该组已被清空）→ 丢弃并计数，不抛、不堆积。
- 内存上界：单组上限 ≈ 16 MiB base64；并发未完成分组建议上限 8，超限丢最旧的组并记录日志（对端要求，见风险 4）。
- 若连接中断时已写出前缀：探针不重传，对端重连后按本条清空即可，不会留下永久半成品。

### 3.6 对端兼容

- 探针**不假设**对端支持分片。若 JM 未升级：它会把 `event=chunk` 当未知事件忽略，同时**收不到**原始完整帧——即该条回执/事件实际丢失。
- 因此分片默认**仅在超过阈值时**触发；默认阈值 256 KiB 远高于当前所有正常帧，未升级场景不受影响。
- `hello` 的 `capabilities` 数组**需包含 `chunk` 值**。登记点是 `BridgeCapabilities.addCapability("chunk")`，由 [bridge-reconnect.md §3.7](bridge-reconnect.md) 定义——`hello` 结构与该数组的序列化都是那份 spec 的职责，本 spec 只登记"需要这个 capability 值"，不定义 `helloJson` 结构，也不定义 `capabilities` 的完整内容或顺序。
- 探针不做"对端是否支持"的协商（`hello` 是探针→JM 单向的，探针无从得知 JM 能力）：兜底是阈值足够高 + `capabilities` 让 JM 提前决定是否下发大数据请求。

### 3.7 `config.yml` 新增

```yaml
bridge:
  chunk:
    enabled: true
    max-frame-bytes: 262144      # 整帧 JSON 的 UTF-8 字节上限（不是载荷上限）
    max-chunks-per-group: 64     # 单组上限；1 Mbps 下 64 片约 134s 独占发送，勿随意调大
```

- 读取必须经 `BridgeConfigSource.chunk()`（[bridge-line-hardening.md §6.2](bridge-line-hardening.md) 的接缝）；字段（`enabled` / `maxFrameBytes` / `maxChunksPerGroup`）由本功能定义。
- 单测用 `FakeBridgeConfigSource` 改值断言行为变化，否则验收退化为"读默认值"的假绿。

## 4. 任务拆分

- [x] `core`：`FrameChunker`（整帧字节量算、base64 切片、`total` 后算、递减重试、`TooLarge`）
- [x] `core`：`ChunkSettings` 字段定义（`enabled` / `maxFrameBytes` / `maxChunksPerGroup`）并接线 `BridgeConfigSource.chunk()`
- [x] `core`：`FrameSender` 组原子预留（`tryReserve(total)` / `commitGroup` / `abortReservation`），**绝不入队前缀**
- [x] `core`：`FrameSender.submit` / `submitReceipt` 接入 `split`（位置固定在滤镜之后、容量判定之前）
- [x] `core`：发送线程按组取出、同组连片写出
- [x] `core`：命令池侧 `TOO_LARGE` / `REJECTED` → 用 `frame.requestId` 重写失败回执（不递归）
- [x] `core`：`groupId` 用完整 UUID；`total` 写入每片
- [x] `core`：装配时 `BridgeCapabilities.addCapability("chunk")`（接缝归 [bridge-reconnect.md §3.7](bridge-reconnect.md)，本功能只管登记值）
- [x] `ProbeConfig`：增 `bridge.chunk.*`（经 `BridgeConfigSource` 接缝）
- [x] 单测：阈值三档边界 / 整帧字节 vs 载荷字节 / base64 往返 / 特殊字符 / `total` 正确 / 原子性 / 单组上限 / `TOO_LARGE` 链路 / 组连续与 ping 插入 / `groupId` 唯一
  （"新 `hello` 清理"一项属对端重组状态机，本仓无对端实现，见 §3.5 与 §5 说明）
- [x] E2E：fixture 构造 1 MiB 回执，断言收到 N 片、base64 解码后字节一致（`e2eChunkFixture` 场景，含 `chunk-fixture`）
- [x] 文档：PRD、ARCHITECTURE、CHANGELOG

**文件归属（并行开发隔离）**

| 归属 | 文件 |
|---|---|
| 本功能独占 | `core/.../bridge/chunk/*` |
| 与其它功能共改（在 [bridge-line-hardening.md](bridge-line-hardening.md) 完成后串行合入） | `core/.../bridge/FrameSender.kt`（hardening 独占文件，本功能在其中填充分片调用与原子预留）、`core/.../config/BridgeConfigSource.kt`（`chunk()` 已预留，本功能定 `ChunkSettings` 字段） |
| 只读依赖（归属其它 spec，**不得改**） | `core/.../bridge/OutboundFrame.kt`（字段集见 [bridge-line-hardening.md §3.3](bridge-line-hardening.md)，本功能**不加字段**）、`hello` 结构与 `capabilities` 数组（[bridge-reconnect.md §3.7](bridge-reconnect.md)） |

## 5. 验收标准

| 场景 | 判据 |
|---|---|
| **阈值三档边界** | 单测：注入 `max-frame-bytes = T`（如 4096，**不用默认值**），构造三帧使**整帧序列化 UTF-8 字节数**分别为 `T-1`、`T`、`T+1` → `T-1` 与 `T` 原样单帧发出（单元素结果、payload 逐字节相同），`T+1` 分片（`total >= 2`）。再补一例：信封写长（超长 `requestId`）使**载荷 < T 但整帧 > T**，断言仍然分片（证明量的是整帧字节而不是载荷字节） |
| 重组先解码 | 单测：按 `seq` 拼接 `data` → `Base64.getDecoder().decode` → `new String(bytes, UTF_8)`，断言结果与原 `payload` 的 **UTF-8 字节完全相同**（先比字节再比字符串）；**并断言"跳过 base64 解码直接拼字符串"与原文不相等**（否则测试根本没走 base64） |
| 特殊字符不破 | 单测：载荷含 `"`、`\`、换行、emoji、中文 → 分片重组后字节完全相同（base64 使 `data` 不含需转义字符，这正是选 base64 的原因） |
| `total` 后算 | 单测：`total` 在每片上都等于切片后的实际片数；构造内容使"预估"必然算错（如长度正好落在片边界 ±1），断言 `total` 仍正确 |
| **同组原子性** | 单测：可靠队列只剩 2 个空位，提交 `total == 64` 的分组 → 返回 `REJECTED`（可丢则为 `DROPPED`）、队列深度**仍为 2**、`reliableRejected` +1、fixture 端收到 **0 个** chunk 帧（不是 2 个前缀帧） |
| 单组上限 | 单测：`total > max-chunks-per-group` → `TOO_LARGE`、计数 +1、队列深度不变、**无任何片被写出** |
| **`TOO_LARGE` 回执链路** | 单测：命令池拿到 `TOO_LARGE` → 用 `frame.requestId` 重写 `fail("回执过大无法分片传输")` 并经 `submitReceipt` 送出；fixture 收到该失败回执且 `requestId` 与原命令一致；该失败回执自身不再分片（不会递归）；失败回执再被拒时只计数并 WARN，不重试 |
| 同组连续 | 单测：两组并发提交（各 4 片），写出序列中同组片 `seq` 连续、另一组不穿插；CONTROL ping 允许插入（插入后两组仍可各自重组） |
| 心跳不被拖死 | 单测：提交 64 片组并让 fixture 慢读 → 期间 ping 仍走旁路在 `writeDeadlineMs` 内写出，不触发 `writeTimeouts` |
| 阈值可配 | 单测：`FakeBridgeConfigSource` 把 `maxFrameBytes` 调到 1024 → 同样内容分片数按 §3.2 公式增加；`enabled=false` → 超阈值返回 `TOO_LARGE`（既不分片也不放行巨帧） |
| 对端清理 | 单测（fixture 侧模拟）：收到半组后收到新 `hello` → 全部组状态清空；随后同 `groupId` 的片被视为孤儿、丢弃计数、不抛 |
| `groupId` 唯一 | 单测：同一 instance 连续两次重连产生的 `groupId` 不相等，且均为 36 字符 UUID（无计数器归零问题） |
| 真机 | E2E fixture 重组成功，`status` 由逐项断言汇总（非"跑完即 PASS"） |

## 6. 风险 / 待定

1. **对端未升级时大数据帧会丢**：这是分片的固有代价。缓解：`hello` 的 `capabilities` 让 JM 提前知道；默认阈值足够高，正常业务不触发。
2. **同组连续写出会短暂阻塞其它帧**：一个 16 MiB 组独占发送期间，普通帧排队（心跳除外，走 `CONTROL` 旁路）。缓解：单组上限 + 回执优先级 + 组内片数上限可配并在配置注释里给出换算式。
3. **`TOO_LARGE` 没有独立的 `bridge_status` 字段**：[bridge-line-hardening.md §3.5](bridge-line-hardening.md) 的权威 schema 未含该计数器，本轮**不新增字段**（避免 schema 分叉），超限先并入已有计数（回执侧 `commandResultRejected`）。若线上需要单独观测，需先改那份 spec 的 schema 再落实现。
4. **对端内存**：本 spec 要求 JM 在收到新 `hello` 时清空分组，并建议并发未完成分组上限 8、单组上限 ≈16 MiB。这是跨仓要求，需在联调时确认 JM 已实现；若未实现，大组会先在 JM 侧堆积。
5. **待定**：`max-frame-bytes` 默认 256 KiB 需与 JM 侧接收缓冲上限联调确认。若 JM 有更小的单帧上限，应**下调本项**（在探针侧分得更碎），而不是让 JM 拒收后靠 catchup 补救。
6. **待定**：是否需要组级 ack / 重传——本轮不做（靠应用层 catchup + 新 `hello` 清空兜底）。若真机出现高频半组，再评估是否需要 `chunk_ack`；届时需同时给出"ack 超时值"与"重传是否会打破同组原子性"的答案。
