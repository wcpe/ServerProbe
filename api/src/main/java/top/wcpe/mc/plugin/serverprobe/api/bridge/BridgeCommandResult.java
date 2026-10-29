package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一条业务指令的执行结果，不可变。
 *
 * <p>{@link #getOutput()} 与 {@link #getError()} 永不为 null（无内容时为空串），
 * 调用方无需判空。</p>
 */
public final class BridgeCommandResult {

    private static final BridgeCommandResult EMPTY_OK = new BridgeCommandResult(true, "", "");

    private final boolean success;
    private final String output;
    private final String error;

    private BridgeCommandResult(boolean success, String output, String error) {
        this.success = success;
        this.output = nullToEmpty(output);
        this.error = nullToEmpty(error);
    }

    /** 成功且无输出。 */
    public static BridgeCommandResult ok() {
        return EMPTY_OK;
    }

    /** 成功并带输出；null 视为空串。 */
    public static BridgeCommandResult ok(String output) {
        return new BridgeCommandResult(true, output, "");
    }

    /** 失败并带可读原因；null 视为空串。 */
    public static BridgeCommandResult fail(String error) {
        return new BridgeCommandResult(false, "", error);
    }

    /**
     * 失败，但<b>同时</b>携带结构化输出与可读原因。
     *
     * <p>为什么需要它：{@link #ok(String)} 只填 {@code output}、{@link #fail(String)} 只填
     * {@code error}，二者互斥。而「部分成功」这类结果必须两边都有——例如配置重载时
     * {@code reloaded} 要如实列出成功的路径（结构化，放 {@code output}），
     * 同时整体判定为失败并给出原因（放 {@code error}）。若只能二选一，
     * 面板要么丢掉失败原因，要么丢掉机器可读的明细。</p>
     *
     * <p>不改变的既有语义：{@link #getSuccess()} 仍为 {@code false}；
     * {@code output} 与 {@code error} 仍永不为 null（null 视为空串）。</p>
     *
     * @param output 结构化输出；可为 null（视为空串）
     * @param error  可读失败原因；可为 null（视为空串）
     */
    public static BridgeCommandResult ofFailure(String output, String error) {
        return new BridgeCommandResult(false, output, error);
    }

    /** 是否成功。布尔访问器用 {@code get} 前缀（ADR-13）。 */
    public boolean getSuccess() {
        return success;
    }

    /** 成功输出；无则空串，永不为 null。 */
    public String getOutput() {
        return output;
    }

    /** 失败原因；无则空串，永不为 null。 */
    public String getError() {
        return error;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BridgeCommandResult)) {
            return false;
        }
        BridgeCommandResult that = (BridgeCommandResult) other;
        return success == that.success && output.equals(that.output) && error.equals(that.error);
    }

    @Override
    public int hashCode() {
        int result = success ? 1 : 0;
        result = 31 * result + output.hashCode();
        result = 31 * result + error.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "BridgeCommandResult{success=" + success + ", output='" + output
                + "', error='" + error + "'}";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
