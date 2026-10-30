# 功能规格：桥对外契约与外部注册

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-30　·　分支：feature/bridge-external-api


## 1. 背景与目标

ServerProbe 的业务 Provider 此前只能由仓内内置模块（MultiCurrencyEconomy / AllinInventorySync）注册，外部插件要接入必须反射 `BeanContainer` 或把 Provider 写进 ServerProbe 源码。本功能把桥契约下沉到 `api` 模块（纯 Java 8），并提供一个**零 IoC 依赖、永不抛异常、永不返回 null、可安全缓存**的静态门面，使任意第三方插件能在自身 `onEnable` 注册业务域、`onDisable` 注销。

目标读者是**外部插件开发者**：他们只看 `api` 模块的 javadoc 与签名就应能正确接入，不需要读 `core` 源码。

## 2. 需求

### 范围内

- `api` 新增 `top.wcpe.mc.plugin.serverprobe.api.bridge`：`BusinessProvider`、`BusinessHost`、`BridgeCommand`、`BridgeCommandResult`、`ProviderHealth`。
- `api` 新增 `top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI` 静态门面 + `BridgeServices` 聚合接口。
- `api` 新增**顶层** `Registration` + `InactiveReason`（`api` 包），供 `api.bridge` 与 `api.reload` 共用（**不在 `api.bridge` 包里**，否则 `api.reload` 会反向依赖 `api.bridge`）。
- `core`：契约删除，`BusinessHost` → `BusinessHostImpl`，全部改用 `api` 类型；保留 `unregister` 语义。
- `plugin`：新增 `@Service BridgeFacadeInstaller` 负责 install/uninstall。
- 归档 ADR-29：承认「外部插件自注册」为正式用法，说明与 ADR-13/15/18 的分工。

### 范围外

- 不引入 Kotlin 到 `api`（ADR-13 继续有效）。
- **治理契约不进 `api`**：`BridgeCommand` 的 `target`/`reason`、`BridgeCommandHandler` 是平台治理专用，外部插件永远用不到。治理指令继续留在 `core.bridge`，由 `platform-*`（已依赖 core）实现。
- 不做 Provider 的 classloader 隔离、不做跨版本协议协商。

## 3. 设计

### 3.1 硬约束与可判定规则

| 约束 | 判定方式 |
|---|---|
| 纯 Java 8，零第三方依赖 | `api` 不新增任何依赖；不使用 `List.of`/`Map.of`/`var` |
| 门面方法永不返回 null | 未就绪时返回**稳定委派代理**（见 3.3），不是快照 |
| 对外 API 一律不抛受检异常 | 无 `throws` 子句；运行时异常也不主动抛 |
| 集合/字符串非 null、不可变 | 空集合优于 null；`Collections.unmodifiable*` 包装 |
| 新增能力只加 `default` 方法 | 已发布签名不改；default 必须返回独立单例（见 3.4 规则） |

**null 规则（唯一权威，其余 spec 以此为准）**：

- 集合、字符串：**一律非 null**，用空值表达"没有"。
- 对象：仅"存在性"语义允许 null，且必须在 javadoc 逐条写 `@return …；无则为 null`。
  允许 null 的读口只有 3 个：`ProbeReadApi.latestSnapshot()`、`ProbeReadApi.lastStartupProfile()`（既有）、
  `ConfigReloadResult.getCode()`（成功时无错误码）。
- `Registration.getInactiveReason()` **改为枚举** `InactiveReason`，不再用自由文本 + null。

**命名规则**：

- 接口的**动作/查询方法**无 `get` 前缀（`domain()`、`domains()`、`register()`、`businessHost()`）。
- **值对象**的属性访问器用 `get` 前缀（`getAction()`、`getOutput()`）。
- **布尔值一律 `get` 前缀**（`getSuccess()`、`getActive()`），遵循 ADR-13：`isX()` 在 Kotlin 侧会暴露为属性 `isX`，
  导致消费方写 `.x` 失败。唯一例外是接口上的 `isAvailable()`——它是纯静态门面的可用性查询，
  不参与值对象互操作，且已成事实标准，保留 `is` 并在 javadoc 标注。

### 3.2 契约类型

```java
package top.wcpe.mc.plugin.serverprobe.api;

/**
 * 通用注册句柄，供业务域与配置重载监听器共用。
 *
 * <p>{@link #close()} 幂等；陈旧句柄 close 不得影响后来注册的同键实现。</p>
 */
public interface Registration extends AutoCloseable {

    /** 本句柄当前是否有效（未被关闭、未被同键新注册顶掉、探针未卸载）。 */
    boolean getActive();

    /** 失效原因；有效时为 {@link InactiveReason#NONE}，永不为 null。 */
    InactiveReason getInactiveReason();

    /**
     * 失效的人可读细节（如"探针未就绪"），无则为空串，永不为 null。
     *
     * <p>程序判断请用 {@link #getInactiveReason()}，不要解析本字段。</p>
     */
    String getInactiveDetail();

    /** 注销并释放。幂等，不抛异常。 */
    @Override
    void close();

    /** 句柄失效原因枚举。 */
    enum InactiveReason {
        /** 有效（未失效）。 */
        NONE,
        /** ServerProbe 尚未就绪或已卸载。 */
        PROBE_UNAVAILABLE,
        /** 调用方主动 close。 */
        CLOSED,
        /** 同键被更新的注册顶替。 */
        REPLACED,
        /** 注册参数非法（如 domain 为空、路径越界）。 */
        INVALID
    }
}
```

> **为什么上提到 `api` 顶层**：`api.bridge` 与 `api.reload` 都要用它。放在 `api.bridge` 会逼
> `api.reload` 反向依赖 `api.bridge`，与分包初衷相悖。

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一个业务域的对接提供者。
 *
 * <h2>实现约定（外部插件必读）</h2>
 * <ul>
 *   <li><b>线程安全</b>：会在 ServerProbe 的独立业务线程池被调用，可能并发进入。</li>
 *   <li><b>绝不抛异常</b>：失败返回 {@link BridgeCommandResult#fail(String)}。抛出的异常会被兜住
 *       并转为失败回执，但会丢失可读原因。</li>
 *   <li><b>调用语义是「至多等待、可能已生效」</b>：ServerProbe 最多等待
 *       {@code bridge.dispatch.timeout-ms}（默认 5000ms）。<b>超时后动作可能仍然执行成功</b>
 *       （无法强杀线程）。因此：</li>
 *   <li><b>写动作必须自带幂等键</b>：payload 中约定字段 {@code taskId}（字符串，全局唯一），
 *       按它去重。不得假设"没收到回执 = 没执行"。</li>
 *   <li><b>不要在本方法内再次调用 {@link BusinessHost#dispatch}</b>：会造成池级等待放大。
 *       需要跨域协作时改为同步组合，或注册时明确声明。</li>
 * </ul>
 *
 * <h2>实现示例</h2>
 * <pre>{@code
 * // Java
 * public final class ActivityProvider implements BusinessProvider {
 *     public String domain() { return "activity"; }
 *     public Map<String, Object> manifest() {
 *         // 值只能是 Map/List/String/数字/布尔（见下方「序列化约束」）。
 *         // 用 api.protocol.ActionSpec.toMap() 声明动作，面板才能拿到每个动作的字段与只读标志。
 *         List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
 *         actions.add(ActionSpec.of("settle", Arrays.asList("activityId", "rewardId"), false).toMap());
 *         Map<String, Object> m = new LinkedHashMap<String, Object>();
 *         m.put("actions", actions);
 *         return m;
 *     }
 *     public BridgeCommandResult dispatch(String action, String payloadJson) {
 *         if ("progress".equals(action)) { ... return BridgeCommandResult.ok(json); }
 *         return BridgeCommandResult.fail("未知动作：" + action);
 *     }
 * }
 * }</pre>
 * <pre>{@code
 * // Kotlin —— 注意 Java 方法名是 domain()，不是属性 domain
 * class ActivityProvider : BusinessProvider {
 *     override fun domain(): String = "activity"
 *     override fun manifest(): Map<String, Any> =
 *         mapOf("actions" to listOf(ActionSpec.of("settle", listOf("activityId", "rewardId"), false).toMap()))
 *     override fun dispatch(action: String, payloadJson: String): BridgeCommandResult = ...
 * }
 * }</pre>
 */
public interface BusinessProvider {

    /** 业务域命名空间（如 economy / inventory / activity）；空串或 null 的注册会被拒绝。 */
    String domain();

    /** 本域能力清单；不得返回 null（无能力返回空 Map）。单域异常不影响其它域。 */
    Map<String, Object> manifest();

    /**
     * 执行一条本域业务动作。
     *
     * @param action      动作名
     * @param payloadJson 结构化参数 JSON；未提供时为空串，永不为 null
     * @return 执行结果，不得返回 null；业务输出放 {@link BridgeCommandResult#getOutput()}
     */
    BridgeCommandResult dispatch(String action, String payloadJson);
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 业务域宿主门面。
 *
 * <p>经 {@link top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI#businessHost()} 取得。
 * <b>该返回值是稳定代理，可安全缓存为字段</b>——探针后续 install/uninstall 都会被自动感知。</p>
 *
 * <p>所有方法线程安全，均不抛异常。</p>
 */
public interface BusinessHost {

    /**
     * 注册一个业务 Provider。
     *
     * <p>同 domain 重复注册以最新为准：旧句柄立即失效（{@code getInactiveReason()==REPLACED}）。
     * 探针未就绪、provider 为 null、domain 为空时返回 <b>失效句柄</b>，绝不抛异常。</p>
     *
     * @param provider 业务 Provider
     * @return 注册句柄；close 幂等且不影响后来注册的同域 Provider
     */
    Registration register(BusinessProvider provider);

    /**
     * 按实例注销一个 Provider（兼容既有调用点）。
     *
     * <p>仅当当前域仍指向该实例时才移除，避免热重载后旧实例回调误删新实例。
     * 等价于对该实例此前取得的句柄调用 {@code close()}，但允许调用方只持有 provider 引用。</p>
     *
     * @return true=已移除；false=该实例并非当前注册者
     */
    boolean unregister(BusinessProvider provider);

    /** 当前已注册的业务域（不可变、按字典序、永不为 null）。 */
    Set<String> domains();

    /**
     * 汇总各域能力清单。
     *
     * <p>逐域隔离：某域 {@link BusinessProvider#manifest()} 抛异常时，该域呈现为
     * {@code {"error": "..."}}，不影响其它域。</p>
     *
     * @return domain → manifest 的不可变映射，永不为 null
     */
    Map<String, Map<String, Object>> manifest();

    /**
     * 直接派发一条业务动作。
     *
     * <p><b>可能阻塞至多 {@code bridge.dispatch.timeout-ms}（默认 5s），禁止在主线程调用。</b>
     * 主线程/玩家事件链路请改用 {@link #dispatchAsync}。</p>
     *
     * @return 执行结果；域未注册或探针未就绪时返回失败结果，不抛异常
     */
    BridgeCommandResult dispatch(String domain, String action, String payloadJson);

    /**
     * 异步派发（推荐的跨插件调用入口）。
     *
     * <p>Java 8 {@code CompletableFuture}；超时由实现内部统一处理，Future 以失败结果完成而非异常完成。</p>
     */
    CompletableFuture<BridgeCommandResult> dispatchAsync(String domain, String action, String payloadJson);

    /** 该域当前健康状态；域未注册返回 {@link ProviderHealth#unknown()}。不阻塞、不做 IO。 */
    ProviderHealth healthOf(String domain);
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条业务指令的不可变值对象。
 *
 * <p>由 Worker 下发的 {@code command} 帧解析而来；业务 Provider 通常只需读
 * {@link #getAction()} 与 {@link #getPayload()}。</p>
 *
 * <p>用静态工厂 + 链式 with 而非 6 参构造器：全 String 的长参数列表传反顺序编译器不报错，
 * 且 Kotlin 消费方无法用具名参数（ADR-13 的已知限制）。</p>
 */
public final class BridgeCommand {

    private final String action;
    private final String payload;
    private final String requestId;
    private final String domain;

    private BridgeCommand(String action, String payload, String requestId, String domain) { ... }

    /**
     * 业务命令（Provider 侧唯一需要关心的形态）。
     *
     * @param action      动作名，不得为 null
     * @param payloadJson 参数 JSON，null 视为空串
     */
    public static BridgeCommand of(String action, String payloadJson) {
        return new BridgeCommand(nz(action), nz(payloadJson), "", "");
    }

    /** 完整形态（桥内核解析下行帧时使用）。 */
    public static BridgeCommand of(String action, String payloadJson, String requestId, String domain) { ... }

    public BridgeCommand withRequestId(String requestId) { ... }
    public BridgeCommand withDomain(String domain) { ... }

    /** 指令动作，永不为 null。 */
    public String getAction() { ... }

    /** 业务结构化参数 JSON，永不为 null（无参数时为空串）。 */
    public String getPayload() { ... }

    /** 关联回执标识，永不为 null（无则为空串）。 */
    public String getRequestId() { ... }

    /** 业务域，永不为 null（治理指令为空串）。 */
    public String getDomain() { ... }

    @Override public boolean equals(Object o) { ... }
    @Override public int hashCode() { ... }
    @Override public String toString() { ... }

    private static String nz(String v) { return v == null ? "" : v; }
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一条业务指令的执行结果，不可变。
 *
 * <p>{@link #getOutput()} 与 {@link #getError()} 永不为 null（无内容时为空串），调用方无需判空。</p>
 */
public final class BridgeCommandResult {

    private static final BridgeCommandResult EMPTY_OK =
        new BridgeCommandResult(true, "", "");

    private final boolean success;
    private final String output;
    private final String error;

    /** 成功且无输出。 */
    public static BridgeCommandResult ok();

    /** 成功并带输出；null 视为空串。 */
    public static BridgeCommandResult ok(String output);

    /** 失败并带可读原因；null 视为空串。 */
    public static BridgeCommandResult fail(String error);

    public boolean getSuccess();

    /** 成功输出；无则空串，永不为 null。 */
    public String getOutput();

    /** 失败原因；无则空串，永不为 null。 */
    public String getError();

    @Override public boolean equals(Object o) { ... }
    @Override public int hashCode() { ... }
    @Override public String toString() { ... }
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 业务域健康状态，不可变。
 *
 * <p>应如实回报：不可用时给出可读原因，不要用 AVAILABLE 掩盖内部故障。</p>
 */
public final class ProviderHealth {

    /** 健康等级。 */
    public enum Status { AVAILABLE, UNAVAILABLE, INITIALIZING, DEGRADED, UNKNOWN }

    public static ProviderHealth available();

    public static ProviderHealth unavailable(String reason);

    public static ProviderHealth initializing(String detail);

    public static ProviderHealth degraded(String reason);

    public static ProviderHealth unknown();

    /** 完整工厂：显式带时间戳（当 Provider 需要报告"自何时起如此"）。 */
    public static ProviderHealth of(Status status, String reason, String detailJson, long sinceMs);

    public Status getStatus();

    /** 可读原因/详情；无则空串，永不为 null。 */
    public String getReason();

    /** 自定义结构化详情 JSON；无则空串，永不为 null。 */
    public String getDetailJson();

    /** 状态起始时间（epoch 毫秒）；未知时为 0。 */
    public long getSinceMs();

    @Override public boolean equals(Object o) { ... }
    @Override public int hashCode() { ... }
    @Override public String toString() { ... }
}
```

> `health()` 方法本身**不声明在 `BusinessProvider` 上**，由 `provider-health` spec 以 `default` 方法追加。
> 这样本 spec 可独立编译交付。追加规则见 §3.4。

### 3.3 门面：稳定委派代理

```java
package top.wcpe.mc.plugin.serverprobe.api;

/**
 * ServerProbe 对外门面的唯一入口。
 *
 * <h2>可安全缓存</h2>
 * <p>所有访问器返回的是<b>稳定代理</b>，不是当时状态的快照。因此下面是正确用法：</p>
 * <pre>{@code
 * // onEnable 里缓存一次，之后探针才加载也照样生效
 * private final BusinessHost host = ServerProbeAPI.businessHost();
 * private Registration reg;
 *
 * public void onEnable() {
 *     reg = host.register(new MyProvider());   // 探针未就绪时 reg 失效，见下
 * }
 * }</pre>
 *
 * <h2>探针后加载怎么办</h2>
 * <p>若注册时探针尚未就绪，句柄 {@code getActive()==false}、原因为
 * {@code PROBE_UNAVAILABLE}。两种处理：</p>
 * <ul>
 *   <li>什么都不用做（**推荐**）：代理可安全缓存为字段，探针后加载也会自动生效；</li>
 *   <li>若确实需要等到就绪（**仅限异步/独立线程**，绝不能在服务器主线程），用 {@link #awaitAvailable(long)} 或轮询 {@link #isAvailable()}；在主线程调它会睡满超时且永远等不到（探针 install 也需主线程）。</li>
 *   <li>或直接忽略——探针不存在时也没有业务可接。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <p>探针 ENABLE 时 {@link #install}，DISABLE 时 {@link #uninstall}。{@code uninstall()} 会：
 * 清空注册表，并把所有在册句柄置为失效（{@code PROBE_UNAVAILABLE}）；再次 install 后注册表为空，
 * <b>不复活</b>旧句柄。</p>
 *
 * <p><b>线程安全</b>：所有静态方法可被任意线程调用，均不抛异常。</p>
 */
public final class ServerProbeAPI {

    private static volatile BridgeServices services;

    private ServerProbeAPI() {}

    /** 探针是否已就绪。实时查询，可用于轮询。 */
    public static boolean isAvailable() { return services != null; }

    /**
     * 有界等待探针就绪。
     *
     * @param timeoutMs 最长等待毫秒；{@code <= 0} 表示只查当前状态、不阻塞
     * @return true=已就绪；false=超时仍未就绪
     */
    public static boolean awaitAvailable(long timeoutMs) { ... }

    /** 业务域宿主门面。稳定代理，永不 null，可缓存。 */
    public static BusinessHost businessHost() { ... }

    /**
     * 配置重载监听器注册表。稳定代理，永不 null，可缓存。
     *
     * <p>由 config-reload 功能填充实现；未接线时返回降级注册表（注册得失效句柄）。</p>
     */
    public static ConfigReloadRegistry configReloads() { ... }

    /**
     * 业务事件上报入口。稳定代理，永不 null，可缓存。
     *
     * <p>由 event-topic-subscription 功能填充实现；未接线时返回降级 sink（上报返回 false）。</p>
     */
    public static BusinessEventSink eventSink() { ... }

    /**
     * 桥运行状态只读视图。稳定代理，永不 null，可缓存。
     *
     * <p>由 bridge-line-hardening 功能填充实现；未接线时返回降级视图（全零/STOPPED）。</p>
     */
    public static BridgeStatus bridgeStatus() { ... }

    /**
     * 注册探针实现。
     *
     * <p><b>仅供 ServerProbe 自身调用。</b>实现层校验调用方 code source：非本插件来源的调用被忽略并 WARN，
     * 防止第三方插件劫持门面（伪造 BusinessHost 可拦截经济写命令）。</p>
     */
    public static void install(BridgeServices impl) { ... }

    /** 注销探针实现；幂等。仅供 ServerProbe 自身调用。 */
    public static void uninstall() { ... }
}
```

**稳定代理的实现要点**（`api` 包内，包私有）：

```java
// 每个子门面一个稳定代理，方法内实时查 services，未就绪则转发到独立降级单例。
private static final BusinessHost BUSINESS_HOST = new BusinessHost() {
    public Registration register(BusinessProvider p) { return delegate().register(p); }
    public boolean unregister(BusinessProvider p)     { return delegate().unregister(p); }
    public Set<String> domains()                      { return delegate().domains(); }
    public Map<String, Map<String, Object>> manifest(){ return delegate().manifest(); }
    public BridgeCommandResult dispatch(String d, String a, String p) { return delegate().dispatch(d, a, p); }
    public CompletableFuture<BridgeCommandResult> dispatchAsync(String d, String a, String p) {
        return delegate().dispatchAsync(d, a, p);
    }
    public ProviderHealth healthOf(String d)          { return delegate().healthOf(d); }

    private BusinessHost delegate() {
        BridgeServices s = services;
        return s == null ? UnavailableBusinessHost.INSTANCE : s.businessHost();
    }
};
```

**关键**：代理本身无状态，因此外部插件缓存它永不失效；`services` 的装载/卸载对已缓存引用立即可见。

### 3.4 `BridgeServices` 演进规则

```java
package top.wcpe.mc.plugin.serverprobe.api;

/**
 * 探针实现向 api 注册的服务集合。
 *
 * <p>用聚合接口而非多个 install 参数：将来新增子门面只加 default 方法，不破 install 签名。</p>
 */
public interface BridgeServices {

    BusinessHost businessHost();

    // 后续功能以 default 追加，一律返回**独立的**降级单例（见规则 2）
    default ConfigReloadRegistry configReloads() { return UnavailableConfigReloadRegistry.INSTANCE; }
    default BusinessEventSink eventSink()        { return UnavailableBusinessEventSink.INSTANCE; }
    default BridgeStatus bridgeStatus()          { return UnavailableBridgeStatus.INSTANCE; }
}
```

**演进规则（违反即破约）**：

1. **只加 `default` 方法**，绝不改已有方法签名/返回类型。
2. default **不得**写成 `UnavailableBridgeServices.INSTANCE.同方法()`——那会在未覆写时递归到自身，
   抛 `StackOverflowError`。必须返回该子门面的**独立**降级单例。
3. 降级单例的行为：查询类返回空值/未知；注册类返回 `InactiveReason.PROBE_UNAVAILABLE` 的失效句柄；
   上报类返回 `false`。**首次被调用时 WARN 一次**（去重），避免"忘了接线"变成静默失效。
4. 新增子门面必须登记到 §3.5 归属表，否则视为破约。
5. `install(impl)` 后，`bridge_status.wired` 列出**已真正接线**的能力。产出机制（可行且免自报）：
   用 `impl.getClass().getDeclaredMethods()` 的返回类型集合，与四个子门面接口逐一比对——
   返回了非降级实现即视为 wired。验收断言：`plugin` 若漏接某个 `override`，`wired` 中对应项缺失。

**`api` 内部包依赖（允许的边，仅此四条；新增边须改本节并说明理由）**：

```
api            （ServerProbeAPI / BridgeServices / Unavailable*）
 ├─→ api.bridge   （BusinessHost / BusinessProvider / BusinessEventSink / BridgeStatus / ProviderHealth …）
 ├─→ api.reload   （ConfigReload*）
 └─→ api.protocol （BusinessProtocol / ActionSpec / Actions —— 见 business-self-registration spec）
api.bridge ─→ api.protocol （单向：manifest 产出的动作声明类型）
api.reload ─→ api （仅用顶层 Registration，不得反向依赖 api.bridge）
```

**`api.protocol` 不得反向依赖 `api.bridge`**：它是纯数据容器与常量，
连 javadoc 都不应用 `{@link}` 指向 `BusinessProvider`（避免编译期依赖边）——写纯文字名称即可。

**`manifest()` 的序列化约束（必须写进 javadoc）**：

core 的 JSON 编码（`ConfigJsonCodec`）对顶层 Map 走 `fillSection` 逐键写入、**只递归 `Map`**，
其余值原样 `section.set`。因此：

- `manifest()` 返回的嵌套值只能是 `Map`、`List`（元素为 String/数字/布尔/`Map`）、String、数字、布尔。
- **不得放入 POJO**（如 `ActionSpec` 实例本身）——编码结果不可预期，且不会有任何报错，
  是典型的"静默失效"。要用 `ActionSpec.toMap()` 转换后再放入。

**降级单例清单（全部放在 `api` 包，包私有枚举单例）**：

| 单例 | 实现 | 关键行为 |
|---|---|---|
| `UnavailableBusinessHost` | `BusinessHost` | `register`→失效句柄 `PROBE_UNAVAILABLE`；`domains()`→空集；`manifest()`→空映射；`dispatch`→失败；`dispatchAsync`→已完成失败；`healthOf`→`unknown()` |
| `UnavailableConfigReloadRegistry` | `ConfigReloadRegistry` | `register`→失效句柄 `PROBE_UNAVAILABLE` |
| `UnavailableBusinessEventSink` | `BusinessEventSink` | `emit`/`emitReliable`→`false` |
| `UnavailableBridgeStatus` | `BridgeStatus` | `getState()`→`STOPPED`；计数→0；字符串→空串 |
| `UnavailableBridgeServices` | `BridgeServices` | **只实现 `businessHost()`**，绝不覆写任何 `default`（防递归） |

> 每个 `default` 直接返回上表对应单例，**不经 `UnavailableBridgeServices` 转发**——这是规则 2 的落地方式。

**二进制兼容前提**（写清以便验收）：追加 `default` 方法对既有实现二进制兼容，前提是既有实现
没有声明同名同参但返回类型不兼容的方法。本次新增的 `health()` 满足该前提（现有实现无 `health`）。

### 3.5 跨 spec 的类型归属

| 类型 | 模块 | 包 | 归属 spec |
|---|---|---|---|
| `Registration` + `InactiveReason` | api | `api`（顶层） | 本 spec |
| `BusinessProvider` / `BusinessHost` | api | `api.bridge` | 本 spec |
| `BridgeCommand` / `BridgeCommandResult` | api | `api.bridge` | 本 spec |
| `ProviderHealth` | api | `api.bridge` | 本 spec（类型）；provider-health 追加 `health()` default |
| `BusinessEventSink` | api | `api.bridge` | event-topic-subscription |
| `ConfigReloadListener` / `ConfigReloadEvent` / `ConfigReloadResult` / `ConfigReloadErrorCode` / `ConfigReloadRegistry` | api | `api.reload` | config-reload |
| `BridgeStatus` | api | `api.bridge` | bridge-line-hardening |
| `ServerProbeAPI` / `BridgeServices` / `Unavailable*` | api | `api` | 本 spec |
| `OutboundFrame` / `InboundRoute` / `BridgeKernel` / `FrameSender` | core | `core.bridge` | bridge-line-hardening（**内部，不对外**） |
| `BridgeCommandHandler`（治理） | core | `core.bridge` | 本 spec（**留在 core，不进 api**） |

**下行路由 `order` / 执行池分配**（唯一权威，后续 spec 只填实现不改号）：

| order | action | 执行池 | 归属 spec |
|---|---|---|---|
| 100 | `config_reload` | RELOAD | config-reload |
| 110 | `business_status` | COMMAND | provider-health |
| 120 | `subscribe` / `unsubscribe` | COMMAND | event-topic-subscription |
| 130 | `bridge_status` | COMMAND | bridge-line-hardening |
| 500 | 业务域派发（兜底） | COMMAND | 本 spec |
| 900 | 平台治理（兜底） | COMMAND | 本 spec |

### 3.6 `core` 侧改造

| 现有 | 改造后 |
|---|---|
| `core.bridge.BusinessProvider` | 删除，改用 `api.bridge.BusinessProvider` |
| `core.bridge.BusinessHost`（`@Service` 类） | 拆为 `BusinessHostImpl`（`@Service`，实现 `api.bridge.BusinessHost`） |
| `core.bridge.BridgeCommandResult` | 删除，改用 `api.bridge.BridgeCommandResult` |
| `core.bridge.BridgeCommand` | **保留**（治理专用，含 target/reason），仅供 `BridgeCommandHandler` 使用 |
| `core.bridge.BridgeCommandHandler` | **保留**（治理专用） |
| `BridgeClient` / `BridgeCommandRegistry` | 治理路径改 import；业务路径改用 api 类型 |

`BusinessHostImpl` 要点：

- `ConcurrentHashMap<String, Entry>`，`Entry` 持有 `provider` 与 `@Volatile active`；
  `register`/`unregister`/`close` 全部走 `compute`/`computeIfPresent`，保证「新已就位、旧仍报 active」不可能发生。
- `register` 前置校验：`provider == null` → 失效句柄 `INVALID`；`domain().isEmpty()` → 失效句柄 `INVALID`。
- `manifest()` 逐域 `runCatching`，单域失败呈现 `{"error":"..."}`。
- `dispatch` 保持「独立 daemon 池 + 有界超时」，但：
  - 显式 `catch (InterruptedException) { Thread.currentThread().interrupt(); return fail("已中断") }`；
  - **重入保护**：`ThreadLocal<Boolean> inDispatch`，已在池线程内再次 `dispatch` → 直接内联执行，
    不占新并发位（避免有界池下的嵌套死锁）。语义写进 javadoc。
- `dispatchAsync` 用 `CompletableFuture.supplyAsync(..., executor)`，超时以失败结果完成。

### 3.7 `plugin` 侧装配

**装配点不能是 `ServerProbe.kt`**：该 object 是 IOC 容器启动者，不是受管 bean，没有 `@Inject`/`@PostEnable`。

```kotlin
// plugin/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/BridgeFacadeInstaller.kt（新增）
@Service
class BridgeFacadeInstaller {

    @Inject
    lateinit var businessHost: BusinessHostImpl

    @PostConstruct
    fun install() {
        ServerProbeAPI.install(object : BridgeServices {
            override fun businessHost(): BusinessHost = businessHost
            // 后续功能在此追加 override
        })
        ProbeLogger.info("对外桥门面已装载：${ServerProbeAPI::class.java.name}")
    }

    @PreDestroy
    fun uninstall() {
        ServerProbeAPI.uninstall()
    }
}
```

- 用 `@Service + @PostConstruct/@PreDestroy`（与 `BusinessHost` 的 `@PreDestroy` 同范式），
  保证 IOC 就绪后装载、销毁前卸载。
- `uninstall()` 必须在所有业务 Provider 的 `@PreDestroy` 之前或之后都能安全工作（幂等 + 失效句柄）。

### 3.8 与既有门面的关系

现存三个入口，必须写清避免外部开发者 import 错：

| 入口 | 现状 | 本次处置 |
|---|---|---|
| `top.wcpe.mc.plugin.serverprobe.ServerProbeApi` | Kotlin object，`read()` 返回 `ProbeReadApi?` | **保留**，补 `@JvmStatic`（纯增量），javadoc 注明「只读数据请用它；业务注册请用 `api.ServerProbeAPI`」 |
| `top.wcpe.mc.plugin.serverprobe.ServerProbeStorageApi` | 抛 `IllegalStateException` | 不动 |
| `top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI`（新） | 永不 null、稳定代理 | **业务注册的唯一推荐入口** |

## 4. 任务拆分

- [ ] `api`：`Registration` + `InactiveReason`（顶层）
- [ ] `api`：`bridge` 包 5 个类型（`BusinessProvider`/`BusinessHost`/`BridgeCommand`/`BridgeCommandResult`/`ProviderHealth`）
- [ ] `api`：`ServerProbeAPI` + `BridgeServices` + 稳定代理 + 3 个 `Unavailable*` 独立单例
- [ ] `api`：`install` 调用方校验（code source 比对，不匹配则忽略 + WARN）
- [ ] `core`：删除 `BusinessProvider`/`BusinessHost`/`BridgeCommandResult`，保留治理契约
- [ ] `core`：`BusinessHostImpl`（句柄语义、逐域 manifest 隔离、中断处理、重入内联、`dispatchAsync`）
- [ ] `plugin`：`BridgeFacadeInstaller`（`@Service` + `@PostConstruct/@PreDestroy`）
- [ ] `plugin`：`ServerProbeApi` 补 `@JvmStatic` + javadoc 指向新门面
- [ ] `integration-*`：两个 Provider 改 import；`refresh()` 改为持有 `Registration` 字段 + `close()` 旧句柄
- [ ] `e2e/harness`：改为经 `api` 自注册的外部消费者
- [ ] 单测：域路由 / 未注册 / 抛异常 / 卡死 / 重复注册顶替 / 陈旧句柄 / `unregister` 返回值 / 中断 / 重入内联 / 逐域 manifest 隔离 / null 与空 domain 拒绝
- [ ] 单测：**降级不抛**（未 install 时全部方法安全返回）、**代理可缓存**（先缓存后 install，注册生效）
- [ ] 单测：`uninstall` 后所有句柄失效且原因为 `PROBE_UNAVAILABLE`，再 install 不复活
- [ ] E2E：外部 harness 注册 `domain=activity` → fixture 下发 → 回执含 **nonce**（证明真执行）→ `onDisable` 后域消失
- [x] ADR-29（已完成：[0029](../adr/0029-bridge-contract-into-api-module.md)）
- [ ] 文档：PRD、ARCHITECTURE 分层图、`docs/API.md`、CHANGELOG

**文件归属（并行开发隔离）**

| 归属 | 文件 |
|---|---|
| 本功能独占 | `api/.../api/Registration.java`、`api/.../api/bridge/*`、`api/.../api/ServerProbeAPI.java`、`api/.../api/BridgeServices.java`、`api/.../api/Unavailable*.java`、`core/.../bridge/BusinessHostImpl.kt`、`plugin/.../BridgeFacadeInstaller.kt` |
| 与其它功能共改 | `core/.../bridge/BridgeClient.kt`（import 与取值器）、`e2e/harness/**` |

## 5. 验收标准

| 项 | 判据 |
|---|---|
| 契约位置 | `api` 无 Kotlin 文件；Java 8 编译通过；`api` 依赖不变（仅 Lombok compileOnly） |
| 纯 Java 8 | 不使用 `List.of`/`Map.of`/`var`；`Optional`/`OptionalInt`/`CompletableFuture` 均在 Java 8 内 |
| 降级不抛 | 未 install 时逐个调用全部门面方法（含 `register(null)`、空 domain），无异常逃逸，句柄 `getActive()==false` 且原因为 `PROBE_UNAVAILABLE` |
| **代理可缓存** | 先 `BusinessHost h = ServerProbeAPI.businessHost()` 缓存，再 `install`，随后 `h.register(...)` 得到 active 句柄 |
| 注册往返 | E2E：harness Provider 返回随机 **nonce**，回执 `output` 含同一 nonce（排除"模板成功"） |
| 注销生效 | E2E：`onDisable` 后 `domains()` 不含该域；再下发返回「域不可用」 |
| `unregister` 兼容 | 单测：`unregister(provider)` 返回 true；对非当前注册者返回 false 且不移除 |
| 重复注册 | 单测：同域注册两次，旧句柄原因为 `REPLACED`；旧句柄 `close()` 后新 Provider 仍在册 |
| 逐域隔离 | 单测：一个 Provider 的 `manifest()` 抛异常，`manifest()` 仍返回其它域且该域含 `error` |
| 中断处理 | 单测：`shutdownNow()` 打断在途 dispatch，返回失败且线程中断标志被恢复 |
| 重入不放大 | 单测：Provider 内再 `dispatch` 另一域，不新增池线程、总耗时 < 外层预算 |
| 门面不可劫持 | **未实现**（原设计的 code source / 类名白名单两道方案均未落地，现仅靠 `install` 的 package-private 可见性提高门槛） |
| 二进制兼容 | 单测：用"仅实现旧接口"的编译产物 + 新 api 调 `dispatch`，无 `AbstractMethodError` |
| Kotlin 互操作 | 单测（Kotlin）：`override fun domain(): String` 可编译并工作；`result.getSuccess()` 可读 |
| null 规则 | 单测：`BridgeCommand.of(null, null)` 各 getter 返回空串；集合返回不可变空集合 |
| 真机 | E2E 真实 Paper，结果文件 `status=PASS`，且 `status` 由逐项断言汇总（非"跑完即 PASS"） |

## 6. 风险 / 待定

1. **二进制不兼容**：`BridgeCommandResult` 从 Kotlin data class 变 Java 类，失去 `copy()`/解构。仓内引用点一次性改完；对外无既有消费者。
2. **`install` 守卫的强度**（**实际未实现，已改正描述**）：原设计用 **code source 比对**，实测在 Gradle 模块化构建下**不可行**——
   `api` 与 `core` 分属不同 classes 目录，连探针自己的单测都装不上。随后计划改为**装配类名白名单**
   （`BridgeFacadeInstaller`，按调用栈匹配），但**该白名单未落地**：`install` 目前仅是 package-private，
   仓库内不存在 `SELF_INSTALLER_CLASSES` 或任何调用栈校验。
   这仍是"提高门槛"而非强隔离（同包声明 + 跨包名冲突），真正的隔离需要 classloader 边界，
   超出本模块职责。**已将此局限写入 ADR-0029 的风险段**。
3. **`unregister` 与 `Registration.close()` 双入口**：两者语义一致（都按实例匹配），
   单测同时覆盖，避免出现"close 能删、unregister 不能"的分裂。
4. **`ServerProbeApi`（旧）补 `@JvmStatic`** 是纯增量，但因它是 `object`，`@JvmStatic` 只加静态转发不改 `INSTANCE`。
5. **`manifest()` 信封形态**（实现明确）：`BusinessHost.manifest()` 返回结构化 `Map`，
   而 `jbis/manifest` 回执是字符串——由 `ManifestRoute` 编码为 `{"domains":{...}}`（沿用改造前形态）。
   这两层不要混淆：前者是对外 API，后者是桥协议。

### 已落地的自动化证据（批次 2 实现）

| 测试（`BusinessHostHandleTest`） | 覆盖的不变量 |
|---|---|
| `同域重复注册顶替且旧句柄不误删新实例` | 句柄失效为 `REPLACED`；陈旧句柄 close 不删新 Provider |
| `句柄关闭幂等且注销域` | close 幂等、注销生效 |
| `unregister 按实例比对` | 非当前注册者返回 false 且不移除 |
| `非法注册参数返回失效句柄` | null / 空 domain 不抛，返回 `INVALID` |
| `单域 manifest 异常被隔离` | 坏域呈现 `error`，健康域不受影响 |
| `Provider 返回 null 被兜住` | 违约实现不产生 NPE |
| **`门面代理可缓存且后安装生效`** | 先缓存引用、后 install → 注册仍生效（核心不变量） |
| `未安装时门面安全降级` | 全部方法安全返回，不抛不 NPE |
| `卸载后句柄失效` | 宿主停止后句柄失效 |
| `重入派发内联执行` | Provider 内再 dispatch 可同步拿到结果（不死锁） |
