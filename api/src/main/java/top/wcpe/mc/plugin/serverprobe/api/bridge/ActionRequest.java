package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一次业务动作的下发请求，不可变。
 *
 * <p>取代原先的 {@code dispatch(String action, String payloadJson)} 双字符串形态：
 * 动作现在能读到<b>自己属于哪个域</b>、<b>关联哪个回执</b>、<b>谁在下发</b>。</p>
 *
 * <p><b>{@code requestId} 恒非空</b>：探针在下发前保证（桥下行帧缺失时补一个）。
 * Provider 若在 manifest 里声明 {@code idempotent=true}，必须用它做本地去重——
 * 「没收到回执」不等于「没执行」，网络超时后调用方会用同一个 {@code requestId} 重发。</p>
 *
 * <p><b>为什么不是 record</b>：本模块发行面向 Java 8（ADR-3），record 需 Java 14+。</p>
 */
public final class ActionRequest {

    private final String domain;
    private final String action;
    private final String payloadJson;
    private final String requestId;
    private final Principal principal;

    /**
     * 构造一次下发请求。
     *
     * @param domain      业务域标识；null 视为空串
     * @param action      动作名；null 视为空串
     * @param payloadJson 结构化参数 JSON；null 视为空串（Provider 侧应容错解析，空串按 {@code {}} 处理）
     * @param requestId   关联回执标识；null 视为空串（探针在真实下发路径上恒填非空值）
     * @param principal   操作者上下文；null 视为 {@link Principal#unknown()}
     */
    public ActionRequest(
            String domain,
            String action,
            String payloadJson,
            String requestId,
            Principal principal) {
        this.domain = nullToEmpty(domain);
        this.action = nullToEmpty(action);
        this.payloadJson = nullToEmpty(payloadJson);
        this.requestId = nullToEmpty(requestId);
        this.principal = principal == null ? Principal.unknown() : principal;
    }

    /**
     * 用结构化参数构造请求（免去手拼 JSON 字符串）。
     *
     * <p><b>为什么需要这个工厂</b>：{@code payloadJson} 是字符串，手写 JSON 时
     * 一个引号或逗号写错就会让 Provider 收到坏参数，而编译器看得见 {@code String}、看不到语法。
     * 本工厂把 {@code Map} 经 {@link top.wcpe.mc.plugin.serverprobe.api.protocol.Payloads#toJson(Map)}
     * 规范化写出（数字不走科学计数法、金额不失精度），是<b>程序内构造请求的推荐形式</b>。</p>
     *
     * <p>注意它<b>不改变协议</b>：线上仍然是 JSON 文本，Provider 侧仍按字符串取参数。
     * 对于需要精确控制 JSON 字节形态的调用方（或用例回放），仍可直接用构造器传原文。</p>
     *
     * @param domain    业务域标识
     * @param action    动作名
     * @param payload   结构化参数；null 视为空对象
     * @param requestId 关联回执标识；null 视为空串
     * @param principal 操作者上下文；null 视为 {@link Principal#unknown()}
     * @return 不可变请求
     * @throws IllegalArgumentException 参数含无法写成 JSON 的类型或结构（调用方错误）
     */
    public static ActionRequest of(
            String domain,
            String action,
            java.util.Map<String, ?> payload,
            String requestId,
            Principal principal) {
        return new ActionRequest(
                domain,
                action,
                top.wcpe.mc.plugin.serverprobe.api.protocol.Payloads.toJson(payload),
                requestId,
                principal);
    }

    /**
     * 用结构化参数构造请求（无回执标识、无操作者上下文的最简形式）。
     *
     * @param domain  业务域标识
     * @param action  动作名
     * @param payload 结构化参数；null 视为空对象
     * @return 不可变请求
     * @throws IllegalArgumentException 参数含无法写成 JSON 的类型或结构
     */
    public static ActionRequest of(String domain, String action, java.util.Map<String, ?> payload) {
        return of(domain, action, payload, "", null);
    }

    /** 业务域标识，永不为 null。 */
    public String getDomain() {
        return domain;
    }

    /** 动作名，永不为 null。 */
    public String getAction() {
        return action;
    }

    /** 结构化参数 JSON，永不为 null（无参数时为空串）。 */
    public String getPayloadJson() {
        return payloadJson;
    }

    /** 关联回执标识，永不为 null；幂等去重的唯一依据。 */
    public String getRequestId() {
        return requestId;
    }

    /** 操作者上下文，永不为 null（未知时为 {@link Principal#unknown()}）。 */
    public Principal getPrincipal() {
        return principal;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActionRequest)) {
            return false;
        }
        ActionRequest that = (ActionRequest) other;
        return domain.equals(that.domain)
                && action.equals(that.action)
                && payloadJson.equals(that.payloadJson)
                && requestId.equals(that.requestId)
                && principal.equals(that.principal);
    }

    @Override
    public int hashCode() {
        int result = domain.hashCode();
        result = 31 * result + action.hashCode();
        result = 31 * result + payloadJson.hashCode();
        result = 31 * result + requestId.hashCode();
        result = 31 * result + principal.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ActionRequest{domain='" + domain + "', action='" + action
                + "', requestId='" + requestId + "', operator='" + principal.getOperator() + "'}";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
