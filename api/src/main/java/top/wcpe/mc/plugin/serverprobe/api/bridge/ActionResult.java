package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次业务动作的执行结果，不可变。
 *
 * <p>取代原先「只有 success / output / error」的 {@link BridgeCommandResult}：业务面板现在能
 * <b>按机器可读的 {@code errorCode} 分流</b>（如 {@code TARGET_NOT_FOUND} 走"提示改名"、
 * {@code ALREADY_RUNNING} 走"提示重复操作"），而不是只能把一段中文文本丢给用户看。</p>
 *
 * <p><b>{@code errorCode} 必须落在 manifest 声明的 {@code errorCodes} 范围内</b>（spec §4.5）。
 * 探针在派发后做一致性检查并告警，但<b>不改写</b>结果——把 Provider 的错误码替换成 {@code UNDECLARED}
 * 会让原本可分流的信息彻底丢失。</p>
 *
 * <p>{@code metadata} 只放诊断信息（耗时、影响行数、批次号等），<b>不影响协议兼容</b>：
 * 下游可以不读它，读不到也不算失败。</p>
 *
 * <p><b>为什么不是 record</b>：本模块发行面向 Java 8（ADR-3），record 需 Java 14+。</p>
 */
public final class ActionResult {

    private static final ActionResult EMPTY_OK = new ActionResult(true, "", "", "", null);

    private final boolean success;
    private final String outputJson;
    private final String errorCode;
    private final String errorMessage;
    private final Map<String, String> metadata;

    /**
     * 构造执行结果。
     *
     * @param success      是否成功
     * @param outputJson   成功输出 JSON 文本；null 或空串表示无输出
     * @param errorCode    机器可读错误码；成功时为空串
     * @param errorMessage 人可读消息；null 视为空串
     * @param metadata     诊断信息；null 视为空映射
     */
    public ActionResult(
            boolean success,
            String outputJson,
            String errorCode,
            String errorMessage,
            Map<String, String> metadata) {
        this.success = success;
        this.outputJson = nullToEmpty(outputJson);
        this.errorCode = nullToEmpty(errorCode);
        this.errorMessage = nullToEmpty(errorMessage);
        this.metadata = Collections.unmodifiableMap(
                metadata == null
                        ? new LinkedHashMap<String, String>()
                        : new LinkedHashMap<String, String>(metadata));
    }

    /** 成功且无输出。 */
    public static ActionResult ok() {
        return EMPTY_OK;
    }

    /**
     * 成功并带输出。
     *
     * @param outputJson 输出 JSON 文本；null 视为无输出
     * @return 成功结果
     */
    public static ActionResult ok(String outputJson) {
        return new ActionResult(true, outputJson, "", "", null);
    }

    /**
     * 成功并带输出与诊断信息。
     *
     * @param outputJson 输出 JSON 文本；null 视为无输出
     * @param metadata   诊断信息；null 视为空
     * @return 成功结果
     */
    public static ActionResult ok(String outputJson, Map<String, String> metadata) {
        return new ActionResult(true, outputJson, "", "", metadata);
    }

    /**
     * 失败（无错误码，仅人可读原因）。
     *
     * <p>供尚未声明错误码的动作使用；能给出 {@link #fail(String, String)} 时应优先给码，
     * 否则面板无法分流。</p>
     *
     * @param errorMessage 人可读原因；null 视为空串
     * @return 失败结果
     */
    public static ActionResult fail(String errorMessage) {
        return new ActionResult(false, "", "", errorMessage, null);
    }

    /**
     * 失败（带机器可读错误码）。
     *
     * <p>错误码应为常量形状的大写下划线串（如 {@code TARGET_NOT_FOUND}）；探针不校验格式，
     * 但会与 manifest 声明比对并在不一致时告警。</p>
     *
     * @param errorCode    机器可读错误码
     * @param errorMessage 人可读原因；null 视为空串
     * @return 失败结果
     */
    public static ActionResult fail(String errorCode, String errorMessage) {
        return new ActionResult(false, "", errorCode, errorMessage, null);
    }

    /** 是否成功。布尔访问器用 {@code get} 前缀（ADR-13）。 */
    public boolean getSuccess() {
        return success;
    }

    /** 成功输出 JSON 文本，永不为 null（无输出时为空串）。 */
    public String getOutputJson() {
        return outputJson;
    }

    /** 机器可读错误码，永不为 null（成功或未声明时为空串）。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 人可读消息，永不为 null（无消息时为空串）。 */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** 诊断信息（不可变、永不为 null）。 */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActionResult)) {
            return false;
        }
        ActionResult that = (ActionResult) other;
        return success == that.success
                && outputJson.equals(that.outputJson)
                && errorCode.equals(that.errorCode)
                && errorMessage.equals(that.errorMessage)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        int result = success ? 1 : 0;
        result = 31 * result + outputJson.hashCode();
        result = 31 * result + errorCode.hashCode();
        result = 31 * result + errorMessage.hashCode();
        result = 31 * result + metadata.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ActionResult{success=" + success + ", errorCode='" + errorCode
                + "', errorMessage='" + errorMessage + "'}";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
