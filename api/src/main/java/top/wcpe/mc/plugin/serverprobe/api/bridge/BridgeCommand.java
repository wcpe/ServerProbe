package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一条业务指令的不可变值对象。
 *
 * <p>由 Worker 下发的 {@code command} 帧解析而来；业务 Provider 通常只需读
 * {@link #getAction()} 与 {@link #getPayload()}。</p>
 *
 * <p>用静态工厂而非长参数构造器：全 String 的参数列表传反顺序编译器不报错，
 * 而 Kotlin 消费方又无法使用具名参数（ADR-13 的已知限制）。</p>
 */
public final class BridgeCommand {

    private final String action;
    private final String payload;
    private final String requestId;
    private final String domain;

    private BridgeCommand(String action, String payload, String requestId, String domain) {
        this.action = nullToEmpty(action);
        this.payload = nullToEmpty(payload);
        this.requestId = nullToEmpty(requestId);
        this.domain = nullToEmpty(domain);
    }

    /**
     * 业务命令（Provider 侧唯一需要关心的形态）。
     *
     * @param action      动作名
     * @param payloadJson 结构化参数 JSON；null 视为空串
     */
    public static BridgeCommand of(String action, String payloadJson) {
        return new BridgeCommand(action, payloadJson, "", "");
    }

    /** 完整形态（桥内核解析下行帧时使用）。 */
    public static BridgeCommand of(String action, String payloadJson, String requestId, String domain) {
        return new BridgeCommand(action, payloadJson, requestId, domain);
    }

    /** 返回带指定回执标识的副本。 */
    public BridgeCommand withRequestId(String requestId) {
        return new BridgeCommand(action, payload, requestId, domain);
    }

    /** 返回带指定业务域的副本。 */
    public BridgeCommand withDomain(String domain) {
        return new BridgeCommand(action, payload, requestId, domain);
    }

    /** 指令动作，永不为 null。 */
    public String getAction() {
        return action;
    }

    /** 业务结构化参数 JSON，永不为 null（无参数时为空串）。 */
    public String getPayload() {
        return payload;
    }

    /** 关联回执标识，永不为 null（无则为空串）。 */
    public String getRequestId() {
        return requestId;
    }

    /** 业务域，永不为 null（治理指令为空串）。 */
    public String getDomain() {
        return domain;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BridgeCommand)) {
            return false;
        }
        BridgeCommand that = (BridgeCommand) other;
        return action.equals(that.action)
                && payload.equals(that.payload)
                && requestId.equals(that.requestId)
                && domain.equals(that.domain);
    }

    @Override
    public int hashCode() {
        int result = action.hashCode();
        result = 31 * result + payload.hashCode();
        result = 31 * result + requestId.hashCode();
        result = 31 * result + domain.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "BridgeCommand{action='" + action + "', domain='" + domain
                + "', requestId='" + requestId + "'}";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
