package top.wcpe.mc.plugin.serverprobe.api;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink;
import top.wcpe.mc.plugin.serverprobe.api.bridge.EmitReceipt;

/**
 * 未就绪时的事件上报降级单例。
 *
 * <p>两个方法一律返回 {@code false}（"未受理"），绝不返回 null、绝不抛异常。
 * 语义上"未受理"与"通道饱和"共用 {@code false}，两者对调用方的后果一致：
 * 可丢事件可以直接丢，不可丢事件须转为业务失败。</p>
 *
 * <p><b>为什么放在 {@code api} 包且是包私有枚举</b>：
 * {@link BridgeServices#eventSink()} 的 {@code default} 必须返回<b>本单例</b>，
 * 经 {@code UnavailableBridgeServices} 转发会在未覆写时递归到自身并抛 {@code StackOverflowError}
 * （见 {@link BridgeServices} 的演进规则）。</p>
 *
 * <p>首次被调用时告警一次：探针忘了接线会让每一次业务上报都静默返回 false，
 * 那是最难定位的一类故障。</p>
 */
enum UnavailableBusinessEventSink implements BusinessEventSink {

    INSTANCE;

    /** 是否已告警（只提示一次，避免被事件量放大成日志风暴）。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Override
    public boolean emit(String domain, String dedupKey, String topic, Map<String, String> fields) {
        warnOnce();
        return false;
    }

    @Override
    public boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields) {
        warnOnce();
        return false;
    }

    @Override
    public boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields, long seq) {
        warnOnce();
        return false;
    }

    /**
     * 强语义入口：明确告知“探针不可用”。
     *
     * <p>这里必须覆写，不能靠默认实现推断。默认实现只能从参数看出“域为空”与“缺 dedupKey”，
     * 其余一律归为“通道饱和”（可重试）——但在本单例里唯一真实的原因就是探针未就绪，
     * 而它<b>重试无意义</b>。把两者混为一谈，会让调用方在探针永久缺失时无限退避重试。</p>
     */
    @Override
    public EmitReceipt emitReceipt(String domain, String dedupKey, String topic, Map<String, String> fields) {
        warnOnce();
        return EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE);
    }

    /** 强语义入口：明确告知“探针不可用”（同上）。 */
    @Override
    public EmitReceipt emitReliableReceipt(
            String domain, String dedupKey, String topic, Map<String, String> fields) {
        warnOnce();
        return EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE);
    }

    /** 一次性告警：提示调用方探针未安装或未接线事件上报。 */
    private void warnOnce() {
        if (WARNED.compareAndSet(false, true)) {
            java.util.logging.Logger.getLogger(UnavailableBusinessEventSink.class.getName()).warning(
                    "ServerProbe 事件上报门面在未就绪状态被调用：事件不会被上送，"
                            + "请在其就绪后重试（可用 ServerProbeAPI.awaitAvailable 等待）");
        }
    }
}
