package top.wcpe.mc.plugin.serverprobe.api.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActionSpec} 的契约测试。
 *
 * <p>本类里有一条**安全语义**必须钉死：未声明风险等级时 {@link ActionSpec#getRisk()} 返回
 * {@link RiskLevel#WRITE} 而不是 {@code READ}。方向反了的后果是面板把写操作当查询直接执行，
 * 风险控制彻底失效；而 {@link ActionSpec#getDeclaredRisk()} 仍保留 null，
 * 是为了让探针能区分「显式声明 WRITE」与「压根没声明」并在注册时告警——
 * 若在构造期就把 null 抹成 WRITE，那个告警就永远发不出来了。</p>
 */
@DisplayName("ActionSpec 契约：风险 fail-safe / null 归一 / 错误码白名单")
class ActionSpecTest {

    // ==================== 风险等级（安全语义） ====================

    @Test
    @DisplayName("未声明风险时 getRisk() 必须按 WRITE 处理，绝不能默认 READ")
    void undeclaredRiskDefaultsToWrite() {
        ActionSpec spec = ActionSpec.of("purge", "清库", null, false, null, null, null);

        assertEquals(RiskLevel.WRITE, spec.getRisk(),
                "未知风险按写操作处理是 fail-safe 方向；默认 READ 会让面板把写操作当查询直接执行");
    }

    @Test
    @DisplayName("未声明风险时 getDeclaredRisk() 仍为 null，且 hasDeclaredRisk() 为 false")
    void undeclaredRiskStaysNullForWarning() {
        ActionSpec spec = ActionSpec.of("purge", "清库", null, false, null, null, null);

        assertNull(spec.getDeclaredRisk(),
                "构造期若把 null 抹成 WRITE，探针就无法在注册时对「未声明风险」记 WARN");
        assertFalse(spec.hasDeclaredRisk());
    }

    @Test
    @DisplayName("显式声明风险时原值生效，且 hasDeclaredRisk() 为 true")
    void explicitRiskIsHonoured() {
        ActionSpec read = ActionSpec.of("balance", "查余额", RiskLevel.READ, false, null, null, null);
        assertEquals(RiskLevel.READ, read.getRisk());
        assertEquals(RiskLevel.READ, read.getDeclaredRisk());
        assertTrue(read.hasDeclaredRisk());

        ActionSpec destructive = ActionSpec.of("drop", "删库", RiskLevel.DESTRUCTIVE, false, null, null, null);
        assertEquals(RiskLevel.DESTRUCTIVE, destructive.getRisk());
    }

    @Test
    @DisplayName("read()/write() 简写分别产出 READ / WRITE 且均非幂等")
    void shorthandFactories() {
        ActionSpec r = ActionSpec.read("balance", "查余额");
        assertEquals(RiskLevel.READ, r.getRisk());
        assertFalse(r.getIdempotent(), "只读动作本无副作用，声明幂等没有意义");

        ActionSpec w = ActionSpec.write("deposit", "存款");
        assertEquals(RiskLevel.WRITE, w.getRisk());
        assertFalse(w.getIdempotent());
    }

    // ==================== null 归一 ====================

    @Test
    @DisplayName("字符串字段为 null 时归一为空串")
    void nullStringsBecomeEmpty() {
        ActionSpec spec = ActionSpec.of(null, null, null, false, null, null, null);
        assertEquals("", spec.getName());
        assertEquals("", spec.getDescription());
        assertEquals("", spec.getInputSchema());
        assertEquals("", spec.getOutputSchema());
    }

    @Test
    @DisplayName("errorCodes 为 null 时归一为空集合，而不是 null")
    void nullErrorCodesBecomeEmpty() {
        ActionSpec spec = ActionSpec.of("balance", "", null, false, null, null, null);
        assertTrue(spec.getErrorCodes().isEmpty());
        assertFalse(spec.declaresAnyErrorCode());
    }

    @Test
    @DisplayName("hasInputSchema 按空串判定：未声明 schema 时为 false")
    void hasInputSchemaByEmptiness() {
        assertFalse(ActionSpec.of("a", "", null, false, null, null, null).hasInputSchema());
        assertFalse(ActionSpec.of("a", "", null, false, "", null, null).hasInputSchema());
        assertTrue(ActionSpec.of("a", "", null, false, "{\"type\":\"object\"}", null, null).hasInputSchema());
    }

    // ==================== 错误码白名单 ====================

    @Test
    @DisplayName("allowsErrorCode：null/空串一律视为未声明，不抛异常")
    void allowsErrorCodeRejectsNullAndEmpty() {
        ActionSpec spec = ActionSpec.of("a", "", null, false, null, null, errorCodesOf("NOT_FOUND"));

        assertFalse(spec.allowsErrorCode(null), "null 必须返回 false 而不是 NPE——它在回执校验路径上");
        assertFalse(spec.allowsErrorCode(""));
        assertTrue(spec.allowsErrorCode("NOT_FOUND"));
        assertFalse(spec.allowsErrorCode("SOME_OTHER"));
    }

    @Test
    @DisplayName("未声明任何错误码时，任何码都不在白名单内")
    void noDeclaredCodesMeansNothingAllowed() {
        ActionSpec spec = ActionSpec.of("a", "", null, false, null, null, null);
        assertFalse(spec.allowsErrorCode("NOT_FOUND"));
    }

    @Test
    @DisplayName("errorCodes 保持声明顺序且不可变；errorCodeList 与之顺序一致")
    void errorCodesPreserveOrderAndAreImmutable() {
        ActionSpec spec = ActionSpec.of("a", "", null, false, null, null,
                errorCodesOf("FIRST", "SECOND", "THIRD"));

        List<String> list = spec.errorCodeList();
        assertEquals(Arrays.asList("FIRST", "SECOND", "THIRD"), list);
        assertThrows(UnsupportedOperationException.class, () -> spec.getErrorCodes().add("INJECTED"));
        assertThrows(UnsupportedOperationException.class, () -> spec.errorCodeList().add("INJECTED"));
    }

    @Test
    @DisplayName("构造后再改调用方的 errorCodes 集合，不影响已声明的规格（防御性拷贝）")
    void mutatingSourceErrorCodesDoesNotAffectSpec() {
        Set<String> codes = new LinkedHashSet<String>();
        codes.add("ORIGINAL");

        ActionSpec spec = ActionSpec.of("a", "", null, false, null, null, codes);
        codes.add("ADDED_LATER");

        assertTrue(spec.allowsErrorCode("ORIGINAL"));
        assertFalse(spec.allowsErrorCode("ADDED_LATER"), "构造后新增的码不得进入白名单");
    }

    // ==================== equals / hashCode ====================

    @Test
    @DisplayName("内容相同的两份规格相等且 hashCode 一致")
    void equalityByContent() {
        ActionSpec a = ActionSpec.of("balance", "查余额", RiskLevel.READ, true,
                "{\"type\":\"object\"}", "", errorCodesOf("NOT_FOUND"));
        ActionSpec b = ActionSpec.of("balance", "查余额", RiskLevel.READ, true,
                "{\"type\":\"object\"}", "", errorCodesOf("NOT_FOUND"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(ActionSpec.of("balance", "查余额", RiskLevel.WRITE, true,
                "{\"type\":\"object\"}", "", errorCodesOf("NOT_FOUND"))), "风险不同即不相等");
    }

    private static Set<String> errorCodesOf(String... codes) {
        return new LinkedHashSet<String>(Arrays.asList(codes));
    }
}
