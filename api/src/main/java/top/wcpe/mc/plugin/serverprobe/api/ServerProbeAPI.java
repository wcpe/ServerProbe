package top.wcpe.mc.plugin.serverprobe.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>什么都不用做</b>：访问器返回的是稳定代理，探针 install 之后同一引用自动按真实实现工作。
 * 唯一需要注意的是注册：若注册发生在探针就绪之前，句柄 {@code getActive()==false}、原因为
 * {@link Registration.InactiveReason#PROBE_UNAVAILABLE}，而探针 install 时<b>不会</b>回头补注册，
 * 因此需要在探针就绪之后重新注册一次。</p>
 *
 * <p><b>不要在主线程用 {@link #awaitAvailable(long)} 等它。</b>旧文档把"轮询等待就绪"写成推荐
 * 用法，是错的：它会把调用线程睡满，而最自然的落点（{@code onEnable}）正是服务器主线程，
 * 探针的 install 也需要主线程完成，于是主线程被占着 → 探针永远装不上 → 只能白等超时。
 * 理由详见该方法的 javadoc。</p>
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
     * <h2>禁止在服务器主线程调用</h2>
     * <p>本方法用 {@code Thread.sleep} 轮询，会把调用线程<b>实打实地睡满</b> {@code timeoutMs}。
     * 而它最自然的落点是 {@code onEnable}——那就在<b>主线程</b>上。探针若在其后加载，
     * 这里会把主线程堵满整个超时（TPS 直接归零），更糟的是<b>永远等不到</b>：
     * 探针的 install 同样要在主线程完成，主线程被占着它就没有机会执行，只能白等到超时。</p>
     *
     * <p><b>推荐做法：不要等。</b>{@link #businessHost()} / {@link #read()} 等访问器返回的是
     * <b>稳定代理</b>，本来就可以在 {@code onEnable} 里缓存成字段，探针后来就绪时同一引用
     * 自动生效——这正是本类采用代理而非快照的初衷。确实需要"就绪后再做事"，请在
     * <b>异步线程</b>（或调度器的下一 tick）里调用本方法。</p>
     *
     * <p>行为保持不变是刻意的：把"主线程调用"改成抛异常或直接返回 false 会打断已有的、
     * 在异步线程上正确使用它的调用方（E2E harness、面板侧的就绪探测等）。
     * 把坑写进文档、把误用的锅明确归到调用点，是影响面最小的做法。</p>
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

    /**
     * 记录错误级日志（api 模块不依赖日志框架，走 JDK 日志的 SEVERE）。
     *
     * <p>与 {@link #warn(String)} 分开是因为两者要能<b>按级别筛</b>：实现侧抛 {@link Error}
     * 说明它已经被打坏（JVM 级 / 类加载级），和"某个查询偶尔抛个 NPE"完全不是一回事。</p>
     */
    private static void severe(String message) {
        java.util.logging.Logger.getLogger(ServerProbeAPI.class.getName()).severe(message);
    }

    /**
     * 已告警过的降级点（去重键 = 方法名）。
     *
     * <p>用集合而不是布尔：一个实现坏掉时往往是"十个查询全抛"，只报一条会让人以为是单点故障。</p>
     */
    private static final Set<String> DEGRADED_REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * 委派实现违约、代理不得不降级时的<b>唯一</b>日志出口（按方法名去重）。
     *
     * <p>原来的写法是 17 处 {@code catch (Throwable ignored)}：日志零痕迹，面板只显示空列表/空值，
     * 运维看到的是"探针没数据"，而真相是"实现每次调用都在抛"——这是本类注释自己担心的
     * 那类静默失败，而且它会把实现侧的真 bug 伪装成"没有数据"。</p>
     *
     * <p>去重是必须的另一个方向：这些方法大多在面板轮询的热路径上（每秒数十次），
     * 一次故障立刻演变成日志风暴，真正的原因反而被淹掉。故同一方法只报一次（不随故障次数放大）。</p>
     *
     * <p>{@link Error} 与 {@link Exception} 分级：前者记为 SEVERE，后者记为 WARNING，
     * 之后都照常降级——契约是"绝不抛"，不能因为级别高就把异常放给调用方。</p>
     *
     * @param method  去重键，形如 {@code read.latestSnapshot}（不放方法签名：故障期换着花样抛不该绕过限频）
     * @param failure 被吞掉的异常；null 表示实现违约返回了 null
     */
    private static void degraded(String method, Throwable failure) {
        if (!DEGRADED_REPORTED.add(method)) {
            return;
        }
        String detail = failure == null
                ? "实现返回 null（违反接口契约）"
                : failure.getClass().getName() + ": " + failure.getMessage();
        String message = "ServerProbe 门面方法 " + method
                + " 委派失败，已降级为安全值（同一方法不再重复告警）：" + detail;
        if (failure instanceof Error) {
            severe(message);
        } else {
            warn(message);
        }
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
            try {
                return delegate().register(provider);
            } catch (Throwable failure) {
                // 原来这七个方法裸委派、无兜底，与 healthOf 的待遇完全不同：
                // 实现一旦违约（典型：核心侧 @PreDestroy 已 shutdownNow 业务池，
                // 而 services 尚未被 uninstall 置空这个窗口里调 dispatchAsync，
                // CompletableFuture.supplyAsync 直接抛 RejectedExecutionException），
                // 异常就穿透给外部插件——直接违反了类注释“均不抛异常”的承诺。
                degraded("businessHost.register", failure);
                return UnavailableRegistration.INSTANCE;
            }
        }

        @Override
        public Registration registerLegacy(LegacyBusinessProvider provider) {
            try {
                return delegate().registerLegacy(provider);
            } catch (Throwable failure) {
                degraded("businessHost.registerLegacy", failure);
                return UnavailableRegistration.INSTANCE;
            }
        }

        @Override
        public boolean unregister(BusinessProvider provider) {
            try {
                return delegate().unregister(provider);
            } catch (Throwable failure) {
                // 降级为 false：语义是“没移除”（该实例并非当前注册者）。
                // 不能返回 true——那会让调用方误以为已注销而不再重试。
                degraded("businessHost.unregister", failure);
                return false;
            }
        }

        @Override
        public Set<String> domains() {
            try {
                return delegate().domains();
            } catch (Throwable failure) {
                // 契约是“永不为 null”：降级为不可变空集，调用方无需判空。
                degraded("businessHost.domains", failure);
                return Collections.emptySet();
            }
        }

        @Override
        public Map<String, Object> manifest() {
            try {
                return delegate().manifest();
            } catch (Throwable failure) {
                // 降级形态与“未安装”一致（空映射）而不是自己拼一个 {"apiVersion":..,"domains":{}}：
                // 编造一个看起来合法的信封会让人相信“已经查过了、确实没有域”，
                // 而空映射配合 degraded 日志才诚实地表达“没拿到”。
                degraded("businessHost.manifest", failure);
                return Collections.emptyMap();
            }
        }

        @Override
        public ActionResult dispatch(ActionRequest request) {
            try {
                return delegate().dispatch(request);
            } catch (Throwable failure) {
                degraded("businessHost.dispatch", failure);
                return ActionResult.fail("ServerProbe 业务派发异常（已降级为失败）");
            }
        }

        @Override
        public CompletableFuture<ActionResult> dispatchAsync(ActionRequest request) {
            try {
                return delegate().dispatchAsync(request);
            } catch (Throwable failure) {
                degraded("businessHost.dispatchAsync", failure);
                // 必须返回一个**已完成的失败 Future**，不能返回 null：调用方按契约直接
                // thenApply/join，null 会把一次可控的降级变成它那边的 NPE。
                // 也不能让 Future 异常完成——契约写明“以失败结果完成，而非异常完成”。
                CompletableFuture<ActionResult> degradedFuture = new CompletableFuture<>();
                degradedFuture.complete(ActionResult.fail("ServerProbe 业务派发异常（已降级为失败）"));
                return degradedFuture;
            }
        }

        @Override
        public DomainStatus healthOf(String domain) {
            try {
                DomainStatus status = delegate().healthOf(domain);
                if (status == null) {
                    degraded("businessHost.healthOf", null);
                    return UnavailableBusinessHost.unregistered(domain, "健康状态不可用");
                }
                return status;
            } catch (Throwable failure) {
                // 契约要求永不抛：实现违约时降级为"未注册"，绝不让异常冒到查询方。
                degraded("businessHost.healthOf", failure);
                return UnavailableBusinessHost.unregistered(domain, "健康状态不可用");
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private BusinessHost delegate() {
            BridgeServices current = services;
            if (current == null) {
                return UnavailableBusinessHost.INSTANCE;
            }
            BusinessHost host = current.businessHost();
            // 原来只兜 services == null：实现违约让 businessHost() 返回 null 时，
            // 这里会把 null 原样交给上面的调用链，随后 NPE 从代理里冒出去——
            // 和未安装完全是同一件事（没有宿主可用），故按同一套降级处理。
            return host == null ? UnavailableBusinessHost.INSTANCE : host;
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
            } catch (Throwable failure) {
                // 契约要求永不抛：实现违约时按"未受理"降级，绝不让异常冒到业务线程。
                // 原来这里是 catch (Throwable ignored)：业务方只看到 emit 返回 false（和
                // “队列满”一模一样），日志无任何痕迹，实现侧的真 bug 被伪装成背压。
                degraded("eventSink.emit", failure);
                return false;
            }
        }

        @Override
        public boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields) {
            try {
                return delegate().emitReliable(domain, dedupKey, topic, fields);
            } catch (Throwable failure) {
                degraded("eventSink.emitReliable", failure);
                return false;
            }
        }

        @Override
        public boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields, long seq) {
            try {
                return delegate().emitReliable(domain, dedupKey, topic, fields, seq);
            } catch (Throwable failure) {
                degraded("eventSink.emitReliableSeq", failure);
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
            } catch (Throwable failure) {
                degraded("eventSink.emitReceipt", failure);
                return EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE);
            }
        }

        /** 强语义入口转发（同上）。 */
        @Override
        public EmitReceipt emitReliableReceipt(
                String domain, String dedupKey, String topic, Map<String, String> fields) {
            try {
                return delegate().emitReliableReceipt(domain, dedupKey, topic, fields);
            } catch (Throwable failure) {
                degraded("eventSink.emitReliableReceipt", failure);
                return EmitReceipt.rejected(EmitReceipt.Reason.PROBE_UNAVAILABLE);
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private BusinessEventSink delegate() {
            BridgeServices current = services;
            if (current == null) {
                return UnavailableBusinessEventSink.INSTANCE;
            }
            BusinessEventSink sink = current.eventSink();
            // 同 BusinessHostProxy.delegate()：实现违约返回 null 时按“未安装”处理，
            // 而不是把 null 交给调用链、让 NPE 从代理里穿透出去。
            return sink == null ? UnavailableBusinessEventSink.INSTANCE : sink;
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
            } catch (Throwable failure) {
                // 契约要求永不抛：实现违约时按"未就绪"降级，绝不让异常冒到插件 onEnable。
                degraded("configReloads.register", failure);
                return UNEXPECTED;
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private ConfigReloadRegistry delegate() {
            BridgeServices current = services;
            if (current == null) {
                return UnavailableConfigReloadRegistry.INSTANCE;
            }
            ConfigReloadRegistry registry = current.configReloads();
            // 同其他代理：实现违约返回 null 时按“未安装”处理，不让 NPE 穿透出去。
            return registry == null ? UnavailableConfigReloadRegistry.INSTANCE : registry;
        }
    }

    /**
     * 只读数据稳定代理。
     *
     * <p><b>无状态</b>：每个方法都实时读取 {@link #services}，因此缓存引用的外部插件总能感知
     * 探针的 install/uninstall。本代理<b>绝不抛异常</b>：委派实现违约抛出时，逐方法降级为
     * 该方法的"无数据"安全值（对象返回 null、列表返回空列表、聚合返回空窗口、取证返回空页）。</p>
     *
     * <p>降级值之所以逐方法给出而不是统一返回 null，是因为 {@link ProbeReadApi} 的各方法
     * 返回契约互不相同：列表承诺空列表、{@link ProbeReadApi#aggregated(int)} 承诺恒非空、
     * {@link ProbeReadApi#networkForensicsStatus()} 承诺对象。统一 null 会让这些契约被破坏，
     * 调用方在"探针未就绪"这一最常见场景下直接 NPE。</p>
     *
     * <p><b>新增接口方法时必须同步补覆写</b>：接口上的 default 实现会静默返回空值，
     * 漏覆写不会编译报错、也不会打日志，只会让该方法永远看不到真数据
     * （`historySnapshots` 就踩过这个坑）。</p>
     */
    private static final class ProbeReadApiProxy implements ProbeReadApi {

        private static final ProbeReadApiProxy INSTANCE = new ProbeReadApiProxy();

        @Override
        public MetricSnapshot latestSnapshot() {
            try {
                return delegate().latestSnapshot();
            } catch (Throwable failure) {
                degraded("read.latestSnapshot", failure);
                return null;
            }
        }

        @Override
        public List<MetricSnapshot> recentSnapshots(int limit) {
            try {
                return delegate().recentSnapshots(limit);
            } catch (Throwable failure) {
                degraded("read.recentSnapshots", failure);
                return Collections.emptyList();
            }
        }

        @Override
        public List<MetricSnapshot> recentSnapshotsSince(long sinceMs) {
            try {
                return delegate().recentSnapshotsSince(sinceMs);
            } catch (Throwable failure) {
                degraded("read.recentSnapshotsSince", failure);
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
            } catch (Throwable failure) {
                degraded("read.historySnapshots", failure);
                return Collections.emptyList();
            }
        }

        @Override
        public AggregatedMetrics aggregated(int windowSize) {
            try {
                return delegate().aggregated(windowSize);
            } catch (Throwable failure) {
                // 契约要求恒非空：违约时也要给出可读的空窗口，而不是 null。
                degraded("read.aggregated", failure);
                return UnavailableProbeReadApi.INSTANCE.aggregated(windowSize);
            }
        }

        @Override
        public StartupProfile lastStartupProfile() {
            try {
                return delegate().lastStartupProfile();
            } catch (Throwable failure) {
                degraded("read.lastStartupProfile", failure);
                return null;
            }
        }

        @Override
        public List<StartupProfile> historyStartupProfiles(int limit) {
            try {
                return delegate().historyStartupProfiles(limit);
            } catch (Throwable failure) {
                degraded("read.historyStartupProfiles", failure);
                return Collections.emptyList();
            }
        }

        @Override
        public String lastStartupComparisonSummary() {
            try {
                return delegate().lastStartupComparisonSummary();
            } catch (Throwable failure) {
                degraded("read.lastStartupComparisonSummary", failure);
                return null;
            }
        }

        @Override
        public NetworkPacketPage queryNetworkPackets(NetworkPacketQuery query) {
            try {
                return delegate().queryNetworkPackets(query);
            } catch (Throwable failure) {
                degraded("read.queryNetworkPackets", failure);
                return NetworkPacketPage.empty();
            }
        }

        @Override
        public NetworkForensicsStatus networkForensicsStatus() {
            try {
                return delegate().networkForensicsStatus();
            } catch (Throwable failure) {
                degraded("read.networkForensicsStatus", failure);
                return NetworkForensicsStatus.unavailable("取证状态查询异常");
            }
        }

        /** 实时取当前实现；未安装时用降级实现（不返回 null）。 */
        private ProbeReadApi delegate() {
            BridgeServices current = services;
            if (current == null) {
                return UnavailableProbeReadApi.INSTANCE;
            }
            ProbeReadApi api = current.read();
            // 同其他代理：实现违约返回 null 时按“未安装”处理。影响比别处更大——
            // 这里每个方法都要读它，null 会让整个只读门面（面板、MCP 工具）集体 NPE。
            return api == null ? UnavailableProbeReadApi.INSTANCE : api;
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
                // 不再建议“用 awaitAvailable 等待”：那句话等于把主线程阻塞当成解法，
                // 而主线程正是探针 install 本身要去的地方（见 awaitAvailable 的 javadoc）。
                warn("ServerProbe 门面在未就绪状态被调用：本次注册不会生效，请在其就绪后重新注册"
                        + "（代理可安全缓存，无需等待）");
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
