package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个业务动作的规格声明，不可变。
 *
 * <p>它回答面板渲染操作表单必须知道的四件事：<b>叫什么</b>、<b>有多危险</b>、
 * <b>能不能重复发</b>、<b>参数与结果长什么样</b>。仅给一串动作名不够——面板只能盲发命令。</p>
 *
 * <p><b>本类不描述任何具体业务语义</b>：动作名、schema 内容、错误码全部由调用方给出，
 * api 包只承载结构。</p>
 *
 * <p><b>为什么不是 record</b>：本模块发行面向 Java 8（ADR-3），record 需 Java 14+。</p>
 */
public final class ActionSpec {

    /** 风险未声明时的默认等级：安全优先，未声明即视为需要确认的写动作。 */
    public static final RiskLevel DEFAULT_RISK = RiskLevel.WRITE;

    private final String name;
    private final String description;
    private final RiskLevel risk;
    private final boolean idempotent;
    private final String inputSchema;
    private final String outputSchema;
    private final Set<String> errorCodes;

    private ActionSpec(
            String name,
            String description,
            RiskLevel risk,
            boolean idempotent,
            String inputSchema,
            String outputSchema,
            Set<String> errorCodes) {
        this.name = nullToEmpty(name);
        this.description = nullToEmpty(description);
        // 保留「是否声明过风险」这一事实（null 即未声明）：探针据此在注册时记 WARN（spec §7），
        // 而取值上仍按 DEFAULT_RISK 生效。若在这里就把 null 抹成 WRITE，告警就再也发不出来了。
        this.risk = risk;
        this.idempotent = idempotent;
        this.inputSchema = nullToEmpty(inputSchema);
        this.outputSchema = nullToEmpty(outputSchema);
        this.errorCodes = Collections.unmodifiableSet(
                errorCodes == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(errorCodes));
    }

    /**
     * 声明一个动作（风险显式给出）。
     *
     * @param name         动作名，由插件自定；null 视为空串
     * @param description  人可读说明；null 视为空串
     * @param risk         风险等级；null 按 {@link #DEFAULT_RISK} 处理
     * @param idempotent   是否支持 {@code requestId} 幂等去重
     * @param inputSchema  入参 JSON Schema 文本；可为 null（表示未声明，探针不校验）
     * @param outputSchema 出参 JSON Schema 文本；可为 null
     * @param errorCodes   该动作可能返回的业务错误码；null 或空表示未声明
     * @return 不可变的动作规格
     */
    public static ActionSpec of(
            String name,
            String description,
            RiskLevel risk,
            boolean idempotent,
            String inputSchema,
            String outputSchema,
            Set<String> errorCodes) {
        return new ActionSpec(name, description, risk, idempotent, inputSchema, outputSchema, errorCodes);
    }

    /**
     * 声明一个只读动作的简写（无 schema、无错误码）。
     *
     * @param name        动作名
     * @param description 人可读说明
     * @return 只读、非幂等（只读动作本无副作用，幂等声明对它无意义）、未声明 schema 的动作规格
     */
    public static ActionSpec read(String name, String description) {
        return of(name, description, RiskLevel.READ, false, null, null, null);
    }

    /**
     * 声明一个写动作的简写（无 schema、无错误码）。
     *
     * @param name        动作名
     * @param description 人可读说明
     * @return 写风险、非幂等、未声明 schema 的动作规格
     */
    public static ActionSpec write(String name, String description) {
        return of(name, description, RiskLevel.WRITE, false, null, null, null);
    }

    /** 动作名，永不为 null（未给出时为空串；空名的动作会在注册校验时被拒）。 */
    public String getName() {
        return name;
    }

    /** 人可读说明，永不为 null（未给出时为空串）。 */
    public String getDescription() {
        return description;
    }

    /** 风险等级，永不为 null（未声明时按 {@link #DEFAULT_RISK} 生效）。 */
    public RiskLevel getRisk() {
        return risk == null ? DEFAULT_RISK : risk;
    }

    /**
     * 声明时给出的原始风险等级；<b>未声明时为 null</b>。
     *
     * <p>与 {@link #getRisk()} 分开是为了让探针能区分「显式声明 WRITE」与「压根没声明」，
     * 后者要在注册时记 WARN（spec §7）。业务消费方应当只用 {@link #getRisk()}。</p>
     */
    public RiskLevel getDeclaredRisk() {
        return risk;
    }

    /** 是否显式声明过风险等级。 */
    public boolean hasDeclaredRisk() {
        return risk != null;
    }

    /** 是否支持按 {@code requestId} 幂等去重。 */
    public boolean getIdempotent() {
        return idempotent;
    }

    /** 入参 JSON Schema 文本，永不为 null（未声明时为空串）。 */
    public String getInputSchema() {
        return inputSchema;
    }

    /** 出参 JSON Schema 文本，永不为 null（未声明时为空串）。 */
    public String getOutputSchema() {
        return outputSchema;
    }

    /** 该动作可能返回的业务错误码（不可变、保持声明顺序、永不为 null）。 */
    public Set<String> getErrorCodes() {
        return errorCodes;
    }

    /** 是否声明了入参 schema。 */
    public boolean hasInputSchema() {
        return !inputSchema.isEmpty();
    }

    /** 是否声明了该动作的业务错误码。 */
    public boolean declaresAnyErrorCode() {
        return !errorCodes.isEmpty();
    }

    /**
     * 判断某个错误码是否在本动作声明的范围内。
     *
     * <p>探针据此在回执里做一致性告警（spec 第 8 节第 7 条）：声明了错误码却返回未声明的码，
     * 说明 Provider 与自己的 manifest 已经不一致，下游按码分流会漏分支。</p>
     *
     * @param errorCode 待检查的错误码；null 或空串一律视为「未声明」
     * @return true=在声明范围内
     */
    public boolean allowsErrorCode(String errorCode) {
        return errorCode != null && errorCodes.contains(errorCode);
    }

    /**
     * 展开错误码为 {@code List<String>}（供 manifest 序列化用；JSON 数组语义）。
     *
     * @return 不可变列表，顺序与声明一致
     */
    public List<String> errorCodeList() {
        return Collections.unmodifiableList(new java.util.ArrayList<String>(errorCodes));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActionSpec)) {
            return false;
        }
        ActionSpec that = (ActionSpec) other;
        return idempotent == that.idempotent
                && name.equals(that.name)
                && description.equals(that.description)
                && risk == that.risk
                && inputSchema.equals(that.inputSchema)
                && outputSchema.equals(that.outputSchema)
                && errorCodes.equals(that.errorCodes);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + description.hashCode();
        result = 31 * result + (risk == null ? 0 : risk.hashCode());
        result = 31 * result + (idempotent ? 1 : 0);
        result = 31 * result + inputSchema.hashCode();
        result = 31 * result + outputSchema.hashCode();
        result = 31 * result + errorCodes.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ActionSpec{name='" + name + "', risk=" + getRisk() + ", idempotent=" + idempotent
                + ", errorCodes=" + errorCodes + '}';
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
