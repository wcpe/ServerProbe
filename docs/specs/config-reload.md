# 功能规格：配置重载通知

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-32　·　分支：feature/config-reload


## 1. 背景与目标

JianManager 需要在改完某插件配置后，让该插件重新加载。ServerProbe 作为服务端 agent 负责**把重载意图通知到真正关心这份配置的插件**，并如实回传结果。

核心语义（ADR-30）：**reload 成功 ≠ 配置生效**。ServerProbe 只做「通知 + 收集声明」，不做任何推断，也不承诺插件真的应用了新配置。JM 需要强保证时用重启。

本功能刻意**不做**配置文件的读写、版本管理、分层合并、灰度编排——那些归 JM CP/Worker。

## 2. 需求

### 范围内

- 新命令：`domain="core"`、`action="config_reload"`、`payload={"paths":[...]}`（空数组或省略 = 全量）。
- 走既有 `core.*` 路由，**不新增通道、不改 proto**。
- 独立重载池执行，单次超时 5s。
- 路径安全：相对路径，仅允许落在服务器工作目录的 `plugins/` 子树内，越界即 `PATH_ESCAPE`。
- 匹配：监听器声明「精确路径」或「目录前缀」（末尾 `/`）。
- 同名路径在途返回 `IN_FLIGHT`，不并发。
- 重复调用幂等（同路径重复成功重载，结果一致，无额外副作用）。
- 回执区分成功 / 部分成功 / 失败，并标注无人认领项。
- 超时**不强杀主线程**，只标记 `TIMEOUT`，该次重载自生自灭。
- 熔断：超时路径进入熔断名单，窗口内不再自动重试；窗口与阈值可配置。
- 归档 ADR-30（通知-认领模型）。

### 范围外

- 不读写配置文件内容（除可选的语法校验）。
- 不推断插件是否真的应用了配置。
- 不提供 `ConfigReloadHandler.reloadConfig(path): String?` 这种「单路径返回值」旧式签名——改用集合通知 + 结构化结果（见 3.2 说明）。

## 3. 设计

### 3.1 处理流程

```
Worker 下发 command(domain=core, action=config_reload, payload={"paths":[...]})
  → BridgeKernel 投递到 **reload 协调池**（读线程立即返回，不等待）
  → ConfigReloadCoordinator.reload(paths)           ← 跑在 serverprobe-reload-N
      1. 规范化路径 + 越界校验（PATH_ESCAPE）
      2. 去重、排序、按 path 查熔断表（熔断中 → TIMEOUT，不执行）
      3. 按 path 查在途表（在途 → IN_FLIGHT；久未返回 → STUCK）
      4. 匹配监听器：精确 + 目录前缀
      5. 无监听器 → bestEffort 列表（不调用任何人）
      6. 有监听器 → **提交到 reload-worker 池**（独立池，见 §3.8），轮转收割，总预算 5s
      7. 收集结果 → 组装回执 → 交 FrameSender
```

> **两池分离是硬要求**：协调器跑 `reload` 池，监听器子任务跑 `reload-worker` 池。
> 若共用同一个有界池，4 个并发 `config_reload` 各占 1 个协调槽后把 32 个子任务提交到同一池，
> 队列一满即无空闲线程 → 协调器全部阻塞在 `future.get(5s)` → 子任务永不执行 → 全部误判 `TIMEOUT`。
> "轮转收割"是收口算法，**解决不了池内自锁**。线程名：`serverprobe-reload-N` / `serverprobe-reload-worker-N`。

**全量重载**（`paths` 为空）：向所有已注册监听器各通知一次，`matchedPaths` 为其声明集合。

### 3.2 对外契约（`api` 模块）

```java
package top.wcpe.mc.plugin.serverprobe.api.reload;

/**
 * 配置重载监听器。
 *
 * <p>插件通过 {@code ServerProbeAPI.configReloads().register(listener)} 注册自己关心的配置路径。
 * 派发时**只调用匹配到路径的监听器**；没被匹配到的监听器不会被调用，也就不会产生结果。</p>
 *
 * <p><b>线程约定</b>：回调在 ServerProbe 的配置重载池（daemon 线程）触发。
 * 若需主线程操作（如 Bukkit 插件重载配置），请自行切回主线程，但总耗时不得超过本次重载超时。</p>
 *
 * <p><b>绝不抛异常</b>：失败请返回 {@link ConfigReloadResult#failed}。抛出异常会被兜住并转为失败，
 * 但会丢失可读原因。</p>
 */
public interface ConfigReloadListener {

    /**
     * 声明本监听器关心的路径。
     *
     * <p>支持两种形态：</p>
     * <ul>
     *   <li>精确路径：{@code plugins/Foo/config.yml}</li>
     *   <li>目录前缀：{@code plugins/Foo/}（<b>末尾斜杠</b>表示目录），匹配该目录下任意层级的文件</li>
     * </ul>
     *
     * <p>路径一律使用 {@code /} 分隔，相对服务器工作目录，且必须位于 {@code plugins/} 下。</p>
     *
     * @return 路径集合；不得返回 null（无关注项返回空集合，等价于不注册）
     */
    Collection<String> watchedPaths();

    /**
     * 配置路径发生变更，请重载并返回结果。
     *
     * @param event 本次重载事件（含命中的路径集合）
     * @return 重载结果；返回 null 视为失败（缺结果不得当作成功）
     */
    ConfigReloadResult onConfigReload(ConfigReloadEvent event);
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.reload;

/**
 * 一次配置重载事件，不可变。
 *
 * <p>用对象而非裸集合作为入参：将来新增字段（如变更来源、md5）不破签名。</p>
 */
public final class ConfigReloadEvent {

    private final List<String> matchedPaths;
    private final List<String> requestedPaths;
    private final boolean fullReload;

    /** 本次**命中本监听器**的路径（不可变，已排序）。 */
    public List<String> getMatchedPaths();

    /** 本次请求中的全部路径；全量重载时为空列表。 */
    public List<String> getRequestedPaths();

    /** 是否全量重载（payload 未指定 paths）。布尔访问器用 get 前缀（ADR-13）。 */
    public boolean getFullReload();
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.reload;

import java.util.OptionalInt;
import top.wcpe.mc.plugin.serverprobe.api.Registration;   // 跨包：api.reload → api（顶层）

/**
 * 一次配置重载的结果，不可变。
 *
 * <p>语义：成功表示「插件已把新配置应用到运行态」，而不是「文件存在」。</p>
 */
public final class ConfigReloadResult {

    /** 成功。 */
    public static ConfigReloadResult ok();

    /** 成功，并附带插件自定义详情 JSON（原样透传给 JM，探针不解析）。 */
    public static ConfigReloadResult ok(String detailJson);

    /** 失败。 */
    public static ConfigReloadResult failed(ConfigReloadErrorCode code, String message);

    /** 失败，并附带详情。 */
    public static ConfigReloadResult failed(ConfigReloadErrorCode code, String message, String detailJson);

    public boolean getOk();

    /** 失败时的错误码；成功时为 null（本类唯一允许 null 的读口）。 */
    public ConfigReloadErrorCode getCode();

    /** 可读消息；成功时为空串，永不为 null。 */
    public String getMessage();

    /** 插件自定义详情 JSON；无则空串，永不为 null。 */
    public String getDetailJson();

    // ---- 自报事实（读口与写口必须成对，否则 core 无法组装 applied 映射）----

    /** 自报「实际加载内容的 md5」；未上报时为空。 */
    public Optional<String> getAppliedMd5();

    /** 自报「实际解析出的版本标识」；未上报时为空。 */
    public Optional<String> getAppliedVersion();

    /** 自报「实际加载的条目数」；未上报时为空（用 OptionalInt 以区分「未上报」与「上报 0」）。 */
    public OptionalInt getAppliedCount();

    public ConfigReloadResult withAppliedMd5(String md5);

    public ConfigReloadResult withAppliedVersion(String version);

    public ConfigReloadResult withAppliedCount(int count);

    @Override public boolean equals(Object o) { ... }
    @Override public int hashCode() { ... }
    @Override public String toString() { ... }
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.reload;

/**
 * 重载失败错误码。
 *
 * <p>注意：{@code BEST_EFFORT} <b>不是</b>错误码——无人认领的路径计入回执的
 * {@code bestEffort} 列表（算成功），不占 {@code failed}。</p>
 */
public enum ConfigReloadErrorCode {
    /** 路径不存在。 */
    FILE_NOT_FOUND,
    /** 文件存在但解析失败（如 YAML 语法错误）。 */
    PARSE_ERROR,
    /** 插件明确拒绝了本次重载。 */
    PLUGIN_REJECTED,
    /** 监听器抛出了异常（已兜住）。 */
    PLUGIN_THREW,
    /** 超时未完成（可能仍在执行，ServerProbe 不强杀）。 */
    TIMEOUT,
    /** 路径越界（不在 plugins/ 子树内，或含 ../ 逃逸）。 */
    PATH_ESCAPE,
    /** 同路径已有重载在途，本次未执行。 */
    IN_FLIGHT,
    /** 上次在途已超时且任务未返回，疑似卡死（本次未执行）。 */
    STUCK,
    /** 探针内部错误（提交被拒、池已关闭等，本次未执行）。 */
    INTERNAL_ERROR
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.reload;

import top.wcpe.mc.plugin.serverprobe.api.Registration;

/**
 * 配置重载监听器注册表。
 *
 * <p>由 {@code ServerProbeAPI.configReloads()} 取得，稳定代理、永不 null。</p>
 */
public interface ConfigReloadRegistry {

    /**
     * 注册监听器。
     *
     * <p>注册时立即校验 {@link ConfigReloadListener#watchedPaths()}：非法条目被丢弃并 WARN；
     * 全部非法或为空集合时返回失效句柄（原因为 {@code INVALID}）——不静默成功。</p>
     *
     * @return 注册句柄；探针未就绪时返回失效句柄（{@code PROBE_UNAVAILABLE}），不抛异常
     */
    Registration register(ConfigReloadListener listener);
}
```

> **为什么不用需求里提的 `ConfigReloadHandler.reloadConfig(path): String?`**
> 单路径 + 单字符串返回值无法表达：多路径批量、无人认领、结构化错误码、自报实际加载内容。
> 集合通知 + 结构化结果与 Beacon `ConfigReloadResult` 的既有范式一致，且能被 JM 直接渲染。

### 3.3 路径安全

```kotlin
// core/bridge/reload/ReloadPathGuard.kt
object ReloadPathGuard {
    /**
     * 规范化并校验路径。
     *
     * 规则：
     * 1. 拒绝绝对路径（Windows 盘符 / 前导 /）
     * 2. 统一分隔符为 /，折叠 . 与 ..
     * 3. 规范化后必须以 plugins/ 开头
     * 4. canonical 后仍须在 <工作目录>/plugins 内（防符号链接逃逸）
     *
     * @return 规范化路径；不合法时返回 null
     */
    fun normalize(raw: String): String?
}
```

- 基准：`new File(".").getCanonicalFile()`（服务器工作目录）。
- 越界、绝对路径、`plugins/../..` 一律 `PATH_ESCAPE`。
- 空串、含 NUL → `PATH_ESCAPE`。

### 3.4 监听器匹配

```kotlin
// 精确：matcher == path
// 目录：matcher.endsWith("/") && path.startsWith(matcher) && 剩余部分不含 ".."
// 两者都不满足 → 不命中
fun matches(declared: String, path: String): Boolean
```

- 声明的目录前缀本身必须通过 `ReloadPathGuard`（防注册 `../` 后命中任意文件）。
- 匹配为**纯字符串前缀**，不做 glob、不做正则（防 ReDoS 与歧义）。

### 3.5 在途与幂等

```kotlin
// core/bridge/reload/InFlightRegistry.kt
class InFlightRegistry {
    /**
     * 尝试占用一个路径。
     *
     * 键为**规范化后**的 path；全量重载使用全局哨兵键 [FULL_RELOAD_KEY]。
     *
     * @return ACQUIRED / IN_FLIGHT（已有同路径在途）/ STUCK（上次在途已超时且任务未返回）
     */
    fun tryAcquire(path: String, nowMs: Long): AcquireResult

    /** 只在任务自身 finally 中调用。 */
    fun release(path: String)

    /** 诊断快照：在途路径 + 已占用时长。 */
    fun snapshot(nowMs: Long): List<InFlightEntry>
}
```

**`release` 归属（消除"不并发"与"永久 IN_FLIGHT"的两难）**：

- `release` **只在任务自身的 `finally` 执行**，协调器超时后**不代它 release**。
  理由：`Future.cancel(true)` 对不响应中断的任务无效，若协调器代为 release，
  同路径第二次请求会与仍在执行的第一份**并发**跑插件配置加载，两份写入交错。
- 代价：永不返回的任务会把路径长期占住。因此 `tryAcquire` 在"已占用且超过 `stuckAfterMs`"时返回
  **`STUCK`**（独立错误码），回执写明「上次重载已超时且未返回，疑似卡死」——
  不能再用 `IN_FLIGHT` 掩盖卡死，那会让运维误判。
- `bridge_status.reload.stuck` 暴露卡死路径（字段已由 `bridge-line-hardening` §3.5 的 schema 定义）。

**全量重载的哨兵键**：

- 全量重载占用全局键 `"*"`。
- 全量在途时，任何单路径请求 → `IN_FLIGHT`（message 注明"全量重载在途"）。
- 任何单路径在途时，全量请求 → `IN_FLIGHT`。
- 牺牲少量吞吐，换取**"同一时刻不会有两份重载并发改插件状态"**这一可断言的不变量。

**幂等**：

- 定义：同路径、同监听器集合、连续两次成功重载，**除 `costMs` 与时间戳外所有回执字段逐项相等**，
  且监听器被调用次数恰为 2，插件内部状态在第二次之后与第一次之后相同（不得累加）。
- spec 要求单测按**结构化字段逐项比较**（不用 `toString`），并断言调用计数与累加型字段不翻倍。

### 3.6 熔断

```kotlin
// core/bridge/reload/CircuitBreaker.kt
class CircuitBreaker(
    private val failureWindowMs: Long,   // 统计窗口
    private val openDurationMs: Long,    // 熔断打开时长（可与窗口不同）
    private val failureThreshold: Int,
    private val maxEntries: Int,
) {
    /** 记录一次超时（可并发调用）。 */
    fun recordTimeout(path: String, nowMs: Long)

    /** 纯函数式判定，只读不改状态。 */
    fun isOpen(path: String, nowMs: Long): Boolean

    /** 诊断快照（不可变）。 */
    fun snapshot(nowMs: Long): List<BreakerEntry>

    /** 因容量满被拒绝登记的次数。 */
    fun rejectedEntries(): Long
}
```

**并发**：`ConcurrentHashMap<String, BreakerState>`，`BreakerState` 为不可变
`(windowStartMs, failures, openedAtMs)`，任何更新走 `compute` 整体替换。禁止裸 `HashMap`（CME 会从回执里冒出来）。

**窗口与边界**：

- `failureWindowMs` 与 `openDurationMs` **拆成两个配置**。原设计用同一个 `windowMs` 承担两种语义，
  `failureThreshold=1` 时二者重合看不出问题，调大阈值立刻语义分裂。
- `isOpen` 判定：`nowMs - openedAtMs < openDurationMs` 为**仍开**；`>=` 即恢复（比较符写进单测）。

**淘汰策略**：`maxEntries` 满时**拒绝新条目**并计数 `rejectedEntries`，不淘汰已有条目。

理由：淘汰最旧会把真正处于熔断的路径挤出去，等于"用大量不同 path 就能悄悄解除熔断"；
拒绝新条目是保守方向——漏记一个熔断（该路径仍会被执行并可能再次卡主线程，但会被单次超时兜住）
比误熔断（放行了本该被挡的路径）安全。`rejectedEntries` 暴露到 `bridge_status.reload.breakerRejectedEntries`
（字段已在 `bridge-line-hardening` §3.5 定义）。

**熔断恢复改为指数退避**（避免"每 60s 准时再卡主线程一次"）：

- 连续 `TIMEOUT` 次数为 N 时，`openDuration = min(baseOpenDurationMs * 2^(N-1), maxOpenDurationMs)`。
- 一次成功重载后计数清零、恢复 base。
- 理由：固定窗口到期即恢复执行，会周期性重复"把主线程卡 5s+"；指数退避让病态插件越来越不常被碰。

**仅 `TIMEOUT` 触发熔断**：`FILE_NOT_FOUND` / `PARSE_ERROR` / `PLUGIN_REJECTED` / `PATH_ESCAPE` / `IN_FLIGHT` / `STUCK` 都是确定性结果，重试有意义。

### 3.7 回执形态

```json
{
  "success": true,
  "reloaded": ["plugins/Foo/config.yml"],
  "bestEffort": ["plugins/Bar/config.yml"],
  "failed": [
    {"path": "plugins/Baz/config.yml", "code": "PARSE_ERROR", "message": "第 12 行缩进错误"}
  ],
  "applied": {"plugins/Foo/config.yml": {"md5": "...", "version": "...", "count": 12}},
  "costMs": 37,
  "error": ""
}
```

**整体成败判定式（唯一权威，消灭歧义）**：

```
success = failed.isEmpty() && error.isEmpty()
```

- `failed` 为空、`error` 为空 → 整体成功（即使全部是 `bestEffort`，也成功）。
- 任一失败 → 整体失败，但 `reloaded` 仍如实列出成功的部分（部分成功由调用方自行决策）。
- 请求级错误（路径数超限、payload 非法）不产生 `failed` 条目，写 `error` 字段 + `success=false`。

字段说明：

- `reloaded`：至少一个监听器返回 `ok()` 的路径。
- `bestEffort`：**没有任何监听器认领**的路径（通知已派发，无人确认）。算成功，单列。
- `failed`：任一监听器返回失败 / 超时 / 越界 / 在途 / 卡死。
- 同一路径既有成功又有失败 → 归入 `failed`（严格口径）。
- `applied`：自报字段，**缺省该键表示"未上报"**，不要写 0。
  键就是**路径原文**（含 `.`）：JSON 对象的键是字面量，编码器不得把 `.` 当成层级分隔符，
  否则 `plugins/Foo/config.yml` 会被写成 `plugins/Foo/config → yml`（E2E 按字面核对键名）。

### 3.8 收口：先全部提交，再轮转收割

直接照搬 Beacon `ReloadOutcomeCollector` 的两条经验（本仓不复用其代码，但语义必须一致）：

1. **先全部提交、再统一收**：逐个"提交→等结果→再提交"会让总耗时等于各插件之和；
   批量提交后总耗时才是"最慢的那个"。
2. **轮转收割**：不得按列表顺序死等第一个。第一个卡住的项会吃光预算，
   后面早已完成的项也被误判 `TIMEOUT`。做法：先非阻塞收割所有 `isDone` 的项，
   再对仍未完成的按剩余预算分片等待（单片 ≤ 10ms），直到全部落定或预算耗尽。

另需区分三种"没有结果"（运维要做的事完全不同）：

| 情形 | 语义 | 计入 |
|---|---|---|
| 提交被拒（池满/已关闭） | 根本**没进队列** | 回执 `failed` + `code=INTERNAL_ERROR`，message 说明"未执行" |
| 排队到超时仍未被取走 | 进了队列但**没轮到** | `TIMEOUT`，message 说明"未轮到执行" |
| 真跑过但没跑完 | 执行了但**没跑完** | `TIMEOUT`，message 说明"已执行未完成" |

### 3.9 超时与「自生自灭」

- 总预算 `timeout-ms`（默认 5000），从进入 reload 池开始计。
- 用 `Future.get(remaining)` 有界等待；超时后 `cancel(true)`（仅中断标记），**不 join、不等待线程退出**。
- 被取消任务若仍在跑，其回执结果**被丢弃**（避免迟到结果污染下一次请求）。
- 熔断记录由协调器在超时判定时写入，不依赖被取消任务自己上报。
- 主线程操作（平台 executor）：平台实现内部自带切主线程 + 有界等待；超时后同样不强杀。
- **在途表的 release 仍由任务自身 `finally` 执行**（见 §3.5），配合 `STUCK` 避免撒谎。

### 3.10 平台侧执行

```kotlin
// core/bridge/reload/PlatformReloadExecutor.kt（core 定义接口，平台实现）
interface PlatformReloadExecutor {
    /** 在平台主线程执行；由实现自己切线程并有界等待，超时返回 null。 */
    fun <T> onMainThread(task: () -> T, timeoutMs: Long): T?
}
```

- Bukkit 实现复用 `BukkitBridgeCommandHandler` 的 `submit(async=false) + CountDownLatch` 范式。
- 代理端（Bungee/Velocity）无主线程约束，直接执行。
- 监听器可自行决定是否切主线程；`PlatformReloadExecutor` 只提供给需要的监听器。

### 3.11 `watchedPaths()` 的取值时机与非法声明

- **每次派发时取其快照**（监听器可在运行期改变关注项）。javadoc 标注「会被高频调用，须轻量」。
- 注册时**立即校验**声明：非法条目（越界、含 NUL、空串）被丢弃 + `WARN`（带 listener 类名与非法值）；
  若全部非法 → 返回失效句柄，`getInactiveReason()==INVALID`、`getInactiveDetail()=="路径越界"`。
- 声明空集合 → 返回失效句柄，`getInactiveDetail()=="未声明关注路径"`（等价于不注册，但不静默）。

### 3.12 `config.yml` 新增

```yaml
bridge:
  reload:
    timeout-ms: 5000
    max-paths-per-request: 32
    # 监听器子任务 worker 池（与协调池分离，见 §3.1）
    worker-pool-size: 8
    stuck-after-ms: 30000
    circuit-breaker:
      enabled: true
      failure-window-ms: 60000
      open-duration-ms: 60000
      max-open-duration-ms: 600000
      failure-threshold: 1
      max-entries: 256
```

> **协调池容量不在此定义**：它是 `bridge.line.reload-pool-size`（见 `bridge-line-hardening` §3.6，
> 两处同义配置会互相覆盖）。本节只管重载自身的语义参数与 worker 池。

## 4. 任务拆分

- [x] `api`：`reload` 包 4 个类型（`ConfigReloadListener`、`ConfigReloadEvent`（含静态工厂）、`ConfigReloadResult`、`ConfigReloadErrorCode`）+ `ConfigReloadRegistry`；`BridgeServices` 加 `default configReloads()`
- [x] `api`：`ServerProbeAPI.configReloads()` + 独立降级单例 `UnavailableConfigReloadRegistry`
- [x] `core`：`ConfigReloadCoordinator`（编排：校验 → 熔断 → 在途 → 匹配 → 提交 worker 池 → 轮转收割 → 收口）
- [x] `core`：`ReloadPathGuard`、`ListenerMatcher`、`InFlightRegistry`（含 `STUCK`）、`CircuitBreaker`（并发安全 + 指数退避）
- [x] `core`：`ConfigReloadRoute`（`InboundRoute`，`order=100`，`executor=RELOAD`，`claims` 纯同步）
- [x] `core`：`PlatformReloadExecutor` 接口 + 默认空实现
- [x] `platform-bukkit`：`BukkitReloadExecutor`
- [x] `platform-bungee` / `platform-velocity`：直通实现
- [x] `core`：经 `BridgeConfigSource.reload()` 读取配置（不直读 `ProbeConfig`）
- [x] 单测：成功 / 无人认领 / 插件拒绝 / 插件抛异常 / 超时 / 熔断 / 熔断指数退避 / 熔断容量拒绝 / 越界 / 在途 / 卡死 `STUCK` / 全量哨兵互斥 / 幂等 / 部分成功 / 路径超限 / 非法声明注册被拒
- [x] 单测：`CircuitBreaker` 并发压测（多线程 `recordTimeout` + `isOpen`，无 CME、无丢失）
- [x] 单测：收口"已完成项不被慢项拖入超时"（1 快 1 慢，快的必须 `ok`）
- [x] 单测：**4 并发 × 每请求 4 path 在 `timeout-ms` 内全部返回、无 `TIMEOUT`**（验证协调池与 worker 池分离）
- [x] E2E：fixture 下发 `core.config_reload` → 断言回执结构与 `costMs`，并断言监听器实际被调用（计数）
- [x] ADR-30（实际取号 **ADR-31**：本仓 ADR-30 已被「移除内置业务集成」占用）
- [x] 文档：PRD、ARCHITECTURE、`docs/API.md`、CHANGELOG

**文件归属（并行开发隔离）**

| 归属 | 文件 |
|---|---|
| 本功能独占 | `api/.../api/reload/*`、`core/.../bridge/reload/*`、`platform-*/.../reload/*` |
| 与其它功能共改 | `core/.../bridge/BridgeClient.kt`（仅加一行 `addRoute`）、`api/.../api/ServerProbeAPI.java`（**只在 FR-30 已预置的访问器上填实现，不改签名**）、`config.yml` 资源 |

## 5. 验收标准

| 场景 | 判据（可自动化，避免顺序无关的弱断言） |
|---|---|
| 成功 | 监听器返回 `ok()` → `reloaded` 含该 path、`failed` 空、`success=true`、`costMs >= 0`、监听器调用计数 +1 |
| 无人认领 | 无监听器 → `bestEffort` 含该 path、`reloaded` 与 `failed` 均空、`success=true` |
| 插件拒绝 | 返回 `failed(PLUGIN_REJECTED, "...")` → `failed[0].code=PLUGIN_REJECTED` |
| 插件抛异常 | 监听器 `throw` → `failed[0].code=PLUGIN_THREW`，整段不失败、不抛 |
| 部分成功 | 两个 path 一成一败 → `reloaded` 与 `failed` 各一条，`success=false`（判定式见 §3.7） |
| 超时 | 注入 `timeout-ms=200` + 监听器 `latch.await()`（永不返回）→ 回执 `TIMEOUT`；**期间向 cmd 池发一条 `bridge_status` 仍立即返回**（证明读线程与命令链都活着） |
| 池分离 | 4 个并发 `config_reload` × 每条 4 个 path（协调池 4 + worker 池 8）→ 全部在预算内返回、**互不产生 `TIMEOUT`**（N-1 的探针） |
| 快慢混合 | 1 个秒回 + 1 个卡死 → 秒回的必须 `ok`，不得被慢项拖成 `TIMEOUT`（验证轮转收割） |
| 熔断 | 超时后窗口内再请求同 path → `TIMEOUT` 且监听器执行计数**不增** |
| 熔断退避 | 连续 3 次超时 → `openDuration` 依次翻倍（单测直接读 breaker 快照断言） |
| 熔断容量 | 灌满 `maxEntries` 后新 path → `rejectedEntries` +1，已有条目**不被淘汰** |
| 熔断并发 | 8 线程并发 `recordTimeout`/`isOpen` 1000 次 → 无异常、计数准确 |
| 越界 | `../../etc/passwd`、`/etc/passwd`、`plugins/../secret`、含 NUL → 均 `PATH_ESCAPE` |
| 在途 | 并发同 path → 第二个 `IN_FLIGHT`，监听器仅执行 1 次 |
| 卡死 | 任务不响应中断且超时 → 后续同 path 返回 `STUCK`（不是 `IN_FLIGHT`），message 含"疑似卡死" |
| 全量互斥 | 全量在途时单路径 → `IN_FLIGHT`；单路径在途时全量 → `IN_FLIGHT` |
| 幂等 | 连续两次成功重载：**逐字段结构化相等**（排除 `costMs`），监听器调用计数恰 +2，累加型自报字段不翻倍 |
| 路径上限 | 33 个 path（上限 32）→ `success=false`、`error` 非空、`failed` 为空 |
| 非法声明 | 监听器声明 `../x` → 注册返回失效句柄、原因为 `INVALID`、日志有 WARN |
| 真机 | Paper 上真实插件配合，fixture 收到完整回执；E2E 逐项断言汇总为 PASS |

## 6. 风险 / 待定

1. **超时后仍在执行**：Bukkit 不允许强杀主线程，该次重载会继续占用主线程。缓解：熔断指数退避（越来越不常碰）+ `bridge_status` 暴露 `reload.stuck`。这是**无法根除**的，ADR-30 必须写明。
2. **`plugins/` 限定过严**：部分插件把配置放在自定义目录。后续可加 `bridge.reload.allowed-roots` 白名单；本轮不做，避免扩大攻击面。
3. **部分成功语义**：本 spec 定 `success=false`（严格），但 `reloaded` 如实列出成功项。JM 需自行决策是否回滚。
4. **无人认领算成功**：产品决策。风险是 JM 误以为已生效——字段名 `bestEffort` 已尽量自解释，JM 展示层应显式标注。
5. **`stuck-after-ms` 默认 30s**：短于该值的卡死仍报 `IN_FLIGHT`。取值需与 `timeout-ms` 联动（≥ 6× timeout）。
