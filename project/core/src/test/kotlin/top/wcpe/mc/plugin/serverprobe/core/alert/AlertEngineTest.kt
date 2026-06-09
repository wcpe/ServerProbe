package top.wcpe.mc.plugin.serverprobe.core.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample

/**
 * [AlertEngine] 防抖/恢复状态机的单元测试。
 *
 * 经 [AlertEngine.configureForTest] 绕过 IOC/配置直接装配:注册一个 [RecordingChannel] 捕获事件,
 * 喂入构造的快照序列,断言事件次数与触发/恢复语义。覆盖:
 * - 防抖:连续越线达 sustainCycles 才触发;
 * - 防重:持续越线只触发一次;
 * - 恢复:触发后回落正常发且仅发一次恢复;
 * - 死锁:sustain=1 立即触发;
 * - 数据缺失:extract=null 时不误发恢复(N/A ≠ 恢复正常)。
 */
class AlertEngineTest {

    /** 被测引擎。 */
    private lateinit var engine: AlertEngine

    /** 捕获事件的 fake 通道。 */
    private lateinit var channel: RecordingChannel

    /** 通道注册中心(真实实现 + fake 通道)。 */
    private lateinit var registry: AlertChannelRegistry

    @BeforeEach
    fun setUp() {
        engine = AlertEngine()
        channel = RecordingChannel()
        registry = AlertChannelRegistry().apply { register(channel) }
    }

    /** 防抖:sustainCycles=3,前两次越线不触发,第三次才触发一次。 */
    @Test
    fun `连续越线达到持续周期才触发`() {
        engine.configureForTest(registry, listOf(tpsRule(threshold = 15.0, sustainCycles = 3)))
        // 第 1、2 次越线:计数累积但未达 3,不触发
        engine.evaluate(serverSnapshot(tps1m = 10.0))
        engine.evaluate(serverSnapshot(tps1m = 10.0))
        assertTrue(channel.events.isEmpty(), "未达持续周期不应触发")
        // 第 3 次越线:达到 3,触发一次
        engine.evaluate(serverSnapshot(tps1m = 10.0))
        assertEquals(1, channel.events.size, "达到持续周期应触发一次")
        assertTrue(channel.events.single().firing, "应为触发事件")
        assertEquals(10.0, channel.events.single().value, 1e-9, "事件观测值应为越线值")
    }

    /** 防抖中途恢复正常:计数清零,后续需重新累积,期间不产出任何事件。 */
    @Test
    fun `防抖期间回正则计数清零不触发`() {
        engine.configureForTest(registry, listOf(tpsRule(threshold = 15.0, sustainCycles = 3)))
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 越线 1
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 越线 2
        engine.evaluate(serverSnapshot(tps1m = 20.0)) // 回正,计数清零(此前未触发,不发恢复)
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 越线 1(重新计)
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 越线 2
        assertTrue(channel.events.isEmpty(), "中途回正后重新累积,期间不应有任何事件")
    }

    /** 防重:已触发后持续越线只发一次触发事件。 */
    @Test
    fun `持续越线只触发一次`() {
        engine.configureForTest(registry, listOf(tpsRule(threshold = 15.0, sustainCycles = 1)))
        repeat(5) { engine.evaluate(serverSnapshot(tps1m = 10.0)) }
        assertEquals(1, channel.events.size, "持续越线应只触发一次")
        assertTrue(channel.events.single().firing)
    }

    /** 恢复:触发后回落正常,发且仅发一次恢复事件;之后再越线可再次触发。 */
    @Test
    fun `触发后回正发一次恢复并可再次触发`() {
        engine.configureForTest(registry, listOf(tpsRule(threshold = 15.0, sustainCycles = 1)))
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 触发
        engine.evaluate(serverSnapshot(tps1m = 20.0)) // 恢复
        engine.evaluate(serverSnapshot(tps1m = 20.0)) // 仍正常,不重复发恢复
        assertEquals(2, channel.events.size, "应有 触发 + 恢复 共两次事件")
        assertTrue(channel.events[0].firing, "第一次为触发")
        assertFalse(channel.events[1].firing, "第二次为恢复")
        assertEquals(20.0, channel.events[1].value, 1e-9, "恢复事件值应为回落后的正常值")
        // 再次越线 → 再次触发
        engine.evaluate(serverSnapshot(tps1m = 10.0))
        assertEquals(3, channel.events.size, "恢复后再越线应能再次触发")
        assertTrue(channel.events[2].firing)
    }

    /** 死锁(事件型):sustainCycles=1,首次出现死锁线程即触发。 */
    @Test
    fun `死锁 sustain 为 1 立即触发`() {
        engine.configureForTest(
            registry,
            listOf(AlertRule(AlertType.DEADLOCK, threshold = 0.0, sustainCycles = 1, level = AlertLevel.CRITICAL, enabled = true))
        )
        engine.evaluate(serverSnapshot(tps1m = 20.0, deadlockedThreadCount = 0)) // 无死锁,不触发
        assertTrue(channel.events.isEmpty(), "无死锁不应触发")
        engine.evaluate(serverSnapshot(tps1m = 20.0, deadlockedThreadCount = 1)) // 出现死锁,立即触发
        assertEquals(1, channel.events.size, "出现死锁应立即触发")
        assertTrue(channel.events.single().firing)
        assertEquals(AlertType.DEADLOCK, channel.events.single().rule.type)
    }

    /** 数据缺失:已触发后取值变 null(如 server 缺失/Folia),只清状态、不误发恢复。 */
    @Test
    fun `数据缺失不误发恢复`() {
        engine.configureForTest(registry, listOf(tpsRule(threshold = 15.0, sustainCycles = 1)))
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 触发
        assertEquals(1, channel.events.size)
        // server=null(代理端语义)→ TPS extract=null:不应产出恢复事件
        engine.evaluate(proxySnapshot())
        assertEquals(1, channel.events.size, "数据缺失不应误报恢复,事件数应仍为 1")
    }

    /** 数据缺失重置计数:防抖累积中遇 null 应清零,需重新累积才触发。 */
    @Test
    fun `数据缺失重置防抖计数`() {
        engine.configureForTest(registry, listOf(tpsRule(threshold = 15.0, sustainCycles = 2)))
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 越线 1
        engine.evaluate(proxySnapshot())              // 数据缺失,计数清零
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 越线 1(重新计,未达 2)
        assertTrue(channel.events.isEmpty(), "数据缺失清零后未达持续周期,不应触发")
        engine.evaluate(serverSnapshot(tps1m = 10.0)) // 越线 2 → 触发
        assertEquals(1, channel.events.size, "重新累积达持续周期应触发")
    }

    /** 总开关思路:空规则集时 evaluate 空转、不产出事件。 */
    @Test
    fun `空规则集不产出事件`() {
        engine.configureForTest(registry, emptyList())
        engine.evaluate(serverSnapshot(tps1m = 1.0))
        assertTrue(channel.events.isEmpty(), "无规则不应产出事件")
    }

    // —— 测试夹具 ——

    /** 捕获 publish 的 fake 通道。 */
    private class RecordingChannel : AlertChannel {
        /** 按序记录收到的事件。 */
        val events = mutableListOf<AlertEvent>()
        override fun publish(event: AlertEvent) {
            events += event
        }
    }

    /** 构造一条 TPS_LOW 规则(WARN 级,启用)。 */
    private fun tpsRule(threshold: Double, sustainCycles: Int): AlertRule =
        AlertRule(AlertType.TPS_LOW, threshold, sustainCycles, AlertLevel.WARN, enabled = true)

    /** 构造含服务器维度的快照,设定 tps1m 与死锁数。 */
    private fun serverSnapshot(tps1m: Double?, deadlockedThreadCount: Int = 0): MetricSnapshot = MetricSnapshot(
        schemaVersion = 1,
        timestampMs = 0,
        serverId = "test",
        platform = ProbePlatform.BUKKIT,
        jvm = jvm(deadlockedThreadCount),
        server = ServerMetrics(
            tick = TickSample(
                tps1m = tps1m,
                tps5m = null,
                tps15m = null,
                msptAvg = null,
                msptP95 = 10.0,
                msptP99 = null,
                source = TickSampleSource.SELF_SAMPLING
            ),
            onlinePlayers = 0,
            maxPlayers = 20,
            uptimeMs = 0
        )
    )

    /** 构造代理端快照:server=null → 服务器维度指标 extract 为 null(模拟数据缺失)。 */
    private fun proxySnapshot(): MetricSnapshot = MetricSnapshot(
        schemaVersion = 1,
        timestampMs = 0,
        serverId = "test",
        platform = ProbePlatform.BUNGEE,
        jvm = jvm(deadlockedThreadCount = 0),
        server = null
    )

    /** 构造测试用 JVM 指标:仅死锁数有意义,其余占位。 */
    private fun jvm(deadlockedThreadCount: Int): JvmMetrics = JvmMetrics(
        heapUsedBytes = 0,
        heapCommittedBytes = 0,
        heapMaxBytes = -1,
        nonHeapUsedBytes = 0,
        nonHeapCommittedBytes = 0,
        nonHeapMaxBytes = -1,
        memoryPools = emptyList(),
        gcYoungCount = 0,
        gcYoungTimeMs = 0,
        gcOldCount = 0,
        gcOldTimeMs = 0,
        gcCollectors = emptyList(),
        threadCount = 0,
        daemonThreadCount = 0,
        peakThreadCount = 0,
        deadlockedThreadCount = deadlockedThreadCount,
        loadedClassCount = 0,
        totalLoadedClassCount = 0,
        processCpuLoad = -1.0,
        systemCpuLoad = -1.0,
        uptimeMs = 0,
        startTimeMs = 0,
        jvmArgs = emptyList()
    )
}
