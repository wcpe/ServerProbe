# 功能规格：事件主题订阅

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-33　·　分支：feature/event-topic-subscription


## 1. 背景与目标

排行榜、实时进度这类业务只关心**某个具体业务对象**（如某个活动 `activity.12345`）的状态变化，但当前所有业务事件全量上行，JM 侧要自行过滤。业务量上来后，无关事件既浪费带宽也浪费 CP 的解析与入库。

本功能让 JM 按主题订阅：探针只上送匹配主题的业务事件，其余**进入上行队列之前**拦截。

## 2. 需求

### 范围内

- `event` 帧新增**可选**顶层字段 `topic`（帧内形态见 §3.1）。
- 业务事件按 `domain + 业务ID` 组主题，如 `activity.12345`。
- 订阅指令：`domain="core"`、`action="subscribe"`、`payload={"topics":["activity.12345","activity.*"]}`；路由 `order=120`（权威分配表见 [bridge-external-api.md §3.5](bridge-external-api.md)）。
- 支持**前缀通配**（`activity.*` 匹配 `activity.` 开头的全部主题）。
- 无订阅时保持现状：全量上报（向后兼容）。
- 有订阅后：只上报匹配主题的业务事件 + **所有非业务事件**（`connected`、`command_result`、`hello` 等不受影响）。
- 过滤为轻量字符串前缀匹配，不做正则。
- **滤镜位于容量判定之前**：被拦截的帧不占可靠队列容量、不计失败（见 §3.5）。

### 范围外

- 不做服务端反向推送（探针不主动告知 JM「有哪些主题」；`core.bridge_status.topics` 是查询口）。
- 不做主题层级继承（`a.*` **匹配** `a.b.c`，见 §3.4 的明确语义）。
- 不做订阅持久化：订阅只存内存，探针重启后为空；跨重连保留（见 §3.8），但不写盘。

## 3. 设计

### 3.1 帧形态

```json
{
  "type": "event",
  "event": "activity_change",
  "instance": "s1",
  "timestamp": 1730000000000,
  "domain": "activity",
  "dedupKey": "12345-1",
  "topic": "activity.12345",
  "data": { "progress": "80" }
}
```

- `topic` 缺失 = 非主题事件（玩家事件、非业务事件、未声明主题的业务事件）。
- 兼容：Worker 未识别 `topic` 时忽略即可，探针不假设对端已支持。
- `topic` 在序列化 JSON 与 `OutboundFrame.topic` 两处出现，**必须同源**：由业务事件发送路径一次算出并同时写入，不允许分叉（滤镜读到的是 `frame.topic`，对端看到的是 payload 里的 `topic`，两者不一致时过滤行为将无法解释）。

### 3.2 业务侧 API

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 业务事件上报入口。
 *
 * <p>经 {@code ServerProbeAPI.eventSink()} 取得<b>稳定代理</b>（可缓存为字段），永不返回 null。
 * 访问器名与 {@code BridgeServices.eventSink()} 同名，不另立别名。</p>
 *
 * <p>所有方法线程安全、<b>永不抛异常</b>、永不返回 null。</p>
 */
public interface BusinessEventSink {

    /**
     * 上报一个<b>可丢</b>业务事件（进度刷新、排行榜快照）。
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；可空（可丢通道不保证去重）
     * @param topic    主题；null 或空串表示未声明主题（有订阅时仍会放行，见 §3.5）
     * @param fields   结构化字段，键值均为字符串；null 视为空 Map
     * @return true=已受理（<b>含"被主题滤镜判定为无需上送"</b>）；false=通道饱和或探针不可用
     */
    boolean emit(String domain, String dedupKey, String topic, Map<String, String> fields);

    /**
     * 上报一个<b>不可丢</b>业务事件（结算、状态变更）。
     *
     * <p><b>要求 dedupKey 非空</b>：可靠通道依赖 JM 按 (domain, dedupKey) 幂等去重，
     * 空 dedupKey 会被拒绝并返回 false——<b>不静默降级为可丢</b>（降级会让调用方误以为事件可靠）。</p>
     *
     * @return true=已受理（含"已落盘待回放"）；false=可靠通道饱和，<b>调用方须转为业务失败</b>
     */
    boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields);
}
```

**归属与实现（定死）**：

| 项 | 位置 | 说明 |
|---|---|---|
| `BusinessEventSink` | `api` / `api.bridge` | **唯一**对外类型，由本 spec 定义（见 [bridge-external-api.md §3.5](bridge-external-api.md) 归属表） |
| `BusinessEventSinkImpl` | `core` / `core.bridge` | 实现上面这个接口，**内部类型，不对外** |
| `UnavailableBusinessEventSink` | `api` / `api` | 未就绪时的降级单例（[bridge-line-hardening.md §3.4](bridge-line-hardening.md) 已登记），两个方法一律返回 `false` |
| `ServerProbeAPI.eventSink()` | `api` / `api` | 稳定代理，委派给 `BridgeServices.eventSink()`；与方法名前缀规则一致（接口查询方法无 `get`） |

- 三份 spec 只允许出现这一套命名，**不接受任何别名**（旧稿曾出现两个指向相反的名字，已统一为 `BusinessEventSink`；实现类固定为 `BusinessEventSinkImpl`）。
- 两个方法的签名即 `BusinessEventSink` 的全部方法；`BridgeServices.eventSink()` 返回该类型，因此签名不得改名、不得加参数、不得改返回类型。

### 3.3 事件分级（谁决定一个事件可丢）

可丢性由**调用方选择的入口**决定，ServerProbe **不猜测业务语义**：

```kotlin
// core/bridge/event/EventPolicy.kt
/**
 * 事件可丢性策略：由调用方入口唯一决定，不从 domain / event 名推断。
 *
 * 不做"按域名推断可丢"的规则表——那会让第三方 Provider 在无意识中丢掉结算事件。
 */
enum class EventPolicy(
    val priority: OutboundFrame.Priority,
    val requiresDedupKey: Boolean,
) {
    /** emit：可丢，允许用丢弃换吞吐。 */
    BEST_EFFORT(OutboundFrame.Priority.BEST_EFFORT_EVENT, requiresDedupKey = false),

    /** emitReliable：不可丢，必须带 dedupKey（spill 回放按它去重）。 */
    RELIABLE(OutboundFrame.Priority.RELIABLE_EVENT, requiresDedupKey = true),
}
```

| 事件来源 | 入口（调用方选择） | 优先级 | 可丢 | dedupKey | 通道与失败后果 |
|---|---|---|---|---|---|
| 结算、状态变更 | `emitReliable` | `RELIABLE_EVENT` | 否 | **必需** | 可靠队列 → spill 落盘；拒绝则 `false`，调用方转业务失败 |
| 进度刷新、排行榜快照 | `emit` | `BEST_EFFORT_EVENT` | 是 | 可空 | best-effort 队列；满则 `DROPPED`，返回 `false` |
| 玩家 `player_join` / `chat` | `emit` | `BEST_EFFORT_EVENT` | 是 | **没有**（不可派生） | best-effort 队列 |
| 命令回执 | `FrameSender.submitReceipt` | `COMMAND_RESULT` | 否 | — | 回执队列（满时有界阻塞） |
| `ping` / `hello` | 内核 | `CONTROL` | — | — | 旁路直写，不入队 |

**规则（不可协商）**：

1. 一旦选定不可逆：`emitReliable` 不会因队列压力降级为可丢，`emit` 也不会升级为可靠。
2. **可靠通道只接受带非空 `dedupKey` 的业务事件。** spill 回放是"至少一次"，去重全靠 JM 按 `(domain, dedupKey)` 幂等。
3. 玩家事件（`player_join`、`chat`）没有业务幂等键，派生不出稳定的 `dedupKey` → **只能 best-effort**。
   - 为什么不用 UUID 兜一个 `dedupKey`：回放重发时 UUID 是新值，JM 无法识别为重复 → 重复入库（比丢一条更糟）。
   - 因此对玩家事件调用 `emitReliable` 返回 `false` + WARN 一次（去重），而不是静默降级。

### 3.4 主题匹配

```kotlin
// core/bridge/topic/TopicMatcher.kt
object TopicMatcher {
    /**
     * 订阅表达式匹配主题。
     *
     * 规则（纯字符串前缀，无正则）：
     * - 精确：expr == topic
     * - 通配：expr 以 ".*" 结尾 → topic.startsWith(expr.dropLast(1))   // "activity.*" → "activity."
     * - 其他含 "*" 的位置不支持 → 视为不匹配（避免歧义）
     */
    fun matches(expr: String, topic: String): Boolean
}
```

**层级语义**：`activity.*` 采用**前缀匹配**，因此 `activity.*` 匹配 `activity.12345` 也匹配 `activity.12345.sub`。这是刻意选择：层级继承会让「订阅了父主题却收不到子主题」成为陷阱，而前缀匹配更符合"我要这个业务域下的一切"的直觉。

### 3.5 滤镜接入与执行顺序

**执行顺序（不可调换）**：

```
emit / emitReliable
  → ① 分级（调用方入口决定 EventPolicy → Priority）
  → ② 滤镜（TopicFilter 可返回 null = 丢弃）
  → ③ 分片（frame-chunking）
  → ④ 容量判定（按片数原子预留）
  → ⑤ 入队
```

与 [bridge-line-hardening.md §3.4](bridge-line-hardening.md) 的 `FrameSender.submit` 顺序（滤镜 → 分片 → 容量判定 + 原子入队）逐项对齐；滤镜在**调用线程**（事件池线程）执行，不在读线程，也不是发送线程。

```kotlin
// core/bridge/topic/TopicFilter.kt（OutboundFilter，order = 200）
class TopicFilter(private val registry: TopicSubscriptionRegistry) : OutboundFilter {

    override fun apply(frame: OutboundFrame): OutboundFrame? {
        // ① 业务域判定：非业务域（玩家/系统/治理）一律不受主题过滤。
        //    只看 isBusinessDomain（domain 非空且不是 core/jbis），不看 event 名——
        //    避免新增 event 子类型时忘了加白名单造成漏过滤。
        if (!frame.isBusinessDomain) return frame
        // ② 无订阅：全量放行（向后兼容）
        if (!registry.hasSubscriptions()) return frame
        // ③ 未声明 topic：放行（见下方"关键决策"，防静默丢失）
        val topic = frame.topic
        if (topic.isNullOrEmpty()) return frame
        // ④ 有订阅且声明了 topic：只放行匹配的
        return if (registry.matchesAny(topic)) frame else null
    }
}
```

**本滤镜只读 `OutboundFrame` 的既有字段**：`topic` 与 `isBusinessDomain`。字段集的唯一权威是 [bridge-line-hardening.md §3.3](bridge-line-hardening.md)——本功能**不新增字段、不改字段语义**，`topic`/`domain` 已在其中定义。

**被滤镜丢弃的语义（定死）**：

| 项 | 结论 |
|---|---|
| 是否入队 | 否 |
| 是否占容量 | 否（容量判定在滤镜之后，根本看不到它） |
| 是否计失败 | 否（`reliableRejected` 不 +1） |
| `emitReliable` 返回 | **`true`**（已受理，只是无需上送） |
| `emit` 返回 | `true` |
| 计数 | `core.bridge_status.sender.filteredOut` +1（字段结构见 [bridge-line-hardening.md §3.5](bridge-line-hardening.md)） |
| 是否分片 | 否（分片在滤镜之后；被丢弃的大帧不产生任何 chunk） |
| `SubmitOutcome` | `FILTERED_OUT` |

**结果映射**：

| 阶段结果 | `SubmitOutcome` | 调用方返回值 | 计数 |
|---|---|---|---|
| 被滤镜丢弃 | `FILTERED_OUT` | `true` | `filteredOut` +1 |
| 可丢帧入队失败 | `DROPPED` | `false` | `bestEffortDropped` +1 |
| 不可丢帧被拒（队列满且 spill 接不下） | `REJECTED` | `false` | `reliableRejected` +1 |
| 不可丢帧由 spill 接管 | `QUEUED` | `true` | `spilled` +1 |
| 分片组超上限（事件路径无 `requestId`） | `TOO_LARGE` | `false` + WARN 一次 | 并入 `reliableRejected`（可丢事件并入 `bestEffortDropped`），不新增 `bridge_status` 字段 |

> spill 接管在 `SubmitOutcome` 上没有独立取值，一律按**已受理**记为 `QUEUED`——绝不能记成 `REJECTED`，否则调用方会把一次成功落盘的事件误判为业务失败。反之，`REJECTED` 只在"队列满且 spill 也接不下"时返回。

**顺序反了会怎样（必须写清，这是本 spec 最重要的正确性约束）**：

- 若先做容量判定再过滤：订阅越多，**无关事件先把可靠队列占满**，于是 `emitReliable` 开始返回 `false`，调用方（按 §3.2 的契约）把业务命令判为失败——一次"只是不关心"的噪音事件，导致结算命令报错。
- 监控上识别这类写反的特征：`topics.count` 上升、`reliableRejected` 上升，而 `filteredOut` 始终为 0（或远小于投递量）。三者同时出现即说明滤镜被挪到了容量判定之后。
- 因此滤镜**不得**实现为"入队后由发送线程丢弃"，也**不得**放在 spill 回放路径上（回放的是已受理的可靠事件，不应再被过滤）。

**关键决策**：有订阅时，**未声明 `topic` 的业务事件仍然放行**。理由——若拦截，任何还没适配 topic 的 Provider 会静默丢事件，这是比多传数据严重得多的故障。JM 想精确过滤时应让 Provider 声明 topic。

### 3.6 订阅注册表

```kotlin
// core/bridge/topic/TopicSubscriptionRegistry.kt
class TopicSubscriptionRegistry {
    /** 原子替换订阅集合；空集合表示「无订阅」（全量上报）。超限/非法 → 整批拒绝。 */
    fun replace(topics: Set<String>): SubscriptionApplyResult

    /** 增量添加；同样整批原子，拒绝时当前生效集合不变。 */
    fun add(topics: Set<String>): SubscriptionApplyResult

    /** 清空订阅，恢复全量上报。幂等。 */
    fun clear()

    fun hasSubscriptions(): Boolean
    fun matchesAny(topic: String): Boolean
    fun snapshot(): Set<String>
}

/** 订阅变更结果：被拒的表达式逐条带原因，绝不静默截断。 */
class SubscriptionApplyResult(
    /** 是否整批应用成功。 */
    val applied: Boolean,
    /** 生效后的全量订阅（回执原样回显给 JM）。 */
    val effective: Set<String>,
    /** 被拒明细：表达式 + 可读原因；成功时为空。 */
    val rejected: List<Rejection>,
) {
    class Rejection(val expression: String, val reason: String)
}
```

- 上限 `max-topics`（默认 256），超出**整批拒绝**并逐条说明（防内存膨胀）。
- 单个表达式长度上限 `max-expression-chars`（默认 256 字符；配置键 `bridge.subscribe.max-expression-chars`）。
- 空字符串、含空白 → 拒绝。
- **不做部分应用**：订阅意图是一组，部分生效会让"我订阅了却收不到"难以诊断；拒绝时当前生效集合保持不变，回执里列出越界明细。
- 为什么整批而非截断：静默截断会让 JM 以为订阅成功，且无法从回执看出少了哪条。
- **命名（ADR-13 + [bridge-external-api.md §3.1](bridge-external-api.md)）**：值对象的布尔访问器一律 `get` 前缀（如 `SubscriptionApplyResult.getApplied()`、配置重载事件的 `getFullReload()`），不引入 `isXxx()` 形式——`isX()` 在 Kotlin 侧会暴露成属性 `isX`，消费方写 `.x` 会失败。唯一例外是 `ServerProbeAPI.isAvailable()`（纯静态门面的可用性查询，已成事实标准）。

### 3.7 订阅指令

`InboundRoute`：`order=120`、`executor=COMMAND`（权威分配表见 [bridge-external-api.md §3.5](bridge-external-api.md)），`claims` 为纯同步判定：`domain == "core" && action in ("subscribe", "unsubscribe")`。

`domain="core"`、`action="subscribe"`：

```json
{"topics": ["activity.12345", "activity.*"]}
```

- `topics` 为空数组 → 清空订阅，恢复全量上报。
- 成功回执：`BridgeCommandResult.ok()`，`output` 为当前生效订阅的 JSON（便于 JM 核对，且与 `bridge_status.topics.expressions` 同值）。
- 失败回执：`BridgeCommandResult.fail(原因)`，`error` 含上限值与逐条被拒明细；**订阅保持变更前的状态**。
- `action="unsubscribe"`：payload 同形，按表达式移除；移除不存在的表达式不报错（幂等），`output` 回显生效集合。

`core.bridge_status` 中 `topics` 的**结构**由 [bridge-line-hardening.md §3.5](bridge-line-hardening.md) 定义，本 spec 只定取值语义：

```json
"topics": {"count": 2, "expressions": ["activity.12345", "activity.*"]}
```

- `count == 0` 表示**无订阅**（全量上报），不是"订阅了 0 个主题所以什么都不发"。
- `expressions` 按字典序、去重、与 `bridge_status` 快照一致。

### 3.8 重连语义

订阅是**内存态、跨重连保留**（探针重启才清空）。重连后：

- 保留最后一次订阅（不清空），避免 JM 未及时重下发期间事件全量涌出。
- 每次连上后 `hello` 重发，`capabilities` 含 `topic`（值由 [bridge-reconnect.md §3.7](bridge-reconnect.md) 定义）。

**可观测性要求（本条必须实现）**：`hello` 需暴露**当前订阅表达式**，让 JM 在连上瞬间就能发现「探针仍在用旧订阅」并重下发，而不必先发一条 `bridge_status`。

- 该字段已由 [bridge-reconnect.md §3.7](bridge-reconnect.md) 定义为 `hello.subscriptions`（字符串数组）。**`hello` 结构的唯一归属是那份 spec**，本文件只提出需求与接线义务，不另立结构、不改 `helloJson`。
- 接线（本功能的义务）：装配时调用 `BridgeCapabilities.addCapability("topic")` 与 `BridgeCapabilities.setSubscriptionSource { registry.snapshot().toList() }`。未接线时对端返回空数组（reconnect spec 不依赖本功能）——因此**断言二者一致**是装配正确性的判据。
- 取值必须与 `core.bridge_status.topics.expressions` **同源同值**（同一个注册表快照），不允许各自拼装。
- 已知故障模式：若装配漏了 `setSubscriptionSource`，`hello.subscriptions` 会恒为空数组而实际仍有订阅。这比"静默沿用旧订阅"好（至少可观测），但必须在装配单测里挡住（断言接线后两者相等）。

## 4. 任务拆分

- [x] `api`：`BusinessEventSink`（`api.bridge`）+ `ServerProbeAPI.eventSink()` 稳定代理 + `UnavailableBusinessEventSink`
- [x] `core`：`BusinessEventSinkImpl`（实现 api 接口，接线 `BridgeServices.eventSink()`）
- [x] `core`：`EventPolicy`（可丢性枚举，仅由入口选择）
- [x] `core`：`TopicSubscriptionRegistry` + `TopicMatcher`（新增）
- [x] `core`：`TopicFilter`（`OutboundFilter`，order=200，**只读** `frame.topic` / `frame.isBusinessDomain`）
- [x] `core`：`SubscribeRoute`（`action=subscribe` / `unsubscribe`，order=120，`claims` 同步、`handle` 投 COMMAND 池）
- [x] `core`：业务事件发送路径写入 `topic`（并保证与 payload 内 `topic` 同源）
- [x] `core`：`bridge_status.sender.filteredOut` 计数（结构已由线路治理定义，本功能填语义）+ `topics` 取值
- [x] `core`：装配时登记 `BridgeCapabilities.addCapability("topic")` + `setSubscriptionSource { registry.snapshot().toList() }`（接缝归 bridge-reconnect §3.7）
- [x] `core`：经 `BridgeConfigSource.subscribe()` 读取配置（该方法已在 [bridge-line-hardening.md §6.2](bridge-line-hardening.md) 的接口权威定义中登记，本功能**只填 `ProbeConfigBridgeConfigSource` 的实现**，不改接口签名）
- [x] 单测：精确 / 前缀 / 不支持通配 / 无订阅全量 / 有订阅过滤 / 未声明 topic 放行 / 整批拒绝 / 清空
- [x] 单测：**滤镜先于容量**（灌 1000 条不匹配事件，断言 `filteredOut=1000`、`reliableQueue=0`、`emitReliable` 全 true）
- [x] 单测：`emitReliable` 空 `dedupKey` → false + WARN，不降级；`emit` 无 dedupKey 正常受理
- [x] 单测：`emitReliable` 在 `REJECTED` 下返回 false 且无异常逃逸（**永不抛**）
- [x] E2E：fixture 订阅 `activity.12345` → 断言 `activity.99999` 不上行、`activity.12345` 上行
- [ ] 文档：PRD、ARCHITECTURE、CHANGELOG（PRD 维持「开发中」；CHANGELOG 已补；**ARCHITECTURE 无桥线路章节**，本功能未改该文件）


**文件归属（并行开发隔离）**

| 归属 | 文件 |
|---|---|
| 本功能独占 | `core/.../bridge/topic/*`、`core/.../bridge/event/EventPolicy.kt`、`api/.../api/bridge/BusinessEventSink.java`、`core/.../bridge/BusinessEventSinkImpl.kt` |
| 与其它功能共改（需串行合入） | `api/.../api/ServerProbeAPI.java`（追加 `eventSink()` 访问器，external-api 独占文件）、`core/.../bridge/BridgeClient.kt`（只加 1 行 filter 注册，线路治理重构后）、`core/.../config/BridgeConfigSource.kt`（追加 `subscribe()`，是 [bridge-line-hardening.md §6.2](bridge-line-hardening.md) 共用的配置接缝）、`core/.../bridge/BridgeStatusRoute.kt`（填 `topics` 取值，结构由线路治理定） |
| 只读依赖（归属其它 spec，**不得改**） | `core/.../bridge/OutboundFrame.kt`（字段集见 [bridge-line-hardening.md §3.3](bridge-line-hardening.md)）、`core/.../bridge/FrameSender.kt`（滤镜链执行位置与 `SubmitOutcome`）、`hello` 结构（[bridge-reconnect.md §3.7](bridge-reconnect.md)） |

## 5. 验收标准

| 场景 | 判据 |
|---|---|
| **无订阅不过滤**（去循环论证） | 无任何订阅时，业务 Provider 依次上报 3 个不同 `topic`（`activity.1` / `activity.2` / `activity.3`）的事件，断言 fixture **收到 3 条且 topic 全等**；同时 `bridge_status.topics.count == 0`（证明"无订阅"真的是没有订阅，而不是"订阅了空集所以不发"） |
| 精确订阅 | 订阅 `activity.12345` 后，`activity.12345` 上行，`activity.99999` 不上行；`topics.expressions` 与回执 `output` 一致 |
| 前缀订阅 | 订阅 `activity.*` 后，`activity.1` 与 `activity.2` 均上行 |
| 层级前缀 | `activity.*` 匹配 `activity.12345.sub`（本 spec 的明确语义） |
| 不支持通配 | `act*ity` 不匹配任何主题（不崩溃、不误匹配） |
| 非业务事件 | `connected` / `command_result` 在订阅生效期间仍全部上行 |
| 未声明 topic | 有订阅时，未带 topic 的业务事件仍上行（防静默丢失） |
| **滤镜先于容量** | 单测：订阅只匹配 `activity.12345`，把 `reliable`/`best-effort` 容量调到极小，灌入 1000 条 `topic=activity.99999` 的 `emitReliable` 事件 → 断言 `sender.filteredOut == 1000`、`reliableQueue == 0`、**1000 次调用全部返回 true**、`reliableRejected == 0` |
| **滤镜丢弃不产生分片** | 单测：一条尺寸超过 `max-frame-bytes` 且不匹配订阅的帧 → 不产生任何 `event=chunk`、`max-chunks-per-group` 未被触碰、`filteredOut` +1 |
| 清空订阅 | `topics: []` 后 `topics.count == 0`，且 3 个不同 topic 全部上行 |
| 上限不静默截断 | 提交 `max-topics + 1` 个表达式 → 回执 `ok=false`、`error` 含上限值与越界明细；`topics.count` **保持变更前的值**；fixture 观测到的上行集合与变更前完全一致（证明没有部分生效） |
| 可靠通道去重键 | 单测：`emitReliable` 传空 `dedupKey` → 返回 false + WARN 一次，事件**未**进入可靠队列；`emit` 传空 `dedupKey` → 正常按 best-effort 受理 |
| 永不抛 | 单测：可靠队列与 spill 全部不可用时 `emitReliable` 返回 false，**无异常逃逸到调用方**（含 `RejectedExecutionException` 被内核消化） |
| 跨重连保留 | 断开重连后不重下发，`activity.12345` 仍上行、`activity.99999` 仍不上行；`hello.subscriptions` 与断连前及 `bridge_status.topics.expressions` 三者一致 |
| `hello` 订阅暴露 | 单测：订阅 2 条后 `BridgeCapabilities.subscriptions()` 与 `registry.snapshot()` 逐项相等；未接线时为**空数组且不抛**（证明接线缺失是可观测的，不是静默沿用） |
| 真机 | E2E fixture 断言过滤生效，`status` 由逐项断言汇总（非"跑完即 PASS"） |

## 6. 风险 / 待定

1. **未声明 topic 放行**会削弱过滤收益。缓解：Provider 适配 topic 后收益才完整；文档明确「想被过滤必须声明 topic」。这是刻意用少量带宽换"不静默丢事件"。
2. **订阅语义跨仓未对齐**：JM 侧需理解「未声明 topic 的业务事件不受过滤」「`count==0` 表示全量上报」。本仓只在 spec、回执与 `hello` 中写明，端到端待联调。
3. **`hello.subscriptions` 的实现不在本 spec 手里**：结构、序列化与 `BridgeCapabilities` 都由 [bridge-reconnect.md §3.7](bridge-reconnect.md) 提供，本功能只负责接线（`addCapability("topic")` + `setSubscriptionSource`）。风险是装配顺序：若本功能晚于 hello 首次发出完成装配，第一次 `hello` 的 `subscriptions` 会是空数组 —— 装配完成后必须断言二者一致，否则"旧订阅"仍可能被漏看一次。
4. **`emitReliable` 强制 `dedupKey` 会让部分既有调用点无法直接迁到可靠通道**（玩家事件）。这是有意的：宁可 best-effort，也不要回放重复入库。需要在文档与 PRD 里对第三方 Provider 讲清。
5. **待定**：`bridge.subscribe.max-topics` 默认 256 是否合适——取决于 JM 侧单连接的实际订阅粒度（按对象订阅时会很快逼近）。首版取 256，真机观察 `topics.count` 分布后再调；上调只改配置，不改协议。
