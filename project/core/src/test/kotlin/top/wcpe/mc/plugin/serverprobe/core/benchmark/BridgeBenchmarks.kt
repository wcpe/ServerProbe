package top.wcpe.mc.plugin.serverprobe.core.benchmark

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHostImpl
import top.wcpe.mc.plugin.serverprobe.core.bridge.FrameSender
import top.wcpe.mc.plugin.serverprobe.core.bridge.TestManifests
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundSink
import top.wcpe.mc.plugin.serverprobe.core.bridge.chunk.FrameChunker
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.BridgeEventPublisher
import top.wcpe.mc.plugin.serverprobe.core.bridge.event.EventPolicy
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicFilter
import top.wcpe.mc.plugin.serverprobe.core.bridge.topic.TopicSubscriptionRegistry
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import java.util.concurrent.atomic.AtomicLong
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest

/**
 * 桥热路径微基准（手动运行：`./gradlew :project:core:benchmarkBridge`）。
 *
 * 覆盖用户关心的四类调用：
 * 1. **事件上行**（拼帧 → 编码 → 滤镜 → 分片快判 → 入队）：业务方最高频调用；
 * 2. **命令派发**（`BusinessHost.dispatch` → 池 → Provider）：面板下发命令的路径；
 * 3. **帧提交**（`FrameSender.submit`）：所有上行流量的必经之路；
 * 4. **JSON 编码**：每次上报都要走，且刚改为自研写出，必须确认没退化。
 *
 * ## 测"正常路径"而不是"队列满"
 * 单次成本类基准都接一个**吞掉帧的假连接**并启动真实发送线程：
 * 不消费队列的话，`submit` 很快撞容量上限转为拒绝，测到的是队列满的路径，
 * 而不是"提交一帧的真实成本"。
 */
object BridgeBenchmarks {

    /** 典型业务事件字段（与真实 Provider 上报量级一致）。 */
    private val TYPICAL_FIELDS = linkedMapOf(
        "activityId" to "12345",
        "progress" to "80",
        "score" to "12800",
        "updatedBy" to "player-uuid-0001",
    )

    /** 典型帧 payload（约 280 字节，接近真实业务事件）。 */
    private const val TYPICAL_PAYLOAD =
        """{"type":"event","event":"activity_change","instance":"s1","timestamp":1730000000000,""" +
            """"domain":"activity","dedupKey":"12345-1","topic":"activity.12345",""" +
            """"data":{"activityId":"12345","progress":"80","score":"12800","updatedBy":"player-uuid-0001"}}"""

    fun run() {
        println("=== 桥热路径微基准（中位数；alloc=每次调用分配字节） ===")
        benchJsonEncode()
        benchFrameSenderSubmit()
        benchEventPublish()
        benchTopicFilter()
        benchCommandDispatch()
        benchChunkerFastPath()
    }

    /** JSON 编码：Map 与集合两条自研路径（确认手写写出不比 nightconfig 慢）。 */
    private fun benchJsonEncode() {
        val payload = linkedMapOf<String, Any?>(
            "type" to "event",
            "event" to "activity_change",
            "instance" to "s1",
            "timestamp" to 1_730_000_000_000L,
            "domain" to "activity",
            "dedupKey" to "12345-1",
            "topic" to "activity.12345",
            "data" to TYPICAL_FIELDS,
        )
        Bench.measure("Json.encode(Map 8 键 + 嵌套 4 键)") { Json.encode(payload) }
        Bench.measure("Json.encode(List 3 元素)") { Json.encode(listOf("activity.1", "activity.*", "economy.2")) }
    }

    /** 帧提交：滤镜链 + 分片快判 + 原子预留 + 入队（发送线程在消费，测正常路径）。 */
    private fun benchFrameSenderSubmit() {
        val sender = FrameSender(LineSettings(reliableCapacity = 8192), null, FrameChunker(ChunkSettings()))
        val sink = CountingSink()
        sender.attach(sink)
        startSenderThread(sender)
        val frame = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "activity_change",
            domain = "activity",
            payload = TYPICAL_PAYLOAD,
            priority = OutboundFrame.Priority.RELIABLE_EVENT,
        )
        try {
            Bench.measure("FrameSender.submit(典型帧, 无订阅)") { sender.submit(frame) }
        } finally {
            sender.shutdown()
        }
    }

    /** 事件发布：最贴近业务方真实调用的整条路径（payload 构造 + 编码 + 提交）。 */
    private fun benchEventPublish() {
        val sender = FrameSender(LineSettings(reliableCapacity = 8192), null, FrameChunker(ChunkSettings()))
        val sink = CountingSink()
        sender.attach(sink)
        startSenderThread(sender)
        val publisher = BridgeEventPublisher({ sender }, { "s1" })
        try {
            Bench.measure("BridgeEventPublisher.publish(4 字段)") {
                publisher.publish("activity", "12345-1", "activity.12345", TYPICAL_FIELDS, EventPolicy.RELIABLE)
            }
        } finally {
            sender.shutdown()
        }
    }

    /** 主题滤镜：无订阅（多数部署常态）、有订阅命中、非业务帧三条路径。 */
    private fun benchTopicFilter() {
        val noSub = TopicFilter(TopicSubscriptionRegistry())
        val withSub = TopicFilter(TopicSubscriptionRegistry().apply { replace(setOf("activity.12345", "economy.*")) })
        val businessFrame = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "activity_change",
            domain = "activity",
            payload = "x",
            topic = "activity.12345",
        )
        // OutboundFrame 不是 data class（字段集是权威且不变的），故显式构造而非 copy。
        val playerFrame = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "player_join",
            domain = "",
            payload = "x",
        )

        Bench.measure("TopicFilter.apply(无订阅, 业务帧)") { noSub.apply(businessFrame) }
        Bench.measure("TopicFilter.apply(有订阅, 命中)") { withSub.apply(businessFrame) }
        Bench.measure("TopicFilter.apply(有订阅, 非业务帧)") { withSub.apply(playerFrame) }
    }

    /** 命令派发：真实走池 + Provider，是面板下发命令的固有成本下界。 */
    private fun benchCommandDispatch() {
        val host = BusinessHostImpl()
        host.register(object : BusinessProvider {
            override fun domain(): String = "bench"
            override fun manifest(): DomainManifest = TestManifests.empty(domain())
            override fun dispatch(request: ActionRequest): ActionResult =
                ActionResult.ok("ok")
        })
        try {
            Bench.measure("BusinessHost.dispatch(池上执行)", iterations = 2000) {
                host.dispatch("bench", "echo", "{}")
            }
        } finally {
            host.shutdown()
        }
    }

    /**
     * 分片器**快路径**：小帧必须"不超阈值直接放行"。
     *
     * 重点核对：若快路径仍对每帧做 `toByteArray(UTF_8)`，所有上行事件都在为
     * "万一要分片"付一次完整字节编码的代价——而实际 99.99% 的帧远小于阈值。
     */
    private fun benchChunkerFastPath() {
        val chunker = FrameChunker(ChunkSettings())
        val typical = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "activity_change",
            domain = "activity",
            payload = TYPICAL_PAYLOAD,
        )
        Bench.measure("FrameChunker.split(典型帧 ~280B)") { chunker.split(typical) }

        // 边界：payload 长度恰为阈值的纯 ASCII 帧 → 字节数也恰为阈值，不应分片。
        val atLimit = OutboundFrame(
            type = OutboundFrame.TYPE_EVENT,
            event = "activity_change",
            domain = "activity",
            payload = "x".repeat(ChunkSettings().maxFrameBytes),
        )
        Bench.measure("FrameChunker.split(恰好等于阈值)") { chunker.split(atLimit) }
    }

    /**
     * 吞吐压测：多线程并发上报，测"每秒受理多少事件"及失败率。
     *
     * 与微基准不同，这里关心并发下的**总吞吐与拒绝率**：有界队列在压力下的行为
     * 比单次延迟更能反映线上能力。
     */
    fun throughput(threads: Int = 4, seconds: Int = 3): Long {
        val sender = FrameSender(
            LineSettings(reliableCapacity = 8192, bestEffortCapacity = 8192),
            null,
            FrameChunker(ChunkSettings()),
        )
        val sink = CountingSink()
        sender.attach(sink)
        startSenderThread(sender)

        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val accepted = AtomicLong()
        val rejected = AtomicLong()
        val publisher = BridgeEventPublisher({ sender }, { "s1" })

        val workers = (1..threads).map { worker ->
            Thread({
                while (System.nanoTime() < deadline) {
                    repeat(256) {
                        if (publisher.publish("activity", "k", "activity.12345", TYPICAL_FIELDS, EventPolicy.RELIABLE)) {
                            accepted.incrementAndGet()
                        } else {
                            rejected.incrementAndGet()
                        }
                    }
                }
            }, "bench-worker-$worker").apply { isDaemon = true; start() }
        }
        workers.forEach { it.join() }

        val total = accepted.get() + rejected.get()
        val perSecond = if (seconds > 0) total / seconds else 0
        val rejectRate = if (total > 0) rejected.get() * 100.0 / total else 0.0
        println(
            "=== 并发压测：%d 线程 × %d 秒 ===".format(threads, seconds) +
                "\n  受理 %,d | 拒绝 %,d（%.2f%%）| 合计 %,d 次/秒".format(
                    accepted.get(), rejected.get(), rejectRate, perSecond,
                ) +
                "\n  实际写出 %,d 帧 | 发送器状态: %s".format(sink.count(), sender.status()),
        )
        sender.shutdown()
        return perSecond
    }

    /** 启动发送线程（只在本基准内使用；生产由 BridgeClient 负责）。 */
    private fun startSenderThread(sender: FrameSender) {
        Thread({ sender.runLoop() }, "bench-sender").apply { isDaemon = true; start() }
    }

    /** 假连接：只计数，不做任何 IO——把"网络成本"排除在指令成本之外。 */
    private class CountingSink : OutboundSink {
        private val written = AtomicLong()
        override fun sendText(text: String): Boolean {
            written.incrementAndGet()
            return true
        }

        fun count(): Long = written.get()
    }
}
