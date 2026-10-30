package top.wcpe.mc.plugin.serverprobe.api.reload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次配置重载事件，不可变。
 *
 * <p>用对象而非裸集合作为入参：将来新增字段（如变更来源、md5）不破签名。</p>
 *
 * <p><b>线程安全</b>：构造时即复制并转为不可变列表，可安全跨线程传递。</p>
 */
public final class ConfigReloadEvent {

    private final List<String> matchedPaths;
    private final List<String> requestedPaths;
    private final boolean fullReload;

    private ConfigReloadEvent(List<String> matchedPaths, List<String> requestedPaths, boolean fullReload) {
        this.matchedPaths = immutableSorted(matchedPaths);
        this.requestedPaths = immutableSorted(requestedPaths);
        this.fullReload = fullReload;
    }

    /**
     * 普通（按路径）重载事件。
     *
     * @param matchedPaths 本次命中本监听器的路径
     * @param requestedPaths 本次请求中的全部路径（非空）
     * @return 不可变事件
     */
    public static ConfigReloadEvent of(List<String> matchedPaths, List<String> requestedPaths) {
        return new ConfigReloadEvent(matchedPaths, requestedPaths, false);
    }

    /**
     * 全量重载事件（payload 未指定 paths）。
     *
     * @param matchedPaths 本监听器声明的全部路径
     * @return 不可变事件
     */
    public static ConfigReloadEvent full(List<String> matchedPaths) {
        return new ConfigReloadEvent(matchedPaths, Collections.<String>emptyList(), true);
    }

    /** 本次<b>命中本监听器</b>的路径（不可变，已排序）。 */
    public List<String> getMatchedPaths() {
        return matchedPaths;
    }

    /** 本次请求中的全部路径；全量重载时为空列表。 */
    public List<String> getRequestedPaths() {
        return requestedPaths;
    }

    /** 是否全量重载（payload 未指定 paths）。布尔访问器用 get 前缀（ADR-13）。 */
    public boolean getFullReload() {
        return fullReload;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConfigReloadEvent)) {
            return false;
        }
        ConfigReloadEvent that = (ConfigReloadEvent) other;
        return fullReload == that.fullReload
                && matchedPaths.equals(that.matchedPaths)
                && requestedPaths.equals(that.requestedPaths);
    }

    @Override
    public int hashCode() {
        int result = matchedPaths.hashCode();
        result = 31 * result + requestedPaths.hashCode();
        result = 31 * result + (fullReload ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ConfigReloadEvent{fullReload=" + fullReload + ", matchedPaths=" + matchedPaths
                + ", requestedPaths=" + requestedPaths + "}";
    }

    /** 复制成不可变且有序的列表：顺序稳定才能让"逐字段相等"这类断言可用。 */
    private static List<String> immutableSorted(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<String>(source);
        Collections.sort(copy);
        return Collections.unmodifiableList(copy);
    }
}
