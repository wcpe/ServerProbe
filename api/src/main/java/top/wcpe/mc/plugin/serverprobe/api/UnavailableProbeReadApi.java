package top.wcpe.mc.plugin.serverprobe.api;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery;
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.AggregatedMetrics;
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot;
import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile;

/**
 * 探针未就绪时的只读数据降级单例。
 *
 * <p>逐方法给出安全返回值，<b>绝不返回 null 集合、绝不抛异常</b>：</p>
 * <ul>
 *   <li>对象查询（快照／画像／对比摘要）返回 {@code null}——与各接口"无数据时为 null"的约定一致；</li>
 *   <li>列表查询返回空列表——与各接口"无数据时为空列表"的约定一致；</li>
 *   <li>{@link #aggregated(int)} 返回"空窗口"聚合（份数 0、其余统计项 null），
 *       因为该方法的契约是<b>结果恒非空</b>；</li>
 *   <li>{@link #queryNetworkPackets(NetworkPacketQuery)} 返回 {@link NetworkPacketPage#empty()}；</li>
 *   <li>{@link #networkForensicsStatus()} 返回"明确不可用"的状态，而非伪造一个可用状态。</li>
 * </ul>
 *
 * <p><b>为什么放在 {@code api} 包且是包私有枚举</b>：
 * {@link BridgeServices#read()} 的 {@code default} 必须返回<b>本单例</b>，
 * 经 {@code UnavailableBridgeServices} 之类的中间态转发会在未覆写时递归到自身并抛
 * {@code StackOverflowError}（见 {@link BridgeServices} 的演进规则）。</p>
 *
 * <p>首次被调用时告警一次：查询一律无数据若完全静默，外部消费方只会看到"面板是空的"，
 * 那是最难定位的一类故障。</p>
 */
enum UnavailableProbeReadApi implements ProbeReadApi {

    INSTANCE;

    /** 是否已告警（只提示一次，避免被高频轮询放大成日志风暴）。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Override
    public MetricSnapshot latestSnapshot() {
        warnOnce();
        return null;
    }

    @Override
    public List<MetricSnapshot> recentSnapshots(int limit) {
        warnOnce();
        return Collections.emptyList();
    }

    @Override
    public List<MetricSnapshot> recentSnapshotsSince(long sinceMs) {
        warnOnce();
        return Collections.emptyList();
    }

    @Override
    public AggregatedMetrics aggregated(int windowSize) {
        warnOnce();
        // 该方法的契约是"恒非空"，故返回空窗口聚合而不是 null。
        return AggregatedMetrics.builder()
                .windowSampleCount(0)
                .tpsAvg(null)
                .msptP95(null)
                .msptP99(null)
                .gcYoungRatePerSec(null)
                .gcOldRatePerSec(null)
                .gcYoungTimeRatePerSec(null)
                .gcOldTimeRatePerSec(null)
                .build();
    }

    @Override
    public StartupProfile lastStartupProfile() {
        warnOnce();
        return null;
    }

    @Override
    public List<StartupProfile> historyStartupProfiles(int limit) {
        warnOnce();
        return Collections.emptyList();
    }

    @Override
    public String lastStartupComparisonSummary() {
        warnOnce();
        return null;
    }

    @Override
    public NetworkPacketPage queryNetworkPackets(NetworkPacketQuery query) {
        warnOnce();
        return NetworkPacketPage.empty();
    }

    @Override
    public NetworkForensicsStatus networkForensicsStatus() {
        warnOnce();
        return NetworkForensicsStatus.unavailable("ServerProbe 尚未就绪或已卸载");
    }

    /** 一次性告警：提示调用方探针未安装、未接线或尚未就绪。 */
    private void warnOnce() {
        if (WARNED.compareAndSet(false, true)) {
            java.util.logging.Logger.getLogger(UnavailableProbeReadApi.class.getName()).warning(
                    "ServerProbe 只读门面在未就绪状态被调用：查询一律返回无数据，"
                            + "请在其就绪后重试（可用 ServerProbeAPI.awaitAvailable 等待）");
        }
    }
}
