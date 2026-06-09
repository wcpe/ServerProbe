package top.wcpe.mc.plugin.serverprobe.core.buffer

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 指标快照近期历史缓冲(FR8 近期历史数据源)。
 *
 * 在内存中以环形缓冲保留最近若干份 [MetricSnapshot]:由编排层在每次采集后写入([record]),
 * 由只读 API 回看([recent])。作为单例 IOC [Service],写方([MetricOrchestrator])与读方
 * ([ProbeReadApiImpl])经注入共享**同一实例**,从而看到同一份近期历史。
 *
 * 容量取自 [ProbeConfig.historyCapacity],经 `by lazy` **延迟到首次访问时**读取:首次写入发生在
 * 采集任务(编排层 `@PostEnable` 后)启动之后,届时 [ProbeConfig] 配置已注入就绪,避免构造期 config 未就绪;
 * 读取异常或值非正时由 [RingBuffer] 的 `require` 拦截,故 [resolveCapacity] 对非正值兜底为默认容量。
 *
 * 线程安全完全委托给内部 [RingBuffer](其方法已 `@Synchronized`),本类自身不再加锁。
 */
@Service
class MetricSnapshotBuffer {

    /**
     * 底层环形缓冲;容量经 [ProbeConfig] 延迟解析(见类 KDoc),`by lazy` 保证只初始化一次且线程安全。
     */
    private val buffer: RingBuffer<MetricSnapshot> by lazy { RingBuffer(resolveCapacity()) }

    /**
     * 记录一份指标快照到近期历史;超出容量时自动滚动覆盖最旧的一份。
     *
     * @param snapshot 待记录的指标快照。
     */
    fun record(snapshot: MetricSnapshot) {
        buffer.add(snapshot)
    }

    /**
     * 获取最近的至多 [limit] 份指标快照,按"新 → 旧"顺序返回(下标 0 为最新)。
     *
     * @param limit 期望返回的最大条数。
     * @return 最近快照列表(新→旧);无数据或 [limit] 非正时为空列表。
     */
    fun recent(limit: Int): List<MetricSnapshot> = buffer.snapshot(limit)

    /**
     * 获取缓冲内时间戳不早于 [sinceMs] 的指标快照,按"新 → 旧"顺序返回(M2 FR8 扩面)。
     *
     * 取缓冲当前全量快照(经 [RingBuffer.snapshot] 以当前已存数为上限,得新→旧一致视图)后,
     * 按 [MetricSnapshot.timestampMs] >= [sinceMs] 过滤。纯内存操作、不读盘:能回看的最早时刻
     * 受缓冲容量限制(早于最旧一份的数据不在缓冲内,自然不会返回)。
     *
     * @param sinceMs 时间下界(epoch 毫秒,含)。
     * @return 不早于 [sinceMs] 的快照列表(新→旧);无满足项时为空列表。
     */
    fun recentSince(sinceMs: Long): List<MetricSnapshot> =
        buffer.snapshot(buffer.size()).filter { it.timestampMs >= sinceMs }

    /**
     * 解析环形缓冲容量:取 [ProbeConfig.historyCapacity],非正时兜底为默认容量(防止 [RingBuffer] 构造抛错)。
     *
     * @return 有效容量(恒为正)。
     */
    private fun resolveCapacity(): Int = ProbeConfig.historyCapacity().takeIf { it > 0 } ?: DEFAULT_HISTORY_CAPACITY

    private companion object {

        /**
         * 默认近期历史容量(份),配置缺失/非法时兜底。
         *
         * 按采集周期约 5 秒估算,360 份覆盖约 30 分钟近期历史。
         */
        private const val DEFAULT_HISTORY_CAPACITY = 360
    }
}
