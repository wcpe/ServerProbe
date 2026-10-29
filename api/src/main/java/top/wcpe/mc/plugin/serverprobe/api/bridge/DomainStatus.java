package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一个业务域的健康缓存视图，不可变。
 *
 * <p>经 {@link BusinessHost#healthOf(String)} 取得。它是<b>探针缓存的只读快照</b>：
 * 取用方绝不因此触发对 Provider 的调用，也不做任何 IO。</p>
 *
 * <p><b>两个时间戳语义不同，不可互相替代</b>：</p>
 * <ul>
 *   <li>{@link #getHealth()}{@code .getSinceMs()} 是 <b>Provider 自报</b>的「自何时起如此」，
 *       未提供时为 0（探针不替 Provider 推断）；</li>
 *   <li>{@link #getRefreshedAtMs()} 是 <b>探针采集</b>该值的时刻，未刷新过时为 0。</li>
 * </ul>
 *
 * <p><b>布尔访问器一律用 {@code get} 前缀</b>（{@code getRegistered()} / {@code getQuarantined()}），
 * 不用 {@code isRegistered()}：后者在 Kotlin 侧会暴露成属性 {@code isRegistered}，
 * 消费方写 {@code .registered} 会失败（ADR-13）。</p>
 *
 * <h2>：规格指纹与运行期调用计数</h2>
 * <p>本类新增三组字段供业务插件面板渲染：</p>
 * <ul>
 *   <li>{@link #getManifestDigest()}——<b>规格指纹</b>。面板先拿指纹，只有变化时才去拉全量 manifest，
 *       避免每次查询都传「几 KB × N 个域」。它在<b>注册时</b>算一次并缓存，
 *       查询路径<b>绝不</b>重新调 {@code Provider.manifest()}。</li>
 *   <li>{@link #getLastDispatchAt()} / {@link #getDispatchCount()} / {@link #getFailureCount()}——
 *       <b>运行期指标</b>，重启归零。注意它们与既有的 {@code dispatchTotal}/{@code dispatchFailed}
 *       是同一组事实的两种读口（后者为历史命名，保留以兼容），取值恒等。</li>
 *   <li>{@link #getDegradedReason()}——不可用/降级原因；健康时为 null（与 {@code ""} 区分：
 *       null 表示"没有降级原因"，空串只可能是数据异常）。</li>
 * </ul>
 *
 * <p><b>既有构造器保留</b>：新增字段一律走「补齐字段的构造器」，旧构造器转调并填空值。
 * 这是刻意的向后兼容——外部实现若自己 {@code new DomainStatus(...)}，升级探针后不该编译不过。</p>
 */
public final class DomainStatus {

    private final String domain;
    private final boolean registered;
    private final ProviderHealth health;
    private final String reasonOverride;
    private final long refreshedAtMs;
    private final FailureInfo lastFailure;
    private final long dispatchTotal;
    private final long dispatchFailed;
    private final long lastCostMs;
    private final boolean quarantined;
    private final String manifestDigest;
    private final long lastDispatchAt;
    private final String degradedReason;

    /**
     * 构造一份域状态快照（时代的 10 参形态）。
     *
     * <p>等价于「没有规格指纹、从未派发过、无降级原因」——{@code dispatchCount}/{@code failureCount}
     * 仍取 {@code dispatchTotal}/{@code dispatchFailed}，两者本就是同一组事实。</p>
     *
     * @param domain 业务域命名空间
     * @param registered 该域当前是否有 Provider 注册
     * @param health Provider 自报值；未刷新过时为 {@link ProviderHealth#unknown()}，永不为 null
     * @param reasonOverride 探针补充的原因（如「Provider 未注册」）；非空时优先于
     *     {@code health.getReason()} 呈现
     * @param refreshedAtMs 探针采集 {@code health} 的时刻；未刷新过为 0
     * @param lastFailure 最近一次 dispatch 失败记录；从未失败为 null
     * @param dispatchTotal 累计派发次数
     * @param dispatchFailed 累计失败次数
     * @param lastCostMs 最近一次派发的耗时（毫秒）
     * @param quarantined 是否处于隔离窗口内（窗口内探针不再调用该域 {@code health()}）
     */
    public DomainStatus(
            String domain,
            boolean registered,
            ProviderHealth health,
            String reasonOverride,
            long refreshedAtMs,
            FailureInfo lastFailure,
            long dispatchTotal,
            long dispatchFailed,
            long lastCostMs,
            boolean quarantined) {
        this(domain, registered, health, reasonOverride, refreshedAtMs, lastFailure,
                dispatchTotal, dispatchFailed, lastCostMs, quarantined, null, 0L, null);
    }

    /**
     * 构造一份域状态快照（完整形态）。
     *
     * @param domain 业务域命名空间
     * @param registered 该域当前是否有 Provider 注册
     * @param health Provider 自报值；未刷新过时为 {@link ProviderHealth#unknown()}，永不为 null
     * @param reasonOverride 探针补充的原因；非空时优先于 {@code health.getReason()} 呈现
     * @param refreshedAtMs 探针采集 {@code health} 的时刻；未刷新过为 0
     * @param lastFailure 最近一次 dispatch 失败记录；从未失败为 null
     * @param dispatchTotal 累计派发次数
     * @param dispatchFailed 累计失败次数
     * @param lastCostMs 最近一次派发的耗时（毫秒）
     * @param quarantined 是否处于隔离窗口内
     * @param manifestDigest 规格指纹（规范化 JSON 的 sha256 前 16 位）；无规格/未注册为 null
     * @param lastDispatchAt 最近一次派发完成时刻（epoch 毫秒）；从未派发为 0
     * @param degradedReason 降级原因；健康为 null
     */
    public DomainStatus(
            String domain,
            boolean registered,
            ProviderHealth health,
            String reasonOverride,
            long refreshedAtMs,
            FailureInfo lastFailure,
            long dispatchTotal,
            long dispatchFailed,
            long lastCostMs,
            boolean quarantined,
            String manifestDigest,
            long lastDispatchAt,
            String degradedReason) {
        this.domain = domain == null ? "" : domain;
        this.registered = registered;
        this.health = health == null ? ProviderHealth.unknown() : health;
        this.reasonOverride = reasonOverride == null ? "" : reasonOverride;
        this.refreshedAtMs = refreshedAtMs;
        this.lastFailure = lastFailure;
        this.dispatchTotal = dispatchTotal;
        this.dispatchFailed = dispatchFailed;
        this.lastCostMs = lastCostMs;
        this.quarantined = quarantined;
        // 空串归一为 null：指纹是"有或没有"，空串只会让下游多写一个分支。
        this.manifestDigest = manifestDigest == null || manifestDigest.isEmpty() ? null : manifestDigest;
        this.lastDispatchAt = lastDispatchAt;
        this.degradedReason = degradedReason == null || degradedReason.isEmpty() ? null : degradedReason;
    }

    /** 业务域命名空间。 */
    public String getDomain() {
        return domain;
    }

    /** 该域当前是否有 Provider 注册。 */
    public boolean getRegistered() {
        return registered;
    }

    /** Provider 自报的健康状态；未刷新过时为 {@code unknown()}，永不为 null。 */
    public ProviderHealth getHealth() {
        return health;
    }

    /** 探针补充的原因；非空时优先于 {@code health.getReason()} 呈现。 */
    public String getReasonOverride() {
        return reasonOverride;
    }

    /** 探针采集 {@link #getHealth()} 的时刻（epoch 毫秒）；未刷新过为 0。 */
    public long getRefreshedAtMs() {
        return refreshedAtMs;
    }

    /** 最近一次 dispatch 失败记录；从未失败为 null。 */
    public FailureInfo getLastFailure() {
        return lastFailure;
    }

    /**
     * 累计派发次数。
     *
     * @deprecated 命名不带“次数”语义且与字段字面量同名，已由 {@link #getDispatchCount()} 取代；
     *     两者取值恒等，新代码请用后者。
     */
    @Deprecated
    public long getDispatchTotal() {
        return dispatchTotal;
    }

    /**
     * 累计失败次数。
     *
     * @deprecated 已由 {@link #getFailureCount()} 取代；两者取值恒等，新代码请用后者。
     */
    @Deprecated
    public long getDispatchFailed() {
        return dispatchFailed;
    }

    /** 最近一次派发的耗时（毫秒）。 */
    public long getLastCostMs() {
        return lastCostMs;
    }

    /** 是否处于隔离窗口内。 */
    public boolean getQuarantined() {
        return quarantined;
    }

    /**
     * 该域 manifest 的内容指纹（规范化 JSON 的 sha256 前 16 位十六进制小写）。
     *
     * <p>用途是<b>省带宽</b>：下游先比对指纹，只有变化时才去拉全量 manifest
     * （每域几 KB × N 个域，全量传一遍代价可观）。</p>
     *
     * <p>计算时机是<b>注册时</b>并缓存；查询路径不重新调 {@code Provider.manifest()}，
     * 因此一个"构造时抛异常"的 Provider 也不会因查询被触发。</p>
     *
     * <p><b>null 的三种情形</b>：该域未注册、Provider 未给出可用清单、或走的是旧契约适配
     * （旧契约没有可稳定计算的强类型清单）。null 表示"没有指纹可比对"，<b>不是</b>空指纹。</p>
     *
     * @return 16 位小写十六进制指纹；无指纹时为 null
     */
    public String getManifestDigest() {
        return manifestDigest;
    }

    /**
     * 最近一次 dispatch 的<b>完成</b>时刻（epoch 毫秒）；从未派发过为 0。
     *
     * <p>与 {@link #getRefreshedAtMs()} 无关：后者是健康值采集时刻，本字段是业务调用时刻。</p>
     *
     * @return epoch 毫秒；0 表示本进程内从未派发过
     */
    public long getLastDispatchAt() {
        return lastDispatchAt;
    }

    /**
     * 自探针启动以来的累计派发次数（成功 + 失败）。
     *
     * <p><b>运行期指标，重启归零</b>，不是持久统计。与 {@link #getDispatchTotal()} 取值恒等，
     * 后者是历史命名，<b>已弃用</b>。</p>
     *
     * @return 累计次数；0 表示从未派发
     */
    public long getDispatchCount() {
        return dispatchTotal;
    }

    /**
     * 自探针启动以来的累计失败次数。
     *
     * <p><b>运行期指标，重启归零</b>。与 {@link #getDispatchFailed()} 取值恒等（后者已弃用）。</p>
     *
     * @return 累计失败次数；恒 ≤ {@link #getDispatchCount()}
     */
    public long getFailureCount() {
        return dispatchFailed;
    }

    /**
     * 该域不可用/降级的原因；<b>健康时为 null</b>。
     *
     * <p>与 {@link #getReasonOverride()} 的关系：后者是探针补充的原因原文（空串表示"探针没补充"），
     * 本字段是<b>呈现口径</b>——探针补充优先、退而用 {@code health.getReason()}，
     * 两者都没有时为 null。下游只需看本字段就能决定"要不要在面板上画一个红点"。</p>
     *
     * @return 人可读降级原因；健康时为 null
     */
    public String getDegradedReason() {
        return degradedReason;
    }

    /**
     * 该域是否可用（已注册且健康等级为 {@link ProviderHealth.Status#AVAILABLE}）。
     *
     * <p>等价于 {@code getRegistered() && getHealth().getStatus() == AVAILABLE}。
     * 提供本方法是为了让面板不必自己拼这条判定——它是最常被误写的一条。</p>
     *
     * @return true=已注册且健康
     */
    public boolean getAvailable() {
        return registered && health.getStatus() == ProviderHealth.Status.AVAILABLE;
    }

    @Override
    public String toString() {
        return "DomainStatus{domain='" + domain + "', registered=" + registered
                + ", health=" + health.getStatus() + ", refreshedAtMs=" + refreshedAtMs
                + ", quarantined=" + quarantined + ", manifestDigest=" + manifestDigest
                + ", dispatchCount=" + dispatchTotal + '}';
    }
}
