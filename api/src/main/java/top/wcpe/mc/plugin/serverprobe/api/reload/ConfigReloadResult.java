package top.wcpe.mc.plugin.serverprobe.api.reload;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * 一次配置重载的结果，不可变。
 *
 * <p>语义：成功表示「插件已把新配置应用到运行态」，而不是「文件存在」。
 * 探针<b>不推断</b>插件是否真的生效，只如实转发插件自报的结果。</p>
 *
 * <p>用工厂方法构造：{@link #ok()} / {@link #ok(String)} /
 * {@link #failed(ConfigReloadErrorCode, String)} / {@link #failed(ConfigReloadErrorCode, String, String)}。</p>
 */
public final class ConfigReloadResult {

    /** 成功且无详情：复用单例（监听器每路径返回一次，避免高频分配）。 */
    private static final ConfigReloadResult EMPTY_OK =
            new ConfigReloadResult(true, null, "", "", null, null, null);

    private final boolean ok;
    private final ConfigReloadErrorCode code;
    private final String message;
    private final String detailJson;
    private final String appliedMd5;
    private final String appliedVersion;
    private final Integer appliedCount;

    private ConfigReloadResult(
            boolean ok,
            ConfigReloadErrorCode code,
            String message,
            String detailJson,
            String appliedMd5,
            String appliedVersion,
            Integer appliedCount) {
        this.ok = ok;
        this.code = code;
        this.message = nullToEmpty(message);
        this.detailJson = nullToEmpty(detailJson);
        this.appliedMd5 = nullToEmpty(appliedMd5);
        this.appliedVersion = nullToEmpty(appliedVersion);
        this.appliedCount = appliedCount;
    }

    /** 成功。 */
    public static ConfigReloadResult ok() {
        return EMPTY_OK;
    }

    /** 成功，并附带插件自定义详情 JSON（原样透传给 JM，探针不解析）。 */
    public static ConfigReloadResult ok(String detailJson) {
        return new ConfigReloadResult(true, null, "", detailJson, null, null, null);
    }

    /** 失败。 */
    public static ConfigReloadResult failed(ConfigReloadErrorCode code, String message) {
        return failed(code, message, "");
    }

    /** 失败，并附带详情。 */
    public static ConfigReloadResult failed(ConfigReloadErrorCode code, String message, String detailJson) {
        ConfigReloadErrorCode effective = code == null ? ConfigReloadErrorCode.PLUGIN_REJECTED : code;
        return new ConfigReloadResult(false, effective, message, detailJson, null, null, null);
    }

    /** 是否成功。布尔访问器用 get 前缀（ADR-13）。 */
    public boolean getOk() {
        return ok;
    }

    /** 失败时的错误码；成功时为 null（本类唯一允许 null 的读口）。 */
    public ConfigReloadErrorCode getCode() {
        return code;
    }

    /** 可读消息；成功时为空串，永不为 null。 */
    public String getMessage() {
        return message;
    }

    /** 插件自定义详情 JSON；无则空串，永不为 null。 */
    public String getDetailJson() {
        return detailJson;
    }

    // ---- 自报事实（读口与写口必须成对，否则 core 无法组装 applied 映射）----

    /** 自报「实际加载内容的 md5」；未上报时为空。 */
    public Optional<String> getAppliedMd5() {
        return appliedMd5.isEmpty() ? Optional.<String>empty() : Optional.of(appliedMd5);
    }

    /** 自报「实际解析出的版本标识」；未上报时为空。 */
    public Optional<String> getAppliedVersion() {
        return appliedVersion.isEmpty() ? Optional.<String>empty() : Optional.of(appliedVersion);
    }

    /**
     * 自报「实际加载的条目数」；未上报时为空。
     *
     * <p>用 {@link OptionalInt} 以区分「未上报」与「上报 0」——后者是合法事实
     * （例如热重载后条目确实清零），写成 0 会让 JM 误以为插件没上报。</p>
     */
    public OptionalInt getAppliedCount() {
        return appliedCount == null ? OptionalInt.empty() : OptionalInt.of(appliedCount);
    }

    /** 附带自报 md5；null 或空串视为「未上报」。 */
    public ConfigReloadResult withAppliedMd5(String md5) {
        return new ConfigReloadResult(ok, code, message, detailJson, md5, appliedVersion, appliedCount);
    }

    /** 附带自报版本标识；null 或空串视为「未上报」。 */
    public ConfigReloadResult withAppliedVersion(String version) {
        return new ConfigReloadResult(ok, code, message, detailJson, appliedMd5, version, appliedCount);
    }

    /** 附带自报条目数；null 视为「未上报」，{@code 0} 是合法取值。 */
    public ConfigReloadResult withAppliedCount(int count) {
        return new ConfigReloadResult(ok, code, message, detailJson, appliedMd5, appliedVersion, count);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConfigReloadResult)) {
            return false;
        }
        ConfigReloadResult that = (ConfigReloadResult) other;
        return ok == that.ok
                && code == that.code
                && message.equals(that.message)
                && detailJson.equals(that.detailJson)
                && appliedMd5.equals(that.appliedMd5)
                && appliedVersion.equals(that.appliedVersion)
                && equalCount(that.appliedCount);
    }

    @Override
    public int hashCode() {
        int result = ok ? 1 : 0;
        result = 31 * result + (code == null ? 0 : code.hashCode());
        result = 31 * result + message.hashCode();
        result = 31 * result + detailJson.hashCode();
        result = 31 * result + appliedMd5.hashCode();
        result = 31 * result + appliedVersion.hashCode();
        result = 31 * result + (appliedCount == null ? 0 : appliedCount.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "ConfigReloadResult{ok=" + ok + ", code=" + code + ", message='" + message
                + "', detailJson='" + detailJson + "', appliedMd5='" + appliedMd5
                + "', appliedVersion='" + appliedVersion + "', appliedCount=" + appliedCount + "}";
    }

    /** 「未上报」与「上报 0」不相等：否则 applied 映射会把 0 与缺省混为一谈。 */
    private boolean equalCount(Integer that) {
        if (appliedCount == null) {
            return that == null;
        }
        return appliedCount.equals(that);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
