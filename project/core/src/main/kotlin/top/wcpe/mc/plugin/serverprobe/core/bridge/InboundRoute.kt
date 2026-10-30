package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult

/**
 * 路由的目标执行池。
 *
 * 三池隔离是事故域边界:重载最容易卡住(插件配置加载可能阻塞主线程),
 * 绝不能与治理/业务命令共池,否则一次卡死会挤占全部命令回执。
 */
enum class ExecutorKind {

    /** 命令执行池:治理指令与业务 Provider 派发。 */
    COMMAND,

    /** 事件发送池:事件分类、滤镜、分片与入队。 */
    EVENT,

    /** 重载协调池:配置重载编排(监听器子任务另走 worker 池,见重载规格)。 */
    RELOAD,
}

/**
 * 下行命令路由扩展点。
 *
 * ## 匹配与执行必须分离
 * [claims] 是**纯同步、O(1)** 的判定,由内核在**桥读线程**上调用——它绝不能做 IO、绝不能阻塞。
 * [handle] 在 [executor] 指定的池上执行,内核**不等待**其结果(回执由池线程发出)。
 *
 * 早期设计用 `handle` 返回 null 表示"未命中",那会逼内核为了判断是否命中而先执行,
 * 等于把耗时 5s 的业务调用搬回读线程 —— 正是本改造要消灭的问题。
 *
 * ## 实现约定
 * - [claims] 不得抛异常;内核会兜底但仍视为 bug。
 * - [handle] 只允许返回结果,不得内部无界等待;失败返回 [BridgeCommandResult.fail]。
 */
interface InboundRoute {

    /** 匹配顺序,数字小的先匹配;权威分配见线路治理规格的 order 表。 */
    val order: Int

    /** 目标执行池;默认命令池。 */
    val executor: ExecutorKind
        get() = ExecutorKind.COMMAND

    /**
     * 是否由本路由处理该命令(纯同步、只读、不阻塞)。
     *
     * @param command 待判定的命令。
     * @return true 表示命中;内核随即把 [handle] 投递到 [executor] 并**停止**匹配后续路由。
     */
    fun claims(command: BridgeCommand): Boolean

    /**
     * 执行一条命令(在 [executor] 池上调用)。
     *
     * @param command 已命中的命令。
     * @return 执行结果,不得返回 null、不得抛异常。
     */
    fun handle(command: BridgeCommand): BridgeCommandResult
}

/**
 * 上行帧滤镜扩展点。
 *
 * 滤镜链在**容量判定之前**执行:被丢弃的帧不占队列容量、不计失败。
 * 顺序颠倒会让"订阅越多、业务越容易失败"(无关事件先占满队列)。
 *
 * 实现须**轻量**(纯字符串/字段判定),因为它跑在发送路径上。
 */
interface OutboundFilter {

    /** 执行顺序,数字小的先执行。 */
    val order: Int

    /**
     * 处理一帧。
     *
     * @param frame 原始帧。
     * @return 改写后的帧(可原样返回);返回 null 表示**无需上送**,由发送器计 `filteredOut`。
     */
    fun apply(frame: OutboundFrame): OutboundFrame?
}

/**
 * 发送器提交结果。
 *
 * 用枚举而非 `Boolean`:调用方需要区分"已入队""被滤镜丢弃""队列满丢弃""被拒""过大",
 * 因为这些情况对外语义完全不同(尤其"被滤镜丢弃"**不算失败**)。
 */
enum class SubmitOutcome {

    /** 已入队,将被写出。 */
    QUEUED,

    /** 被滤镜判定为无需上送——**不算失败**,调用方应视为已受理。 */
    FILTERED_OUT,

    /** 可丢帧因队列满被丢弃(已计数)。 */
    DROPPED,

    /** 不可丢帧被拒(已计数),调用方须转为业务失败。 */
    REJECTED,

    /** 单组片数超上限,无法分片传输(已计数),调用方须转为失败回执。 */
    TOO_LARGE,
}
