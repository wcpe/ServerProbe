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
 * 读取近期历史快照的方法待 M2 按需补充(届时为纯增方法,不破坏现有 SPI)。
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
}
