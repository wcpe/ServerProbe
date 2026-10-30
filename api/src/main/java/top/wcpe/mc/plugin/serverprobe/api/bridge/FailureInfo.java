package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一个业务域最近一次 dispatch 失败的记录，不可变。
 *
 * <p>由探针自行维护（不依赖 Provider 覆写 {@link BusinessProvider#health()}）：
 * 即使 Provider 从不实现健康上报，面板也能看到「这个域最近为什么失败、连续失败了几次」。</p>
 *
 * <p>{@code message} 已经过脱敏与限长处理（凭据值替换为 {@code ***}、URL 去掉 query），
 * 可安全展示与落库。</p>
 */
public final class FailureInfo {

    private final String message;
    private final long timestampMs;
    private final int consecutiveFailures;

    /**
     * 构造一条失败记录。
     *
     * @param message 可读失败原因（已脱敏）；null 视为空串
     * @param timestampMs 失败发生时刻（epoch 毫秒）
     * @param consecutiveFailures 截至该次失败的连续失败次数（成功一次后清零）
     */
    public FailureInfo(String message, long timestampMs, int consecutiveFailures) {
        this.message = message == null ? "" : message;
        this.timestampMs = timestampMs;
        this.consecutiveFailures = consecutiveFailures;
    }

    /** 可读失败原因（已脱敏与限长）；无则空串，永不为 null。 */
    public String getMessage() {
        return message;
    }

    /** 失败发生时刻（epoch 毫秒）。 */
    public long getTimestampMs() {
        return timestampMs;
    }

    /** 截至该次失败的连续失败次数；成功后清零。 */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FailureInfo)) {
            return false;
        }
        FailureInfo that = (FailureInfo) other;
        return timestampMs == that.timestampMs
                && consecutiveFailures == that.consecutiveFailures
                && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        int result = message.hashCode();
        result = 31 * result + (int) (timestampMs ^ (timestampMs >>> 32));
        result = 31 * result + consecutiveFailures;
        return result;
    }

    @Override
    public String toString() {
        return "FailureInfo{message='" + message + "', timestampMs=" + timestampMs
                + ", consecutiveFailures=" + consecutiveFailures + "}";
    }
}
