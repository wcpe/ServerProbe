# 功能规格：桥线路治理

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-31　·　分支：feature/bridge-line-hardening


## 1. 背景与目标

桥是**单条 WebSocket 连接**承载全部流量：治理命令、业务命令、事件上行、心跳。当前实现有五处结构性风险：

1. `BridgeClient.handleCommand` 在**桥读线程同步等待**业务执行（`future.get(5000ms)`），一个卡死的 Provider 让读线程 5 秒不处理任何下行帧。
2. 事件上行是「发不出去就丢弃 + WARN」，业务事件（结算、状态变更）会静默丢。
3. 所有工作共用一个**无界**缓存池，重载这类最容易卡住的操作与命令执行互相挤占。
4. **写侧没有超时**：`writeFrameLocked` 在写锁内做无超时的 `write+flush`。对端不读时写永久阻塞并持锁，`close()` 也要抢同一把锁 → 发送线程、心跳、重连、`stop()` 一起挂死。
5. **入站帧没有大小上限**：`readFrame` 用 `ByteArray(len.toInt())`，畸形长度会变负数或触发巨型分配；`runLoop` 只 catch `Exception`，接不住 `OutOfMemoryError` → daemon 线程死掉、桥永久离线。

本功能把桥重构成「读线程只解析 + 分发」的内核，提供三池隔离、事件背压、写侧有界与入站上限，并给出后续功能挂载的**稳定扩展点**——使它们能并行开发而不互相改同一个文件。

## 2. 需求

### 范围内

- 读线程只做「解析帧 + 投递」，任何执行不在读线程等待；`onFrame` **绝不抛**。
- 三个独立 daemon 池（命令 / 事件 / 重载），**全部有界 + 显式拒绝策略**；路由可指定目标池。
- 发送通道优先级：命令回执 > 不可丢事件 > 可丢事件 > 控制帧旁路。
- 事件分级与背压：可丢（有界丢弃 + 计数）/ 不可丢（有界 + 落盘兜底 + 满则回压）。
- 写侧有界：写阻塞超时 → 强关 socket 解阻塞。
- 入站帧大小上限，超限丢帧不断连。
- `core.bridge_status` 暴露队列深度、丢弃/拒绝计数、写超时、连接状态。
- 停机协议：有序关闭，无残留线程。

### 范围外

- 不做多连接/多路复用（保持单 WS）。
- 不做事件重放保证（补偿仍由 JM 侧 catchup 负责）。
- 不引入第三方队列库（纯 JDK）。

## 3. 设计

### 3.1 线程模型

```
WS socket
   │
   ├─ 读线程 "ServerProbe-Bridge"（唯一）
   │     readMessage → Json.parse → BridgeKernel.onFrame（绝不等待、绝不抛）
   │        匹配 InboundRoute → 按 route.executor 投递到对应池
   │        投递被拒 → 就地生成失败回执 / 计数丢弃（不抛）
   │
   ├─ 命令池 "serverprobe-cmd-N"      （有界 + AbortPolicy）
   │     治理 handler / 业务 Provider → 生成回执 → FrameSender.submitReceipt
   │
   ├─ 事件池 "serverprobe-event-N"    （有界 + AbortPolicy）
   │     事件分类 → 滤镜 → 分片 → FrameSender 入队
   │
   ├─ 重载池 "serverprobe-reload-N"   （有界 + AbortPolicy，独立于命令池）
   │     配置重载 → 回执
   │
   ├─ 发送线程 "serverprobe-sender"
   │     优先级轮询取帧 → write（有超时 watchdog）
   │
   └─ 落盘线程 "serverprobe-spill"（单线程）
         可靠队列满时异步写 spill 文件 + 位点
```

**关键不变量**：

- 读线程上不做任何阻塞调用，不抛任何异常。
- 任何池满都不导致读线程异常或断连。
- 任何 socket 写都有 deadline，绝不无限阻塞。

### 3.2 扩展点

```kotlin
// core/bridge/BridgeKernel.kt
/**
 * 桥内核：读循环与分发。读线程只调 [onFrame]，本类绝不阻塞、绝不抛。
 */
class BridgeKernel(
    private val sender: FrameSender,
    private val pools: BridgePools,
) {
    fun addRoute(route: InboundRoute)
    fun addOutboundFilter(filter: OutboundFilter)

    /** 读线程调用：解析 + 投递，返回即结束；任何异常就地吞掉并计数。 */
    fun onFrame(raw: String)
}

/** 路由的目标执行池。 */
enum class ExecutorKind { COMMAND, EVENT, RELOAD }

/**
 * 下行路由扩展点。
 *
 * **匹配与执行分离**：[claims] 必须是纯同步、O(1) 的判定，在读线程执行；
 * [handle] 在 route.executor 指定的池上执行，内核**不等待**其结果。
 * 这样内核无需为了判断"是否命中"而等待一个可能耗时 5s 的 handle。
 */
interface InboundRoute {
    /** 数字小的先匹配；见 external-api §3.5 的权威分配表。 */
    val order: Int

    /** 目标执行池。 */
    val executor: ExecutorKind get() = ExecutorKind.COMMAND

    /** 纯同步匹配：是否由本路由处理这条命令。不得做 IO、不得阻塞。 */
    fun claims(command: BridgeCommand): Boolean

    /** 在 [executor] 池上执行；只允许返回结果，不得内部无界等待。 */
    fun handle(command: BridgeCommand): BridgeCommandResult
}

/** 上行帧滤镜。可改写 frame，或返回 null 表示丢弃（丢弃不算失败，见 §3.4）。 */
interface OutboundFilter {
    val order: Int
    fun apply(frame: OutboundFrame): OutboundFrame?
}
```

**权威 `order` / `executor` 表见 [bridge-external-api.md §3.5](bridge-external-api.md)。后续 spec 只填实现，不改号。**

**`InboundRoute.async` 已删除**：原设计用它表达"是否异步"，但语义与「读线程绝不等待」冲突且无法实现。
现在一律投池，是否需要"更快"由 `executor` 选择表达。

### 3.3 `OutboundFrame`（唯一权威定义）

```kotlin
// core/bridge/OutboundFrame.kt
/**
 * 一条待发出的上行帧。
 *
 * 字段集是**唯一权威**：后续功能只读不增（新增字段须改本节并同步所有 spec）。
 */
class OutboundFrame(
    /** 帧类型：event / command_result / hello / ping。 */
    val type: String,
    /** event 子类型（command_result / connected / chunk / <domain>_change …）；非 event 为空串。 */
    val event: String,
    /** 业务域；非业务为空串。 */
    val domain: String,
    /** 已序列化的完整 JSON 文本。 */
    val payload: String,
    /** 优先级：见 [Priority]。 */
    val priority: Priority,
    /** 主题（event-topic-subscription 用）；无则 null。 */
    val topic: String? = null,
    /** 关联回执标识（用于分片超限时回写失败）；无则空串。 */
    val requestId: String = "",
    /** 分片分组 ID（frame-chunking 用）；非分片为 null。 */
    val groupId: String? = null,
    /** 分片序号，从 0 开始；非分片为 0。 */
    val seq: Int = 0,
    /** 分片总数；非分片为 1。 */
    val total: Int = 1,
    /** 分片对应的原始 event 子类型；非分片为 null。 */
    val ofType: String? = null,
) {
    /** 是否业务域事件（用于主题过滤）。 */
    val isBusinessDomain: Boolean get() = domain.isNotEmpty() && domain != "core" && domain != "jbis"

    enum class Priority {
        /** 控制帧（ping/pong/hello）：走旁路，不入队，见 §3.5。 */
        CONTROL,
        /** 命令回执：最高队列优先级。 */
        COMMAND_RESULT,
        /** 不可丢事件。 */
        RELIABLE_EVENT,
        /** 可丢事件。 */
        BEST_EFFORT_EVENT;

        /** 可丢性由优先级唯一决定，不再有独立的 reliable 字段。 */
        val droppable: Boolean get() = this == BEST_EFFORT_EVENT
    }
}
```

> 原设计有 `priority` + `reliable` 两个字段，且枚举注释把 `CONTROL` 排在最低而风险段又说它最高——
> 自相矛盾。现改为**单一 `priority`**，可丢性由 `droppable` 派生。

### 3.4 发送器、背压与滤镜顺序

```kotlin
// core/bridge/FrameSender.kt
class FrameSender(
    /** 当前连接；重连时由 BridgeClient attach/detach，解决"持有旧 ws 永久失效"。 */
    private val connection: AtomicReference<MinimalWebSocketClient?>,
) {
    fun attach(ws: MinimalWebSocketClient)
    fun detach()

    /**
     * 提交一帧（事件/回执通用入口）。
     *
     * 执行顺序（**顺序不可变**）：
     *   ① 滤镜链（可丢弃）
     *   ② 分片（frame-chunking）
     *   ③ 容量判定 + 原子入队
     *
     * @return 见 [SubmitOutcome]
     */
    fun submit(frame: OutboundFrame): SubmitOutcome

    /**
     * 提交一条命令回执。与 [submit] 的差别：回执队列满时**在调用线程做有界阻塞**
     * （至多 [RECEIPT_OFFER_BUDGET_MS]），仍失败则返回 `Rejected` 并计数。
     *
     * <p><b>只允许在池线程（cmd/事件/重载）调用，严禁在读线程调用。</b>
     * 读线程的饱和回执必须用 [offerReceipt]。</p>
     */
    fun submitReceipt(frame: OutboundFrame): SubmitOutcome

    /**
     * 非阻塞提交回执，**专供读线程使用**。
     *
     * <p>只做一次 `offer`，绝不阻塞、绝不抛。失败仅计数（`commandResultRejected`），
     * 由 JM 侧超时兜底——读线程的可用性优先于单条回执的送达。</p>
     */
    fun offerReceipt(frame: OutboundFrame): SubmitOutcome

    /** 发送线程主循环。 */
    fun runLoop()

    fun status(): SenderStatus
}

/**
 * 发送器状态快照（不可变，整体替换，避免多字段撕裂读）。
 */
class SenderStatus(
    val commandResultQueue: Int,
    val commandResultRejected: Long,
    val reliableQueue: Int,
    val reliableRejected: Long,
    val bestEffortDropped: Long,
    val filteredOut: Long,
    val spilled: Long,
    val spillPending: Long,
    /**
     * 落盘内存缓冲溢出被丢弃的条数（**真实数据丢失**）。
     *
     * 与 `reliableRejected` 的区别：后者是"拒绝受理、调用方拿到 false 可重试"，
     * 而这里丢的是**已被受理**的可靠事件（调用方当时收到 true）。
     * 触发场景：大批量回灌时生产速率远超发送速率 → 队列满 → 落盘 → 落盘缓冲也满 → 丢最旧。
     * 该值持续 > 0 说明回灌流量已超过单条连接承载能力。
     */
    val spillDropped: Long,
    val writeTimeouts: Long,
    val writeFailures: Long,
)

enum class SubmitOutcome {
    /** 已入队，将被写出。 */
    QUEUED,
    /** 被滤镜判定为无需上送（**不算失败**，见下）。 */
    FILTERED_OUT,
    /** 可丢帧因队列满被丢弃（已计数）。 */
    DROPPED,
    /** 不可丢帧被拒（已计数），调用方须转为业务失败。 */
    REJECTED,
    /** 单组片数超上限，无法分片传输（已计数），调用方须转为失败回执。 */
    TOO_LARGE,
}
```

**滤镜必须在容量判定之前**（否则"订阅越多、业务越容易失败"）：

- 被滤镜丢弃的帧**不入队、不占容量**、**不计失败**，`emitReliable` 返回 `true`（已受理，只是无需上送）。
- `bridge_status` 单独计数 `filteredOut`，便于核对"订阅是否真的省了流量"。

**队列与拒绝策略（全部定死）**：

```kotlin
class BridgePools(
    cmd: ThreadPoolExecutor,     // 有界 ArrayBlockingQueue(cap) + AbortPolicy
    event: ThreadPoolExecutor,   // 同上
    reload: ThreadPoolExecutor,  // 同上
)
```

- 三池一律 `ThreadPoolExecutor(size, size, 60s, ArrayBlockingQueue(cap), daemonFactory, AbortPolicy)`。
- **拒绝必须在内核内消化**：`BridgeKernel` 投递时 `catch (RejectedExecutionException)`：
  - 命令类 → 就地构造 `fail("命令通道饱和")` 回执并 `offerReceipt`（**非阻塞**，读线程零阻塞）；
  - 事件类 → 计数丢弃；
  - 重载类 → 就地构造 `fail("重载通道饱和")` 回执并 `offerReceipt`。
- **读线程只能用非阻塞入口**：`submit`（内部 `offer` 语义）与 `offerReceipt`；
  **禁止在读线程调 `submitReceipt`**（它会做有界阻塞，最长 500ms，100 条饱和命令就会把读线程卡 50s，
  超过 `READ_TIMEOUT_MS` 30s）。饱和回执若 `offerReceipt` 再失败 → 只计数 + WARN 一次，
  由 JM 侧超时兜底：**读线程可用性优先于单条回执送达**。
- `onFrame` 外层再包 `runCatching`，**保证任何路径都不把异常抛给读循环**
  （否则异常会冒到 `runLoop` 的 `catch(Exception)` → 关连接 → 重连风暴）。

**不可丢事件落盘兜底（带提交位点，防活锁）**：

- 目录 `data/bridge-spill/`，文件 `spill.jsonl` + 同目录位点文件 `spill.off`（已提交字节偏移）。
- **提交语义**：只有 `sendText` 正常返回后才推进位点。未推进的记录在下次回放时重发（至少一次）。
- **确认方式必须用对象引用相等**（实现修正）：回放时 `next()` 把帧实例交给调用方，该同一实例经可靠队列
  流到发送线程，`onSent(frame)` 用 `===` 定位对应记录。
  **不能用字节数或内容哈希推断**——发送顺序与文件顺序不保证一致，按长度推进会跳过未确认记录而**丢数据**；
  且位点只能推进"连续已确认的前缀"，一旦跨过未确认记录，那条记录就永久丢了。
- 回放**只在已连接时**由 spill 线程滴灌，每批不超过队列剩余容量；未连接时不动（不会活锁）。
  重连成功后重置回放位置，未确认记录会被重新读出。
- 半截尾行（崩溃残留）视为未提交并截断。
- 上限：文件数 + 总字节双上限（可配）。超限时**只丢已提交的前缀**（轮转时把未提交尾部重写进新文件）；
  未提交的绝不丢。
- 写盘由独立单线程 `serverprobe-spill` 异步执行，**绝不在事件线程同步写盘**。
- 回放重复由 JM 侧 `dedupKey` 去重；因此**只有带 `dedupKey` 的业务事件**才能走 reliable 通道
  （玩家事件无 dedupKey，只能走 best-effort）。

**命令回执不落盘**（实现修正）：回执有时效性，重放无意义。回执队列满时直接拒绝并计数
（`commandResultRejected`），不走 spill——否则会把有时效的回执混进"不可丢事件"的语义里。

**写侧有界（新增，最关键）**：

- Java socket 没有写超时，`soTimeout` 只作用于读。
- 写前记录 `writingSinceMs`（`@Volatile`）；**读循环每次迭代都调用 `checkWriteDeadline()`**
  —— 既在读到帧之后、也在读超时之后。
  > **为什么必须在"读到帧之后"也检查**：若只在读超时分支检查，对端"持续下行但停止读取"
  > （最真实的拥塞形态）时读线程永不进入 timeout 分支，写线程永远不会被解阻塞。
  这与 `bridge-reconnect` §3.2 心跳判定必须在两处检查是同一个道理。
- 判定：`now - writingSinceMs > writeDeadlineMs`（默认 10s）→ **直接 `socket.close()`** 解阻塞。
- **`soTimeout` 必须由 `LineSettings.readTimeoutMs` 注入**，默认值需 ≤ `writeDeadlineMs`
  （建议 `readTimeoutMs` 默认 5s，`writeDeadlineMs` 默认 10s）。
  > 原设计用现有的 `READ_TIMEOUT_MS = 30s` 作心跳节拍，会让写侧检测延迟下限变成 30s，
  > `writeDeadlineMs = 10s` 名存实亡。
- 这是 Java 里唯一可移植的"解阻塞写"手段，不追求优雅（半帧被截断由对端按协议丢弃并重连）。
- `MinimalWebSocketClient.close()` **只置 `closed=true` + `socket.close()`，不获取写锁、不写任何帧**。
  > 原设计写"先 close 再尽力发 close 帧"是不可能执行的：socket 关闭后输出流已失效。
  > 也不允许"不持锁直接写 close 帧"——会与发送线程交错写出半帧。
  close 帧由发送线程观察到 `closed` 后尽力发（受同一 deadline 约束），或直接不发（对端按 EOF 处理）。
- 计数 `writeTimeouts` / `writeFailures` 暴露到 `bridge_status`。

**入站硬上限（新增）**：

- `bridge.line.max-inbound-bytes`（默认 1 MiB）。
- 超限或长度非法 → 抛 `FrameTooLargeException(message, payloadSkipped)`，读循环捕获后**计数**，并分级处置：
  - `payloadSkipped=true`（载荷已被完整跳过、流仍同步）→ **丢弃该帧并继续读**，连接保持；
  - `payloadSkipped=false`（流已不可同步）→ 抛出并重连。
- **为什么分级**（实现修正）：原设计一律"保持连接"，但对端声明 2 GiB 长度时，跳过载荷等于把畸形帧
  放大成带宽消耗（也是 DoS 面）。因此只有声明长度 ≤ 上限 4 倍时才尝试跳过。
- `readFrame` 修正：`len` 为负或超上限时立即拒绝，绝不 `ByteArray(len.toInt())`
  （原实现 `len > Int.MAX_VALUE` 时 `toInt()` 溢出为负 → `NegativeArraySizeException`；
  约 2 GiB 时巨型分配 → `OutOfMemoryError`。两者都能打死桥线程）。
- `runLoop` 增加 `catch (t: Throwable)` 兜底：记日志后重连（`Error` 也要接住，否则桥永久离线）。

**心跳旁路**：

- `Priority.CONTROL` 不入队，直接 `synchronized(writeLock)` 写单帧。
- 理由：分片组是发送临界区，若 ping 排队会被最长 ≈ 单组上限字节的写延迟拖住，导致 JM 误判失联。
- 代价：ping 可能插入某个分片组中间。对端按 `event=chunk` + `groupId` 重组，不依赖连续性，可接受。

### 3.4.1 可测性设计（实现补充）

三处依赖被抽成接口/函数，否则核心逻辑无法单测（`core` 的裸测试类路径没有 nightconfig，
且 `MinimalWebSocketClient` 是 final + 需要真实 socket）：

| 接缝 | 生产实现 | 测试替换 | 理由 |
|---|---|---|---|
| `OutboundSink`（`sendText`） | `MinimalWebSocketClient` 实现它 | 记录型假连接 | 发送器的分级/优先级逻辑不该依赖真实网络 |
| `BridgeKernel` 的 `parse` | `Json.parse` 封装 | 纯逻辑解析器 | 解析与调度是两件事 |
| `BridgeKernel` 的 `encodeReceipt` | `Json.encode` 封装 | 纯逻辑编码器 | 同上 |

**回执编码失败必须被兜住**（实现修正）：`encodeReceipt` 可能因运行期 JSON 库异常返回 null 或抛
`Error`（`NoClassDefFoundError` 不是 `Exception`）。若不在 `sendReceipt` 内兜住，
池线程会**直接死掉**，回执通道永久失效。故：编码失败 → 计数 `receiptEncodeFailures` + 放弃该条回执
（JM 侧超时兜底），绝不让异常逃出池线程。

### 3.5 `core.bridge_status`

`InboundRoute`，`order=130`，`executor=COMMAND`。**权威全量 schema**（其它 spec 只能填值，不得改结构）：

```json
{
  "connected": false,
  "state": "BACKOFF",
  "reconnects": 7,
  "lastDisconnectReason": "AUTH_FAILED",
  "lastDisconnectDetail": "认证失败（token 无效或已轮换）",
  "lastDisconnectAtMs": 1730000000000,
  "lastConnectedAtMs": 1729999900000,
  "currentBackoffMs": 8000,
  "consecutiveFailures": 3,
  "pools": {
    "cmd":    {"size": 4, "active": 0, "queued": 0, "rejected": 0},
    "event":  {"size": 2, "active": 0, "queued": 0, "rejected": 0},
    "reload": {"size": 4, "active": 0, "queued": 0, "rejected": 0}
  },
  "sender": {
    "commandResultQueue": 0,
    "commandResultRejected": 0,
    "reliableQueue": 0,
    "reliableRejected": 0,
    "bestEffortDropped": 0,
    "filteredOut": 0,
    "spilled": 0,
    "spillPending": 0,
    "spillDropped": 0,
    "writeTimeouts": 0,
    "writeFailures": 0
  },
  "inbound": {"frames": 0, "tooLarge": 0, "parseErrors": 0},
  "reload": {"inflight": [], "stuck": [], "breakerOpen": [], "breakerRejectedEntries": 0},
  "wired": ["businessHost", "eventSink", "configReloads", "bridgeStatus"],
  "topics": {"count": 0, "expressions": []},
  "version": "2",
  "capabilities": ["chunk", "topic", "config_reload", "business_status"],
  "timestamp": 1730000001000
}
```

- `state` ∈ `STOPPED | CONNECTING | CONNECTED | BACKOFF`，由 `bridge-reconnect` 维护。
- `lastDisconnectReason` 初值为空串（**不是** `UNKNOWN`）；`UNKNOWN` 仅表示"确实断了但归不了类"。
- `connected` 保留为便捷布尔（等价于 `state==CONNECTED`），文档注明可由 `state` 推导。
- `hello` 是 `version`/`capabilities` 的唯一真源（见 `bridge-reconnect` §3.6）；此处为同一份内存值的镜像。

对外只读视图（`api.bridge`）：

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 桥运行状态只读视图。由 {@code ServerProbeAPI.bridgeStatus()} 取得，稳定代理、永不 null。
 * 所有方法只读内存快照，不做 IO，可在任意线程调用。
 */
public interface BridgeStatus {

    /** 连接状态机。 */
    enum State { STOPPED, CONNECTING, CONNECTED, BACKOFF }

    State getState();

    /** 便捷布尔：等价于 {@code getState()==CONNECTED}。 */
    boolean getConnected();

    long getReconnects();

    /** 最近断开原因码；从未断开时为空串，永不为 null。 */
    String getLastDisconnectReason();

    /** 最近断开的人可读说明；无则空串。 */
    String getLastDisconnectDetail();

    long getLastDisconnectAtMs();

    long getLastConnectedAtMs();

    long getCurrentBackoffMs();

    int getCommandQueueDepth();

    long getCommandRejected();

    int getReliableEventQueueDepth();

    long getReliableEventRejected();

    long getBestEffortDropped();

    long getWriteTimeouts();
}
```

> api 视图**不含** `topics`/`pools`/`filters`/`routes` 等诊断细节（YAGNI）。
> 需要细粒度诊断用 `core.bridge_status` 回执。

### 3.6 `config.yml` 新增

```yaml
bridge:
  line:
    # 读超时兼心跳节拍（须 ≤ write-deadline-ms）
    read-timeout-ms: 5000
    # 池容量（有界，防无界积压）
    cmd-pool-size: 4
    cmd-queue-capacity: 64
    event-pool-size: 2
    event-queue-capacity: 512
    # 重载**协调**池；监听器子任务走 bridge.reload.worker-pool-size（见 §6.3）
    reload-pool-size: 4
    reload-queue-capacity: 32
    # 可丢事件容量（与可靠队列分离）
    best-effort-capacity: 2048
    # 可靠事件容量
    reliable-capacity: 1024
    # 回执队列容量与有界阻塞预算
    receipt-capacity: 256
    receipt-offer-budget-ms: 500
    # 写侧 watchdog
    write-deadline-ms: 10000
    # 入站硬上限
    max-inbound-bytes: 1048576
    # 落盘兜底
    spill-enabled: true
    spill-dir: data/bridge-spill
    spill-max-files: 8
    spill-max-bytes: 8388608
  dispatch:
    timeout-ms: 5000
    pool-size: 8
```

> **配置键唯一性**：reload 的池容量只有 `bridge.line.reload-pool-size` 一处；
> `config-reload` spec 不再重复定义 `bridge.reload.pool-size`（避免两处配置互相覆盖）。
> 重载子任务 worker 池见 `bridge.reload.worker-pool-size`。

## 4. 任务拆分

- [ ] `core`：`OutboundFrame`（含 `Priority.droppable`）、`InboundRoute`（`claims`/`handle`/`executor`）、`OutboundFilter`
- [ ] `core`：`BridgePools`（三池，有界 + AbortPolicy + daemon 工厂）
- [ ] `core`：`BridgeKernel.onFrame`（解析 → 匹配 → 投池；拒绝就地消化；绝不抛）
- [ ] `core`：`FrameSender`（`attach/detach`、滤镜→分片→入队顺序、`SubmitOutcome`、`submitReceipt` 有界阻塞 + `offerReceipt` 非阻塞）
- [ ] `core`：`EventSpillStore`（位点提交、滴灌回放、双上限、半截尾行截断）
- [ ] `core`：`BusinessEventSinkImpl`（实现 `api.bridge.BusinessEventSink`）
- [ ] `core`：`MinimalWebSocketClient` 写侧 watchdog（每次读循环迭代检查）+ `close` 只关 socket + 入站上限 + `FrameTooLargeException`
- [ ] `core`：`BridgeStatusRoute`（`order=130`）+ `BridgeStatusImpl`
- [ ] `core`：`BridgeClient` 改为连接/重连/生命周期 + 读循环委托内核 + 发送委托 `FrameSender`
- [ ] `core`：停机协议（见 §6.1）
- [ ] `core`：`BridgeConfigSource` + 全部 Settings 数据类 + `ProbeConfigBridgeConfigSource`（§6.2）
- [ ] `e2e/harness`：`ScenarioResultWriter` 增加断言聚合契约（`assertTrue(name, cond)` + `status` 由全部断言汇总 + `assertions.failed`）——**批次 0 必须交付**，否则 7 份 spec 的"真机 PASS"全部退化为跑完即 PASS
- [ ] 单测：读线程不阻塞 / 三池隔离 / 路由可指定池 / 拒绝不抛不断连 / 回执优先 / 可丢丢弃 / 可靠拒绝 / 滤镜先于容量 / spill 位点往返 / 写超时解阻塞 / 入站超限丢帧 / 停机无残留线程 / 读线程零阻塞（饱和回执走 `offerReceipt`）
- [ ] E2E：慢 Provider + fixture 停止读取 + 大帧，断言桥自动恢复且状态计数正确
- [ ] 文档：PRD、ARCHITECTURE（线程模型图）、CHANGELOG

**文件归属（并行开发隔离）**

| 归属 | 文件 |
|---|---|
| 本功能独占 | `core/.../bridge/{BridgeKernel,FrameSender,BridgePools,OutboundFrame,InboundRoute,EventSpillStore,BridgeStatusRoute,BridgeStatusImpl}.kt`、`core/.../bridge/stats/*` |
| 与其它功能共改 | `core/.../bridge/BridgeClient.kt`（本功能重构一次，之后其它功能只加注册行）、`MinimalWebSocketClient.kt`、`ProbeConfig.kt`（只追加段） |

## 5. 验收标准

| 项 | 判据（全部可自动化，零墙钟阈值） |
|---|---|
| 读线程不阻塞 | Provider 进入后置 latch A；测试等 A 再喂第 2 帧，断言第 2 帧在 latch B 完成**且此刻 A 仍未被释放**（第 2 帧严格先于第 1 帧完成） |
| 路由可指定池 | 单测：`config_reload` 路由的 `handle` 执行线程名前缀为 `serverprobe-reload`；`business_status` 为 `serverprobe-cmd` |
| 拒绝不抛 | 单测：把 cmd 池队列填满后喂命令帧，`onFrame` 正常返回；产出 `fail("命令通道饱和")` 回执；连接状态仍 `CONNECTED` |
| 拒绝不断连 | 单测：连续 100 条超载命令，`BridgeClient` 无重连、无异常逃逸 |
| 回执优先 | 单测：**先**提交 1000 条事件并等发送线程写出第 1 条（latch），**再**提交回执 → 断言回执出现在"已写出事件的下一位置"，而非队尾。对立用例：先提交回执、事件已在队列 → 回执在事件之前写出 |
| 可丢丢弃 | 单测：可丢队列满后 `DROPPED`、`bestEffortDropped` +1、不抛 |
| 可靠拒绝 | 单测：可靠队列满且 spill 也接不下 → `REJECTED`、`reliableRejected` +1，**无异常逃逸到 Provider**；spill 接管时必须是 `QUEUED`（不得记成 `REJECTED`） |
| 读线程零阻塞 | 单测：用计数型 `FrameSender` 桩记录调用线程名与阻塞次数；填满 cmd 池后喂 100 条命令 → 断言读线程从不调 `submitReceipt`（只调 `offerReceipt`/`submit`），累计阻塞时长 0 |
| 滤镜先于容量 | 单测：订阅只匹配 1 个 topic，灌入 1000 条不匹配事件 → `filteredOut=1000`、`reliableQueue=0`、`emitReliable` 全部返回 true |
| 不在事件线程写盘 | 单测：注入 `FileSystem` 接缝记录写盘线程名，断言不含 `serverprobe-event` 前缀；spill 交接队列设容量 1 并预先占满，断言 `submit` **仍立即返回**且计数 +1（零墙钟） |
| spill 位点 | 单测：写入 10 条 → 只提交前 4 条 → 重启回放只重发后 6 条；半截尾行被截断 |
| 写超时解阻塞 | 单测（注入 `readTimeoutMs=50`、`writeDeadlineMs=200` + 桩 socket）：①`writeTimeouts` 0→1；②桩 socket 记录 `close()` 调用时刻与 `writingSinceMs` 差 ≤ 3×`readTimeoutMs`；③`close()` 未获取 `writeLock`（桩 Lock 断言）。**必测**：fixture 持续发文本帧 + 停止读取 → 仍须 `writeTimeouts==1`（验证"读到帧后也检查"这条） |
| 入站超限 | 单测：注入声明长度 > 上限的帧 → 丢帧、`tooLarge` +1、**连接保持**；长度溢出为负 → 同样丢帧不崩 |
| 停机无残留 | 单测：`stop()` 后 `Thread.getAllStackTraces()` 无 `serverprobe-cmd/event/reload/reload-worker/sender/spill/health/health-probe` 前缀线程 |
| 状态有真源 | 单测：填满 cmd 队列后断言 `pools.cmd.queued == executor.queue.size`、`pools.cmd.active == executor.activeCount`、`pools.cmd.rejected` 与真实拒绝次数一致；各造一次事件后断言 `filteredOut`/`spilled`/`writeTimeouts` 均 > 0（排除恒 0 占位） |
| 状态完整 | 单测：`bridge_status` 含 §3.5 全部字段且为合法 JSON |
| 真机 | Paper 注入慢 Provider + 大帧，TPS 采集与心跳不断；E2E 由 `ScenarioResultWriter` 逐项断言汇总为 PASS（`assertions.failed` 为空） |

### 已落地的自动化证据（批次 0 实现）

| 测试 | 覆盖的不变量 |
|---|---|
| `BridgeKernelTest.onFrame 不等待路由执行` | 读线程不在路由执行上等待（latch 断言，零墙钟） |
| `BridgeKernelTest.池饱和时就地降级且不抛` | 池饱和不抛、不断连，计数正确 |
| `BridgeKernelTest.路由按 order 匹配` | 低序号先命中并短路 |
| `BridgeKernelTest.畸形帧被忽略且不抛` | 解析失败只计数 |
| `FrameSenderTest.可丢事件队列满即丢弃` | 可丢分级 |
| `FrameSenderTest.可靠事件队列满即拒绝` | 可靠分级（不静默丢） |
| `FrameSenderTest.滤镜先于容量判定` | 被滤镜丢弃的帧不占容量、不计失败 |
| `FrameSenderTest.命令回执优先于事件写出` | **先制造稳定积压**再断言顺序（避免假绿） |
| `MinimalWebSocketClientGuardTest.写阻塞超时被 watchdog 解除` | 对端只建连不读 → watchdog 强关解阻塞（真实 socket + 真实握手） |
| `MinimalWebSocketClientGuardTest.空闲时 watchdog 不误报` | 无在途写不误判 |
| `ScenarioResultWriterTest`（6 例） | 断言聚合、失败名单、并发登记不丢、原子写 |

## 6. 关键约定

### 6.1 停机顺序

```
① 停止接收新帧（running=false，读循环退出）
② reload 池 shutdownNow（在途重载没有理由继续——目标配置已随停用过期）
③ cmd 池 shutdown（不打断在途业务，给有界时间跑完）
④ FrameSender 有界抽干（最长 2s）
⑤ spill flush + fsync + 推进位点
⑥ socket.close()
⑦ 线程 join（各自有界），断言无残留
```

理由：reload 池任务会回调外部插件的 `onConfigReload`，而外部插件通常已在自身 `onDisable` 里
关掉配置对象/连接池 → 必须最先打断。cmd 池反之，业务写操作被打断会造成"半执行"，给时间跑完更安全。

### 6.2 `BridgeConfigSource` 接缝（7 份 spec 的共同前置）

`ProbeConfig.conf` 是 TabooLib 注入的 `Configuration`，`core` 单测 classpath **不含**
`basic-configuration`，且 `conf` 是 `lateinit`。若各功能直接调 `ProbeConfig.*`，
所有"阈值可配""超时可调"类验收都写不出来（会退化成读默认值的假绿）。

```kotlin
// core/config/BridgeConfigSource.kt —— 接口的**唯一权威定义**
interface BridgeConfigSource {
    fun enabled(): Boolean
    fun url(): String
    fun token(): String
    fun instance(): String
    fun line(): LineSettings
    fun dispatch(): DispatchSettings
    fun reload(): ReloadSettings
    fun chunk(): ChunkSettings
    fun subscribe(): SubscribeSettings
    fun health(): HealthSettings
    fun reconnect(): ReconnectSettings
}
```

**各 Settings 的字段（缺一不可，否则对应 spec 的"注入小值"验收无法编码）**：

```kotlin
data class LineSettings(
    val readTimeoutMs: Long,        // soTimeout 兼心跳节拍，默认 5000
    val writeDeadlineMs: Long,      // 写侧 watchdog，默认 10000
    val maxInboundBytes: Int,       // 入站硬上限，默认 1 MiB
    val cmdPoolSize: Int,           // 4
    val cmdQueueCapacity: Int,      // 64
    val eventPoolSize: Int,         // 2
    val eventQueueCapacity: Int,    // 512
    val reloadPoolSize: Int,        // 4
    val reloadQueueCapacity: Int,   // 32
    val bestEffortCapacity: Int,    // 2048
    val receiptCapacity: Int,       // 256
    val receiptOfferBudgetMs: Long, // 500
    val spillEnabled: Boolean,
    val spillDir: String,
    val spillMaxFiles: Int,
    val spillMaxBytes: Long,
)

data class DispatchSettings(
    val timeoutMs: Long,            // 5000
    val poolSize: Int,              // 业务线程池，默认 8
)

data class ReloadSettings(
    val timeoutMs: Long,            // 5000
    val maxPathsPerRequest: Int,    // 32
    val workerPoolSize: Int,        // reload 子任务 worker 池，默认 8（见 §6.3）
    val stuckAfterMs: Long,         // 30000
    val breakerEnabled: Boolean,
    val breakerFailureWindowMs: Long,
    val breakerOpenDurationMs: Long,
    val breakerMaxOpenDurationMs: Long,
    val breakerFailureThreshold: Int,
    val breakerMaxEntries: Int,
)

data class ChunkSettings(
    val enabled: Boolean,
    val maxFrameBytes: Int,         // 262144
    val maxChunksPerGroup: Int,     // 64
)

data class SubscribeSettings(
    val maxTopics: Int,             // 256
    val maxExpressionChars: Int,    // 256
)

data class HealthSettings(
    val refreshIntervalMs: Long,    // 缓存刷新周期
    val healthTimeoutMs: Long,      // 单次 health() 有界等待
    val retainAfterUnregisterMs: Long, // 注销后保留，默认 300000
    val maxEntries: Int,            // 256
)

data class ReconnectSettings(
    val pingIntervalMs: Long,       // 周期 ping，默认 20000
    val pongTimeoutMs: Long,        // 默认 3 × pingIntervalMs
    val initialBackoffMs: Long,     // 1000
    val maxBackoffMs: Long,         // 30000
    val authFailedMaxBackoffMs: Long,      // 10000
    val authFailedDegradeThreshold: Int,   // 10
    val authFailedDegradedBackoffMs: Long, // 60000
)
```

- `ProbeConfigBridgeConfigSource` 读 `ProbeConfig`（生产），并在 `refreshFromDisk()` 内部调 `conf.reload()`。
- 单测注入 `FakeBridgeConfigSource`（各 Settings 可改值），从而能断言"调小阈值后行为变化"。
- **该接口不是对外契约**（留在 core）；`api` 模块不得引用它。
- 新增 Settings 或其字段必须登记在本节——各功能 spec 不得自行往接口加方法或加字段。

### 6.3 重载池与 worker 池必须分离（避免池内自锁）

`config_reload` 路由本身跑在 **reload 协调池**（`reloadPoolSize`），而它提交的**监听器子任务**
必须投到**另一个** `reload-worker` 池（`ReloadSettings.workerPoolSize`）。

若两者共用同一个池：4 个并发 `config_reload` 各占 1 个协调槽、共提交 32 个子任务到同一有界池，
队列一满即无可用线程 → 4 个协调器全部阻塞在 `future.get(5s)` → 子任务永不执行 → 全部误判 `TIMEOUT`
并触发熔断。"轮转收割"解决不了这个问题（它是收口算法，不是池隔离）。

线程命名：协调池 `serverprobe-reload-N`，worker 池 `serverprobe-reload-worker-N`。
停机时两池都 `shutdownNow`。

## 7. 风险 / 待定

1. **`BridgeClient` 重构面大**：风险最高的一次改动，必须单测先行，且**独立提交**，不与任何新功能混提。
2. **写超时靠 watchdog 强关 socket**：是"解阻塞"而非"优雅取消"，会截断半帧。对端必须容忍（按协议丢弃不完整帧并重连）。已写进验收。
3. **控制帧旁路会插入分片组中间**：对端按 `groupId`/`seq` 重组，不依赖连续性，可接受。
4. **`bridge.line.*` 默认值**需真机实测调整，首版取保守值。
5. **待定**：`reload` 池容量 4 是否足够——取决于单次请求的 path 数上限（config-reload 定 32）。若并发请求多，收口依赖"轮转收割"而非池容量，见 config-reload §3.8。
