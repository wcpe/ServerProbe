package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import top.wcpe.mc.plugin.serverprobe.api.Registration;
import top.wcpe.mc.plugin.serverprobe.api.protocol.Fields;
import top.wcpe.mc.plugin.serverprobe.api.protocol.Payloads;

/**
 * 业务域宿主门面。
 *
 * <p>经 {@code ServerProbeAPI.businessHost()} 取得。<b>该返回值是稳定代理，可安全缓存为字段</b>——
 * 探针后续 install/uninstall 都会被自动感知（缓存降级快照则不会，那会让"探针后加载"的场景永久失效）。</p>
 *
 * <p>所有方法线程安全。运行期<b>不抛异常</b>（失败一律降级为失败结果）；唯一的例外是
 * {@link #dispatch(String, String, Map)} 等"结构化参数重载"在"参数无法写成 JSON"时抛出的
 * {@link IllegalArgumentException}——那是调用方编程错误，不是运行期故障，理由见各方法 javadoc。</p>
 *
 * <h2> / 的两条主线</h2>
 * <ul>
 *   <li>能力发现走 {@link #manifest()}：返回带 {@code apiVersion} 的聚合信封，逐域标明是否声明了规格；</li>
 *   <li>动作执行走 {@link #dispatch(ActionRequest)}：入参带 {@code requestId} 与操作者上下文，
 *       出参带机器可读 {@code errorCode}。</li>
 * </ul>
 * <p>旧的字符串形态（{@link #dispatch(String, String, String)}）保留为兼容入口，语义等价于「没有
 * requestId 与操作者上下文的请求」，并<b>丢不出</b>错误码与诊断信息——新代码请用强类型版本。</p>
 */
public interface BusinessHost {

    /**
     * 注册一个业务 Provider。
     *
     * <p>同 domain 重复注册以最新为准：旧句柄立即失效（{@link Registration.InactiveReason#REPLACED}）。
     * 探针未就绪、provider 为 null、domain 为空时返回<b>失效句柄</b>，绝不抛异常。</p>
     *
     * <p>注册时做四项校验（spec §7），任一项不通过都返回失效句柄并记日志：</p>
     * <ul>
     *   <li>{@code domain} 格式非法（须匹配 {@code ^[a-z0-9-]{1,32}$}）→ 拒绝，记 ERROR；</li>
     *   <li>动作名在本域内重复 → 拒绝，记 ERROR；</li>
     *   <li>{@code apiVersion} 不受支持 → <b>不拒绝</b>，降级为 legacy 规格（{@code specDeclared=false}），记 WARN；</li>
     *   <li>{@code risk} 未声明 → 按 {@link RiskLevel#WRITE} 处理，记 WARN。</li>
     * </ul>
     *
     * @param provider 业务 Provider
     * @return 注册句柄；close 幂等且不影响后来注册的同域 Provider
     */
    Registration register(BusinessProvider provider);

    /**
     * 注册一个<b>旧契约</b> Provider（{@link LegacyBusinessProvider}）。
     *
     * <p>内部经 {@link LegacyBusinessProviderAdapter#adapt(LegacyBusinessProvider)} 适配后走
     * {@link #register(BusinessProvider)}：该域在聚合 manifest 里标 {@code specDeclared=false}，
     * 动作风险一律按 {@link RiskLevel#WRITE} 处理。</p>
     *
     * <p>方法名带 {@code Legacy} 而不是重载 {@code register}：两个重载会让
     * {@code register(null)} 无法通过编译，而"传 null 得失效句柄"是既有契约的一部分。</p>
     *
     * @param provider 旧契约 Provider；null 返回失效句柄
     * @return 注册句柄；close 幂等
     */
    Registration registerLegacy(LegacyBusinessProvider provider);

    /**
     * 按实例注销一个 Provider（兼容既有调用点）。
     *
     * <p>仅当当前域仍指向该实例时才移除，避免插件热重载后旧实例的卸载回调误删新实例。</p>
     *
     * <p><b>经 {@link #registerLegacy(LegacyBusinessProvider)} 注册的域不能用本方法注销</b>：
     * 注册时产生的是适配器包装实例，调用方手里没有它。请用返回的句柄 {@code close()}。</p>
     *
     * @return true=已移除；false=该实例并非当前注册者
     */
    boolean unregister(BusinessProvider provider);

    /** 当前已注册的业务域（不可变、按字典序、永不为 null）。 */
    Set<String> domains();

    /**
     * 汇总各域能力清单（spec §6 的权威形态）。
     *
     * <p>返回的 Map 可直接编码为 JSON，结构固定为：</p>
     * <pre>
     * {
     *   "apiVersion": 1,
     *   "domains": {
     *     "activity": {
     *       "displayName": "活动系统",
     *       "description": "...",
     *       "specDeclared": true,
     *       "actions": [ { "name": "start", "risk": "WRITE", "idempotent": true, ... } ],
     *       "attributes": {}
     *     }
     *   }
     * }
     * </pre>
     *
     * <p>逐域隔离：某域 {@code manifest()} 抛异常时，该域呈现为 {@code {"error": "..."}}，
     * 不影响其它域。</p>
     *
     * <p>{@code specDeclared=false} 表示该域来自旧契约或版本降级——动作清单可能不完整，
     * 风险与幂等性都是探针的保守默认值，面板不应据此渲染精确表单。</p>
     *
     * @return 聚合清单的不可变映射，永不为 null
     */
    Map<String, Object> manifest();

    /**
     * 执行一条业务动作（强类型入口，推荐）。
     *
     * <p><b>可能阻塞至多 {@code bridge.dispatch.timeout-ms}（默认 5s），禁止在主线程调用。</b>
     * 主线程/玩家事件链路请改用 {@link #dispatchAsync(ActionRequest)}。</p>
     *
     * <p>{@link ActionRequest#getRequestId()} 为空时由探针补一个（保证 Provider 侧恒非空）。</p>
     *
     * @param request 下发请求；null 返回失败结果，不抛异常
     * @return 执行结果；域未注册或探针未就绪时返回失败结果，绝不返回 null
     */
    ActionResult dispatch(ActionRequest request);

    /**
     * 执行一条业务动作（兼容入口：无 requestId、无操作者上下文）。
     *
     * <p>等价于 {@code dispatch(new ActionRequest(domain, action, payloadJson, "", Principal.unknown()))}，
     * 再把 {@link ActionResult} 折回 {@link BridgeCommandResult}——<b>错误码与诊断信息在这一步丢失</b>，
     * 因为旧结果类型没有承载它们的字段。需要按错误码分流时请用强类型入口。</p>
     *
     * <p><b>可能阻塞至多 {@code bridge.dispatch.timeout-ms}（默认 5s），禁止在主线程调用。</b></p>
     *
     * @param domain      业务域命名空间
     * @param action      动作名
     * @param payloadJson 结构化参数 JSON；null 视为空串
     * @return 执行结果；域未注册或探针未就绪时返回失败结果，不抛异常
     * @deprecated 本入口<b>丢错误码与诊断信息</b>（旧结果类型没有承载它们的字段）。
     *     新代码请用 {@link #dispatch(ActionRequest)} / {@link #dispatchAsync(ActionRequest)}，
     *     它们能拿到 {@code errorCode} 与 {@code metadata}。保留仅为了已编译的旧插件不失效。
     */
    @Deprecated
    default BridgeCommandResult dispatch(String domain, String action, String payloadJson) {
        ActionResult result = dispatch(
                new ActionRequest(domain, action, payloadJson, "", Principal.unknown()));
        if (result == null) {
            return BridgeCommandResult.fail("Provider 未返回结果");
        }
        if (result.getSuccess()) {
            return BridgeCommandResult.ok(result.getOutputJson());
        }
        String message = result.getErrorMessage();
        if (message.isEmpty()) {
            message = result.getErrorCode();
        }
        return BridgeCommandResult.fail(message);
    }

    /**
     * 直接派发一条业务动作，参数用结构化 Map 给出（{@link #dispatch(String, String, String)} 的便利重载）。
     *
     * <p>参数经 {@link Payloads#toJson(Map)} 规范化后写成 JSON 对象文本，再交给上面那个方法——
     * 因此阻塞语义、超时、结果兜底完全相同：<b>可能阻塞至多
     * {@code bridge.dispatch.timeout-ms}（默认 5s），禁止在主线程调用</b>。</p>
     *
     * <p>{@code null} 或空 Map 等价于 {@code {}}：Provider 侧永远拿到一段可解析的 JSON，
     * 不会收到 null 或空串。</p>
     *
     * <p><b>本方法可能抛 {@link IllegalArgumentException}</b>。这与"探针方法永不抛异常"并不冲突：
     * 该异常只在一件事上抛出——调用方给了探针<b>无法表达的取值</b>（嵌套集合/数组/POJO、
     * {@code null} 键、NaN/无穷、嵌套过深）。它是<b>调用方编程错误</b>，不是运行期故障：
     * 若改成"写进 error 返回一个失败结果"，这段代码在开发期往往一直"看起来正常"，
     * 直到面板上出现一条缺字段的账单才被发现。所以这里选择快速失败。业务执行侧的失败
     * （域不可用、Provider 抛异常、超时）仍然一律降级为失败结果，不上抛。</p>
     *
     * <p>需要精确控制 JSON 文本（含 {@code null} 值、数字格式、字段顺序）时，
     * 继续用 {@link #dispatch(String, String, String)} 的字符串版本。</p>
     *
     * <p><b>注意重载歧义</b>：{@code dispatch(domain, action, null)} 无法通过编译——
     * {@code null} 同时匹配 {@code String} 与 {@code Map} 两个重载。要传空载荷请用
     * {@link Payloads#empty()}（或 {@link Payloads#EMPTY_FIELDS}）；确实要走字符串版本时
     * 显式转型 {@code (String) null}。</p>
     *
     * @param domain  业务域命名空间
     * @param action  动作名
     * @param payload 结构化参数；null 视为空对象
     * @return 执行结果；域未注册或探针未就绪时返回失败结果
     * @throws IllegalArgumentException 参数含无法写成 JSON 的类型或结构（调用方错误）
     * @deprecated 同 {@link #dispatch(String, String, String)}：结果里没有错误码。
     *     新代码请用 {@link #dispatch(ActionRequest)}（或自行 {@code Payloads.toJson} 后传入）。
     */
    @Deprecated
    default BridgeCommandResult dispatch(String domain, String action, Map<String, ?> payload) {
        return dispatch(domain, action, Payloads.toJson(payload));
    }

    /**
     * 异步派发（推荐的跨插件调用入口）。
     *
     * <p>超时由实现内部统一处理：Future 以失败结果完成，而非异常完成。</p>
     *
     * @param request 下发请求；null 时以失败结果完成
     * @return 以执行结果完成的 Future，永不为 null
     */
    CompletableFuture<ActionResult> dispatchAsync(ActionRequest request);

    /**
     * 异步派发（兼容入口），语义同 {@link #dispatch(String, String, String)} 的异步版本。
     *
     * @param domain      业务域命名空间
     * @param action      动作名
     * @param payloadJson 结构化参数 JSON；null 视为空串
     * @return 以执行结果完成的 Future，永不为 null
     */
    default CompletableFuture<BridgeCommandResult> dispatchAsync(
            String domain, String action, String payloadJson) {
        return dispatchAsync(new ActionRequest(domain, action, payloadJson, "", Principal.unknown()))
                .thenApply(result -> {
                    if (result == null) {
                        return BridgeCommandResult.fail("Provider 未返回结果");
                    }
                    if (result.getSuccess()) {
                        return BridgeCommandResult.ok(result.getOutputJson());
                    }
                    String message = result.getErrorMessage();
                    if (message.isEmpty()) {
                        message = result.getErrorCode();
                    }
                    return BridgeCommandResult.fail(message);
                });
    }

    /**
     * 异步派发（推荐的跨插件调用入口），参数用结构化 Map 给出。
     *
     * <p>参数经 {@link Payloads#toJson(Map)} 规范化；{@code null} 或空 Map 等价于 {@code {}}。
     * 超时语义与 Future 完成方式同 {@link #dispatchAsync(String, String, String)}：
     * 以失败<b>结果</b>完成，而非异常完成。</p>
     *
     * <p>序列化在<b>调用线程</b>上同步完成，因此不支持的取值会立刻以
     * {@link IllegalArgumentException} 抛给调用方（而不是塞进 Future）——这类错误越早暴露越好，
     * 且与同步版本的失败方式保持一致。业务执行侧的异常仍按上文一律转成失败结果。</p>
     *
     * <p><b>注意重载歧义</b>：{@code dispatchAsync(domain, action, null)} 无法通过编译，
     * 传空载荷请用 {@link Payloads#empty()}（或 {@link Payloads#EMPTY_FIELDS}）。</p>
     *
     * @param domain  业务域命名空间
     * @param action  动作名
     * @param payload 结构化参数；null 视为空对象
     * @return 以执行结果完成的 Future，永不为 null
     * @throws IllegalArgumentException 参数含无法写成 JSON 的类型或结构（调用方错误）
     * @deprecated 同 {@link #dispatch(String, String, String)}：结果里没有错误码。
     *     新代码请用 {@link #dispatchAsync(ActionRequest)}。
     */
    @Deprecated
    default CompletableFuture<BridgeCommandResult> dispatchAsync(
            String domain, String action, Map<String, ?> payload) {
        return dispatchAsync(domain, action, Payloads.toJson(payload));
    }

    /**
     * 同步派发一条业务动作，参数用 {@link Fields} 构造器直接给出（{@link #dispatch(String, String, Map)} 的免样板重载）。
     *
     * <p>存在意义只有一条：让调用点省掉 {@code .build()}。</p>
     * <pre>{@code
     * host.dispatch("economy", "grant",
     *         Fields.of("player", uuid).with("amount", new BigDecimal("1250.00")));
     * }</pre>
     *
     * <p>取值规范化与异常语义与 {@link #dispatch(String, String, Map)} 完全一致
     * （它最终就是 {@code dispatch(domain, action, payload.build())}）。
     * 业务执行侧失败仍降级为失败结果，不外抛。</p>
     *
     * @param domain  业务域
     * @param action  动作名
     * @param payload 参数构造器；{@code null} 视为空参数（写出 {@code {}}）
     * @return 执行结果，永不为 null
     * @throws IllegalArgumentException 参数含无法表达为 JSON 的取值（调用方编程错误）
     * @deprecated 同 {@link #dispatch(String, String, String)}：结果里没有错误码。
     *     新代码请用 {@link #dispatch(ActionRequest)}，需要免样板时先用 {@code Fields} 装配、
     *     再 {@code Payloads.toJson} 传参。
     */
    @Deprecated
    default BridgeCommandResult dispatch(String domain, String action, Fields payload) {
        return dispatch(domain, action, payload == null ? null : payload.build());
    }

    /**
     * 查询一个业务域的健康状态。
     *
     * <p>返回的是<b>探针缓存的只读快照</b>：本方法**不会调用** Provider 的
     * {@link BusinessProvider#health()}，不做 IO、不派发业务命令、不新建线程。
     * Provider 的健康值由探针的独立刷新线程（{@code serverprobe-health} /
     * {@code serverprobe-health-probe}）周期采集，因此这里读到的是"上次采集的结果"，
     * 其时刻见 {@link DomainStatus#getRefreshedAtMs()}。</p>
     *
     * <p>为什么不在本方法内直接调 {@code health()}：本方法可能被业务线程或命令线程调用，
     * 而三方 {@code health()} 可能卡住；在查询路径上同步调用会让一次卡死拖住整条调用链。</p>
     *
     * <p>未注册的域不会报错：返回 {@code registered=false} 的快照，原因见
     * {@link DomainStatus#getReasonOverride()}。绝不返回 null、绝不抛异常。</p>
     *
     * @param domain 业务域命名空间；null 或空串返回未注册快照
     * @return 该域的只读状态快照，永不为 null
     */
    DomainStatus healthOf(String domain);
}
