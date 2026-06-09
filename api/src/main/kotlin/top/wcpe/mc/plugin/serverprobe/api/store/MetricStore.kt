package top.wcpe.mc.plugin.serverprobe.api.store

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile

/**
 * 存储后端 SPI(FR8.2)。
 *
 * 定义启动画像与指标历史的持久化契约。**默认且唯一内置实现为本地文件**(P7,JSON/JSONL,
 * 原子写入、可配滚动与保留),探针自身不内置、不依赖任何数据库。本接口作为扩展点预留,
 * 第三方可自行实现 DB/远程等后端进行替换。
 *
 * ## M2 开放接口扩面(FR8)
 * 新增三个**带默认实现**的读取/批量写入方法([readStartupProfiles]、[readHistory]、[appendHistory] 批量重载),
 * 供第三方对接 DB/远程后端时按需覆盖。默认实现保持"空读 / 批量退化为逐条"语义,因此**旧实现无需改动即向后兼容**
 * (Kotlin 接口默认方法,不破坏既有 SPI 与既有调用)。
 */
interface MetricStore {

    /**
     * 保存一份启动画像(每次启动一份)。
     *
     * @param profile 待持久化的启动画像。
     */
    fun saveStartupProfile(profile: StartupProfile)

    /**
     * 读取最近一次的启动画像,用于与本次启动对比。
     *
     * @return 最近一次启动画像;无历史记录时为 null。
     */
    fun lastStartupProfile(): StartupProfile?

    /**
     * 追加一条指标历史记录(聚合后写入,行式追加)。
     *
     * @param snapshot 待追加的指标快照。
     */
    fun appendHistory(snapshot: MetricSnapshot)

    /**
     * 读取历史归档的若干份启动画像(M2 SPI 扩面,FR8)。
     *
     * 本地/第三方实现可读归档目录(由新到旧)返回至多 [limit] 份;**默认返回空列表**,
     * 使尚未实现该能力的存储后端保持向后兼容(不强制覆盖)。可能涉及读盘,调用方宜在异步上下文调用。
     *
     * @param limit 期望返回的最大份数;非正时返回空列表。
     * @return 历史启动画像列表(由新到旧);默认空。
     */
    fun readStartupProfiles(limit: Int): List<StartupProfile> = emptyList()

    /**
     * 读取指定时间范围内的历史指标快照(M2 SPI 扩面,FR8)。
     *
     * 返回 [MetricSnapshot.timestampMs] 落在 `[sinceMs, untilMs]`(闭区间)内的历史快照,至多 [limit] 条;
     * **默认返回空列表**以保持向后兼容。可能涉及读盘,调用方宜在异步上下文调用。
     *
     * @param sinceMs 时间范围下界(epoch 毫秒,含)。
     * @param untilMs 时间范围上界(epoch 毫秒,含)。
     * @param limit 期望返回的最大条数;非正时返回空列表。
     * @return 范围内历史快照列表;默认空。
     */
    fun readHistory(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot> = emptyList()

    /**
     * 批量追加指标历史记录(M2 SPI 扩面,FR8)。
     *
     * **默认实现退化为逐条调用 [appendHistory]**,使既有实现无需改动即可用;
     * DB/远程等后端可覆盖本方法以批量写入(单事务/单请求)降低开销。
     *
     * @param snapshots 待追加的指标快照列表。
     */
    fun appendHistory(snapshots: List<MetricSnapshot>) {
        snapshots.forEach { appendHistory(it) }
    }
}
