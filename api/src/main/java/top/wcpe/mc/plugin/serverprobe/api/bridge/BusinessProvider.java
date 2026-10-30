package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一个业务域的对接提供者（强类型契约）。
 *
 * <h2>实现约定（外部插件必读）</h2>
 * <ul>
 *   <li><b>线程安全</b>：会在 ServerProbe 的独立业务线程池被调用，可能并发进入。</li>
 *   <li><b>绝不抛异常</b>：失败返回 {@link ActionResult#fail(String, String)}。抛出的异常会被兜住
 *       并转为失败回执，但会丢失可读原因与错误码。</li>
 *   <li><b>调用语义是「至多等待、可能已生效」</b>：ServerProbe 最多等待
 *       {@code bridge.dispatch.timeout-ms}（默认 5000ms）。<b>超时后动作可能仍然执行成功</b>
 *       （无法强杀线程）。</li>
 *   <li><b>声明 {@code idempotent} 的动作必须按 {@link ActionRequest#getRequestId()} 去重</b>：
 *       调用方在网络超时后会用同一个 {@code requestId} 重发，不得假设"没收到回执 = 没执行"。</li>
 *   <li><b>不要在本方法内再次调用 {@code BusinessHost.dispatch}</b>：会放大池占用。</li>
 * </ul>
 *
 * <h2>manifest 的序列化约束</h2>
 * <p>{@link #manifest()} 返回的是强类型对象，探针负责把它编码成 JSON，因此插件不再需要关心
 * 「哪些类型能进 manifest」。但 {@code inputSchema} / {@code outputSchema} 必须是<b>合法 JSON 文本</b>：
 * 探针只透传、不解析，写坏了只有面板拿到坏 schema。</p>
 *
 * <h2>实现示例（Java）</h2>
 * <p>下面的动作名与字段名全部是占位示例，探针不认识它们的含义：</p>
 * <pre>{@code
 * public final class DemoProvider implements BusinessProvider {
 *     public String domain() { return "demo"; }
 *
 *     public DomainManifest manifest() {
 *         List<ActionSpec> actions = new ArrayList<ActionSpec>();
 *         actions.add(ActionSpec.read("query", "只读查询"));
 *         actions.add(ActionSpec.of("apply", "有副作用的操作", RiskLevel.DESTRUCTIVE, true,
 *                 "{\"type\":\"object\"}", null, new LinkedHashSet<String>(Arrays.asList("TARGET_NOT_FOUND"))));
 *         Map<String, String> attrs = new LinkedHashMap<String, String>();
 *         attrs.put("version", "1.2.0");
 *         return DomainManifest.of("demo", "示例业务", "示例能力清单", actions, attrs);
 *     }
 *
 *     public ActionResult dispatch(ActionRequest request) {
 *         if ("query".equals(request.getAction())) {
 *             return ActionResult.ok("{\"value\":42}");
 *         }
 *         return ActionResult.fail("TARGET_NOT_FOUND", "目标不存在");
 *     }
 * }
 * }</pre>
 *
 * <h2>实现示例（Kotlin）</h2>
 * <p>注意 Java 方法名是 {@code domain()}，<b>不是</b>属性 {@code domain}：</p>
 * <pre>{@code
 * class DemoProvider : BusinessProvider {
 *     override fun domain(): String = "demo"
 *     override fun manifest(): DomainManifest =
 *         DomainManifest.of("demo", "示例业务", "示例能力清单", emptyList(), emptyMap())
 *     override fun dispatch(request: ActionRequest): ActionResult =
 *         ActionResult.fail("NOT_IMPLEMENTED", "未实现")
 * }
 * }</pre>
 *
 * <h2>旧契约怎么办</h2>
 * <p>早期返回裸 {@code Map} 的 Provider 请改实现 {@link LegacyBusinessProvider}：
 * 探针会用 {@link LegacyBusinessProviderAdapter} 自动适配注册，你的域在聚合 manifest 里
 * 标为 {@code specDeclared=false}，动作风险按 {@link RiskLevel#WRITE} 保守处理。
 * 但旧契约没有动作级规格，面板只能盲发命令——请尽快迁移到本接口。</p>
 */
public interface BusinessProvider {

    /** 业务域命名空间（`^[a-z0-9-]{1,32}$`）；空串、null 或格式非法的注册会被拒绝。 */
    String domain();

    /**
     * 本域能力清单；不得返回 null（无能力返回空 {@code actions} 的清单）。
     *
     * <p>单域抛异常不影响其它域：该域在聚合 manifest 里呈现为 {@code error}。</p>
     */
    DomainManifest manifest();

    /**
     * 执行一条本域业务动作。
     *
     * @param request 下发请求（域、动作、参数、回执标识、操作者上下文），永不为 null
     * @return 执行结果，不得返回 null
     */
    ActionResult dispatch(ActionRequest request);

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
     * 显式给出 {@code sinceMs}。注意简写工厂的时间戳口径：
     * {@code available()} / {@code unavailable(...)} / {@code initializing(...)} / {@code degraded(...)}
     * 会把 {@code sinceMs} 填为**构造该值时的当前时刻**，而 {@link ProviderHealth#unknown()} 恒为 0；
     * 要表达"这个状态从某一刻开始"请用 {@code of(...)}，不要依赖简写工厂。</p>
     *
     * <p>实现约定：不得返回 null（返回 null 会被记为 {@code DEGRADED("health() 返回 null")}）；
     * 抛出的异常会被兜住并转为该域的 {@code DEGRADED}，不影响其它域。</p>
     *
     * @return 健康状态，永不为 null
     */
    default ProviderHealth health() {
        return ProviderHealth.unknown();
    }
}
