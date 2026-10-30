# 功能规格：Provider 健康状态

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-35　·　分支：feature/provider-health


## 1. 背景与目标

JM 目前只能从 `jbis/manifest` 知道「有哪些域、支持哪些动作」，但不知道「这个域现在是否可用、初始化到哪一步、上次为什么失败」。运维看到业务命令失败时，无法区分是「插件没装」「插件禁用了」还是「Provider 内部坏了」。

本功能让每个 Provider 可自报健康状态，并新增 `core.business_status` 汇总查询。

第一遍审核改变了两个前提，本版据此重写设计：

1. **`ProviderHealth` 类型已由 [bridge-external-api.md](bridge-external-api.md)（FR-30）定义并实现**（`api.bridge`）。本 spec 不再"新增 `ProviderHealth`"，只负责向 `BusinessProvider` 追加 `health()` 默认方法、提供注册表、路由与内置 Provider 覆写。重复定义类型会让 `api` 出现两份同名类或让 FR-30 的 `BusinessHost.healthOf()` 找不到类型，必须避免。
2. **`health()` 不能在命令池上直调**。`business_status` 本身跑在 `serverprobe-cmd` 池上，若它在处理过程中直接调 `provider.health()`，一个卡 30s 的 `health()` 会占住一个 cmd 槽，而该查询自身已占一槽 → 嵌套占 2 槽，且"只读内存、不做 IO"的说法不成立。本版改为**缓存 + 独立刷新线程**。

## 2. 需求

### 范围内

- `BusinessProvider` 追加 `health()` **默认方法**（只加 `default`、返回 `ProviderHealth.unknown()`、保证二进制兼容），旧实现零改动兼容。
- `ProviderHealthRegistry`：缓存各域 `health()` 结果、记录最近 dispatch 失败、按域保留/清理。
- 独立刷新线程 `serverprobe-health`（调度）与 `serverprobe-health-probe`（执行 `health()`），周期性与 dispatch 后刷新缓存。
- 新增 `core.business_status` 命令（`order=110`），只读缓存快照。
- ServerProbe 自身记录「最近一次 dispatch 失败原因」，即使 Provider 未覆写 `health()` 也能给出可观测信息。

### 范围外

- **不定义 `ProviderHealth` 类型**：类型、`Status` 枚举、全部静态工厂（含 `of(status, reason, detailJson, sinceMs)`）由 [bridge-external-api.md §3.2](bridge-external-api.md) 的 FR-30 交付。
- 不做 `health()` 之外的探活（不写探测帧、不探测 Provider 依赖的端口/数据库）。`health()` 由刷新线程调用，**绝不在命令线程调用**。
- 不做健康状态的持久化与历史曲线。
- 不做自动摘除（Provider 不健康时仍可被派发，只是回执会失败）。
- **不往 `bridge_status` 里加字段**：该 schema 的唯一归属是 [bridge-line-hardening.md §3.5](bridge-line-hardening.md)。
- 经 [bridge-line-hardening.md §6.2](bridge-line-hardening.md) 的 `BridgeConfigSource.health()` 读取 `HealthSettings`（该方法已在接口权威定义中登记，本功能只填生产实现，不改接口签名）。

## 3. 设计

### 3.1 类型归属与 `health()` 追加

| 类型 / 成员 | 模块 | 归属 | 本 spec 的动作 |
|---|---|---|---|
| `ProviderHealth`（含 `Status`、`available()`…`of(...)`） | api `api.bridge` | bridge-external-api（FR-30） | **只引用，不定义** |
| `BusinessProvider.health()` | api `api.bridge` | 本 spec | 追加 `default` 方法（唯一 api 改动） |
| `BusinessHost.healthOf(domain)` | api `api.bridge` | 本 spec | **新增**（见下方"对上一版 spec 的修订"） |
| `ProviderHealthRegistry` / `BusinessStatusRoute` | core `core.bridge` | 本 spec | 新增 |

> **对上一版 spec 的修订（以实际代码为准）**：上一版说 `BusinessHost.healthOf(domain)` "由
> bridge-external-api（FR-30）定义、本 spec 只填数据源"。**实际核对：FR-30 未交付该方法**，
> `api.bridge.BusinessHost` 当前只有 `register`/`unregister`/`domains`/`manifest`/`dispatch`/`dispatchAsync`。
> 因此本 spec 承担它的**定义 + 实现**：在 `BusinessHost` 上追加
> `DomainStatus healthOf(String domain)`（`DomainStatus` 一并上提到 `api.bridge`，见下），
> 由 `BusinessHostImpl` 委派给 `ProviderHealthRegistry.viewOf`。
>
> 为什么仍要暴露到 api：第三方插件想自查"我这个域在探针眼里是什么状态"，
> 只能靠 `business_status` 命令就太绕；`healthOf` 是这条能力的唯一最小入口。
>
> **`DomainStatus` 上提 api 的连带约束**（ADR-13）：布尔访问器一律 `get` 前缀
> （`getRegistered()` / `getQuarantined()`），**禁止** `isRegistered()`——后者在 Kotlin 侧
> 会暴露成属性 `isRegistered`，消费方写 `.registered` 会失败。

本 spec 对 `api` 的**唯一**改动：

```java
// api/.../api/bridge/BusinessProvider.java —— 仅追加下面这段，不动任何既有签名
/**
 * 本域当前健康状态。
 *
 * <p>默认返回 {@link ProviderHealth#unknown()}，既有实现无需改动即兼容。</p>
 *
 * <p><b>会在探针的独立刷新线程（{@code serverprobe-health-probe}）上被调用，不在命令线程上。</b>
 * 但仍应快速返回：实现不应做网络/磁盘 IO，也不应无界等待——探针只等
 * {@code bridge.health.timeout-ms}（默认沿用 dispatch 超时预算 5000ms），
 * 超时该域会被记为 {@code DEGRADED("health() 超时")} 并在隔离窗口内不再查询。</p>
 *
 * <p>需要报告「自何时起如此」时用 {@link ProviderHealth#of(ProviderHealth.Status, String, String, long)}
 * 显式给出 {@code sinceMs}；直接返回 {@code available()} 等简写工厂时，该字段为工厂调用时刻
 * 的毫秒时间戳（{@code unknown()} 除外，它恒为 0 表示"未提供"）。</p>
 *
 * <p>实现约定：不得返回 null（返回 null 会被记为 {@code DEGRADED("health() 返回 null")}）；
 * 抛出的异常会被兜住并转为该域的 {@code DEGRADED}，不影响其它域。</p>
 *
 * @return 健康状态，永不为 null
 */
default ProviderHealth health() {
    return ProviderHealth.unknown();
}
```

**追加规则（违反即破约）**：

- 只加 `default`，不改 `BusinessProvider` 已有方法签名/返回类型。
- `default` 必须返回 `ProviderHealth.unknown()`，不得返回 null、不得在 default 内做任何 IO。
- 二进制兼容前提（与 [bridge-external-api.md §3.4](bridge-external-api.md) 同口径）：既有实现未声明同名同参、返回类型不兼容的方法。已核实现有两个内置 Provider 均无 `health`，满足前提。

**`sinceMs` 语义（消除原稿"工厂取不到非 0 值"的矛盾）**：

- 简写工厂的 `sinceMs` 口径（**以 `ProviderHealth` 的实际实现为准，本条已修正**）：
  `available()` / `unavailable(reason)` / `initializing(detail)` / `degraded(reason)` 都会填
  `System.currentTimeMillis()`，**只有 `unknown()` 恒为 0**（表示"未提供"）。
  上一版写"简写工厂一律为 0"是错的，与接口实现不符。
- 要显式指定时间戳请用 `ProviderHealth.of(status, reason, detailJson, sinceMs)`（FR-30 完整工厂）。
- **探针不替 Provider 兜底填 `sinceMs`**（不推断"首次观测到该状态的时间"）；运维要"观测时间"请读回执里探针自己的 `refreshedAtMs`。两者语义不同，见 §3.4。

**命名约束（ADR-13）**：本 spec 不新增 api 值对象；回执 JSON 的布尔字段用 `registered`/`inFlight`/`stalled`（JSON 不受 ADR-13 约束）。若将来把 `DomainStatus` 上提到 `api`，布尔访问器一律 `get` 前缀（`getRegistered()`），禁止 `isRegistered()`——原稿未出现 `isXxx()`，此条作为固化约束，防止后续退化。

### 3.2 为什么不能在命令池上直调 Provider（原设计缺陷）

原设计 `viewOf(domain, provider)` 在 `business_status` 处理过程中**直接调 `provider.health()`**，问题：

| 问题 | 后果 |
|---|---|
| `business_status` 跑在 `serverprobe-cmd-N` 池（`order=110`，见 [bridge-external-api.md §3.5](bridge-external-api.md)） | 查询自身已占 1 个 cmd 槽 |
| 池上又同步 `provider.health()` | 一个卡 30s 的 `health()` 占住第 2 个槽；cmd 池有界（默认 4），两个慢域就能让整个命令通道饱和 |
| "查询只读内存、不做 IO" | 直接调用意味着查询路径上执行了任意第三方代码，结论不成立 |
| `health()` 抛异常/超时 | 无法与"查询本身失败"区分，且异常直接影响回执生成 |

**新模型：查询只读缓存，Provider 只在刷新线程上被调用。**

```
BridgeKernel 读线程
  └─ business_status 路由 (order=110, COMMAND 池)
       └─ ProviderHealthRegistry.snapshot()/viewOf()  ← 只读不可变快照，O(域数)，无 IO、无 dispatch

BusinessHostImpl.dispatch 完成（cmd 池线程）
  └─ registry.recordDispatch(domain, result, costMs)   ← 纯内存累加
  └─ registry.requestRefresh(domain)                   ← 仅置位 + 唤醒，不阻塞

"serverprobe-health"（单线程 daemon，调度者 + 清理者）
  ├─ 周期 sweep（默认 30s）∪ 被 requestRefresh 置位的域
  ├─ 提交 provider.health() 到 probe 池，future.get(healthTimeoutMs) 有界等待
  │    ├─ TimeoutException      → DEGRADED("health() 超时") + 隔离该域
  │    ├─ ExecutionException    → DEGRADED("health() 抛出异常：" + 异常类名)
  │    └─ 返回 null             → DEGRADED("health() 返回 null")
  └─ sweepExpired()：注销超保留窗口的域、超上限时裁剪

"serverprobe-health-probe-1"（单线程 daemon，有界队列 1）
  └─ 唯一调用 provider.health() 的线程名
```

**为什么需要两个线程**：一个线程无法给自己发起的调用设上限。调度线程负责"决定查谁、查完怎么归类、什么时候清理"，执行线程负责"真正进第三方代码"，超时由调度线程的 `future.get(budget)` 施加。两者的线程名前缀都不是 `serverprobe-cmd`，这本身就是"没在命令线程跑 Provider"的可断言证据（见 §5）。

### 3.3 `ProviderHealthRegistry`

```kotlin
// core/bridge/ProviderHealthRegistry.kt（新增）
class ProviderHealthRegistry(
    private val settings: HealthSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 启动 serverprobe-health 与 serverprobe-health-probe。幂等。 */
    fun start()

    /** shutdownNow 两个线程（见 §3.7 停机协议）。幂等。 */
    fun stop()

    /** Provider 注册时调用；初始化条目并请求一次刷新。 */
    fun attach(domain: String, provider: BusinessProvider)

    /**
     * Provider 注销时调用，**按实例比对**：仅当当前域仍指向该实例才标记为「已注销」。
     * 与 [BusinessHost.unregister] 同语义，防热重载后旧实例回调误删新注册。
     */
    fun detach(domain: String, provider: BusinessProvider)

    /** dispatch 完成后调用；纯内存、O(1)、不阻塞、不抛。 */
    fun recordDispatch(domain: String, result: BridgeCommandResult, costMs: Long)

    /** 请求尽快刷新某域；合并去重。 */
    fun requestRefresh(domain: String)

    /** 单域视图；只读缓存，不做 IO、不调 Provider。 */
    fun viewOf(domain: String): DomainStatus

    /** 全量视图；只读缓存。 */
    fun snapshot(): HealthSnapshot

    /** 调试/验收用：本进程内已创建的刷新线程名。 */
    fun threadNames(): List<String>
}

/**
 * 阈值全部由构造参数注入——**单测可注入 0 或 50ms 这类极端值**，
 * 不读 `ProbeConfig`（core 单测 classpath 无 `basic-configuration`，`conf` 是 `lateinit`）。
 *
 * **对上一版 spec 的修订（以 `BridgeConfigSource.kt` 既有定义为准）**：
 * `HealthSettings` 已在线路治理（批次 0）落地，字段名为
 * `refreshIntervalMs` / `healthTimeoutMs` / `retainAfterUnregisterMs` / `maxEntries`。
 * 上一版 spec 写的 `sweepIntervalMs` / `retentionMs` / `maxDomains` 是**同一批语义的别名**，
 * 以代码为准，不重命名（改名会同时打穿 `ProbeConfig` 键名、`config.yml` 与 `bridge_status` 文档）。
 * 本 spec 只**追加**两个真正缺失的字段：隔离窗口与回执截断长度。
 */
data class HealthSettings(
    /** `health()` 有界等待预算；语义与 `bridge.dispatch.timeout-ms` 一致（"我们最多等 Provider 这么久"）。 */
    val healthTimeoutMs: Long = 1_000,
    /** 周期 sweep 间隔（既有字段名 `refreshIntervalMs`，语义相同）。 */
    val refreshIntervalMs: Long = 10_000,
    /** **新增**：超时/异常后该域的隔离窗口：窗口内不再调用其 `health()`，直接沿用上次结论。 */
    val quarantineMs: Long = 60_000,
    /** 注销后状态保留窗口（既有字段名 `retainAfterUnregisterMs`，语义相同）。 */
    val retainAfterUnregisterMs: Long = 300_000,
    /** 条目上限（含已注销条目；既有字段名 `maxEntries`，语义相同）。 */
    val maxEntries: Int = 256,
    /** **新增**：每域 `reason` 在回执内的截断长度。 */
    val reasonMaxChars: Int = 512,
    /** **新增**：每域 `detailJson` 在回执内的截断长度。 */
    val detailMaxChars: Int = 2048,
)

/** 单域缓存视图，不可变。 */
class DomainStatus(
    val domain: String,
    val registered: Boolean,
    /** Provider 自报值；未刷新过时为 `ProviderHealth.unknown()`。 */
    val health: ProviderHealth,
    /** Provider 未注册时探针补充的原因；非空时优先于 `health.getReason()` 呈现。 */
    val reasonOverride: String,
    /** 本次 Provider 自报值的采集时刻（探针观测时间，与 `health.getSinceMs()` 语义不同）。 */
    val refreshedAtMs: Long,
    val lastFailure: FailureInfo?,
    val dispatchTotal: Long,
    val dispatchFailed: Long,
    val lastCostMs: Long,
    /** 是否处于隔离窗口内。 */
    val quarantined: Boolean,
)

class FailureInfo(
    val message: String,
    val timestampMs: Long,
    val consecutiveFailures: Int,
)

/** 全量视图，不可变；`core.business_status` 直接序列化它。 */
class HealthSnapshot(
    val domains: List<DomainStatus>,   // 稳定顺序：已注册在前，其次按 domain 字典序
    val refreshInFlight: Boolean,
    /** probe 线程被占住、提交被拒时为 true（见下方"有界性"）。 */
    val stalled: Boolean,
    val quarantined: List<String>,
    val lastSweepAtMs: Long,
    val skippedThisSweep: List<String>,
)
```

**刷新循环要点**：

- 每轮：`due = (周期到期 ∪ requestRefresh 置位) − 隔离窗口内`，逐域 `refreshOne`，随后 `sweepExpired()`，再"有界等待到下次 sweep 或被唤醒"（用 `ReentrantLock` + `Condition.awaitNanos`，`requestRefresh` 里 `signal`）。
- `refreshOne` 严格按 §3.2 的三条归类处理，**任何分支都不抛**（`runCatching` 兜底），单域失败绝不影响本轮其它域。
- `health()` 抛出的异常只取 `javaClass.simpleName` + 经脱敏的 message，**限长 `reasonMaxChars`**；`detailJson` 同样截断到 `detailMaxChars`。脱敏规则（本 spec 自持，`core` 内无现成实现）：
  - `token=<值>` / `password=<值>` / `authorization: <值>` 形态 → 值替换为 `***`；
  - 含 URL 的 message 一律先去掉 query（凭据通常在 query 里）；
  - 其余按 `reasonMaxChars` 截断并加 `…`。实现落在 `core/bridge/health/*`，与 `bridge-reconnect` 的 `BridgeLog.redact` 共用同一套替换表（避免两处规则 drift）。

**probe 池的有界性（关键取舍）**：

```kotlin
probe = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(1),
    daemonThreadFactory("serverprobe-health-probe"),
    ThreadPoolExecutor.AbortPolicy(),
)
```

- 单线程 = 同时只有一个 `health()` 在跑，线程数恒定，**不会因 Provider 卡死而泄漏线程**（Java 无法强杀线程）。
- 卡死后果：该 probe 线程被占住后，后续提交被 `AbortPolicy` 拒绝 → 调度线程置 `refresh.stalled=true`、WARN 一次、**本轮剩余域跳过刷新（保留上次缓存，查询仍成功）**。这是本设计的已知上限，写进 §6 风险与验收。

**清理（"定期清理"的执行者就是 `serverprobe-health`）**：

- 注销超过 `retainAfterUnregisterMs` 的条目 → 整条删除（`lastFailure` 一并丢弃，文档口径一致）。
- 条目数 > `maxEntries` → 按「已注销且 `refreshedAtMs` 最旧」优先淘汰；**已注册的条目永不淘汰**，即使因此短暂超上限。

### 3.4 `core.business_status`（本 spec 独占的回执）

`domain="core"`、`action="business_status"`、`order=110`、`executor=COMMAND`（号位见 [bridge-external-api.md §3.5](bridge-external-api.md)）。`payload` 可选 `{"domain":"economy"}`（不传则全量）：

```json
{
  "domains": {
    "economy": {
      "registered": true,
      "health": "AVAILABLE",
      "reason": "",
      "detailJson": "",
      "sinceMs": 1730000000000,
      "refreshedAtMs": 1730000000500,
      "quarantined": false,
      "lastFailure": {
        "message": "外部插件未就绪",
        "timestampMs": 1729999990000,
        "consecutiveFailures": 2
      },
      "dispatch": {"total": 120, "failed": 2, "lastCostMs": 3}
    },
    "inventory": {
      "registered": false,
      "health": "UNKNOWN",
      "reason": "Provider 未注册",
      "detailJson": "",
      "sinceMs": 0,
      "refreshedAtMs": 0,
      "quarantined": false,
      "dispatch": {"total": 0, "failed": 0, "lastCostMs": 0}
    }
  },
  "refresh": {
    "inFlight": false,
    "stalled": false,
    "quarantined": ["activity"],
    "lastSweepAtMs": 1730000001000,
    "skippedThisSweep": []
  },
  "timestamp": 1730000001000
}
```

- `sinceMs` 原样转发 `ProviderHealth.getSinceMs()`（未提供即 0，探针不填）；`refreshedAtMs` 是探针采集时刻，二者不可互相替代。
- `registered=false` 的域**仅在 JM 显式查询该域时**出现；全量查询只列「已注册域 + 保留窗口内的已注销域」。
- `health()` 抛异常 / 超时 / 返回 null → 该域 `DEGRADED` + 对应原因，**其它域不受影响，整段回执仍 `success=true`**。
- 整段查询**不调用 `dispatch`、不做 IO、不新建线程**，O(域数)。
- 回执体积有界：域数上限 `maxEntries`，每域 `reason ≤ reasonMaxChars`、`detailJson ≤ detailMaxChars`，防恶意 Provider 撑爆回执。
- 原稿的 `"breaker": {}` 空占位已删除（本轮无熔断器，空对象只会误导解析方）。

### 3.5 与 `bridge_status` / `jbis/manifest` 的关系

- **`core.bridge_status`（`order=130`）的 schema 唯一归属 [bridge-line-hardening.md §3.5](bridge-line-hardening.md)。** 本 spec 不新增、不改写其中任何字段，也不复制一份 schema。
- `core.business_status`（`order=110`）是本 spec 自己的 action，字段见 §3.4，与 `bridge_status` 无重叠。
- `jbis/manifest` 保持不变（能力清单）。`core.business_status` 是运行状态，两者职责分离。
- 可选增强：`manifest` 每域附 `health`——**本轮不做**，避免破坏 JM 既有解析。

### 3.6 生命周期

- `attach`：Provider 注册时初始化计数、`refreshedAtMs=0`、`registered=true`，并 `requestRefresh`。
- `detach`：标记 `registered=false` 并记录注销时刻；条目保留 `retainAfterUnregisterMs` 供查询，之后由 `serverprobe-health` 的 `sweepExpired()` 清理。
- 探针停用：§3.7。

### 3.7 停机协议（插入 [bridge-line-hardening.md §6.1](bridge-line-hardening.md) 的序列）

```
① 停止接收新帧（running=false，读循环退出）
② reload 池 shutdownNow ‖ health 刷新线程 shutdownNow（同批）
     └─ serverprobe-health + serverprobe-health-probe 均 shutdownNow
        理由与 reload 池相同：health() 是只读上报，打断无副作用，且停用时不该再进第三方代码
③ cmd 池 shutdown（不打断在途业务）
④ FrameSender 有界抽干
⑤ spill flush + fsync
⑥ socket.close()
⑦ 线程 join（各自有界）
```

- `stop()` 幂等；`serverprobe-health*` 均为 daemon 线程，即使某个 Provider 的 `health()` 不可中断地卡住，也不会阻止 JVM 退出。
- 因此 line-hardening 的"无残留线程"断言口径补一句：**在 Provider 正常返回的前提下**断言无 `serverprobe-health*` 线程；违反该前提时残留 1 个 probe 线程是已知且已登记的取舍（§6 风险 2）。

## 4. 任务拆分

- [x] `api`：`BusinessProvider` 追加 `health()` default（**只此一处**改 api；不新建 `ProviderHealth`）
- [x] `core`：`ProviderHealthRegistry`（`attach`/`detach` 实例比对、`recordDispatch`、缓存与不可变视图、逐域隔离、脱敏与限长）
- [x] `core`：`serverprobe-health` 调度线程（周期 sweep + `requestRefresh` 合并 + 有界等待唤醒）
- [x] `core`：`serverprobe-health-probe` 执行线程（有界单线程池 + `future.get(timeout)` + 超时 cancel + 隔离 + 拒绝时 `stalled`）
- [x] `core`：清理（保留窗口 + 上限 256 + 淘汰顺序；已注册条目永不淘汰）
- [x] `core`：`BusinessStatusRoute`（`InboundRoute`，`order=110`，`executor=COMMAND`）
- [x] `core`：`BusinessHostImpl` 接 `attach`/`detach`/`recordDispatch`/`requestRefresh`（`dispatch` 收尾处各一行）
- [x] `core`：`BusinessHost.healthOf(domain)` 的实现改为读 `registry.viewOf(domain).health`（本 spec 承担 api 定义 + core 实现，见 §3.1 修订）
- [x] `core`：停机协议插入两个 health 线程的 `shutdownNow`（与 reload 池同批）
- [x] `plugin`：`ProbeConfig` 追加 `bridge.health.*`（实际键名以代码为准：`refresh-interval-ms`/`timeout-ms`/`quarantine-ms`/`retain-after-unregister-ms`/`max-entries`/`reason-max-chars`/`detail-max-chars`），并在 `ProbeConfigBridgeConfigSource.health()` 中组装 `HealthSettings`；`config.yml` 补注释
- [x] ~~`integration-*`：两个内置 Provider 覆写 `health()`（反映外部插件是否就绪）~~
  —— **本项已作废**：两个内置集成模块已被 FR-37（ADR-0030）整体删除，仓内不再有内置 Provider。
  本功能改为只提供 `health()` 接缝，由第三方插件自行覆写。
- [x] 单测：五等级呈现 / 默认兼容 / `health()` 抛异常 / 返回 null / 超时 / 隔离与停滞 / 失败记录与清零 / 未注册域 / 保留与清理 / 回执截断 / 脱敏 / 查询不派发 / 查询不开线程不做 IO / 刷新线程名
- [x] E2E：注册正常 Provider + 抛异常 Provider + 卡死 Provider，断言 `business_status` 三者区分呈现且整段成功
  —— `e2eHealthFixture`（harness 场景 `health-fixture`，真机 Paper 1.20.1）。三个要点：
  ①三域按字典序 `a`/`b`/`c` 注册（`e2e-health-a-ok`/`-b-throw`/`-c-stuck`），因为 refresh 线程按域名字典序
  逐个 probe 且 probe 池是单线程有界队列——卡死域排最前会占住唯一 probe 线程，另外两域根本拿不到首次健康值；
  ②卡死域 `health()` 真阻塞 5s 且**吞掉** `future.cancel(true)` 的中断（配置 `bridge.health.timeout-ms=300`），
  因此 `DEGRADED("health() 超时")` 只可能来自有界等待到点，不可能来自"返回降级"；
  ③结果文件由逐项断言汇总：`assertions.total=16 / passed=16`，含「连续查询 10 次 `dispatch.total` 恒为 0」
  与「Provider 侧 `dispatch()` 一次未被调用」两份互相独立的证据。配置显式写死 `health.timeout-ms: 300`、
  `refresh-interval-ms: 600000`、`quarantine-ms: 600000`（避免测试期周期 sweep 再次 probe 卡死域而把正常域连带判成超时）。
- [x] 文档：PRD、`docs/API.md`（补 `health()` 与 `business_status`）、CHANGELOG
  —— ARCHITECTURE 线程表**未补**：该文件当前没有 `serverprobe-*` 桥线程表可增量更新（线程模型写在 `BridgeClient` 类注释里）。

**文件归属（并行开发隔离）**

| 归属 | 文件 |
|---|---|
| 本功能独占 | `core/.../bridge/ProviderHealthRegistry.kt`、`core/.../bridge/BusinessStatusRoute.kt`、`core/.../bridge/health/*` |
| 与其它功能共改 | `api/.../api/bridge/BusinessProvider.java`（仅加 default，文件归 bridge-external-api，需**串行合入**）、`core/.../bridge/BusinessHostImpl.kt`、`core/.../bridge/BridgeClient.kt`（一行 route 注册）、`core/.../config/ProbeConfig.kt`（只追加段） |
| **不归本功能** | `api/.../api/bridge/ProviderHealth.java` —— 由 bridge-external-api（FR-30）定义并实现，本 spec 只引用不修改 |

## 5. 验收标准

| 场景 | 判据 |
|---|---|
| 默认兼容 | 不覆写 `health()` 的旧 Provider 编译通过；回执该域 `health=UNKNOWN`、`sinceMs=0` |
| 五等级呈现 | 分别覆写返回 `available()`/`unavailable("插件未安装")`/`initializing(...)`/`degraded(...)`/`unknown()` → 回执 `health` 逐一对应，`reason` 原样转发（含中文） |
| `of(...)` 时间戳 | 覆写返回 `ProviderHealth.of(DEGRADED, "内部状态损坏", "{\"k\":1}", 1730000000000)` → 回执 `sinceMs==1730000000000`、`detailJson` 原样；与 `refreshedAtMs` 不相等（证明二者独立） |
| **查询不派发** | 桩 Provider 的 `dispatch` 自增计数；注册后等一次刷新完成，再连续查询 `business_status` 10 次 → 断言 `dispatch` 计数**始终为 0** |
| **查询不新建线程、不做 IO** | 查询前记录 `Thread.getAllStackTraces().keys` 的名字集合；查询 10 次后集合**无新增**；IO 接缝（计数型 `FileSystem` + `BridgeConfigSource`）调用次数为 0 |
| **刷新线程隔离** | 桩 Provider 在 `health()` 内记录 `Thread.currentThread().name` → 断言前缀为 `serverprobe-health-probe`，且**不是** `serverprobe-cmd` |
| **超时归类** | `healthTimeoutMs` 注入 50ms、`health()` 阻塞 200ms → 该域 `health=DEGRADED`、`reason` 含 `health() 超时`、`quarantined=true`；**回执整体 `success=true`**；其它域取值不变 |
| 抛异常隔离 | A 域 `health()` 抛 `IllegalStateException`，B 域返回 `available()` → A 为 `DEGRADED`（原因含 `health() 抛出异常`）、B 为 `AVAILABLE`、整体 `success=true` |
| 返回 null | `health()` 返回 null → `DEGRADED` + 原因含 `health() 返回 null`，不抛 NPE |
| 隔离与停滞 | 卡死 Provider 占住 probe 线程后，连续 sweep 3 轮 → `refresh.stalled=true`；其余域仍返回**上次缓存值**且查询 `success=true`；`threadNames()` 长度恒为 2（`serverprobe-health` + `serverprobe-health-probe`），**不随轮次增长**（证明卡死不泄漏线程） |
| 失败记录 | dispatch 失败 2 次 → `consecutiveFailures=2`；成功 1 次后清零；`dispatch.total/failed` 累加正确 |
| 未注册域 | 查询未注册域 → `registered=false` + 原因，`success=true`，不报错 |
| 保留与清理 | `retainAfterUnregisterMs` 注入 0 → `detach` 后下一次 `sweepExpired()` 该域从全量回执消失；注入 300 个已注销域 → 条目数 ≤ `maxEntries` 且**已注册域一个不少** |
| 回执截断 | `degraded(reason)` 传 2000 字符 → 回执内 `reason` ≤ 512；`detailJson` 传 10000 字符 → ≤ 2048 |
| 脱敏 | 失败原因含 `token=abc123` → 输出为 `token=***`，明文不出现 |
| **不碰 `bridge_status`** | 单测断言 `BridgeStatusRoute` 注册表/回执字段与本 spec 无关；`bridge_status` 回执字段集与 [bridge-line-hardening.md §3.5](bridge-line-hardening.md) 逐字一致（有 diff 即失败） |
| 停机 | `stop()` 后再 `start()` 幂等；Provider 正常返回时，`stop()` 后无 `serverprobe-health*` 线程残留 |
| 真机 | E2E 真实 Paper：正常/异常/卡死三类 Provider 区分呈现，结果文件 `status` 由逐项断言汇总（非"跑完即 PASS"） |

> 已删除原稿「100 域下耗时 < 5ms」：既是 flaky 的墙钟阈值，也不证明"不调 dispatch、只读内存"。改为上面三条可判定断言（派发计数为 0 / 线程集合无新增 / IO 接缝计数为 0）。

## 6. 风险 / 待定

1. **Provider 自报可能不诚实**：`health()` 返回 `available()` 但 dispatch 一直失败时，回执会同时显示「可用」和「连续失败 N 次」，由 JM 决策。探针不替 Provider 判断——与「只如实回传，不做推断」原则一致。同理探针也不替 Provider 填 `sinceMs`。
2. **卡死 `health()` 的取舍（已登记）**：Java 无法强杀线程。某 Provider 的 `health()` 不可中断地卡住后，唯一的 probe 线程被永久占住，其它域的刷新退化为"沿用缓存"，回执以 `refresh.stalled=true` 暴露该事实。代价是"其它域的 health 不再新鲜"，收益是**线程数恒定、不会泄漏、命令通道完全不受影响**——这正是本版要解决的问题本身。若真机上出现，运维可通过 `business_status` 定位到具体域（见 `quarantined`）。
3. **保留窗口与上限的内存占用**：`maxEntries=256` ×（1 条失败信息 + 少量计数）可忽略；`detailJson` 由回执截断挡住，但**注册表本身仍持有 Provider 返回的原串**——若担心，可在 `recordHealth` 时即截断存储（实现时按此做）。
4. **配置接缝**：`bridge.health.*` 经 `BridgeConfigSource.health()` 读取（该方法已在
   [bridge-line-hardening.md §6.2](bridge-line-hardening.md) 的接口权威定义中登记）。
   生产实现 `ProbeConfigBridgeConfigSource` 负责组装 `HealthSettings`；单测注入 `FakeBridgeConfigSource` 的极端值。
5. **待定**：`bridge.health.timeout-ms` 默认取 5000（沿用 `bridge.dispatch.timeout-ms`）是否合适——取决于最慢内置 Provider 的 `health()` 实测耗时。无法在文档阶段定：需真机采集后调；当前取值与 dispatch 预算一致，语义自洽（"我们对 Provider 的耐心是同一个"）。
6. **待定**：`refreshIntervalMs` 默认 30s 与 JM 轮询节奏的关系。无法定：JM 侧轮询频率未定。当前取 30s（远小于 `retainAfterUnregisterMs`），即便 JM 更频繁轮询，读到的也只是同一份缓存，代价可控。
