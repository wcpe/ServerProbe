package top.wcpe.mc.plugin.serverprobe.api.store;

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot;
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile;

/**
 * 存储后端 SPI(FR8.2)。
 *
 * 定义启动画像与指标历史的持久化契约。**默认且唯一内置实现为本地文件**(P7,JSON/JSONL,
 * 原子写入、可配滚动与保留),探针自身不内置、不依赖任何数据库。本接口作为扩展点预留,
 * 第三方可自行实现 DB/远程等后端进行替换。
 *
 * <h2>M2 开放接口扩面(FR8)</h2>
 * 新增三个**带默认实现**的读取/批量写入方法({@link #readStartupProfiles}、{@link #readHistory}、{@link #appendHistory} 批量重载),
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
     * 读取历史归档的若干份启动画像(M2 SPI 扩面,FR8)。
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
     * 读取指定时间范围内的历史指标快照(M2 SPI 扩面,FR8)。
     *
     * 返回 {@link MetricSnapshot#getTimestampMs()} 落在 {@code [sinceMs, untilMs]}(闭区间)内的历史快照,至多 {@code limit} 条;
     * **默认返回空列表**以保持向后兼容。可能涉及读盘,调用方宜在异步上下文调用。
     *
     * @param sinceMs 时间范围下界(epoch 毫秒,含)。
     * @param untilMs 时间范围上界(epoch 毫秒,含)。
     * @param limit 期望返回的最大条数;非正时返回空列表。
     * @return 范围内历史快照列表;默认空。
     */
    default java.util.List<MetricSnapshot> readHistory(long sinceMs, long untilMs, int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * 读取指定时间范围内的历史指标快照,并声明排序语义(FR-26,公开只读 API 的底层入口)。
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
     * 批量追加指标历史记录(M2 SPI 扩面,FR8)。
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
