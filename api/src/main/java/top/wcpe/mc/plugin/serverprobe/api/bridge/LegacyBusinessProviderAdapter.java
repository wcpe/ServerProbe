package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 把<b>旧契约</b>（{@link LegacyBusinessProvider}：裸 {@code Map} manifest + 双字符串 dispatch）
 * 适配为 / 的强类型 {@link BusinessProvider}（spec §5.1）。
 *
 * <h2>适配规则（逐条对应 spec §5.1 / §7）</h2>
 * <ul>
 *   <li>动作名从 {@code manifest().get("actions")} 提取：元素可以是动作名字符串，
 *       也可以是 {@code api.protocol.ActionSpec#toMap()} 产出的 Map（取其中 {@code name}）。
 *       其它形态一律忽略——宁可少列一个动作，也不要猜出一个错误的名字。</li>
 *   <li>{@code risk} 恒为 {@link RiskLevel#WRITE}，<b>不看旧 manifest 的 {@code readOnly}</b>。</li>
 *   <li>{@code idempotent} 恒为 {@code false}，{@code inputSchema}/{@code outputSchema} 为未声明，
 *       {@code errorCodes} 为空。</li>
 * </ul>
 *
 * <h2>为什么 risk 默认 WRITE，而不是照着 readOnly 标 READ</h2>
 * <p>安全优先。旧 manifest 的 {@code readOnly} 只是"作者自愿填的一个布尔"，没有契约约束；
 * 一旦误填 {@code true} 而动作实际写库，面板会把它当查询直接执行——风险控制形同虚设。
 * 反过来的代价只是"只读动作多一次确认"，这可以接受。真正想要精确风险声明的，
 * 迁移到 {@link BusinessProvider} 显式声明即可。</p>
 *
 * <h2>怎么判断"这个域是适配来的"</h2>
 * <p>聚合 manifest 时用 {@link #isLegacy(BusinessProvider)} 判定，据此标 {@code specDeclared=false}。
 * 判定依据是<b>适配器实例本身</b>，不是 manifest 内容——内容无法区分"旧契约"与"新契约但没声明动作"。</p>
 *
 * <h2>注销</h2>
 * <p>请使用 {@code register} 返回的注册句柄 {@code close()}。不要拿原 {@link LegacyBusinessProvider}
 * 实例去调 {@code unregister}：类型上就传不进去，而且每次 {@link #adapt(LegacyBusinessProvider)}
 * 都产生一个新的包装实例，按实例比对必然不匹配。</p>
 */
public final class LegacyBusinessProviderAdapter implements BusinessProvider {

    /** 旧契约在聚合 manifest 里展示的说明后缀，让运维一眼看出这个域还没声明规格。 */
    static final String LEGACY_DESCRIPTION = "（未声明动作规格的旧契约 Provider，风险一律按 WRITE 处理）";

    private final LegacyBusinessProvider delegate;

    private LegacyBusinessProviderAdapter(LegacyBusinessProvider delegate) {
        this.delegate = delegate;
    }

    /**
     * 把旧契约 Provider 包装为强类型 Provider。
     *
     * @param provider 旧契约 Provider；null 返回 null（由调用方按无效注册处理）
     * @return 包装后的 Provider；非 null 入参恒返回非 null
     */
    public static BusinessProvider adapt(LegacyBusinessProvider provider) {
        if (provider == null) {
            return null;
        }
        return new LegacyBusinessProviderAdapter(provider);
    }

    /**
     * 判断一个已注册 Provider 是否由本适配器包装而来。
     *
     * @param provider 待判定的 Provider；null 返回 false
     * @return true=来自旧契约适配，聚合 manifest 应标 {@code specDeclared=false}
     */
    public static boolean isLegacy(BusinessProvider provider) {
        return provider instanceof LegacyBusinessProviderAdapter;
    }

    /** 取回被包装的旧契约实例（诊断用；没有它也能工作）。 */
    public LegacyBusinessProvider unwrap() {
        return delegate;
    }

    @Override
    public String domain() {
        return delegate.domain();
    }

    /**
     * 生成强类型清单。
     *
     * <p>旧 manifest 的结构完全不可控（第三方随便放什么），因此逐个 action 元素做类型判定，
     * 畸形元素直接跳过而不是抛异常——一个写坏的元素不该让整个域从面板上消失。</p>
     */
    @Override
    public DomainManifest manifest() {
        Map<String, Object> legacy = delegate.manifest();
        List<ActionSpec> actions = legacy == null ? new ArrayList<ActionSpec>() : extractActions(legacy);
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put(ATTRIBUTE_SPEC_DECLARED, Boolean.FALSE.toString());
        return new DomainManifest(
                DomainManifest.CURRENT_API_VERSION,
                delegate.domain(),
                delegate.domain(),
                LEGACY_DESCRIPTION,
                actions,
                attributes);
    }

    /**
     * 转派旧 dispatch，并把 {@link BridgeCommandResult} 映射为 {@link ActionResult}。
     *
     * <p>旧契约没有错误码，因此 {@code errorCode} 恒为空串——这正是迁移的真实收益来源：
     * 下游只能读人可读文本，无法按码分流。</p>
     */
    @Override
    public ActionResult dispatch(ActionRequest request) {
        BridgeCommandResult legacy = delegate.dispatch(request.getAction(), request.getPayloadJson());
        if (legacy == null) {
            // 旧契约违约返回 null：探针侧还有一层兜底，这里给一条能定位问题的原因。
            return ActionResult.fail("旧契约 Provider 未返回结果");
        }
        if (legacy.getSuccess()) {
            return ActionResult.ok(legacy.getOutput());
        }
        return ActionResult.fail(legacy.getError());
    }

    /** 健康状态直接转派：旧契约的 health() 语义与新契约一致。 */
    @Override
    public ProviderHealth health() {
        return delegate.health();
    }

    @Override
    public String toString() {
        return "LegacyBusinessProviderAdapter{domain='" + safeDomain() + "'}";
    }

    /** 取域名但绝不抛（toString 与日志路径上不能让畸形实现炸掉调用方）。 */
    private String safeDomain() {
        try {
            return delegate.domain();
        } catch (Throwable ignored) {
            return "<domain() 抛出异常>";
        }
    }

    /**
     * 从旧 manifest 提取动作清单。
     *
     * <p>接受两种元素形态（其余跳过）：</p>
     * <ul>
     *   <li>{@code String} —— 动作名本身；</li>
     *   <li>{@code Map} —— 取 {@code name} 键（{@code ActionSpec#toMap()} 的产物）。</li>
     * </ul>
     */
    private static List<ActionSpec> extractActions(Map<String, Object> legacy) {
        List<ActionSpec> actions = new ArrayList<ActionSpec>();
        Object raw = legacy.get(LegacyBusinessProvider.ACTIONS_KEY);
        if (!(raw instanceof Iterable)) {
            return actions;
        }
        for (Object element : (Iterable<?>) raw) {
            String name = actionNameOf(element);
            if (name == null || name.isEmpty()) {
                continue;
            }
            actions.add(ActionSpec.of(
                    name,
                    "",
                    RiskLevel.WRITE,
                    false,
                    null,
                    null,
                    new LinkedHashSet<String>()));
        }
        return actions;
    }

    /** 取单个 action 元素的名字；无法判定时返回 null。 */
    private static String actionNameOf(Object element) {
        if (element instanceof String) {
            return (String) element;
        }
        if (element instanceof Map) {
            Object name = ((Map<?, ?>) element).get(LegacyBusinessProvider.ACTION_NAME_KEY);
            return name instanceof String ? (String) name : null;
        }
        return null;
    }

    /**
     * 适配清单里 {@code attributes} 的固定键：值恒为 {@code "false"}。
     *
     * <p>保留在 attributes 里是为了让<b>只读 manifest 的下游</b>（不读聚合信封、只读某域清单的场景）
     * 也能看出这个域未声明规格；聚合层的 {@code specDeclared} 才是权威字段。</p>
     */
    public static final String ATTRIBUTE_SPEC_DECLARED = "specDeclared";
}
