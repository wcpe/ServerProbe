package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个业务域的能力清单，不可变。
 *
 * <p>取代原先「裸 {@code Map<String,Object>}」的形态：下游拿到本对象就能判断内容是否合法、
 * 据此渲染表单、按风险决定二次确认，而不必猜 manifest 里有哪些键、值是什么类型。</p>
 *
 * <p><b>{@code attributes} 是刻意的逃生舱</b>：业务特有信息（版本号、依赖插件名、自定义菜单结构…）
 * 放这里，探针与业务面板都不解释、原样透传。这样新增业务元信息不需要改 api 契约。</p>
 *
 * <p><b>为什么不是 record</b>：本模块发行面向 Java 8（ADR-3），record 需 Java 14+。</p>
 */
public final class DomainManifest {

    /**
     * 当前 manifest 协议版本。
     *
     * <p>探针按此值做协商（spec §5.2）：等于本值即原生支持；不等于则<b>降级为 legacy 适配并记 WARN</b>，
     * 不静默失败，也不直接拒绝——拒绝会让一个只差版本号声明的 Provider 完全不可用。</p>
     */
    public static final int CURRENT_API_VERSION = 1;

    private final int apiVersion;
    private final String domain;
    private final String displayName;
    private final String description;
    private final List<ActionSpec> actions;
    private final Map<String, String> attributes;

    /**
     * 构造能力清单。
     *
     * @param apiVersion  manifest 协议版本；{@code <= 0} 视为未声明，按 {@link #CURRENT_API_VERSION} 补齐
     * @param domain      业务域标识；null 视为空串（与 {@code BusinessProvider#domain()} 一致由探针校验）
     * @param displayName 供下游展示的名称；null 视为空串
     * @param description 人可读说明；null 视为空串
     * @param actions     动作规格；null 视为空列表；顺序保留（面板按声明顺序渲染）
     * @param attributes  扩展位；null 视为空映射
     */
    public DomainManifest(
            int apiVersion,
            String domain,
            String displayName,
            String description,
            List<ActionSpec> actions,
            Map<String, String> attributes) {
        this.apiVersion = apiVersion <= 0 ? CURRENT_API_VERSION : apiVersion;
        this.domain = nullToEmpty(domain);
        this.displayName = nullToEmpty(displayName);
        this.description = nullToEmpty(description);
        // 防御性拷贝：调用方之后改自己的 list/map 不应影响已声明的清单。
        this.actions = Collections.unmodifiableList(
                actions == null ? new ArrayList<ActionSpec>() : new ArrayList<ActionSpec>(actions));
        this.attributes = Collections.unmodifiableMap(
                attributes == null
                        ? new LinkedHashMap<String, String>()
                        : new LinkedHashMap<String, String>(attributes));
    }

    /**
     * 以当前协议版本构造一份清单（最常用形态）。
     *
     * @param domain      业务域标识
     * @param displayName 供下游展示的名称
     * @param description 人可读说明
     * @param actions     动作规格
     * @param attributes  扩展位（可为 null）
     * @return 不可变清单，{@code apiVersion == CURRENT_API_VERSION}
     */
    public static DomainManifest of(
            String domain,
            String displayName,
            String description,
            List<ActionSpec> actions,
            Map<String, String> attributes) {
        return new DomainManifest(CURRENT_API_VERSION, domain, displayName, description, actions, attributes);
    }

    /** manifest 协议版本，恒为正数。 */
    public int getApiVersion() {
        return apiVersion;
    }

    /** 业务域标识，永不为 null（未给出时为空串）。 */
    public String getDomain() {
        return domain;
    }

    /** 供下游展示的名称，永不为 null（未给出时为空串）。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 人可读说明，永不为 null（未给出时为空串）。 */
    public String getDescription() {
        return description;
    }

    /** 动作规格（不可变、保持声明顺序、永不为 null）。 */
    public List<ActionSpec> getActions() {
        return actions;
    }

    /** 扩展位（不可变、永不为 null）：平台不解释，原样透传。 */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * 按动作名查找规格。
     *
     * @param action 动作名；null 或空串返回 null
     * @return 匹配的规格；未声明该动作时返回 null
     */
    public ActionSpec findAction(String action) {
        if (action == null || action.isEmpty()) {
            return null;
        }
        for (ActionSpec spec : actions) {
            if (action.equals(spec.getName())) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 本域的 {@code apiVersion} 是否为探针当前支持的版本。
     *
     * @return true=原生支持；false=需降级为 legacy 适配
     */
    public boolean isApiVersionSupported() {
        return apiVersion == CURRENT_API_VERSION;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DomainManifest)) {
            return false;
        }
        DomainManifest that = (DomainManifest) other;
        return apiVersion == that.apiVersion
                && domain.equals(that.domain)
                && displayName.equals(that.displayName)
                && description.equals(that.description)
                && actions.equals(that.actions)
                && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        int result = apiVersion;
        result = 31 * result + domain.hashCode();
        result = 31 * result + displayName.hashCode();
        result = 31 * result + description.hashCode();
        result = 31 * result + actions.hashCode();
        result = 31 * result + attributes.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "DomainManifest{apiVersion=" + apiVersion + ", domain='" + domain
                + "', actions=" + actions.size() + '}';
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
