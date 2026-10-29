package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一次业务动作的操作者上下文，不可变。
 *
 * <p>由业务面板（JM CP）在下发前填充，经探针<b>原样透传</b>给 Provider，
 * 让插件自己的审计流水能记到「谁、在哪个节点、为什么」——探针不解释任何字段。</p>
 *
 * <p><b>为什么操作者身份不塞进 payload</b>：payload 是业务自己的参数命名空间，
 * 身份字段混进去会和业务字段撞名，也会让 Provider 难以区分「业务参数」与「平台注入」。
 * 独立对象让这件事变成类型约束，而不是文档约定。</p>
 *
 * <p>本类不校验取值语义（例如 {@code reason} 是否对高风险动作必填由下游决定）：
 * api 包只放跨域通用词汇，无法替任何具体业务判定「哪些动作算高风险」。</p>
 *
 * <p><b>为什么不是 record</b>：本模块发行面向 Java 8（ADR-3），record 需 Java 14+，
 * 故与 {@link ProviderHealth} / {@link DomainStatus} 一致用 final 类 + 静态工厂。</p>
 */
public final class Principal {

    /** 下行帧内承载操作者上下文的字段名（`command` 帧顶层）。 */
    public static final String FIELD_PRINCIPAL = "principal";

    /** 操作者上下文内的用户名字段。 */
    public static final String FIELD_OPERATOR = "operator";

    /** 操作者上下文内的用户 ID 字段。 */
    public static final String FIELD_OPERATOR_ID = "operatorId";

    /** 操作者上下文内的节点 UUID 字段。 */
    public static final String FIELD_NODE_ID = "nodeId";

    /** 操作者上下文内的操作原因字段。 */
    public static final String FIELD_REASON = "reason";

    /** 操作者未知时的空实例：各字符串字段为空串、ID 为 0。 */
    private static final Principal UNKNOWN = new Principal("", 0L, "", "");

    private final String operator;
    private final long operatorId;
    private final String nodeId;
    private final String reason;

    /**
     * 构造操作者上下文。
     *
     * @param operator   操作者用户名；null 视为空串
     * @param operatorId 操作者用户 ID；未知为 0
     * @param nodeId     实例所属节点 UUID；null 视为空串
     * @param reason     操作原因（高风险操作一般必填）；null 视为空串
     */
    public Principal(String operator, long operatorId, String nodeId, String reason) {
        this.operator = nullToEmpty(operator);
        this.operatorId = operatorId;
        this.nodeId = nullToEmpty(nodeId);
        this.reason = nullToEmpty(reason);
    }

    /** 操作者未知时的空实例；永不返回 null。探针在拿不到操作者信息时使用它。 */
    public static Principal unknown() {
        return UNKNOWN;
    }

    /** 操作者用户名，永不为 null（未知时为空串）。 */
    public String getOperator() {
        return operator;
    }

    /** 操作者用户 ID，未知时为 0。 */
    public long getOperatorId() {
        return operatorId;
    }

    /** 实例所属节点 UUID，永不为 null（未知时为空串）。 */
    public String getNodeId() {
        return nodeId;
    }

    /** 操作原因，永不为 null（未填写时为空串）。 */
    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Principal)) {
            return false;
        }
        Principal that = (Principal) other;
        return operatorId == that.operatorId
                && operator.equals(that.operator)
                && nodeId.equals(that.nodeId)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        int result = operator.hashCode();
        result = 31 * result + (int) (operatorId ^ (operatorId >>> 32));
        result = 31 * result + nodeId.hashCode();
        result = 31 * result + reason.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Principal{operator='" + operator + "', operatorId=" + operatorId
                + ", nodeId='" + nodeId + "', reason='" + reason + "'}";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
