package top.wcpe.mc.plugin.serverprobe.core.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform
import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.JvmMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample

/**
 * [AlertType] 取值([AlertType.extract])与比较([AlertType.violated])的纯逻辑单元测试。
 *
 * 枚举多态自封装取值与比较,无需 IOC/平台环境,直接对各常量断言。
 * 重点覆盖各类型的 N/A(null)分支:代理端 server=null、Folia tps/mspt=null、堆无上限(heapMax≤0)。
 */
class AlertTypeTest {

    /** 浮点断言容差。 */
    private val delta = 1e-9

    /** TPS_LOW:取 tps1m;低于阈值越线、不低于不越线。 */
    @Test
    fun `TPS_LOW 取 tps1m 并按下限比较`() {
        val snapshot = serverSnapshot(tps1m = 12.5)
        assertEquals(12.5, AlertType.TPS_LOW.extract(snapshot)!!, delta)
        assertTrue(AlertType.TPS_LOW.violated(12.5, 15.0), "低于阈值应越线")
        assertFalse(AlertType.TPS_LOW.violated(19.0, 15.0), "不低于阈值不应越线")
        assertFalse(AlertType.TPS_LOW.violated(15.0, 15.0), "等于阈值不应越线(严格小于)")
    }

    /** TPS_LOW:代理端 server=null → 取值为 null(N/A)。 */
    @Test
    fun `TPS_LOW 代理端 server 为 null 时取值为 null`() {
        assertNull(AlertType.TPS_LOW.extract(proxySnapshot()), "代理端无服务器维度,TPS 应为 null")
    }

    /** TPS_LOW:Folia 全局 tps1m=null → 取值为 null(N/A)。 */
    @Test
    fun `TPS_LOW Folia tps 为 null 时取值为 null`() {
        assertNull(AlertType.TPS_LOW.extract(serverSnapshot(tps1m = null)), "Folia 无全局 TPS,应为 null")
    }

    /** MSPT_HIGH:取 msptP95;高于阈值越线。 */
    @Test
    fun `MSPT_HIGH 取 msptP95 并按上限比较`() {
        val snapshot = serverSnapshot(tps1m = 20.0, msptP95 = 80.0)
        assertEquals(80.0, AlertType.MSPT_HIGH.extract(snapshot)!!, delta)
        assertTrue(AlertType.MSPT_HIGH.violated(80.0, 50.0), "高于阈值应越线")
        assertFalse(AlertType.MSPT_HIGH.violated(40.0, 50.0), "不高于阈值不应越线")
    }

    /** MSPT_HIGH:Folia msptP95=null → 取值为 null(N/A)。 */
    @Test
    fun `MSPT_HIGH Folia mspt 为 null 时取值为 null`() {
        assertNull(AlertType.MSPT_HIGH.extract(serverSnapshot(tps1m = null, msptP95 = null)), "Folia 无 MSPT,应为 null")
    }

    /** HEAP_USAGE_HIGH:有上限时取占用率百分比;高于阈值越线。 */
    @Test
    fun `HEAP_USAGE_HIGH 有上限时取占用率百分比`() {
        // 已用 900、上限 1000 → 90%
        val snapshot = serverSnapshot(tps1m = 20.0, heapUsedBytes = 900, heapMaxBytes = 1000)
        assertEquals(90.0, AlertType.HEAP_USAGE_HIGH.extract(snapshot)!!, delta)
        assertTrue(AlertType.HEAP_USAGE_HIGH.violated(90.0, 85.0), "高于阈值应越线")
        assertFalse(AlertType.HEAP_USAGE_HIGH.violated(80.0, 85.0), "不高于阈值不应越线")
    }

    /** HEAP_USAGE_HIGH:堆无上限(heapMax=-1)→ 占用率不可计算,取值为 null(N/A)。 */
    @Test
    fun `HEAP_USAGE_HIGH 堆无上限时取值为 null`() {
        val snapshot = serverSnapshot(tps1m = 20.0, heapUsedBytes = 900, heapMaxBytes = -1)
        assertNull(AlertType.HEAP_USAGE_HIGH.extract(snapshot), "堆无上限时占用率应为 null")
    }

    /** HEAP_USAGE_HIGH:堆上限为 0 → 同样不可计算(避免除零),取值为 null。 */
    @Test
    fun `HEAP_USAGE_HIGH 堆上限为零时取值为 null`() {
        val snapshot = serverSnapshot(tps1m = 20.0, heapUsedBytes = 100, heapMaxBytes = 0)
        assertNull(AlertType.HEAP_USAGE_HIGH.extract(snapshot), "堆上限为 0 时应为 null(避免除零)")
    }

    /** DEADLOCK:取死锁线程数(恒有值);大于阈值(通常 0)越线。 */
    @Test
    fun `DEADLOCK 取死锁线程数并按下限比较`() {
        // 无死锁 → 0,不越线;有死锁 → >0,越线。代理端亦有 JVM 维度,死锁恒可取。
        assertEquals(0.0, AlertType.DEADLOCK.extract(proxySnapshot())!!, delta)
        assertEquals(2.0, AlertType.DEADLOCK.extract(serverSnapshot(tps1m = 20.0, deadlockedThreadCount = 2))!!, delta)
        assertTrue(AlertType.DEADLOCK.violated(2.0, 0.0), "死锁数 >0 应越线")
        assertFalse(AlertType.DEADLOCK.violated(0.0, 0.0), "无死锁不应越线")
    }

    // —— 测试夹具 ——

    /** 构造含服务器维度的服务端快照,按需设定 tick / 堆 / 死锁数。 */
    private fun serverSnapshot(
        tps1m: Double?,
        msptP95: Double? = 10.0,
        heapUsedBytes: Long = 0,
        heapMaxBytes: Long = -1,
        deadlockedThreadCount: Int = 0
    ): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(0)
        .serverId("test")
        .platform(ProbePlatform.BUKKIT)
        .jvm(jvm(heapUsedBytes, heapMaxBytes, deadlockedThreadCount))
        .server(
            ServerMetrics.builder()
                .tick(
                    TickSample.builder()
                        .tps1m(tps1m)
                        .tps5m(null)
                        .tps15m(null)
                        .msptAvg(null)
                        .msptP95(msptP95)
                        .msptP99(null)
                        .source(TickSampleSource.SELF_SAMPLING)
                        .build()
                )
                .onlinePlayers(0)
                .maxPlayers(20)
                .uptimeMs(0)
                .build()
        )
        .build()

    /** 构造代理端快照:server=null(无服务器维度),仅 JVM 维度。 */
    private fun proxySnapshot(): MetricSnapshot = MetricSnapshot.builder()
        .schemaVersion(1)
        .timestampMs(0)
        .serverId("proxy")
        .platform(ProbePlatform.BUNGEE)
        .jvm(jvm(heapUsedBytes = 0, heapMaxBytes = -1, deadlockedThreadCount = 0))
        .server(null)
        .build()

    /** 构造测试用 JVM 指标:仅堆与死锁数有意义,其余占位。 */
    private fun jvm(heapUsedBytes: Long, heapMaxBytes: Long, deadlockedThreadCount: Int): JvmMetrics = JvmMetrics.builder()
        .heapUsedBytes(heapUsedBytes)
        .heapCommittedBytes(0)
        .heapMaxBytes(heapMaxBytes)
        .nonHeapUsedBytes(0)
        .nonHeapCommittedBytes(0)
        .nonHeapMaxBytes(-1)
        .memoryPools(emptyList())
        .gcYoungCount(0)
        .gcYoungTimeMs(0)
        .gcOldCount(0)
        .gcOldTimeMs(0)
        .gcCollectors(emptyList())
        .threadCount(0)
        .daemonThreadCount(0)
        .peakThreadCount(0)
        .deadlockedThreadCount(deadlockedThreadCount)
        .loadedClassCount(0)
        .totalLoadedClassCount(0)
        .processCpuLoad(-1.0)
        .systemCpuLoad(-1.0)
        .uptimeMs(0)
        .startTimeMs(0)
        .jvmArgs(emptyList())
        .build()
}
