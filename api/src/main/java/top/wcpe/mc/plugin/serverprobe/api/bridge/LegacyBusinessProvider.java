package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.Map;

/**
 * <b>已废弃形态</b>的业务 Provider 契约（起由 {@link BusinessProvider} 取代）。
 *
 * <h2>它为什么还在</h2>
 * <p>期间发布的第三方插件实现的是旧签名（{@code Map<String,Object> manifest} +
 * {@code BridgeCommandResult dispatch(String, String)}）。把
 * {@link BusinessProvider} 改成强类型后，这些插件若被直接当作 {@link BusinessProvider} 使用，
 * 会在新方法上抛 {@link AbstractMethodError}——它们根本没有那两个方法。</p>
 *
 * <p>保留本接口有两个作用：</p>
 * <ol>
 *   <li><b>给旧插件一个可编译的落点</b>：把 {@code implements BusinessProvider} 改成
 *       {@code implements LegacyBusinessProvider}（一词之差），其余代码不动；</li>
 *   <li><b>给探针一个可判定的类型</b>：注册时据 {@link LegacyBusinessProviderAdapter} 识别
 *       「这是旧契约」，从而在聚合 manifest 里标 {@code specDeclared=false}、对动作采用保守默认值。</li>
 * </ol>
 *
 * <p>本接口<b>不再演进</b>：新能力只加到 {@link BusinessProvider}。请尽快迁移——
 * 旧形态没有动作级规格，业务面板只能盲发命令，也无法按风险决定二次确认。</p>
 *
 * @deprecated 请实现 {@link BusinessProvider}（强类型 manifest 与分发契约）。
 */
@Deprecated
public interface LegacyBusinessProvider {

    /** 业务域命名空间（`^[a-z0-9-]{1,32}$`）；空串、null 或格式非法的注册会被拒绝。 */
    String domain();

    /**
     * 本域能力清单（裸 Map 形态）。
     *
     * <p>动作名由 {@link #ACTIONS_KEY} 对应的列表给出，元素可以是动作名字符串，
     * 也可以是 {@code top.wcpe.mc.plugin.serverprobe.api.protocol.ActionSpec#toMap()} 产出的 Map
     * （键 {@code name}/{@code fields}/{@code readOnly}）。适配器只取动作名，
     * 其余信息一概不采信（见 {@link LegacyBusinessProviderAdapter}）。</p>
     *
     * @return 能力清单；不得返回 null（无能力返回空 Map）
     */
    Map<String, Object> manifest();

    /**
     * 执行一条本域业务动作。
     *
     * <p>线程与异常约定与 {@link BusinessProvider#dispatch(ActionRequest)} 相同：
     * 在探针独立业务线程池上被调用、可能并发进入、绝不抛异常（失败返回
     * {@link BridgeCommandResult#fail(String)}）。</p>
     *
     * @param action      动作名
     * @param payloadJson 结构化参数 JSON；未提供时为空串，永不为 null
     * @return 执行结果，不得返回 null
     */
    BridgeCommandResult dispatch(String action, String payloadJson);

    /** 本域当前健康状态；默认未知。语义与 {@link BusinessProvider#health()} 完全一致。 */
    default ProviderHealth health() {
        return ProviderHealth.unknown();
    }

    /** manifest 中承载动作清单的键名（与 {@code api.protocol.BusinessProtocol#MANIFEST_ACTIONS} 同值）。 */
    String ACTIONS_KEY = "actions";

    /** 动作声明中的动作名键（与 {@code BusinessProtocol#ACTION_NAME} 同值）。 */
    String ACTION_NAME_KEY = "name";
}
