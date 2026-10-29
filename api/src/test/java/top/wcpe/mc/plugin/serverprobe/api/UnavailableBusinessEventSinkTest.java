package top.wcpe.mc.plugin.serverprobe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.plugin.serverprobe.api.bridge.EmitReceipt;

/**
 * 降级单例的强语义入口测试。
 *
 * <p>放在本包是因为 {@link UnavailableBusinessEventSink} 是包私有枚举（刻意的：
 * 它只能由 {@link BridgeServices} 的 {@code default} 方法返回，不应被外部直接引用）。</p>
 *
 * <p>钉住的是一条容易被“默认实现”覆盖掉的语义：探针未就绪<b>不是</b>通道饱和。
 * 若两者混为一谈，调用方（尤其是回灌循环）会依据 {@code isRetryable()} 对探针永久缺失
 * 这种情形做无限退避重试。</p>
 */
class UnavailableBusinessEventSinkTest {

    private static final Map<String, String> EMPTY = Collections.emptyMap();

    @Test
    @DisplayName("降级单例必须报“探针不可用”，不能报成可重试的饱和")
    void reportsProbeUnavailable() {
        EmitReceipt receipt = UnavailableBusinessEventSink.INSTANCE.emitReceipt("demo", "k", null, EMPTY);
        assertFalse(receipt.isAccepted());
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, receipt.getReason());
        assertFalse(receipt.isRetryable(), "探针永久缺失时让调用方无限重试是错的");
    }

    @Test
    @DisplayName("可靠通道的降级原因同样为探针不可用")
    void reliableReportsProbeUnavailable() {
        EmitReceipt receipt = UnavailableBusinessEventSink.INSTANCE.emitReliableReceipt("demo", "k", null, EMPTY);
        assertFalse(receipt.isAccepted());
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, receipt.getReason());
        assertFalse(receipt.isRetryable());
    }

    @Test
    @DisplayName("布尔入口仍然一律 false，既有调用方行为不变")
    void booleanEntriesStillReject() {
        assertFalse(UnavailableBusinessEventSink.INSTANCE.emit("demo", "k", null, EMPTY));
        assertFalse(UnavailableBusinessEventSink.INSTANCE.emitReliable("demo", "k", null, EMPTY));
        assertFalse(UnavailableBusinessEventSink.INSTANCE.emitReliable("demo", "k", null, EMPTY, 1L));
        // 反向确认：强语义入口给出的是具体原因，而不是“已受理”的假象。
        assertTrue(!UnavailableBusinessEventSink.INSTANCE.emitReceipt("demo", "k", null, EMPTY).isAccepted());
    }
}
