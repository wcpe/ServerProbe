package top.wcpe.mc.plugin.serverprobe.core.mcp

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 有界事件环形缓冲（FR-20）：只保留最近 [capacity] 条事件，按时间顺序提供过滤查询。
 *
 * 线程安全（[ConcurrentLinkedDeque]），读多写少；玩家事件监听写入、MCP 请求线程读取。
 */
/** 有界事件环形缓冲（FR-20）：只保留最近 [capacity] 条事件，按时间顺序提供过滤查询。 */
class BoundedEventRing<T>(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "容量必须大于零" }
    }

    /** 默认容量：2000（FR-20 事件时间线）。 */
    private companion object {
        const val DEFAULT_CAPACITY = 2000
    }

    private val deque = ConcurrentLinkedDeque<T>()

    /** 追加事件；超出容量时丢弃最旧一条。 */
    fun add(event: T) {
        deque.addLast(event)
        while (deque.size > capacity) deque.pollFirst()
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
