package top.wcpe.mc.plugin.serverprobe.api;

import java.util.concurrent.atomic.AtomicBoolean;

import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener;
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadRegistry;

/**
 * 未就绪时的配置重载注册表降级单例。
 *
 * <p>{@link #register(ConfigReloadListener)} 一律返回失效句柄（{@code PROBE_UNAVAILABLE}），
 * 绝不返回 null、绝不抛异常。</p>
 *
 * <p><b>为什么放在 {@code api} 包且是包私有枚举</b>：
 * {@link BridgeServices#configReloads()} 的 {@code default} 必须返回<b>本单例</b>，
 * 经 {@code UnavailableBridgeServices} 转发会在未覆写时递归到自身并抛 {@code StackOverflowError}
 * （见 {@link BridgeServices} 的演进规则）。</p>
 *
 * <p>首次被调用时告警一次：探针忘了接线会让每一次注册都静默失效，
 * 那是最难定位的一类故障。</p>
 */
enum UnavailableConfigReloadRegistry implements ConfigReloadRegistry {

    INSTANCE;

    /** 是否已告警（只提示一次，避免被注册量放大成日志风暴）。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Override
    public Registration register(ConfigReloadListener listener) {
        warnOnce();
        return UnavailableRegistration.INSTANCE;
    }

    /** 一次性告警：提示调用方探针未安装或未接线配置重载。 */
    private void warnOnce() {
        if (WARNED.compareAndSet(false, true)) {
            java.util.logging.Logger.getLogger(UnavailableConfigReloadRegistry.class.getName()).warning(
                    "ServerProbe 配置重载门面在未就绪状态被调用：监听器不会被注册，"
                            + "请在其就绪后重试（可用 ServerProbeAPI.awaitAvailable 等待）");
        }
    }

    /** 未就绪时返回的失效句柄。 */
    private enum UnavailableRegistration implements Registration {

        INSTANCE;

        @Override
        public boolean getActive() {
            return false;
        }

        @Override
        public InactiveReason getInactiveReason() {
            return InactiveReason.PROBE_UNAVAILABLE;
        }

        @Override
        public String getInactiveDetail() {
            return "ServerProbe 尚未就绪或已卸载";
        }

        @Override
        public void close() {
            // 失效句柄的 close 是无操作：它从未真正注册过任何东西。
        }
    }
}
