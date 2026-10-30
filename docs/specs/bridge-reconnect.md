# 功能规格：桥连接自愈与状态暴露

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-36　·　分支：feature/bridge-reconnect


## 1. 背景与目标

桥已有的重连是「指数退避 + 无限重试」，但有两个功能缺口：

1. **token 轮换后无法自愈**：`start()` 只在启用时读一次 `bridge.url` / `bridge.token`，轮换 token 后必须重启服务器才能生效。
2. **断开原因不可见**：运维只看到「插件桥连接异常，将重连」，无法回答「多久重连一次、上次为什么断、现在退避到几秒」。

第一遍审核还查出三个**实现层面的阻塞事实**，本版据此重写设计（不再沿用原稿的判定方式）：

| 已核实的事实（文件:行） | 原稿的问题 |
|---|---|
| `MinimalWebSocketClient.readMessage()` 对 `OPCODE_PONG` 是 `continue`（[MinimalWebSocketClient.kt:161](project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/bridge/MinimalWebSocketClient.kt#L161)），pong **永不出现在返回值里** | 原稿"连续 N 次读超时后仍无 pong"无法实现：上层拿不到 pong，也无法区分"没 pong"与"没流量" |
| `ProbeConfig.conf` 是 `Configuration` 内存视图；全仓无 `conf.reload()` 调用点；core 单测 classpath 不含 `basic-configuration`，`conf` 是 `lateinit` | 原稿"每轮调 `ProbeConfig.reloadFromDisk()`"，但单测无法构造真实 `ProbeConfig`，验收写不出来 |
| 握手非 101 时抛 `IOException("WebSocket 握手失败，服务端未返回 101：$statusLine")`（[MinimalWebSocketClient.kt:93](project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/bridge/MinimalWebSocketClient.kt#L93)） | 原稿靠解析异常 message 取状态码，脆弱 |
| `error("URL 缺少 host: $uri")`（[MinimalWebSocketClient.kt:63](project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/bridge/MinimalWebSocketClient.kt#L63)）与 `URI("$url?token=$token&instance=$instance")`（[BridgeClient.kt:124](project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/bridge/BridgeClient.kt#L124)） | **两条现存 token 泄漏路径**：`$uri` 含 `?token=...`，会被上层 `warn("插件桥连接异常,将重连:${e.message}")` 写进日志（[BridgeClient.kt:137](project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/bridge/BridgeClient.kt#L137)） |

本功能补上功能缺口，并先修掉这些实现阻塞点。

## 2. 需求

### 范围内

- token 轮换后可自愈重连：重连前重新读取配置，token 变化时用新值（**仅** `url`/`token`/`instance`；`enabled` 是启动期语义，见 §3.3）。
- `core.bridge_status` 暴露**本 spec 负责的字段**：连接状态、重连次数、最近断开原因（脱敏）、当前退避时长、上次连上时间等 8 项（§3.6）。
- 断开原因分类（认证失败 / 网络不可达 / 对端关闭 / 心跳超时 / 配置为空 / 主动停止 / 未知），**每类都有可自动化的判定依据**。
- 心跳判活改为基于「最后 pong 时间戳」，`MinimalWebSocketClient` 暴露 `lastPongAtMs`。
- `hello` 作为 `version` / `capabilities` / 当前订阅表达式的唯一真源。
- 修掉两条 token 泄漏路径，并给日志与异常 message 设立统一脱敏出口。

### 范围外

- 不做主动探测 JM 是否存活（只靠 WS 连接状态）。
- 不做多 Worker 负载均衡/故障转移。
- 不做 token 的本地持久化或加密存储。
- **不定义 `bridge_status` 的 schema**（唯一归属 [bridge-line-hardening.md §3.5](bridge-line-hardening.md)），也不改 `api.bridge.BridgeStatus` 的签名。
- 不改 [bridge-line-hardening.md §6.2](bridge-line-hardening.md) 的 `BridgeConfigSource` 接口签名。

## 3. 设计

### 3.1 连接状态机

```
STOPPED ──start()──→ CONNECTING ──成功──→ CONNECTED
                         │                   │
                         │失败               │断开
                         ↓                   ↓
                    BACKOFF ←───────────────┘
                         │
                    （退避到期，重读 url/token/instance）→ CONNECTING
```

状态由 `BridgeConnectionStats` 的**不可变快照**承载（§3.6），不是若干独立 `@Volatile` 字段。

### 3.2 心跳判活：周期性 ping + `lastPingSentAtMs` 门控

**两个必须同时解决的问题**：

1. `readMessage()` 里 `OPCODE_PONG -> continue`，pong 被就地消化；上层无法区分"没 pong"与"没流量"。
2. **现状 ping 只在读超时分支发送**（已核实 [BridgeClient.kt:215-217](project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/bridge/BridgeClient.kt#L215-L217)，全文无周期性 ping）。
   于是连接上有持续下行文本帧时读永不超时 → **从不发 ping** → 对端没有机会回 pong →
   `lastPongAtMs` 停在握手时刻 → 90s 后必然误判 `HEARTBEAT_TIMEOUT`，导致"忙但健康"的连接每 ~90s 断一次。

**改法一：ping 改为周期性主动发送**（不依赖读超时）：

```kotlin
// core/bridge/HeartbeatScheduler.kt（新增，单线程 daemon "serverprobe-heartbeat"）
/**
 * 按 pingIntervalMs 周期性发送 ping，与读循环解耦。
 *
 * 读循环仍然保留"读超时后补发一次 ping"的既有行为（作为发送线程卡住时的兜底），
 * 但心跳不再依赖读超时触发。
 */
class HeartbeatScheduler(private val intervalMs: Long, private val ping: () -> Unit)
```

**改法二：判定加门控**——只有"已发出 ping 且之后没收到 pong"才算超时：

```kotlin
// core/bridge/MinimalWebSocketClient.kt
/** 最近一次收到服务端 pong 的时刻；握手成功时初始化为当前时刻（防"刚连上误判"）。 */
@Volatile var lastPongAtMs: Long = 0L

/** 最近一次主动发出 ping 的时刻；从未发过为 0。 */
@Volatile var lastPingSentAtMs: Long = 0L

// OPCODE_PONG -> { lastPongAtMs = now(); continue }
// sendPing()   -> { lastPingSentAtMs = now(); /* 原有写帧逻辑 */ }
```

判定条件（三个条件同时满足才算心跳超时）：

```kotlin
fun isHeartbeatTimedOut(now: Long, pongTimeoutMs: Long): Boolean {
    if (pongTimeoutMs <= 0L) return false                    // 关闭判定
    val pingAt = lastPingSentAtMs
    if (pingAt <= 0L) return false                           // 从未发过 ping：没有依据
    if (lastPongAtMs >= pingAt) return false                 // 最近一次 ping 已得到 pong
    return now - pingAt > pongTimeoutMs                      // 发出后超时仍无 pong
}
```

判定检查点：**每次读循环迭代都查**（读到帧后 + 读超时后），与写侧 watchdog 同一原则。

> **同毫秒的边界**：`sendPing` 与握手初始化可能落在同一毫秒，此时 `lastPongAtMs >= pingAt` 判定为
> "已覆盖"（保守方向，不会误报超时）。代价是需要额外一个 ping 周期才能检出真正的静默，
> 这是刻意的取舍——**宁可晚一轮，不可误杀健康连接**。

- `pongTimeoutMs` 默认 `3 × pingIntervalMs`。启动时若配成 `<= 2 × pingIntervalMs` → WARN 并夹到 `3 ×`。
- `pongTimeoutMs = 0` 表示**关闭**该判定，退回纯读超时节拍（兼容"JM 不回 pong"的部署）。
- 超时后的处理与其它断开一致：`markDisconnected(HEARTBEAT_TIMEOUT, ...)` → 关 socket → 退避重连。
- `HeartbeatTimeout` 必须继承 `IOException`，且**不得**继承 `EOFException`
  （否则会被 `runLoop` 的 `catch (e: EOFException)` 先吃掉，归因成 `PEER_CLOSED`）：

```kotlin
// core/bridge/HeartbeatTimeout.kt
class HeartbeatTimeout(message: String) : java.io.IOException(message)
```

### 3.3 配置读取接缝

**已核实的前置事实**：`ProbeConfig.conf` 是 TabooLib `Configuration` 的**内存视图**，`getString(...)` 读内存快照；运行期改磁盘 `config.yml` **不生效**，必须显式 `conf.reload()`；全仓当前**没有任何** `conf.reload()` 调用点。同时 core 单测 classpath **不含** `basic-configuration`，且 `conf` 是 `lateinit` → 单测无法用真实 `ProbeConfig`。

**因此 `BridgeClient` 不直接读 `ProbeConfig`，只依赖接缝接口**（接口定义见 [bridge-line-hardening.md §6.2](bridge-line-hardening.md)，含 `enabled()/url()/token()/instance()/line()/dispatch()/reload()/chunk()/subscribe()/health()/reconnect()` 及各自的 Settings 数据类）：

```kotlin
// core/config/ProbeConfigBridgeConfigSource.kt（新增，生产实现）
/**
 * 生产实现：读 `ProbeConfig`，并在重连前把磁盘上的 config.yml 重读进内存快照。
 *
 * 注意命名：[BridgeConfigSource.reload] 返回的是「配置重载功能的设置对象」（ReloadSettings），
 * 与「从磁盘重读配置」无关。二者绝不能混名，故本实现从磁盘重读的方法叫 [refreshFromDisk]，
 * 且它是**内部细节、不是接口方法**。
 */
class ProbeConfigBridgeConfigSource : BridgeConfigSource {

    override fun enabled(): Boolean = ProbeConfig.bridgeEnabled()
    override fun url(): String = ProbeConfig.bridgeUrl()
    override fun token(): String = ProbeConfig.bridgeToken()
    override fun instance(): String = ProbeConfig.bridgeInstance()
    // line()/dispatch()/reload()/chunk() 委托 ProbeConfig 对应段

    /**
     * 从磁盘重读 config.yml。**仅 [BridgeClient] 在每轮重连前调用**，不在读循环内调用。
     * 整体不抛：文件缺失/损坏/解析失败时保留内存旧值并返回 false，桥继续用旧配置重连。
     */
    fun refreshFromDisk(): Boolean =
        runCatching { conf.reload(); true }
            .onFailure { ProbeLogger.warn("config.yml 重读失败，沿用旧配置：${it.javaClass.simpleName}") }
            .getOrDefault(false)
}
```

**热读语义（必须写清，避免误解"全都能热改"）**：

| 配置项 | 生效时机 | 说明 |
|---|---|---|
| `bridge.enabled` | **仅 `start()`** | `start()` 里的 `bridgeEnabled()` 只读一次；运行期改 false **不会**停连（停连靠 `stop()`/插件停用）。这是有意为之：让"改配置即断连"这种意外中断不可能发生。 |
| `bridge.url` / `bridge.token` / `bridge.instance` | **每轮重连前重读** | 本 spec 的核心增量，token 轮换自愈即靠这条。 |
| `bridge.heartbeat.*` / `bridge.reconnect.*` | 构造期 | 经 `ReconnectSettings` 注入（§3.4），改后需重连周期外重启探针。 |

关键约束：

- `refreshFromDisk()` 自身不抛，失败保留旧值；**它是生产实现的内部细节，不是 `BridgeConfigSource` 的方法**——单测注入 fake 时无需（也无法）模拟磁盘重读。
- token **绝不进日志**：轮换探测只记「已轮换」这一事实，不记任何新旧值（§3.4）。
- 若配置在连接成功后变化，**不主动断开**（避免配置抖动导致业务中断）；下一次重连时自然生效。
- `conf.reload()` 会刷新整个 `config.yml` 的内存快照，对其它配置读取方是「更早看到新值」，不构成破坏；`Configuration` 自带 `reloadGeneration` 版本机制，读取方无需加锁。
- 重读频率受指数退避限制（最快 `initialBackoffMs`，默认 1s），不会打爆磁盘。

### 3.4 凭据不外泄（两条现存路径必须修）

| 路径 | 现状 | 后果 |
|---|---|---|
| P1 | `error("URL 缺少 host: $uri")` | `$uri` 含 `?token=<真实值>`；异常冒到 `warn("插件桥连接异常,将重连:${e.message}")` → **token 明文进日志** |
| P2 | `URI("$url?token=$token&instance=$instance")` 未做 query 编码 | token 含 `&` / `#` / 空格时抛 `URISyntaxException`，其 `message` **也含 token 明文**；且含 `&` 时会被解析成额外参数（连接语义错乱） |

修法：

```kotlin
// ① MinimalWebSocketClient.connect()：错误信息不得拼 uri
val host = uri.host ?: error("URL 缺少 host")     // 去掉 $uri（其余 require/error 同此规则，一律不带 uri）

// ② BridgeClient：base 由 url 解析（url 本身不含凭据），query 单独拼并显式编码
private fun buildTarget(url: String, token: String, instance: String): URI {
    val base = try {
        URI(url)
    } catch (e: URISyntaxException) {
        throw IOException("桥 URL 非法（凭据已省略）")   // 不挂 cause、不复述原串
    }
    val host = base.host ?: throw IOException("桥 URL 缺少 host（凭据已省略）")
    val port = if (base.port > 0) base.port else DEFAULT_WS_PORT
    val path = if (base.rawPath.isNullOrEmpty()) "/" else base.rawPath
    val query = "token=" + encodeQuery(token) + "&instance=" + encodeQuery(instance)
    return try {
        // 7 参构造：对非法字符抛 URISyntaxException（而不是在连接期才炸）
        URI("ws", null, host, port, path, query, null)
    } catch (e: URISyntaxException) {
        throw IOException("桥 URL 非法（凭据已省略）")
    }
}

/** Java 8 兼容的 query 编码：URLEncoder 把空格编成 '+'，需再换成 %20 以免被对端当字面加号。 */
private fun encodeQuery(v: String): String =
    java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
```

> `error("URL 缺少 host")` 用的是 `IllegalStateException`，它会被 `runLoop` 的兜底 `catch` 收走 → 按 §3.5 归 `UNKNOWN`（detail 只含类名）。归 `UNKNOWN` 而非 `UNREACHABLE` 是对的：这是**配置错误**，不是网络问题，`UNREACHABLE` 会误导运维去查网络。若希望更精确，可在 `start()` 前置校验 url 形态并直接 `CONFIG_EMPTY` 口径处理（已列入任务拆分）。


- 另有**全局规则**：`BridgeClient` 的连接类日志一律经统一出口构造文本，不直接插值 `e.message`：

```kotlin
// core/bridge/BridgeLog.kt（新增）
object BridgeLog {
    /** 连接失败的可读文本：只有异常类名 + 脱敏后的 message；secrets 中的值一律替换为 *** 。 */
    fun connectFailure(e: Throwable, secrets: List<String>): String
    /** 通用脱敏：把 secrets 里的每个非空串替换为 ***，并把 token=xxx 形态一并脱敏。 */
    fun redact(text: String, secrets: List<String>): String
}
```

- `ProbeLogger` 增加**测试专用 sink** 以支持"日志不含 token"的自动断言（纯增量，默认 null、行为不变）：

```kotlin
// core/util/ProbeLogger.kt
/** 测试专用日志捕获口；生产保持 null（不改变任何行为）。 */
@Volatile
var sink: ((level: String, msg: String) -> Unit)? = null
```

### 3.5 断开原因分类

```kotlin
// core/bridge/DisconnectReason.kt（新增）
enum class DisconnectReason(val code: String, val detail: String) {
    AUTH_FAILED("AUTH_FAILED", "认证失败（token 无效或已轮换）"),
    UNREACHABLE("UNREACHABLE", "网络不可达（连接被拒绝/超时，或服务端拒绝握手）"),
    PEER_CLOSED("PEER_CLOSED", "对端关闭连接"),
    HEARTBEAT_TIMEOUT("HEARTBEAT_TIMEOUT", "心跳超时（超过 pongTimeoutMs 未收到 pong）"),
    CONFIG_EMPTY("CONFIG_EMPTY", "bridge.url 或 bridge.token 未配置"),
    STOPPED("STOPPED", "探针主动停止"),
    /** 仅表示「确实断了但归不了类」；**不是**「从未断开」的取值。 */
    UNKNOWN("UNKNOWN", "未知原因");
}
```

**握手失败改为专用异常**（替代从 message 解析状态码）：

```kotlin
// core/bridge/BridgeHandshakeException.kt（新增，core 内部，不进 api）
/**
 * WebSocket 握手未拿到 101。
 *
 * @property statusCode 服务端返回的 HTTP 状态码；无法解析时为 -1。
 * @property statusLine 原始状态行，**已截断到 128 字符且经脱敏**（状态行本身不含凭据，截断只为防畸形输入）。
 */
class BridgeHandshakeException(
    val statusCode: Int,
    val statusLine: String,
) : IOException("WebSocket 握手失败：HTTP $statusCode")
```

`MinimalWebSocketClient.connect()` 非 101 分支改为：解析 `statusLine` 取状态码 → `throw BridgeHandshakeException(code, sanitizedLine)`。状态码解析失败时 `statusCode = -1`。

判定规则（**每行都可单测**）：

| 现象 | 归类 |
|---|---|
| `BridgeHandshakeException` 且 `statusCode in setOf(401, 403)` | `AUTH_FAILED` |
| `BridgeHandshakeException` 且其余 4xx / 5xx / `-1` | `UNREACHABLE` |
| `ConnectException` / `UnknownHostException` / 连接阶段的 `SocketTimeoutException` | `UNREACHABLE` |
| `EOFException`（对端正常关闭 close 帧 / 流结束） | `PEER_CLOSED` |
| `now - ws.lastPongAtMs > pongTimeoutMs`（§3.2 判定触发） | `HEARTBEAT_TIMEOUT` |
| `url` 或 `token` 为空 | `CONFIG_EMPTY`，见下方注 |
| `stop()` 主动断开 | `STOPPED` |
| 其它 `Throwable` | `UNKNOWN`（detail 只记异常类名，不含 message 敏感串） |

> **`CONFIG_EMPTY` 的真实触发点在 `start()`，不在循环内**（已核实 [BridgeClient.kt:78-83](project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/bridge/BridgeClient.kt#L78-L83)）：现实现 `start()` 在 `url`/`token` 为空时**直接 return，压根不建线程**，所以"循环里判空 → 退避"是死分支。改造后语义定为：
> - `start()` 时空 → 保持现有行为（WARN 一次 + 不建线程，`state` 停在 `STOPPED`，`lastDisconnectReason` 保持空串——**不是** `CONFIG_EMPTY`，因为从未尝试连接）；
> - 已在循环内（曾经连过）时 `refreshFromDisk()` 后读到空 → 归 `CONFIG_EMPTY` 并退避，但不无限重试：连续 `CONFIG_EMPTY` ≥ 10 次后与 `AUTH_FAILED` 同样降频到 60s（配置被人删空时不该每 30s 刷一次 WARN）。


`lastDisconnectReason` / `lastDisconnectDetail` 的**初值都是空串**（不是 `UNKNOWN`）——`UNKNOWN` 只用于"确实断了但归不了类"。这与 [bridge-line-hardening.md §3.5](bridge-line-hardening.md) 的 schema 定义一致。

### 3.6 状态快照（修撕裂读）

原设计 6 个独立 `@Volatile` 字段，`toJson()` 逐个读会读到不一致组合（例如 `state=CONNECTED` 但 `lastDisconnectReason` 来自上一次断开）。改为**整体替换的不可变快照**，`toJson()`/`bridge_status` 只读一次引用：

```kotlin
// core/bridge/BridgeConnectionStats.kt（新增）
/** 连接状态不可变快照：一次写入、整体替换，读方永远看到自洽的一组值。 */
class BridgeConnectionSnapshot(
    val state: BridgeStatus.State,
    val reconnects: Long,
    /** 空串 = 从未断开（不是 UNKNOWN）。 */
    val lastDisconnectReason: String,
    val lastDisconnectDetail: String,
    val lastDisconnectAtMs: Long,
    val lastConnectedAtMs: Long,
    val currentBackoffMs: Long,
    val consecutiveFailures: Int,
) {
    companion object { fun initial(): BridgeConnectionSnapshot = ... }
}

class BridgeConnectionStats(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile
    private var snapshot: BridgeConnectionSnapshot = BridgeConnectionSnapshot.initial()

    /** 唯一读口：返回自洽的整体。 */
    fun get(): BridgeConnectionSnapshot = snapshot

    fun markConnecting()
    fun markConnected()
    /** 断开三要素（reason/detail/atMs）在同一快照内同时设置，不可能只更新一半。 */
    fun markDisconnected(reason: DisconnectReason, detail: String, backoffMs: Long)
    fun markBackoff(backoffMs: Long)
    fun markStopped()

    private fun replace(next: BridgeConnectionSnapshot) { /* 持锁构造 → 赋值 */ }
}
```

**快照不变量**（单测逐条断言，见 §5）：

1. `lastDisconnectAtMs == 0L` ⟺ `lastDisconnectReason.isEmpty()`
2. `state == CONNECTED` ⇒ `currentBackoffMs == 0L && consecutiveFailures == 0`
3. `reconnects` 单调不减

**本 spec 在 `bridge_status` 中负责的字段**（schema 见 [bridge-line-hardening.md §3.5](bridge-line-hardening.md)，本 spec 只填值、不改结构）：

| 字段 | 本 spec 写入 |
|---|---|
| `state` | ✔ 状态机写（`STOPPED/CONNECTING/CONNECTED/BACKOFF`，枚举复用 `api.bridge.BridgeStatus.State`，不另立第二份） |
| `reconnects` | ✔ 累计成功连入次数 |
| `lastDisconnectReason` | ✔ 空串初值 |
| `lastDisconnectDetail` | ✔ 人可读、已脱敏、限长 |
| `lastDisconnectAtMs` | ✔ |
| `lastConnectedAtMs` | ✔ |
| `currentBackoffMs` | ✔ |
| `consecutiveFailures` | ✔ |
| `connected` | 派生值（`state==CONNECTED`），由 line-hardening 定义，本 spec 不单独维护 |
| `pools` / `sender` / `inbound` / `wired` / `topics` / `version` / `capabilities` / `timestamp` | ✘ 见 line-hardening §3.5（`version`/`capabilities` 的值由 §3.7 提供，是同一份内存值的镜像） |

### 3.7 `hello`：`version` / `capabilities` / 订阅的唯一真源

```kotlin
// core/bridge/BridgeCapabilities.kt（新增）
/**
 * 桥能力的唯一真源。
 *
 * 各功能**在装配时登记自己要加的 capability 值**，hello 与 bridge_status 都读这里——
 * 不允许各自硬编码一份字符串数组（否则必然 drift）。
 */
object BridgeCapabilities {

    /** 协议版本。本轮从 "1" 升到 "2"：只做加法（新字段只增不改、旧字段语义不变）。 */
    const val PROTOCOL_VERSION: String = "2"

    /** 登记一个能力值；幂等、线程安全。 */
    fun addCapability(name: String)

    /** 稳定顺序（按登记顺序），便于断言。 */
    fun snapshot(): List<String>

    /**
     * 当前订阅表达式来源；由 event-topic-subscription 在装配时接上。
     * 未接线时返回空列表——本 spec 不依赖 topic 实现。
     */
    fun setSubscriptionSource(source: () -> List<String>)
    fun subscriptions(): List<String>
}
```

`helloJson` 改为读 `BridgeCapabilities`：

```json
{
  "type": "hello",
  "instance": "s1",
  "platform": "bukkit",
  "version": "2",
  "capabilities": ["chunk", "topic", "config_reload", "business_status"],
  "subscriptions": ["economy.change", "inventory.sync"]
}
```

各 capability 由谁登记（**本 spec 只提供登记点与序列化**）：

| 值 | 登记方 |
|---|---|
| `chunk` | frame-chunking |
| `topic` | event-topic-subscription |
| `config_reload` | config-reload |
| `business_status` | provider-health |

- `version` 从 `"1"` 升到 `"2"`；**N 侧只做加法**：JM 必须忽略未知字段，探针不删除/不改语义既有字段。
- `capabilities` 与 `subscriptions` 为新增可选字段，旧 JM 忽略不影响。
- `subscriptions` 附在 `hello` 的目的：让 JM 能发现「探针仍在用旧订阅表达式」（例如探针侧配置没更新），无需额外查询。
- **待联调项（无法在本仓闭合）**：现有 fixture 只校验 `path` / `token` / `instance`，**完全不检查 `version`**，所以单测与 E2E 都**不会**暴露"JM 因 `version` 从 1→2 拒绝连接"的风险。需与 JM 侧确认其 `version` 处理策略（忽略 / 严格相等 / 最小版本），确认后补一条 fixture 的 version 断言与对应 E2E 用例。

### 3.8 退避策略

保留现有：`1s → 2s → 4s → 8s → 16s → 30s` 上限（`initialBackoffMs` / `maxBackoffMs` 可配）。

新增：**认证失败不退避到最长**。若识别为 `AUTH_FAILED`：

- 退避封顶 `authFailedMaxBackoffMs`（默认 10s），使 token 轮换后能较快恢复；
- 连续 `AUTH_FAILED` ≥ `authFailedDegradeThreshold`（默认 10）次 → 降频到 `authFailedDegradedBackoffMs`（默认 60s），并 WARN 一次（不彻底放弃）。

**成功连上即重置**：`backoff = initialBackoffMs`、`consecutiveFailures = 0`；`reconnects` 保留累计值（总量指标，不重置）。

### 3.9 日志限频

同一 `DisconnectReason` 连续出现时：首条 WARN，其后降 DEBUG 并累加 `suppressedCount`；原因变化或连接成功后重置计数。下一次恢复 WARN 时带上「上次以来已抑制 N 条」。避免 Worker 长期不可达把日志刷爆。

限频只作用于**连接类**日志；`hello`/握手成功、token 轮换检测仍各打一次 INFO。

## 4. 任务拆分

- [ ] `core`：`DisconnectReason`（新增；`UNKNOWN` 仅表示"断了但归不了类"）
- [ ] `core`：`BridgeConnectionSnapshot` + `BridgeConnectionStats`（整体替换 + 三条不变量）
- [ ] `core`：`HeartbeatTimeout : IOException`（**不得**继承 `EOFException`，否则会被 `catch (EOFException)` 抢走归因）
- [ ] `core`：`HeartbeatScheduler`（单线程 daemon `serverprobe-heartbeat`，按 `pingIntervalMs` 周期发 ping，不依赖读超时）
- [ ] `core`：`MinimalWebSocketClient` 暴露 `@Volatile var lastPongAtMs`（握手成功时初始化）+ `@Volatile var lastPingSentAtMs`（`sendPing()` 更新）+ `OPCODE_PONG` 分支更新 pong 时刻
- [ ] `core`：心跳判定 `isHeartbeatTimedOut(now)`（三条件门控：发过 ping、pong 未覆盖该 ping、超时仍无 pong）；每次读循环迭代检查（读到帧后 + 读超时后）
- [ ] `core`：`BridgeHandshakeException(statusCode, statusLine)`（非 101 分支解析状态码后抛出，替代从 message 解析）
- [ ] `core`：`MinimalWebSocketClient` 脱敏改造（`error("URL 缺少 host")` 去掉 `$uri`；`buildTarget` 用 `URI(scheme, null, host, port, path, query, null)` + 显式 query 编码；`URISyntaxException` 兜底固定文案且清空 cause message）
- [ ] `core`：`BridgeLog.connectFailure` / `redact` 统一日志与异常文本出口
- [ ] `core`：`ProbeLogger` 增测试专用 `sink`（默认 null，行为不变）
- [ ] `core`：`BridgeConfigSource` 注入（`ProbeConfigBridgeConfigSource`，内部 `refreshFromDisk()` 调 `conf.reload()`；**该方法不是接口方法**）
- [ ] `core`：`BridgeClient.start()` 前置校验 url 形态（`scheme` / `host` 可解析），不合法时按 `CONFIG_EMPTY` 口径 WARN 一次并**不建线程**（避免每个退避周期都把配置错误当连接异常刷日志）
- [ ] `core`：`BridgeClient.runLoop` 改为按 `BridgeConfigSource` 读值 + 每轮 `refreshFromDisk()` + 原因分类 + 统计 + 限频日志；`CONFIG_EMPTY` 连续 10 次同样降频到 60s
- [ ] `core`：`AUTH_FAILED` 专用退避（封顶 10s / 连续 10 次降 60s / 成功重置）
- [ ] `core`：`bridge_status` 填本 spec 的 8 个字段（不改 schema、不改 `BridgeStatus.java` 签名）
- [ ] `core`：`BridgeCapabilities`（`PROTOCOL_VERSION="2"`、`addCapability`、`setSubscriptionSource`）+ `helloJson` 改读它
- [ ] `e2e/fixture`：`ProtocolWorkerFixture` 从「`accept()` 一次」改为**按脚本多次 accept**（accept→握手→若干帧→close→可选拒绝 N 次（401/RST）→再 accept），并记录每次握手的 token 与 version 供断言
- [ ] `e2e/fixture`：补 `version` / `capabilities` / `subscriptions` 断言（现 fixture 完全不检查 `version`，见 §3.7 待联调项；确认 JM 策略后即可启用）
- [ ] 单测：token 轮换用新值 / 各原因归类 / 退避序列与封顶 / 成功后重置 / 快照不变量 / hello 内容 / 日志限频 / 日志与异常 message 不含 token / `CONFIG_EMPTY` 两处语义
- [ ] 文档：PRD、ARCHITECTURE（状态机与快照）、CHANGELOG

**文件归属（并行开发隔离）**

| 归属 | 文件 |
|---|---|
| 本功能独占 | `core/.../bridge/DisconnectReason.kt`、`BridgeConnectionStats.kt`、`BridgeCapabilities.kt`、`BridgeHandshakeException.kt`、`BridgeLog.kt` |
| 与其它功能共改 | `core/.../bridge/BridgeClient.kt`（连接段，与线路治理重构不同区段，但需**串行合入**）、`MinimalWebSocketClient.kt`（`lastPongAtMs` + 握手异常 + 脱敏）、`core/.../config/ProbeConfig.kt`（只追加段）、`core/.../util/ProbeLogger.kt`（仅加 `sink`）、`e2e/harness/ProtocolWorkerFixture.kt` |
| **不归本功能** | `api/.../api/bridge/BridgeStatus.java` —— 归 [bridge-line-hardening.md](bridge-line-hardening.md)；本 spec 只在其**已定义的访问器上填实现**，不改签名、不增字段、不改枚举 |

## 5. 验收标准

| 场景 | 判据 |
|---|---|
| **心跳超时（可自动化）** | fixture 脚本：**记录收到 ping**（断言 `>=1` 次）后**不回 pong**、且不发任何文本帧；`pingIntervalMs` 注入 100ms、`pongTimeoutMs` 注入 300ms → 断言 `lastDisconnectReason == HEARTBEAT_TIMEOUT`、`reconnects >= 1` |
| **忙连接仍发 ping** | fixture **持续发文本帧**（每 20ms 一帧）且**不回 pong** → 断言 fixture 仍**收到过 ping**（证明周期性 ping 生效，不依赖读超时），随后判 `HEARTBEAT_TIMEOUT` |
| **忙但健康不误判**（反向用例） | fixture 持续发文本帧 **且正常回 pong**，运行 3 × `pongTimeoutMs` → `state==CONNECTED`、`reconnects==0`、`lastDisconnectReason` 为空串（这条防的就是"忙连接每 90s 假重连"） |
| 心跳正常不误判 | fixture 正常回 pong、不发文本帧，运行 3 × `pongTimeoutMs` → `state==CONNECTED`、`reconnects==0`、`lastDisconnectReason` 仍为空串 |
| `pongTimeoutMs=0` 关闭判定 | 同上 fixture 不回 pong，但超时预算配 0 → 连接不断、无 `HEARTBEAT_TIMEOUT` |
| **重连计数与原因** | fixture 脚本：握手→发 2 帧→`close`→accept；断言 `reconnects==1`、`lastDisconnectReason==PEER_CLOSED`、`lastDisconnectDetail` 非空、`lastDisconnectAtMs>0` |
| **退避递增与重置** | 同一脚本连续 3 次「accept 后立刻 close」→ `currentBackoffMs` 依次 1000/2000/4000、`consecutiveFailures` 递增；随后一次成功握手后 `currentBackoffMs==0`、`consecutiveFailures==0`、`reconnects` 不重置 |
| **token 轮换自愈** | fixture 第 1 次握手记录 token=A 后 close；测试把 `FakeBridgeConfigSource` 的 token 改成 B → 第 2 次 accept 断言收到的 query 中 `token=B`（fixture 记录每次握手的 token 以支持该断言） |
| 认证失败归类 | fixture 对前 2 次握手返回 `401` → `lastDisconnectReason==AUTH_FAILED`、`consecutiveFailures==2`、`currentBackoffMs<=10000` |
| 其它 4xx/5xx | fixture 返回 `500` → `lastDisconnectReason==UNREACHABLE` |
| 不可达归类 | 指向已关闭端口 → `ConnectException` → `UNREACHABLE` |
| `CONFIG_EMPTY` 两处语义 | ①`start()` 时 token 为空 → 不建线程、`state==STOPPED`、`lastDisconnectReason==""`（从未尝试连接）；②连上后把 token 改成空 → 记 `CONFIG_EMPTY` 并退避，连续 10 次后 `currentBackoffMs==60000` |
| **token 不泄漏（自动）** | 注入含 token 的畸形 `url`（`ws:///x`、`token` 含 `&`/`#`/空格/换行）→ 安装 `ProbeLogger.sink` 捕获**全部**日志，断言日志文本、以及所有捕获到的异常 `message` 与 `toString()` **均不含 token 子串**（含 URL 编码前的原串与其百分号编码形式） |

### 已落地的自动化证据（批次 1 实现）

| 测试（`BridgeReconnectTest`） | 覆盖的不变量 |
|---|---|
| `未发 ping 时不判心跳超时` | 门控条件 1：没有 ping 不判定 |
| `已发 ping 且 pong 未到超时则判定` | 门控条件 2+3：pong 未覆盖且超时 |
| `pong 覆盖后不判超时` | 门控条件 2：pong 晚于 ping 则健康（防"忙连接被误杀"） |
| `关闭心跳判定时不报警` | `pongTimeoutMs=0` 关闭语义 |
| `401 握手抛认证失败异常` | 状态码可精确获取（替代从 message 解析） |
| `503 握手带状态码` | 非认证类 4xx/5xx 可区分 |
| `握手失败消息不泄漏 token` | 异常消息不含凭据（自动断言，非人工检查） |
| `握手成功初始化 pong 时刻` | 刚连上不被误判超时 |
| **快照不撕裂** | 两线程交替 `markConnected`/`markDisconnected` 各 20 万次，另一线程循环 `get()` 断言 §3.6 三条不变量**全部成立**，无混搭组合 |
| 原因初值 | 刚启动未连接（`STOPPED`）时 `lastDisconnectReason==""`、`lastDisconnectAtMs==0`；**不是** `UNKNOWN` |
| 字段完整 | 断言 `bridge_status` 含 §3.6 表中本 spec 的 8 个字段且类型正确，且**未新增**任何字段（与 line-hardening §3.5 的字段集逐字比对） |
| **hello 真源** | 单测：`addCapability("x")` 后 `helloJson` 含 `x`；`PROTOCOL_VERSION=="2"`；`setSubscriptionSource` 未接线时 `subscriptions` 为 `[]`，接线后与 `snapshot()` 同源 |
| 日志限频 | 单测：同一原因连续 5 次断开 → WARN 仅 1 条、其余降 DEBUG、`suppressedCount==4`；原因变化后重新 WARN |
| 真机 | E2E：真实 Paper 内由 harness 扮演 JM（同协议、脚本化多次 accept），断言与上表同口径逐项成立且 TPS 无异常，结果文件 `status` 由逐项断言汇总（非"跑完即 PASS"） |

> 已删除原稿「真机断网/重启 Worker 观察自动恢复」：完全不可自动化，且原 fixture 只 `accept()` 一次，**结构上无法测重连**。改为上面按脚本多次 `accept`（含拒绝握手）的用例。

## 6. 风险 / 待定

1. **`BridgeClient` 重构面大**：风险最高的一次改动，单测先行、**独立提交**，不与任何新功能混提。
2. **心跳判定是行为变更**：现有实现注释明写"本客户端不强依赖 pong"。改成 `lastPongAtMs` 判定后，若某个 JM 部署根本不回 pong，会被判 `HEARTBEAT_TIMEOUT` 而反复重连。**缓解**：`pongTimeoutMs=0` 可关闭判定；首版默认 90s（3 × ping 间隔），且 fixture 侧确认 JM 会回 pong（现有 fixture 已 `OPCODE_PING -> sendFrame(OPCODE_PONG, ...)`）。**待联调**：与 JM 侧确认线上一定会回 pong，确认前真机灰度保持判定开启但观察 `HEARTBEAT_TIMEOUT` 出现率。
3. **`BridgeHandshakeException` 只覆盖"非 101"**：若 JM 先返回 101、随后才因凭据问题 `close`，本 spec 会归 `PEER_CLOSED` 而非 `AUTH_FAILED`。**待联调**：确认 JM 的拒绝时机（握手期拒绝 vs 连上后踢）。确认前不做帧级认证错误识别（避免猜协议）。
4. **`conf.reload()` 的磁盘 IO 频率**：受指数退避限制（最快 `initialBackoffMs`），可接受；若真机发现抖动，可在 `ProbeConfigBridgeConfigSource.refreshFromDisk()` 内加「最小重读间隔 10s」节流（不改接口、不改 `BridgeClient`）。
5. **`bridge.heartbeat.*` / `bridge.reconnect.*` 未经 `BridgeConfigSource`**：该接口归 [bridge-line-hardening.md §6.2](bridge-line-hardening.md)，本 spec 不擅自加方法，改用 `ReconnectSettings` 构造注入（生产由 `ProbeConfig` 填充，单测注入 100ms/300ms 这类极端值以实现 §5 的心跳用例）。这是唯一"配置接缝未与地基合并"的点，显式记录：若后续统一收口，`ReconnectSettings` 可平移为接口上的 `heartbeat()`/`reconnect()`，其余设计不变。
6. **`version` 1→2 的兼容性无法在本仓验证**：见 §3.7 待联调项。当前结论：单测/E2E 都不会暴露该风险，必须靠与 JM 侧确认 + 一条 fixture version 断言补齐。
7. **退避无抖动**：单探针场景可接受（不会自我同步）；多探针同时重连才会雪崩，超出本轮范围。留待多 Worker 场景再评估。
