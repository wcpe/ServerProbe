package top.wcpe.mc.plugin.serverprobe.core.control

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 待上行的控制面事件队列。
 *
 * ## 为什么需要队列
 * 迁移发生在启动期,此刻反向 WS **可能还没连上**(桥线程与迁移线程互不等待)。迁移事件若"连不上就丢",
 * 控制面就永远不知道这台机器已经迁移(协议规格 §3.5 第 6 步要求上报)。故先入队,等桥建立连接后由
 * [top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient] 取出补发。
 *
 * ## 边界
 * - 队列**有界**([MAX_PENDING]):事件是低频管理事实,不该无限堆积;满时丢最旧一条并 WARN(不静默)。
 * - 仅在内存中排队:进程退出后未补发的事件随进程消失。迁移事件不影响功能一致性(身份文件已落盘,
 *   控制面另有对账手段),不为它引入持久化队列。
 * - 线程安全:入队发生在启动后台线程,出队发生在桥读线程,内部加锁串行化。
 */
object ControlPlaneEventQueue {

    /** 待发事件（事件名 + 结构化字段）。 */
    class PendingEvent internal constructor(
        /** 事件子类型(帧内 `event` 字段)。 */
        val event: String,
        /** 结构化字段(帧内 `data` 对象)。 */
        val data: Map<String, Any?>,
    )

    /** 队列本体:受 [lock] 保护。 */
    private val pending = ArrayDeque<PendingEvent>()

    /** 并发保护锁。 */
    private val lock = Any()

    /**
     * 入队一个待上行事件。
     *
     * @param event 事件子类型。
     * @param data 结构化字段。
     */
    fun enqueue(event: String, data: Map<String, Any?>) {
        synchronized(lock) {
            while (pending.size >= MAX_PENDING) {
                val dropped = pending.removeFirst()
                ProbeLogger.warn("控制面事件队列已满,丢弃最旧事件(${dropped.event})")
            }
            pending.addLast(PendingEvent(event, data))
        }
    }

    /**
     * 取出并清空全部待发事件（桥连上后调用）。
     *
     * @return 事件列表（按入队顺序）；空队列返回空列表。
     */
    fun drain(): List<PendingEvent> = synchronized(lock) {
        if (pending.isEmpty()) {
            return emptyList()
        }
        val drained = ArrayList<PendingEvent>(pending)
        pending.clear()
        drained
    }

    /** 当前积压事件数（供诊断/测试断言）。 */
    fun size(): Int = synchronized(lock) { pending.size }

    /** 清空队列（仅测试用：隔离用例间的全局状态）。 */
    internal fun clear() {
        synchronized(lock) { pending.clear() }
    }

    /** 队列容量上限:迁移/治理这类管理事实是低频事件,几条足够。 */
    private const val MAX_PENDING = 16
}
