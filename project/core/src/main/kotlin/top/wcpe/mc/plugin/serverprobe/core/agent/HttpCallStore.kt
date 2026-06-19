package top.wcpe.mc.plugin.serverprobe.core.agent

import top.wcpe.mc.plugin.serverprobe.api.model.HttpCall
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 外呼记录的近期内存缓冲(M5,运行期常驻)。
 *
 * 由平台层的外呼监控服务把"已归因(填好 [HttpCall.plugin])"的记录写入;供 `/probe http` 命令读取近期明细。
 * 有界环形缓冲(超出容量淘汰最旧),线程安全(外呼在任意线程产生、命令在主线程读取)。
 *
 * 作为 IOC [Service],无平台依赖,落位于 core。
 */
@Service
class HttpCallStore {

    /** 同步锁:外呼可能并发写入,命令读取需一致快照。 */
    private val lock = Any()

    /** 近期外呼环形缓冲(队首最旧、队尾最新)。 */
    private val ring = ArrayDeque<HttpCall>()

    /** 容量上限。 */
    private var capacity = DEFAULT_CAPACITY

    /**
     * 配置容量(由插件读配置后调用)。
     *
     * @param cap 容量上限(下限 1)
     */
    fun configure(cap: Int) {
        synchronized(lock) {
            capacity = cap.coerceAtLeast(1)
            trim()
        }
    }

    /**
     * 追加一条外呼记录(超容量淘汰最旧)。
     *
     * @param call 已归因的外呼记录
     */
    fun add(call: HttpCall) {
        synchronized(lock) {
            ring.addLast(call)
            trim()
        }
    }

    /**
     * 取最近若干条外呼记录(**最新在前**)。
     *
     * @param limit 取前若干(下限 0)
     * @return 近期外呼记录(降序,新→旧)
     */
    fun recent(limit: Int): List<HttpCall> = synchronized(lock) {
        ring.toList().takeLast(limit.coerceAtLeast(0)).asReversed()
    }

    /** @return 当前缓冲条数 */
    fun size(): Int = synchronized(lock) { ring.size }

    /** 超容量时从队首淘汰最旧。 */
    private fun trim() {
        while (ring.size > capacity) {
            ring.removeFirst()
        }
    }

    private companion object {

        /** 默认容量。 */
        private const val DEFAULT_CAPACITY = 500
    }
}
