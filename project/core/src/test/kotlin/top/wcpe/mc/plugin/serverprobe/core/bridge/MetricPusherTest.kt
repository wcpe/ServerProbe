package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot

/**
 * [MetricPusher] 单测：推送节奏之外的**语义**逐条锁定。
 *
 * 覆盖：无快照不推帧、去重键与 sequence 单调递增、桥断开时静默跳过（不补旧值）、
 * 失败原因变化才更新状态、恢复后清理失败状态。帧内字段细节由 [MetricSnapshotFrameTest] 负责。
 */
class MetricPusherTest {

    /** 收帧记录器：既当上行通道，也保存发出的帧树供断言。 */
    private val frames = mutableListOf<Map<String, Any?>>()

    /** 尚未采样（快照为 null）时一帧都不推，且不算失败。 */
    @Test
    fun `无快照时不推帧`() {
        val pusher = pusher(snapshot = null, outcome = { UplinkOutcome.Sent })

        assertNull(pusher.pushOnce(), "无快照不应推帧")
        assertTrue(frames.isEmpty(), "不应发出任何帧")
        assertNull(pusher.lastFailureReason, "无快照不是失败")
    }

    /** 正常推送：帧内去重键带实例与秒级时间戳，sequence 进程内单调递增。 */
    @Test
    fun `推帧带去重键与递增 sequence`() {
        val pusher = pusher(snapshot = snapshot(), outcome = { UplinkOutcome.Sent }, clockSeconds = { 1_760_842_364L })

        assertEquals(1L, pusher.pushOnce())
        assertEquals(2L, pusher.pushOnce())

        assertEquals(2, frames.size)
        assertEquals("metric:instance-1:1760842364", frames[0]["dedupKey"])
        assertEquals(1L, objectOf(frames[0]["payload"])["sequence"])
        assertEquals(2L, objectOf(frames[1]["payload"])["sequence"], "sequence 必须单调递增")
    }

    /** 桥未连接：静默跳过（已接受的取舍），既不算失败也不重发旧值。 */
    @Test
    fun `桥未连接时静默跳过`() {
        val pusher = pusher(snapshot = snapshot(), outcome = { UplinkOutcome.NotConnected })

        assertNull(pusher.pushOnce(), "未连接时不应报告成功")
        assertNull(pusher.lastFailureReason, "未连接属预期取舍，不应记成失败")

        // 下一次仍按当前快照重新编码（sequence 继续前进），绝不把上一帧当新的补发
        pusher.pushOnce()
        assertEquals(2L, objectOf(frames[1]["payload"])["sequence"])
    }

    /** 发送失败：原因记录在案；同一原因反复失败不改变状态（配合 WARN 去重）。 */
    @Test
    fun `同一失败原因只记录一次状态`() {
        val pusher = pusher(snapshot = snapshot(), outcome = { UplinkOutcome.Failed("写锁忙") })

        assertNull(pusher.pushOnce())
        assertEquals("写锁忙", pusher.lastFailureReason)
        assertNull(pusher.pushOnce())
        assertEquals("写锁忙", pusher.lastFailureReason, "同一原因不应产生新的状态变化")
    }

    /** 恢复：成功一次即清理失败状态，便于下一次失败重新告警。 */
    @Test
    fun `成功一次即清理失败状态`() {
        var outcome: UplinkOutcome = UplinkOutcome.Failed("写锁忙")
        val pusher = pusher(snapshot = snapshot(), outcome = { outcome })

        pusher.pushOnce()
        assertEquals("写锁忙", pusher.lastFailureReason)

        outcome = UplinkOutcome.Sent
        assertEquals(2L, pusher.pushOnce())
        assertNull(pusher.lastFailureReason, "成功后应清理失败状态")
    }

    /**
     * 断连也要清掉历史失败原因。
     *
     * 否则断连期间的 `metrics_now` 回执会拿上一次失败（例如“写锁忙”）当本次原因，
     * 让人误以为链路仍在写、只是忙。
     */
    @Test
    fun `断连时清理历史失败原因`() {
        var outcome: UplinkOutcome = UplinkOutcome.Failed("写锁忙")
        val pusher = pusher(snapshot = snapshot(), outcome = { outcome })

        pusher.pushOnce()
        assertEquals("写锁忙", pusher.lastFailureReason)

        outcome = UplinkOutcome.NotConnected
        assertNull(pusher.pushOnce())
        assertNull(pusher.lastFailureReason, "断连后不应继续持有旧失败原因")
    }

    /** 读取快照抛异常：降级为"不推帧 + 记失败"，绝不向调用方抛。 */
    @Test
    fun `读快照异常时降级为不推帧`() {
        val pusher = MetricPusher(
            snapshots = { error("采集管线异常") },
            instance = { "instance-1" },
            send = { frame -> frames += frame; UplinkOutcome.Sent },
        )

        assertNull(pusher.pushOnce())
        assertTrue(frames.isEmpty())
        assertTrue(pusher.lastFailureReason!!.contains("读取指标快照失败"), "实际:${pusher.lastFailureReason}")
    }

    /** 断言用：把帧树中的嵌套对象取成 Map。 */
    @Suppress("UNCHECKED_CAST")
    private fun objectOf(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: throw AssertionError("期望嵌套对象，实际:$value")

    /** 构造被测推送器：快照、上行结果与时钟都可注入。 */
    private fun pusher(
        snapshot: MetricSnapshot?,
        outcome: () -> UplinkOutcome,
        clockSeconds: () -> Long = { 1_760_842_364L },
    ): MetricPusher = MetricPusher(
        snapshots = { snapshot },
        instance = { "instance-1" },
        send = { frame -> frames += frame; outcome() },
        clockSeconds = clockSeconds,
    )

    /** 最小可用快照（字段细节由 [MetricSnapshotFrameTest] 负责）。 */
    private fun snapshot(): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(1_760_842_364_000L)
        .serverId("server-1")
        .platform(ProbePlatform.BUKKIT)
        .jvm(JvmMetrics.builder().heapUsedBytes(1L).heapMaxBytes(2L).threadCount(3).uptimeMs(4L).build())
        .build()
}
