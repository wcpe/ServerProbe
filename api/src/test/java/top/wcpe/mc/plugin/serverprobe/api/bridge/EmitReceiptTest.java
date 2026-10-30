package top.wcpe.mc.plugin.serverprobe.api.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EmitReceipt} 与 {@code BusinessEventSink} 强语义入口的契约测试。
 *
 * <p>这些断言钉住的是“为什么没受理”与“该不该重试”这两个对外承诺：
 * 一旦有人把它们改成一律返回 REJECTED_SATURATED，回灌调用方就会退化成无限重试。</p>
 */
class EmitReceiptTest {

    private static final Map<String, String> EMPTY = Collections.emptyMap();

    @Test
    @DisplayName("受理凭据：已受理时 accepted 为真、原因恒为 ACCEPTED、无序号")
    void acceptedHasNoSeqWhenDiscardable() {
        EmitReceipt receipt = EmitReceipt.accepted();
        assertTrue(receipt.isAccepted());
        assertEquals(EmitReceipt.Reason.ACCEPTED, receipt.getReason());
        assertEquals(0L, receipt.getSeq(), "可丢事件不占序列");
        assertEquals(0L, receipt.getSeqEpoch());
        assertFalse(receipt.isRetryable(), "已受理不需要重试");
    }

    @Test
    @DisplayName("受理凭据：可靠事件回传探针分配的序号，供业务侧做本地水位线")
    void acceptedReliableCarriesSeq() {
        EmitReceipt receipt = EmitReceipt.accepted(1024L, 1L);
        assertTrue(receipt.isAccepted());
        assertEquals(1024L, receipt.getSeq());
        assertEquals(1L, receipt.getSeqEpoch());
    }

    @Test
    @DisplayName("只有通道饱和值得重试；参数类失败重试一万次也不会成功")
    void onlySaturatedIsRetryable() {
        assertTrue(EmitReceipt.rejected(EmitReceipt.Reason.REJECTED_SATURATED).isRetryable());
        assertFalse(EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE).isRetryable(),
                "探针未就绪时重试无意义，否则调用方会无限退避");
        assertFalse(EmitReceipt.rejected(EmitReceipt.Reason.INVALID_DOMAIN).isRetryable(),
                "域名非法是调用方 bug，重试无用");
        assertFalse(EmitReceipt.rejected(EmitReceipt.Reason.MISSING_DEDUP_KEY).isRetryable(),
                "缺去重键是调用方 bug，重试无用");
        assertFalse(EmitReceipt.rejected(EmitReceipt.Reason.INVALID_SEQUENCE).isRetryable());
    }

    @Test
    @DisplayName("原因永不为 null：传 null 保守归为探针不可用")
    void reasonNeverNull() {
        EmitReceipt receipt = EmitReceipt.rejected(null);
        assertNotNull(receipt.getReason());
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, receipt.getReason());
        assertFalse(receipt.isAccepted());
    }

    @Test
    @DisplayName("从参数反推原因：先判定“必定失败”的两种，其余保守归为饱和")
    void inferRejectionOrder() {
        assertEquals(EmitReceipt.Reason.INVALID_DOMAIN, EmitReceipt.inferRejection(null, "k"));
        assertEquals(EmitReceipt.Reason.INVALID_DOMAIN, EmitReceipt.inferRejection("", "k"));
        assertEquals(EmitReceipt.Reason.MISSING_DEDUP_KEY, EmitReceipt.inferRejection("demo", null));
        assertEquals(EmitReceipt.Reason.MISSING_DEDUP_KEY, EmitReceipt.inferRejection("demo", ""));
        assertEquals(EmitReceipt.Reason.REJECTED_SATURATED, EmitReceipt.inferRejection("demo", "k"),
                "参数看着没问题时归为可重试，宁可多试一次也不能误判为永久失败");
    }

    @Test
    @DisplayName("equals/hashCode 把序号纳入比较：两条不同位点的事件不得相等")
    void equalityIncludesSeq() {
        assertEquals(EmitReceipt.accepted(1L, 1L), EmitReceipt.accepted(1L, 1L));
        assertEquals(EmitReceipt.accepted(1L, 1L).hashCode(), EmitReceipt.accepted(1L, 1L).hashCode());
        assertNotEquals(EmitReceipt.accepted(1L, 1L), EmitReceipt.accepted(2L, 1L));
        assertNotEquals(EmitReceipt.accepted(1L, 1L), EmitReceipt.rejected(EmitReceipt.Reason.REJECTED_SATURATED));
    }

    @Test
    @DisplayName("accepted() 复用同一实例，避免高频上报路径上的无谓分配")
    void acceptedSingleton() {
        assertSame(EmitReceipt.accepted(), EmitReceipt.accepted());
    }

    @Test
    @DisplayName("默认实现能正确区分“探针没就绪”与“通道饱和”")
    void defaultReceiptDistinguishesUnavailableFromSaturated() {
        BusinessEventSink unavailable = new RejectingSink(false);
        EmitReceipt whenProbeMissing = unavailable.emitReceipt("demo", "k", null, EMPTY);
        assertEquals(EmitReceipt.Reason.REJECTED_SATURATED, whenProbeMissing.getReason(),
                "未覆写强语义入口时只能保守归为可重试");

        EmitReceipt onBadDomain = unavailable.emitReceipt("", "k", null, EMPTY);
        assertEquals(EmitReceipt.Reason.INVALID_DOMAIN, onBadDomain.getReason(),
                "空域是能从参数直接判定的");
    }

    /** 一个只会在布尔入口返回 false 的最小实现，用于验证接口默认实现。 */
    private static final class RejectingSink implements BusinessEventSink {

        private final boolean accept;

        RejectingSink(boolean accept) {
            this.accept = accept;
        }

        @Override
        public boolean emit(String domain, String dedupKey, String topic, Map<String, String> fields) {
            return accept;
        }

        @Override
        public boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields) {
            return accept;
        }
    }
}
