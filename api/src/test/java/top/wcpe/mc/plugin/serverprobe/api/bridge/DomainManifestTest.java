package top.wcpe.mc.plugin.serverprobe.api.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DomainManifest} 的契约测试。
 *
 * <p>为什么这些语义值得逐个钉死：本类是**跨插件公开契约**——第三方业务插件构造它、
 * 探针与 Loom 面板消费它。Kdoc 承诺了「永不为 null」「不可变」「保持声明顺序」
 * 「版本不匹配降级而不拒绝」等行为，一旦有人图省事把 null 归一删掉、或把防御性拷贝
 * 换成直接持有调用方的 list，消费方就会在运行期撞上 NPE 或看到被外部悄悄改掉的清单，
 * 而这类回归在没有测试时**不会让编译失败**。</p>
 */
@DisplayName("DomainManifest 契约：null 归一 / 不可变 / 版本协商")
class DomainManifestTest {

    // ==================== apiVersion 归一 ====================

    @Test
    @DisplayName("apiVersion <= 0 视为未声明，补齐为 CURRENT_API_VERSION")
    void nonPositiveApiVersionFallsBackToCurrent() {
        assertEquals(DomainManifest.CURRENT_API_VERSION,
                new DomainManifest(0, "eco", "经济", "", null, null).getApiVersion());
        assertEquals(DomainManifest.CURRENT_API_VERSION,
                new DomainManifest(-7, "eco", "经济", "", null, null).getApiVersion(),
                "负数同样是未声明，不是「比 1 小所以降级」");
    }

    @Test
    @DisplayName("显式给出的正版本号原样保留（不覆盖成当前版本）")
    void explicitPositiveApiVersionIsPreserved() {
        assertEquals(99, new DomainManifest(99, "eco", "经济", "", null, null).getApiVersion());
    }

    @Test
    @DisplayName("版本协商：等于当前版本才是原生支持，否则需降级为 legacy 适配")
    void apiVersionSupportedOnlyWhenEqual() {
        assertTrue(DomainManifest.of("eco", "经济", "", null, null).isApiVersionSupported());
        assertFalse(new DomainManifest(2, "eco", "经济", "", null, null).isApiVersionSupported(),
                "更高版本也必须降级而不是直接拒绝——拒绝会让只差版本号声明的 Provider 完全不可用");
        assertFalse(new DomainManifest(DomainManifest.CURRENT_API_VERSION + 1, "eco", "经济", "", null, null)
                .isApiVersionSupported());
    }

    // ==================== null 归一 ====================

    @Test
    @DisplayName("字符串字段为 null 时归一为空串，而不是把 null 透给消费方")
    void nullStringsBecomeEmpty() {
        DomainManifest m = new DomainManifest(1, null, null, null, null, null);
        assertEquals("", m.getDomain());
        assertEquals("", m.getDisplayName());
        assertEquals("", m.getDescription());
    }

    @Test
    @DisplayName("actions/attributes 为 null 时归一为空集合，而不是 null")
    void nullCollectionsBecomeEmpty() {
        DomainManifest m = new DomainManifest(1, "eco", "", "", null, null);
        assertTrue(m.getActions().isEmpty());
        assertTrue(m.getAttributes().isEmpty());
    }

    // ==================== 不可变 + 防御性拷贝 ====================

    @Test
    @DisplayName("构造后再改调用方的 list/map，不影响已声明的清单（防御性拷贝）")
    void mutatingSourceCollectionsDoesNotAffectManifest() {
        List<ActionSpec> actions = new ArrayList<ActionSpec>();
        actions.add(ActionSpec.read("balance", "查余额"));
        Map<String, String> attrs = new HashMap<String, String>();
        attrs.put("version", "1.0");

        DomainManifest m = DomainManifest.of("eco", "经济", "", actions, attrs);

        actions.add(ActionSpec.write("set", "改余额"));
        attrs.put("version", "2.0");
        attrs.put("注入", "脏数据");

        assertEquals(1, m.getActions().size(), "构造后新增的元素不得出现在清单里");
        assertEquals("1.0", m.getAttributes().get("version"));
        assertFalse(m.getAttributes().containsKey("注入"));
    }

    @Test
    @DisplayName("getActions/getAttributes 返回不可变视图，外部改不动")
    void returnedCollectionsAreUnmodifiable() {
        DomainManifest m = DomainManifest.of("eco", "经济", "",
                new ArrayList<ActionSpec>(Arrays.asList(ActionSpec.read("balance", "查余额"))),
                new LinkedHashMap<String, String>());

        assertThrows(UnsupportedOperationException.class,
                () -> m.getActions().add(ActionSpec.write("hack", "注入")));
        assertThrows(UnsupportedOperationException.class,
                () -> m.getAttributes().put("k", "v"));
    }

    @Test
    @DisplayName("actions 保持声明顺序：面板按声明顺序渲染")
    void actionsPreserveDeclarationOrder() {
        DomainManifest m = DomainManifest.of("eco", "经济", "", Arrays.asList(
                ActionSpec.read("z_first", ""),
                ActionSpec.read("a_second", ""),
                ActionSpec.read("m_third", "")), null);

        assertEquals("z_first", m.getActions().get(0).getName());
        assertEquals("a_second", m.getActions().get(1).getName());
        assertEquals("m_third", m.getActions().get(2).getName());
    }

    // ==================== findAction ====================

    @Test
    @DisplayName("findAction：未声明/空名返回 null，命中返回原规格")
    void findActionSemantics() {
        ActionSpec balance = ActionSpec.read("balance", "查余额");
        DomainManifest m = DomainManifest.of("eco", "经济", "", Arrays.asList(balance), null);

        assertSame(balance, m.findAction("balance"));
        assertNull(m.findAction(null), "null 不应抛异常，按「没有这个动作」处理");
        assertNull(m.findAction(""), "空串同理");
        assertNull(m.findAction("withdraw"), "未声明的动作返回 null 而不是抛错");
    }

    // ==================== equals / hashCode ====================

    @Test
    @DisplayName("内容相同的两份清单相等且 hashCode 一致（可安全用于缓存键/去重）")
    void equalityByContent() {
        DomainManifest a = DomainManifest.of("eco", "经济", "说明",
                Arrays.asList(ActionSpec.read("balance", "查余额")),
                mapOf("version", "1.0"));
        DomainManifest b = DomainManifest.of("eco", "经济", "说明",
                Arrays.asList(ActionSpec.read("balance", "查余额")),
                mapOf("version", "1.0"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(DomainManifest.of("mail", "经济", "说明",
                Arrays.asList(ActionSpec.read("balance", "查余额")), mapOf("version", "1.0"))),
                "domain 不同即不相等");
    }

    private static Map<String, String> mapOf(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }
}
