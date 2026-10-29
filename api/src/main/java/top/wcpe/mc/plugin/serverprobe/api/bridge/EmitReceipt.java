package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 一次事件上报的受理凭据，不可变。
 *
 * <p>取代「一个 {@code boolean} 打天下」的形态。原先 {@code emit} 只回答「有没有受理」，
 * 而<b>不受理的原因决定了调用方该做什么</b>：</p>
 * <ul>
 *   <li>队列饱和 → <b>退避后重试</b>（事件没丢，只是队列满）；</li>
 *   <li>{@code dedupKey} 缺失 → <b>调用方 bug</b>，重试一万次也不会成功；</li>
 *   <li>探针未就绪 → <b>静默忽略</b>（插件先于探针加载是常态，重试无意义）；</li>
 *   <li>参数非法 → <b>调用方 bug</b>，应修正而不是重试。</li>
 * </ul>
 * <p>四种情况原先都只能拿到 {@code false}，调用方无法区分「该重试」与「重试无用」。
 * 回灌场景下这个差别是致命的：把「永久失败」当成「暂时饱和」反复重试，会把 CPU 让出去又拿回来，
 * 永远推进不下去。</p>
 *
 * <p><b>为什么不是 record</b>：本模块发行面向 Java 8，record 需 Java 14+。</p>
 */
public final class EmitReceipt {

    /** 受理结果的原因分类。<b>新增取值是兼容的</b>（{@code enum} 扩展不破坏字节码），但调用方应写好默认分支。 */
    public enum Reason {

        /** 已受理：事件已进入队列（可丢）或可靠队列/落盘缓冲（不可丢）。 */
        ACCEPTED,

        /** 通道饱和，<b>稍后重试可能成功</b>：队列满且落盘缓冲也接不下。 */
        REJECTED_SATURATED,

        /** 探针未就绪或已卸载，<b>重试无意义</b>：插件先于探针加载的正常情形。 */
        PROBE_UNAVAILABLE,

        /** 参数非法（域名超长/格式错误），<b>调用方 bug</b>。 */
        INVALID_DOMAIN,

        /** 可靠事件缺少 {@code dedupKey}，<b>调用方 bug</b>；不会降级为可丢通道。 */
        MISSING_DEDUP_KEY,

        /** 调用方指定的 {@code seq} 非正数，<b>调用方 bug</b>。 */
        INVALID_SEQUENCE
    }

    private static final EmitReceipt ACCEPTED = new EmitReceipt(true, Reason.ACCEPTED, 0L, 0L);

    private final boolean accepted;
    private final Reason reason;
    private final long seq;
    private final long seqEpoch;

    private EmitReceipt(boolean accepted, Reason reason, long seq, long seqEpoch) {
        this.accepted = accepted;
        this.reason = reason == null ? Reason.PROBE_UNAVAILABLE : reason;
        this.seq = seq;
        this.seqEpoch = seqEpoch;
    }

    /** 已受理（可丢事件不占序列，故 {@code seq} 为 0）。 */
    public static EmitReceipt accepted() {
        return ACCEPTED;
    }

    /**
     * 已受理且带序列位点（不可丢事件专用）。
     *
     * @param seq     探针分配（或调用方指定）的域内序号；{@code > 0}
     * @param seqEpoch 序号纪元；仅当序号语义真的重置时才变
     * @return 受理凭据
     */
    public static EmitReceipt accepted(long seq, long seqEpoch) {
        return new EmitReceipt(true, Reason.ACCEPTED, seq, seqEpoch);
    }

    /**
     * 未受理。
     *
     * @param reason 原因；{@code null} 按 {@link Reason#PROBE_UNAVAILABLE} 处理
     * @return 未受理凭据（{@code seq} 恒 0）
     */
    public static EmitReceipt rejected(Reason reason) {
        return new EmitReceipt(false, reason, 0L, 0L);
    }

    /**
     * 从参数反推不可受理的原因（实现未覆写强语义入口时的兼容回退）。
     *
     * <p>只能判定那几种<b>必定失败</b>的情形（空域、缺去重键）；
     * 其余一律归为“通道饱和”——那是最保守的选项，因为它可重试。
     * 若把它误报成“探针不可用”，调用方会静默丢弃事件；误报成“饱和”最多是多试一次。</p>
     *
     * @param domain   业务域
     * @param dedupKey 去重键
     * @return 推断出的原因，永不为 null
     */
    public static Reason inferRejection(String domain, String dedupKey) {
        if (domain == null || domain.isEmpty()) {
            return Reason.INVALID_DOMAIN;
        }
        if (dedupKey == null || dedupKey.isEmpty()) {
            return Reason.MISSING_DEDUP_KEY;
        }
        return Reason.REJECTED_SATURATED;
    }

    /** 是否已受理。等价于原 {@code boolean} 返回值。 */
    public boolean isAccepted() {
        return accepted;
    }

    /** 未受理的原因；已受理时为 {@link Reason#ACCEPTED}。永不为 null。 */
    public Reason getReason() {
        return reason;
    }

    /**
     * 该事件是否<b>值得重试</b>。
     *
     * <p>只有 {@link Reason#REJECTED_SATURATED} 返回 true——其余都是「重试也不会改变结果」。
     * 回灌循环应当据此决定「退避重试」还是「记日志并跳过」。</p>
     *
     * @return true=稍后重试可能成功
     */
    public boolean isRetryable() {
        return reason == Reason.REJECTED_SATURATED;
    }

    /**
     * 域内序号；仅不可丢事件且已受理时 {@code > 0}，其余为 0。
     *
     * <p>把它回传给调用方是为了让业务侧能做「本地水位线」——不必再去查 {@code bridge_status}。</p>
     *
     * @return 序号；0 表示无序号
     */
    public long getSeq() {
        return seq;
    }

    /** 序号纪元；仅在有 {@code seq} 时有意义，其余为 0。 */
    public long getSeqEpoch() {
        return seqEpoch;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmitReceipt)) {
            return false;
        }
        EmitReceipt that = (EmitReceipt) other;
        return accepted == that.accepted
                && reason == that.reason
                && seq == that.seq
                && seqEpoch == that.seqEpoch;
    }

    @Override
    public int hashCode() {
        int result = accepted ? 1 : 0;
        result = 31 * result + reason.hashCode();
        result = 31 * result + (int) (seq ^ (seq >>> 32));
        result = 31 * result + (int) (seqEpoch ^ (seqEpoch >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "EmitReceipt{accepted=" + accepted + ", reason=" + reason
                + (seq > 0 ? ", seq=" + seq + ", seqEpoch=" + seqEpoch : "") + '}';
    }
}
