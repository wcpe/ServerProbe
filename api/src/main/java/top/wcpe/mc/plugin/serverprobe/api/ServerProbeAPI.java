package top.wcpe.mc.plugin.serverprobe.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest;
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult;
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink;
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost;
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider;
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainStatus;
import top.wcpe.mc.plugin.serverprobe.api.bridge.EmitReceipt;
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProvider;
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery;
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.AggregatedMetrics;
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot;
import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile;
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener;
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadRegistry;

/**
 * ServerProbe 对外门面的唯一入口。
 *
 * <h2>可安全缓存</h2>
 * <p>所有访问器返回的是<b>稳定委派代理</b>，不是当时状态的快照。因此下面是正确用法：</p>
 * <pre>{@code
 * // onEnable 里缓存一次；即使探针后加载，同一引用也能正常工作
 * private final BusinessHost host = ServerProbeAPI.businessHost();
 *
 * public void onEnable() {
 *     registration = host.register(new MyProvider());
 * }
 * }</pre>
 * <p>若换成"未就绪时返回降级快照"，缓存一次就永久失效——且没有任何异常或日志提示，
 * 是典型的静默失败。这是本类刻意采用代理而非快照的原因。</p>
 *
 * <p><b>{@link #read()} 与 {@link #businessHost()} 同级</b>：两者都是稳定代理、都可缓存为字段。
 * 只读数据（快照／历史／启动画像／取证）一律走 {@link #read()}，业务注册走 {@link #businessHost()}。</p>
 *
 * <h2>探针后加载怎么办</h2>
 * <p>若注册时探针尚未就绪，句柄 {@code getActive()==false}、原因为
 * {@link Registration.InactiveReason#PROBE_UNAVAILABLE}。两种处理：</p>
 * <ul>
 *   <li>用 {@link #awaitAvailable(long)} 等待就绪后重试注册（推荐）；</li>
 *   <li>或直接忽略——探针不存在时也没有业务可接。</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <p>探针 ENABLE 时 {@link #install(BridgeServices)}，DISABLE 时 {@link #uninstall()}。
 * {@code uninstall()} 会清空注册表并把所有在册句柄置为失效；再次 install 后注册表为空，
 * <b>不复活</b>旧句柄。</p>
 *
 * <p><b>线程安全</b>：所有静态方法可被任意线程调用，均不抛异常。</p>
 *
 * <h2>装配入口的可见性（这不是安全边界）</h2>
 * <p>{@link #install(BridgeServices)} 与 {@link #uninstall()} 是<b>包内可见</b>（package-private），
 * 唯一的合法调用方是同包的 {@code top.wcpe.mc.plugin.serverprobe.api.BridgeFacadeInstaller}
 * （位于 plugin 模块，因 classpath 模式下同包可跨 jar 合并，故运行时同属一个包）。</p>
 *
 * <p><b>本探针不做进程内沙箱。</b>同一 JVM 内的插件若刻意声明同名包并自行构造
 * {@code top.wcpe.mc.plugin.serverprobe.api} 下的类，<b>仍可</b>调用这两个方法并顶替探针实现。
 * 可见性收窄带来的只是误用成本变化：从"一行 public 调用"变成"必须显式声明同包类型、
 * 并与 api 包发生包名冲突"。<b>凡涉及跨插件的信任，请按"同 JVM 代码皆不可信"处理，
 * 不要引用本类的调用权限作为防线。</b></p>
 */
public final class ServerProbeAPI {

    /** 当前探针实现；null 表示未安装（或已卸载）。 */
    private static volatile BridgeServices services;

    private ServerProbeAPI() {
    }

    /** 探针是否已就绪。实时查询，可用于轮询。 */
    public static boolean isAvailable() {
        return services != null;
    }

    /**
     * 有界等待探针就绪。
     *
     * @param timeoutMs 最长等待毫秒；{@code <= 0} 表示只查当前状态、不阻塞
     * @return true=已就绪；false=超时仍未就绪
     */
    public static boolean awaitAvailable(long timeoutMs) {
        if (services != null) {
            return true;
        }
        if (timeoutMs <= 0L) {
            return false;
        }
        // 轻量轮询而非锁/条件变量:安装是一次性事件,轮询开销可忽略,
        // 也避免在 API 模块里引入并发原语的生命周期管理。
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (services != null) {
                return true;
            }
            try {
                Thread.sleep(AWAIT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return services != null;
            }
        }
        return services != null;
    }

    /**
     * 业务域宿主门面。
     *
     * <p>返回稳定代理：<b>永不返回 null</b>，可安全缓存为字段。探针未就绪时其行为等价于
     * "注册得到失效句柄 / 派发返回失败"。</p>
     */
    public static BusinessHost businessHost() {
        return BusinessHostProxy.INSTANCE;
    }

    /**
     * 业务事件上报门面。
     *
     * <p>返回稳定代理：<b>永不返回 null</b>，可安全缓存为字段。探针未就绪时其行为等价于
     * "一律未受理"（两个方法返回 {@code false}）。</p>
     *
     * <p>方法名不带 {@code get} 前缀，与 {@link #businessHost()} 及
     * {@code BridgeServices.eventSink()} 保持一致（接口查询方法无 {@code get}）。</p>
     */
    public static BusinessEventSink eventSink() {
        return BusinessEventSinkProxy.INSTANCE;
    }

    /**
     * 配置重载监听器注册表门面。
     *
     * <p>返回稳定代理：<b>永不返回 null</b>，可安全缓存为字段。探针未就绪时其行为等价于
     * "注册得到失效句柄"。</p>
     *
     * <p>方法名与 {@code BridgeServices.configReloads()} 一致（接口查询方法无 {@code get} 前缀）。</p>
     */
    public static ConfigReloadRegistry configReloads() {
        return ConfigReloadRegistryProxy.INSTANCE;
    }

    /**
     * 只读数据门面。
     *
     * <p>返回稳定代理：<b>永不返回 null</b>，可安全缓存为字段。它与 {@link #businessHost()}
     * <b>同级同范式</b>——都是稳定代理，都允许在 {@code onEnable} 里缓存一次并长期持有。</p>
     *
     * <p>探针未就绪或已卸载时全部查询降级为"无数据"：快照／启动画像／对比摘要返回 {@code null}，
     * 列表返回空列表，{@link ProbeReadApi#aggregated(int)} 返回空窗口聚合（份数 0），
     * 取证查询返回空页、状态报告"未启用"。<b>任意线程调用均不抛异常。</b></p>
     *
     * <p>方法名与 {@code BridgeServices.read()} 一致（接口查询方法无 {@code get} 前缀）。</p>
     */
    public static ProbeReadApi read() {
        return ProbeReadApiProxy.INSTANCE;
    }

    /**
     * 注册探针实现。
     *
     * <p><b>包内可见</b>：仅同包的 {@code BridgeFacadeInstaller} 调用。可见性收窄只是降低误用概率，
     * <b>不构成安全边界</b>——同 JVM 的代码若刻意声明同包仍能调用，详见类注释。</p>
     *
     * @param impl 探针实现；null 视为卸载
     */
    static void install(BridgeServices impl) {
        if (impl == null) {
            uninstall();
            return;
        }
        services = impl;
    }

    /** 注销探针实现；幂等。包内可见，仅同包的 {@code BridgeFacadeInstaller} 调用。 */
    static void uninstall() {
        services = null;
    }

    /** 记录告警（api 模块不依赖日志框架，走 JDK 日志）。 */
    private static void warn(String message) {
        java.util.logging.Logger.getLogger(ServerProbeAPI.class.getName()).warning(message);
    }

    /** 等待轮询间隔（毫秒）。 */
    private static final long AWAIT_POLL_MS = 20L;

    /**
     * 业务宿主稳定代理。
     *
     * <p><b>无状态</b>：每个方法都实时读取 {@link #services}，因此缓存引用的外部插件总能感知
     * 探针的 install/uninstall。</p>
     */
    private static final class BusinessHostProxy implements BusinessHost {

        private static final BusinessHostProxy INSTANCE = new BusinessHostProxy();

        @Override
        public Registration register(BusinessProvider provider) {
            return delegate().register(provider);
        }

        @Override
        public Registration registerLegacy(LegacyBusinessProvider provider) {
            return delegate().registerLegacy(provider);
        }

        @Override
        public boolean unregister(BusinessProvider provider) {
            return delegate().unregister(provider);
        }

        @Override
        public Set<String> domains() {
            return delegate().domains();
        }

        @Override
        public Map<String, Object> manifest() {
            return delegate().manifest();
        }

        @Override
        public ActionResult dispatch(ActionRequest request) {
            return delegate().dispatch(request);
        }

        @Override
        public CompletableFuture<ActionResult> dispatchAsync(ActionRequest request) {
            return delegate().dispatchAsync(request);
        }

        @Override
        public DomainStatus healthOf(String domain) {
            try {
                return delegate().healthOf(domain);
            } catch (Throwable ignored) {
                // 契约要求永不抛：实现违约时降级为"未注册"，绝不让异常冒到查询方。
                return UnavailableBusinessHost.unregistered(domain, "健康状态不可用");
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private BusinessHost delegate() {
            BridgeServices current = services;
            return current == null ? UnavailableBusinessHost.INSTANCE : current.businessHost();
        }
    }

    /**
     * 业务事件上报稳定代理。
     *
     * <p><b>无状态</b>：每个方法都实时读取 {@link #services}，因此缓存引用的外部插件总能感知
     * 探针的 install/uninstall。本代理<b>绝不抛异常</b>：委派实现违约抛出时也只降级为 false。</p>
     */
    private static final class BusinessEventSinkProxy implements BusinessEventSink {

        private static final BusinessEventSinkProxy INSTANCE = new BusinessEventSinkProxy();

        @Override
        public boolean emit(String domain, String dedupKey, String topic, Map<String, String> fields) {
            try {
                return delegate().emit(domain, dedupKey, topic, fields);
            } catch (Throwable ignored) {
                // 契约要求永不抛：实现违约时按"未受理"降级，绝不让异常冒到业务线程。
                return false;
            }
        }

        @Override
        public boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields) {
            try {
                return delegate().emitReliable(domain, dedupKey, topic, fields);
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override
        public boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields, long seq) {
            try {
                return delegate().emitReliable(domain, dedupKey, topic, fields, seq);
            } catch (Throwable ignored) {
                return false;
            }
        }

        /**
         * 强语义入口转发。
         *
         * <p>必须显式转发：接口的 {@code default} 会直接调本代理自己的 {@code emit}，
         * 而那是查过 {@code services} 的——但若不走这里，返回值就退化成“从参数反推原因”，
         * 丢掉真实实现知道的精确原因（尤其是委托对象抛异常时的归因）。</p>
         */
        @Override
        public EmitReceipt emitReceipt(String domain, String dedupKey, String topic, Map<String, String> fields) {
            try {
                return delegate().emitReceipt(domain, dedupKey, topic, fields);
            } catch (Throwable ignored) {
                return EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE);
            }
        }

        /** 强语义入口转发（同上）。 */
        @Override
        public EmitReceipt emitReliableReceipt(
                String domain, String dedupKey, String topic, Map<String, String> fields) {
            try {
                return delegate().emitReliableReceipt(domain, dedupKey, topic, fields);
            } catch (Throwable ignored) {
                return EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE);
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private BusinessEventSink delegate() {
            BridgeServices current = services;
            return current == null ? UnavailableBusinessEventSink.INSTANCE : current.eventSink();
        }
    }

    /**
     * 配置重载注册表稳定代理。
     *
     * <p><b>无状态</b>：每次注册都实时读取 {@link #services}，因此缓存引用的外部插件总能感知
     * 探针的 install/uninstall。本代理<b>绝不抛异常</b>：委派实现违约抛出时也降级为失效句柄。</p>
     */
    private static final class ConfigReloadRegistryProxy implements ConfigReloadRegistry {

        private static final ConfigReloadRegistryProxy INSTANCE = new ConfigReloadRegistryProxy();

        /** 委派违约时返回的失效句柄（与未就绪语义一致：探针侧没接住这次注册）。 */
        private static final Registration UNEXPECTED = new Registration() {

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
                return "ServerProbe 配置重载注册异常";
            }

            @Override
            public void close() {
                // 从未注册成功，close 无操作。
            }
        };

        @Override
        public Registration register(ConfigReloadListener listener) {
            try {
                return delegate().register(listener);
            } catch (Throwable ignored) {
                // 契约要求永不抛：实现违约时按"未就绪"降级，绝不让异常冒到插件 onEnable。
                return UNEXPECTED;
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private ConfigReloadRegistry delegate() {
            BridgeServices current = services;
            return current == null ? UnavailableConfigReloadRegistry.INSTANCE : current.configReloads();
        }
    }

    /**
     * 只读数据稳定代理。
     *
     * <p><b>无状态</b>：每个方法都实时读取 {@link #services}，因此缓存引用的外部插件总能感知
     * 探针的 install/uninstall。本代理<b>绝不抛异常</b>：委派实现违约抛出时，逐方法降级为
     * 该方法的"无数据"安全值（对象返回 null、列表返回空列表、聚合返回空窗口、取证返回空页）。</p>
     *
     * <p>降级值之所以逐方法给出而不是统一返回 null，是因为 {@link ProbeReadApi} 的九个方法
     * 返回契约各不相同：列表承诺空列表、{@link ProbeReadApi#aggregated(int)} 承诺恒非空、
     * {@link ProbeReadApi#networkForensicsStatus()} 承诺对象。统一 null 会让这些契约被破坏，
     * 调用方在"探针未就绪"这一最常见场景下直接 NPE。</p>
     */
    private static final class ProbeReadApiProxy implements ProbeReadApi {

        private static final ProbeReadApiProxy INSTANCE = new ProbeReadApiProxy();

        @Override
        public MetricSnapshot latestSnapshot() {
            try {
                return delegate().latestSnapshot();
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public List<MetricSnapshot> recentSnapshots(int limit) {
            try {
                return delegate().recentSnapshots(limit);
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }

        @Override
        public List<MetricSnapshot> recentSnapshotsSince(long sinceMs) {
            try {
                return delegate().recentSnapshotsSince(sinceMs);
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }

        /**
         * 历史归档回读。
         *
         * <p><b>必须显式覆写</b>：接口上它是 default 方法，未覆写时代理会直接落到默认实现、
         * 永远返回空列表——看上去“正常”，实则每查必空，且不会报错（典型的静默失败）。</p>
         */
        @Override
        public List<MetricSnapshot> historySnapshots(long sinceMs, long untilMs, int limit) {
            try {
                return delegate().historySnapshots(sinceMs, untilMs, limit);
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }

        @Override
        public AggregatedMetrics aggregated(int windowSize) {
            try {
                return delegate().aggregated(windowSize);
            } catch (Throwable ignored) {
                // 契约要求恒非空：违约时也要给出可读的空窗口，而不是 null。
                return UnavailableProbeReadApi.INSTANCE.aggregated(windowSize);
            }
        }

        @Override
        public StartupProfile lastStartupProfile() {
            try {
                return delegate().lastStartupProfile();
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public List<StartupProfile> historyStartupProfiles(int limit) {
            try {
                return delegate().historyStartupProfiles(limit);
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }

        @Override
        public String lastStartupComparisonSummary() {
            try {
                return delegate().lastStartupComparisonSummary();
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public NetworkPacketPage queryNetworkPackets(NetworkPacketQuery query) {
            try {
                return delegate().queryNetworkPackets(query);
            } catch (Throwable ignored) {
                return NetworkPacketPage.empty();
            }
        }

        @Override
        public NetworkForensicsStatus networkForensicsStatus() {
            try {
                return delegate().networkForensicsStatus();
            } catch (Throwable ignored) {
                return NetworkForensicsStatus.unavailable("取证状态查询异常");
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private ProbeReadApi delegate() {
            BridgeServices current = services;
            return current == null ? UnavailableProbeReadApi.INSTANCE : current.read();
        }
    }

    /**
     * 未安装时的降级业务宿主。
     *
     * <p>所有方法安全返回：注册得失效句柄、派发返回失败、集合为空。
     * 首次被调用时告警一次，避免"忘了接线"变成完全静默。</p>
     */
    private enum UnavailableBusinessHost implements BusinessHost {

        INSTANCE;

        private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public Registration register(BusinessProvider provider) {
            warnOnce();
            return UnavailableRegistration.INSTANCE;
        }

        @Override
        public Registration registerLegacy(LegacyBusinessProvider provider) {
            warnOnce();
            return UnavailableRegistration.INSTANCE;
        }

        @Override
        public boolean unregister(BusinessProvider provider) {
            warnOnce();
            return false;
        }

        @Override
        public Set<String> domains() {
            return Collections.emptySet();
        }

        @Override
        public Map<String, Object> manifest() {
            return Collections.emptyMap();
        }

        @Override
        public ActionResult dispatch(ActionRequest request) {
            warnOnce();
            return ActionResult.fail("ServerProbe 尚未就绪");
        }

        @Override
        public CompletableFuture<ActionResult> dispatchAsync(ActionRequest request) {
            CompletableFuture<ActionResult> future = new CompletableFuture<>();
            future.complete(ActionResult.fail("ServerProbe 尚未就绪"));
            return future;
        }

        @Override
        public DomainStatus healthOf(String domain) {
            return unregistered(domain, "ServerProbe 尚未就绪");
        }

        /** 未注册/不可用时的降级快照；本静态方法同时供代理的异常兜底复用。 */
        static DomainStatus unregistered(String domain, String reason) {
            return new DomainStatus(
                    domain == null ? "" : domain,
                    false,
                    ProviderHealth.unknown(),
                    reason,
                    0L,
                    null,
                    0L,
                    0L,
                    0L,
                    false,
                    //：无注册表就无清单可取，指纹必然缺席；
                    // 降级原因与 reason 同值，面板不必再拼一次呈现口径。
                    null,
                    0L,
                    reason);
        }

        private void warnOnce() {
            if (WARNED.compareAndSet(false, true)) {
                warn("ServerProbe 门面在未就绪状态被调用：业务注册不会生效，请在其就绪后重试"
                        + "（可用 ServerProbeAPI.awaitAvailable 等待）");
            }
        }
    }

    /** 未安装时返回的失效句柄。 */
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
            // 失效句柄的 close 是无操作:它从未真正注册过任何东西。
        }
    }
}
