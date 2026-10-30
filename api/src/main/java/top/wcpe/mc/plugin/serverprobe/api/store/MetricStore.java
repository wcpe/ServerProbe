package top.wcpe.mc.plugin.serverprobe.api.store;

import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot;
import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile;

/**
 * 存储后端 SPI。
 *
 * 定义启动画像与指标历史的持久化契约。**默认且唯一内置实现为本地文件**(P7,JSON/JSONL,
 * 原子写入、可配滚动与保留),探针自身不内置、不依赖任何数据库。本接口作为扩展点预留,
 * 第三方可自行实现 DB/远程等后端进行替换。
 *
 * <h2>M2 开放接口扩面</h2>
 * 新增若干**带默认实现**的读取/批量写入方法({@link #readStartupProfiles}、{@link #readHistory}、
 * {@link #readHistorySince}、{@link #readHistoryUntil}、{@link #appendHistory} 批量重载),
 * 供第三方对接 DB/远程后端时按需覆盖。默认实现保持"空读 / 批量退化为逐条"语义,因此**旧实现无需改动即向后兼容**
 * (Java 接口默认方法,不破坏既有 SPI 与既有调用)。
 */
public interface MetricStore {

    /**
     * 保存一份启动画像(每次启动一份)。
     *
     * @param profile 待持久化的启动画像。
     */
    void saveStartupProfile(StartupProfile profile);

    /**
     * 读取最近一次的启动画像,用于与本次启动对比。
     *
     * @return 最近一次启动画像;无历史记录时为 null。
     */
    StartupProfile lastStartupProfile();

    /**
     * 追加一条指标历史记录(聚合后写入,行式追加)。
     *
     * @param snapshot 待追加的指标快照。
     */
    void appendHistory(MetricSnapshot snapshot);

    /**
     * 读取历史归档的若干份启动画像(M2 SPI 扩面)。
     *
     * 本地/第三方实现可读归档目录(由新到旧)返回至多 {@code limit} 份;**默认返回空列表**,
     * 使尚未实现该能力的存储后端保持向后兼容(不强制覆盖)。可能涉及读盘,调用方宜在异步上下文调用。
     *
     * @param limit 期望返回的最大份数;非正时返回空列表。
     * @return 历史启动画像列表(由新到旧);默认空。
     */
    default java.util.List<StartupProfile> readStartupProfiles(int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * 读取指定时间范围内的历史指标快照(M2 SPI 扩面)。
     *
     * 返回 {@code MetricSnapshot.getTimestampMs()} 落在 {@code [sinceMs, untilMs]}（**闭区间**，
     * 两端均含）内的历史快照，至多 {@code limit} 条。三个区间读取方法共用同一套闭区间语义，
     * 按边界是否可省略分为：
     * <ul>
     *   <li>{@link #readHistory(long, long, int)}：两端都必须给；</li>
     *   <li>{@link #readHistorySince(long, int)}：只给下界，上界取实现所见的当前时刻；</li>
     *   <li>{@link #readHistoryUntil(long, int)}：只给上界，下界不设限。</li>
     * </ul>
     * {@code limit} 的截断方向由实现决定，本地文件实现取范围内最早的若干条，
     * 需要尾部语义时应覆写对应方法（详见 {@link #readHistoryUntil(long, int)}）。
     *
     * **默认返回空列表**以保持向后兼容。可能涉及读盘，调用方宜在异步上下文调用。
     *
     * @param sinceMs 时间范围下界（epoch 毫秒，含）。
     * @param untilMs 时间范围上界（epoch 毫秒，含）。
     * @param limit   期望返回的最大条数；非正时返回空列表。
     * @return 范围内历史快照列表；默认空。
     * @see #readHistorySince(long, int)
     * @see #readHistoryUntil(long, int)
     */
    default java.util.List<MetricSnapshot> readHistory(long sinceMs, long untilMs, int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * 读取指定时间范围内的历史指标快照,并声明排序语义(公开只读 API 的底层入口)。
     *
     * 与 {@link #readHistory(long, long, int)} 的唯一区别:实现应返回**由新到旧**排序、
     * 保留范围内**最新的** {@code limit} 条(基准方法对条数的选择不作承诺)。
     * 默认实现 = 调用基准方法后**就地反转**——对"升序随收随截"的本地实现这仍可能留下较旧的条,
     * 因此内置本地实现**覆盖本方法**以正确兑现语义;第三方 DB/远程后端可直接以查询排序兑现,
     * 也可沿用默认反转(数据量小时可接受)。
     *
     * 默认反转实现保持既有第三方实现零改动、向后兼容。
     *
     * @param sinceMs 时间范围下界(epoch 毫秒,含)。
     * @param untilMs 时间范围上界(epoch 毫秒,含)。
     * @param limit 期望返回的最大条数;非正时返回空列表。
     * @return 范围内历史快照列表(由新到旧,保留最新的 limit 条);默认空。
     */
    default java.util.List<MetricSnapshot> readHistoryLatestFirst(long sinceMs, long untilMs, int limit) {
        java.util.List<MetricSnapshot> ascending = readHistory(sinceMs, untilMs, limit);
        java.util.Collections.reverse(ascending);
        return ascending;
    }

    /**
     * 读取 {@code sinceMs} 及之后的历史指标快照（**只设下界、无上界**）。
     *
     * <h2>区间</h2>
     * 闭区间下界为 {@code sinceMs}（含），上界取「实现所见的当前时刻」（含）；语义上是
     * {@link #readHistory(long, long, int)} 在 {@code untilMs = System.currentTimeMillis()} 时的特例，
     * 因此**不会漏掉刚刚落入的快照**。排序与 {@code limit} 约定与
     * {@link #readHistory(long, long, int)} 完全一致，见 {@link #readHistoryUntil(long, int)} 的截断方向说明。
     *
     * <h2>为什么需要这个重载</h2>
     * {@link #readHistory(long, long, int)} 的两个边界都是基本类型，调用方若自己传
     * {@code System.currentTimeMillis()}，既要在调用点抄一遍时钟，又可能与实现的时间源不一致
     * （两次读取之间时钟前进）。
     *
     * <p>默认实现退化为 {@code untilMs = System.currentTimeMillis()}，使既有实现无需改动即可用；
     * 若某个存储有自己的时间源（例如归档写入时的时钟），覆写本方法可以保持与其一致。</p>
     *
     * @param sinceMs 时间范围下界（epoch 毫秒，含）。
     * @param limit   期望返回的最大条数；非正时返回空列表。
     * @return 范围内历史快照列表；默认空。
     * @see #readHistory(long, long, int)
     * @see #readHistoryUntil(long, int)
     */
    default java.util.List<MetricSnapshot> readHistorySince(long sinceMs, int limit) {
        return readHistory(sinceMs, System.currentTimeMillis(), limit);
    }

    /**
     * 读取 {@code untilMs} 及之前的历史指标快照（**只设上界、无下界**）。
     *
     * <h2>区间</h2>
     * 闭区间上界为 {@code untilMs}（含），下界不设限。排序与 {@code limit} 约定与
     * {@link #readHistory(long, long, int)} 完全一致；与 {@link #readHistorySince(long, int)} 互为镜像，
     * 三者共同覆盖「双边界 / 只给下界 / 只给上界」三种查询形态。典型场景是只关心某个时刻之前的回看，
     * 例如「崩溃前的那批快照」。
     *
     * <h2>为什么需要这个重载</h2>
     * {@link #readHistory(long, long, int)} 要求同时给出两个边界，无法表达「某时刻之前的历史」——
     * 调用方只能猜一个下界（如 {@code 0} 或「当前时刻减一个很大的数」），既脏又可能漏数据。
     *
     * <h2>默认实现的无下界哨兵</h2>
     * 默认实现以 {@link Long#MIN_VALUE} 作为下界调用 {@link #readHistory(long, long, int)}
     * （epoch 毫秒的最小值，早于任何真实采样时刻，等价于不设下界），既有实现无需改动即可用；
     * 该哨兵只出现在默认实现内部，不改变 SPI 的方法签名（两侧边界仍是必填的 {@code long}）。
     *
     * <p><b>覆写提示</b>：若某个存储的下界会参与日期换算、分页或 SQL 条件，且无法安全处理
     * {@code Long.MIN_VALUE}，应覆写本方法自行表达「无下界」（例如直接枚举上界之前的文件或分区），
     * 避免把哨兵代入其内部换算。</p>
     *
     * <p><b>{@code limit} 的截断方向（重要）</b>：本方法承诺的是「{@code untilMs} 及之前
     * <b>最近的</b> {@code limit} 条」，而不是「范围内最早的 {@code limit} 条」。
     * 两者在跨多个日文件时结果完全相反，因此：</p>
     * <ul>
     *   <li>内置的本地文件存储<b>已覆写本方法</b>，按「先扫最新文件、命中超限即淘汰最旧」实现，
     *       内存与 {@code limit} 同阶，可直接用于「崩溃前最后 N 条」这类场景。</li>
     *   <li>第三方存储若只实现了 {@link #readHistory(long, long, int)}，默认委托会沿用它的
     *       截断方向（可能是「最早 N 条」）。<b>需要尾部语义时必须覆写本方法</b>，
     *       否则回看类查询会拿到与意图相反的一端。</li>
     * </ul>
     *
     * @param untilMs 时间范围上界（epoch 毫秒，含）。
     * @param limit   期望返回的最大条数；非正时返回空列表。
     * @return 范围内历史快照列表（时间正序）；默认空。
     * @see #readHistory(long, long, int)
     * @see #readHistorySince(long, int)
     */
    default java.util.List<MetricSnapshot> readHistoryUntil(long untilMs, int limit) {
        return readHistory(Long.MIN_VALUE, untilMs, limit);
    }

    /**
     * 批量追加指标历史记录(M2 SPI 扩面)。
     *
     * **默认实现退化为逐条调用 {@link #appendHistory}**,使既有实现无需改动即可用;
     * DB/远程等后端可覆盖本方法以批量写入(单事务/单请求)降低开销。
     *
     * @param snapshots 待追加的指标快照列表。
     */
    default void appendHistory(java.util.List<MetricSnapshot> snapshots) {
        for (MetricSnapshot s : snapshots) {
            appendHistory(s);
        }
    }
}
