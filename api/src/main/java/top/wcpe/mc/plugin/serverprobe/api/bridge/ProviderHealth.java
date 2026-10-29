package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 业务域健康状态，不可变。
 *
 * <p>应如实回报：不可用时给出可读原因，不要用 {@link Status#AVAILABLE} 掩盖内部故障——
 * 面板会据此判断是否还要继续下发业务命令。</p>
 */
public final class ProviderHealth {

    /** 健康等级。 */
    public enum Status {

        /** Provider 就绪，可正常处理动作。 */
        AVAILABLE,

        /** 依赖的外部插件/服务不可用（如未安装、被禁用）。 */
        UNAVAILABLE,

        /** 仍在初始化（如等待外部插件就绪）。 */
        INITIALIZING,

        /** Provider 自身异常（如内部状态损坏）。 */
        DEGRADED,

        /** 未实现 health()，探针无法判断。 */
        UNKNOWN
    }

    private static final ProviderHealth UNKNOWN = new ProviderHealth(Status.UNKNOWN, "", "", 0L);

    private final Status status;
    private final String reason;
    private final String detailJson;
    private final long sinceMs;

    private ProviderHealth(Status status, String reason, String detailJson, long sinceMs) {
        this.status = status == null ? Status.UNKNOWN : status;
        this.reason = nullToEmpty(reason);
        this.detailJson = nullToEmpty(detailJson);
        this.sinceMs = sinceMs;
    }

    /** 就绪。 */
    public static ProviderHealth available() {
        return new ProviderHealth(Status.AVAILABLE, "", "", System.currentTimeMillis());
    }

    /** 依赖不可用。 */
    public static ProviderHealth unavailable(String reason) {
        return new ProviderHealth(Status.UNAVAILABLE, reason, "", System.currentTimeMillis());
    }

    /** 初始化中。 */
    public static ProviderHealth initializing(String detail) {
        return new ProviderHealth(Status.INITIALIZING, detail, "", System.currentTimeMillis());
    }

    /** 降级。 */
    public static ProviderHealth degraded(String reason) {
        return new ProviderHealth(Status.DEGRADED, reason, "", System.currentTimeMillis());
    }

    /** 未知（未实现 health()）。 */
    public static ProviderHealth unknown() {
        return UNKNOWN;
    }

    /** 完整工厂：显式带时间戳（当 Provider 需要报告"自何时起如此"）。 */
    public static ProviderHealth of(Status status, String reason, String detailJson, long sinceMs) {
        return new ProviderHealth(status, reason, detailJson, sinceMs);
    }

    public Status getStatus() {
        return status;
    }

    /** 可读原因/详情；无则空串，永不为 null。 */
    public String getReason() {
        return reason;
    }

    /** 自定义结构化详情 JSON；无则空串，永不为 null。 */
    public String getDetailJson() {
        return detailJson;
    }

    /** 状态起始时间（epoch 毫秒）；未知时为 0。 */
    public long getSinceMs() {
        return sinceMs;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProviderHealth)) {
            return false;
        }
        ProviderHealth that = (ProviderHealth) other;
        return status == that.status && reason.equals(that.reason)
                && detailJson.equals(that.detailJson) && sinceMs == that.sinceMs;
    }

    @Override
    public int hashCode() {
        int result = status.hashCode();
        result = 31 * result + reason.hashCode();
        result = 31 * result + detailJson.hashCode();
        result = 31 * result + (int) (sinceMs ^ (sinceMs >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "ProviderHealth{status=" + status + ", reason='" + reason + "'}";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
