package top.wcpe.mc.plugin.serverprobe.api;

import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink;
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost;
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadRegistry;

/**
 * 探针实现向 {@code api} 注册的服务集合。
 *
 * <p>用聚合接口而非多个 install 参数：将来新增子门面只加 {@code default} 方法，
 * 不破坏 {@link ServerProbeAPI#install(BridgeServices)} 的签名。</p>
 *
 * <h2>演进规则（违反即破约）</h2>
 * <ol>
 *   <li>只加 {@code default} 方法，绝不改已有方法签名/返回类型。</li>
 *   <li>{@code default} 必须返回该子门面的<b>独立降级单例</b>，
 *       <b>不得</b>写成 {@code UnavailableBridgeServices.INSTANCE.同方法()}——
 *       那会在未覆写时递归到自身并抛 {@code StackOverflowError}。</li>
 *   <li>降级单例行为：查询类返回空值/未知；注册类返回失效句柄；上报类返回 false。</li>
 * </ol>
 */
public interface BridgeServices {

    /** 业务域宿主门面。 */
    BusinessHost businessHost();

    /**
     * 业务事件上报门面。
     *
     * <p>未覆写时返回降级的 {@link UnavailableBusinessEventSink}（两个方法一律返回 false）。
     * 覆写后返回探针的真实实现。</p>
     */
    default BusinessEventSink eventSink() {
        return UnavailableBusinessEventSink.INSTANCE;
    }

    /**
     * 配置重载监听器注册表门面。
     *
     * <p>未覆写时返回降级的 {@link UnavailableConfigReloadRegistry}（注册一律得失效句柄）。
     * 覆写后返回探针的真实实现。</p>
     */
    default ConfigReloadRegistry configReloads() {
        return UnavailableConfigReloadRegistry.INSTANCE;
    }

    /**
     * 只读数据门面。
     *
     * <p>未覆写时返回降级的 {@link UnavailableProbeReadApi}（查询一律无数据、绝不抛异常）。
     * 覆写后返回探针的真实实现。</p>
     */
    default ProbeReadApi read() {
        return UnavailableProbeReadApi.INSTANCE;
    }
}
