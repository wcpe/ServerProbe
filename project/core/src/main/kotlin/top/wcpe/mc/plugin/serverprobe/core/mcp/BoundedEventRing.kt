package top.wcpe.mc.plugin.serverprobe.core.mcp

import java.util.concurrent.LinkedBlockingDeque

/**
 * 有界事件环形缓冲（FR-20）：只保留最近 [capacity] 条事件，按时间顺序提供过滤查询。
 *
 * 线程安全（[LinkedBlockingDeque] 内部锁保证 add 与裁剪原子，容量严格有界），
 * 读多写少；玩家事件监听写入、MCP 请求线程读取。
 */
class BoundedEventRing<T>(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "容量必须大于零" }
    }

    /** 默认容量：2000（FR-20 事件时间线）。 */
    private companion object {
        const val DEFAULT_CAPACITY = 2000
    }

    private val deque = LinkedBlockingDeque<T>(capacity)

    /** 追加事件；容量已满时丢弃最旧一条（并发下严格有界）。 */
    fun add(event: T) {
        // offerLast 满时返回 false 不抛异常；满则先移除最旧再追加，保证严格有界。
        if (!deque.offerLast(event)) {
            deque.pollFirst()
            deque.offerLast(event)
        }
    }

    /** 返回最近至多 [limit] 条（时间正序）；[limit] 非正时返回空列表。 */
    fun recent(limit: Int): List<T> = recent(limit) { true }

    /** 返回满足过滤条件的最近至多 [limit] 条（时间正序）；[limit] 非正时返回空列表。 */
    fun recent(limit: Int, filter: (T) -> Boolean): List<T> {
        if (limit <= 0) return emptyList()
        return deque.asSequence().filter(filter).toList().takeLast(limit)
    }

    /** 当前元素数量。 */
    fun size(): Int = deque.size
}
