package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import taboolib.module.configuration.Configuration
import top.wcpe.mc.plugin.serverprobe.core.control.ConfigTier
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * 独立模式**逐键回归**单测(FR-31 最重要的验收项)。
 *
 * 断言两件事,合起来就是"与改造前逐字节一致":
 * 1. **取值形状**:66 个取值方法各自问 `config.yml` 哪个**键**、用哪个**方法**、什么**默认值**——
 *    这里把改造前的键名/方法/默认值固化成一张表,任何一处漂移(改名、换类型、动默认值)都会红。
 * 2. **返回值**:配置全空时,66 个方法各自返回改造前的默认值(含 `coerceAtLeast` 等兜底转换的结果)。
 *
 * 手段是动态代理替身(单测 classpath 无配置后端,无法构造真实 [Configuration]),
 * 它在被调用时记录 `方法 + 键 + 默认值`,于是"读取形状"可被精确断言。
 */
class ProbeConfigStandaloneTest {

    /** 记录所有对 `config.yml` 的读取。 */
    private lateinit var recorder: RecordingConfiguration

    @BeforeEach
    fun setUp() {
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
        recorder = RecordingConfiguration()
        ProbeConfig.conf = recorder.proxy
    }

    @AfterEach
    fun tearDown() {
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
        ProbeConfig.conf = RecordingConfiguration().proxy
    }

    /** 66 个取值方法读取的键/方法/默认值,必须与改造前一模一样。 */
    @Test
    fun `66 键的读取形状与改造前一致`() {
        callAllGetters()

        val actual = recorder.reads
            .filterNot { it.key.startsWith(ALERT_RULE_PREFIX) }
            .map { it.signature() }
            .toSet()

        assertEquals(EXPECTED_READS.toSet(), actual, "键名/读取方法/默认值不得漂移")
        assertEquals(EXPECTED_READS.size, EXPECTED_READS.toSet().size, "基线表自身不得有重复项")
    }

    /** 每个配置键都必须被真正读到(不允许"方法还在但键被改掉/漏读")。 */
    @Test
    fun `每个定档键都被实际读取`() {
        callAllGetters()

        val read = recorder.reads.map { it.key }.filterNot { it.startsWith(ALERT_RULE_PREFIX) }.toSet()
        // alert.rules 是段,经其子路径读取,故单独排除后应有 65 个键
        assertEquals(ConfigTier.keys() - ALERT_RULES, read, "除 alert.rules 段外,66 键都应被读到")
    }

    /** 配置全空时,66 个方法返回改造前的默认值。 */
    @Test
    fun `配置全空时返回改造前的默认值`() {
        assertEquals(100L, ProbeConfig.collectPeriodTicks())
        assertEquals(360, ProbeConfig.historyCapacity())
        assertEquals(5, ProbeConfig.startupTopN())
        assertEquals(12, ProbeConfig.aggregationWindow())
        assertNull(ProbeConfig.configuredServerName(), "server-name 未配置时为 null")
        assertFalse(ProbeConfig.debug())
        assertEquals(30, ProbeConfig.historyRetention())
        assertTrue(ProbeConfig.historyFileEnabled())
        assertEquals(7, ProbeConfig.historyFileRetentionDays())
        assertEquals(30, ProbeConfig.historyFileArchiveDays())
        assertEquals(10, ProbeConfig.agentStackMaxSamples())
        assertEquals(200, ProbeConfig.historyFileMaxTotalMb())
        assertEquals(600L, ProbeConfig.worldSamplePeriodTicks())
        assertTrue(ProbeConfig.worldEntityTypes())
        assertEquals(60L, ProbeConfig.foliaObservedRegionExpireSeconds())
        assertTrue(ProbeConfig.networkForensicsEnabled())
        assertEquals(emptySet<String>(), ProbeConfig.networkForensicsPayloadPacketTypes())
        assertEquals(emptySet<String>(), ProbeConfig.networkForensicsPayloadChannels())
        assertEquals(64 * 1_024, ProbeConfig.networkForensicsMaxPayloadBytes())
        assertEquals(60, ProbeConfig.networkForensicsRetentionDays())
        assertEquals(4L * 1_024 * 1_024 * 1_024, ProbeConfig.networkForensicsMaxTotalBytes())
        assertTrue(ProbeConfig.httpMonitorEnabled())
        assertTrue(ProbeConfig.httpMonitorLogToConsole())
        assertFalse(ProbeConfig.httpMonitorLogHeaders())
        assertTrue(ProbeConfig.httpMonitorFileEnabled())
        assertEquals(500, ProbeConfig.httpMonitorRecentCapacity())
        assertEquals(20L, ProbeConfig.httpMonitorDrainPeriodTicks())
        assertEquals(7, ProbeConfig.httpMonitorFileRetentionDays())
        assertEquals(30, ProbeConfig.httpMonitorFileArchiveDays())
        assertFalse(ProbeConfig.metricsEnabled())
        assertEquals("127.0.0.1", ProbeConfig.metricsHost())
        assertEquals(9940, ProbeConfig.metricsPort())
        assertEquals("", ProbeConfig.metricsToken())
        assertEquals(listOf("127.0.0.1"), ProbeConfig.metricsAllowedIps())
        assertFalse(ProbeConfig.mcpEnabled())
        assertEquals("127.0.0.1", ProbeConfig.mcpHost())
        assertEquals(9942, ProbeConfig.mcpPort())
        assertEquals("", ProbeConfig.mcpSecret())
        assertEquals(4, ProbeConfig.mcpTaskMaxConcurrent())
        assertEquals(30L, ProbeConfig.mcpTaskTimeoutMinutes())
        assertEquals(64, ProbeConfig.mcpTaskOutputMebibytes())
        assertEquals(60L, ProbeConfig.mcpTaskRetentionMinutes())
        assertEquals(24L, ProbeConfig.mcpArtifactRetentionHours())
        assertEquals(20, ProbeConfig.mcpAuditMaxFileMb())
        assertEquals(30, ProbeConfig.mcpAuditRetentionDays())
        assertEquals(10L, ProbeConfig.mcpArtifactMaxGib())
        assertFalse(ProbeConfig.bridgeEnabled())
        assertEquals("", ProbeConfig.bridgeUrl())
        assertEquals("", ProbeConfig.bridgeInstance())
        assertEquals("", ProbeConfig.bridgeToken())
        assertFalse(ProbeConfig.alertEnabled())
        assertTrue(ProbeConfig.alertChannelLog())
        assertTrue(ProbeConfig.alertChannelInGame())
        assertFalse(ProbeConfig.alertWebhookEnabled())
        assertEquals("", ProbeConfig.alertWebhookUrl())
        assertEquals(3_000, ProbeConfig.alertWebhookTimeoutMs())
        assertFalse(ProbeConfig.cpuEnabled())
        assertFalse(ProbeConfig.incisionEnabled())
        assertEquals(20L, ProbeConfig.cpuSamplePeriodTicks())
        assertEquals(60, ProbeConfig.cpuWindowRounds())
        assertFalse(ProbeConfig.webEnabled())
        assertEquals("127.0.0.1", ProbeConfig.webHost())
        assertEquals(9941, ProbeConfig.webPort())
        assertEquals("", ProbeConfig.webToken())
        assertEquals(listOf("127.0.0.1"), ProbeConfig.webAllowedIps())
        assertEquals(7, ProbeConfig.alertRules().size, "内置规则共 7 条,与配置空值无关")
    }

    /** 配置有值时,独立模式必须原样透传(含去空白、空串兜底、下限收敛等既有转换)。 */
    @Test
    fun `配置有值时按既有转换返回`() {
        ProbeConfig.conf = RecordingConfiguration(
            mapOf(
                "collect-period-ticks" to 40L,
                "server-name" to "  生存服  ",
                "debug" to true,
                "metrics.allowed-ips" to listOf(" 10.0.0.1 ", " "),
                "web.allowed-ips" to emptyList<String>(),
                "bridge.url" to "  ws://127.0.0.1:9102/ws/plugin-bridge  ",
                "alert.channels.webhook.timeout-ms" to 10,
                "network-forensics.max-total-gib" to 2L,
                "metrics.host" to "   ",
            ),
        ).proxy

        assertEquals(40L, ProbeConfig.collectPeriodTicks())
        assertEquals("生存服", ProbeConfig.configuredServerName())
        assertEquals(true, ProbeConfig.debug())
        assertEquals(listOf("10.0.0.1"), ProbeConfig.metricsAllowedIps(), "逐项去空白并剔除空串")
        assertEquals(listOf("127.0.0.1"), ProbeConfig.webAllowedIps(), "空列表兜底为默认本机白名单")
        assertEquals("ws://127.0.0.1:9102/ws/plugin-bridge", ProbeConfig.bridgeUrl(), "去空白")
        assertEquals(500, ProbeConfig.alertWebhookTimeoutMs(), "低于下限收敛到下限")
        assertEquals(2L * 1_024 * 1_024 * 1_024, ProbeConfig.networkForensicsMaxTotalBytes(), "GiB 转字节")
        assertEquals("127.0.0.1", ProbeConfig.metricsHost(), "空白串兜底为默认主机")
    }

    /** alert.rules 段:7 条内置规则 × 4 个子键,默认值沿用 RuleSpec。 */
    @Test
    fun `告警规则按子路径解析`() {
        ProbeConfig.alertRules()

        val reads = recorder.reads.filter { it.key.startsWith(ALERT_RULE_PREFIX) }
        assertEquals(28, reads.size, "7 条规则 × 4 个子键")
        assertEquals(
            setOf("threshold", "sustain-cycles", "level", "enabled"),
            reads.map { it.key.substringAfterLast('.') }.toSet(),
        )
        // 阈值按规则不同,抽查 TPS 警告与死锁
        assertTrue(reads.contains(Read("getDouble", "alert.rules.tps-warn.threshold", 18.0)))
        assertTrue(reads.contains(Read("getInt", "alert.rules.tps-warn.sustain-cycles", 3)))
        assertTrue(reads.contains(Read("getString", "alert.rules.tps-warn.level", null)))
        assertTrue(reads.contains(Read("getBoolean", "alert.rules.tps-warn.enabled", true)))
        assertTrue(reads.contains(Read("getDouble", "alert.rules.deadlock.threshold", 0.0)))
        assertTrue(reads.contains(Read("getInt", "alert.rules.deadlock.sustain-cycles", 1)))
    }

    /** 依序调用全部 66 个取值方法(顺序无关,只为把读取形状录全)。 */
    private fun callAllGetters() {
        val calls = listOf<() -> Any?>(
            { ProbeConfig.collectPeriodTicks() }, { ProbeConfig.historyCapacity() },
            { ProbeConfig.startupTopN() }, { ProbeConfig.aggregationWindow() },
            { ProbeConfig.configuredServerName() }, { ProbeConfig.debug() },
            { ProbeConfig.historyRetention() }, { ProbeConfig.historyFileEnabled() },
            { ProbeConfig.historyFileRetentionDays() }, { ProbeConfig.historyFileArchiveDays() },
            { ProbeConfig.agentStackMaxSamples() }, { ProbeConfig.historyFileMaxTotalMb() },
            { ProbeConfig.worldSamplePeriodTicks() }, { ProbeConfig.worldEntityTypes() },
            { ProbeConfig.foliaObservedRegionExpireSeconds() }, { ProbeConfig.networkForensicsEnabled() },
            { ProbeConfig.networkForensicsPayloadPacketTypes() }, { ProbeConfig.networkForensicsPayloadChannels() },
            { ProbeConfig.networkForensicsMaxPayloadBytes() }, { ProbeConfig.networkForensicsRetentionDays() },
            { ProbeConfig.networkForensicsMaxTotalBytes() }, { ProbeConfig.httpMonitorEnabled() },
            { ProbeConfig.httpMonitorLogToConsole() }, { ProbeConfig.httpMonitorLogHeaders() },
            { ProbeConfig.httpMonitorFileEnabled() }, { ProbeConfig.httpMonitorRecentCapacity() },
            { ProbeConfig.httpMonitorDrainPeriodTicks() }, { ProbeConfig.httpMonitorFileRetentionDays() },
            { ProbeConfig.httpMonitorFileArchiveDays() }, { ProbeConfig.metricsEnabled() },
            { ProbeConfig.metricsHost() }, { ProbeConfig.metricsPort() },
            { ProbeConfig.metricsToken() }, { ProbeConfig.metricsAllowedIps() },
            { ProbeConfig.mcpEnabled() }, { ProbeConfig.mcpHost() },
            { ProbeConfig.mcpPort() }, { ProbeConfig.mcpSecret() },
            { ProbeConfig.mcpTaskMaxConcurrent() }, { ProbeConfig.mcpTaskTimeoutMinutes() },
            { ProbeConfig.mcpTaskOutputMebibytes() }, { ProbeConfig.mcpTaskRetentionMinutes() },
            { ProbeConfig.mcpArtifactRetentionHours() }, { ProbeConfig.mcpAuditMaxFileMb() },
            { ProbeConfig.mcpAuditRetentionDays() }, { ProbeConfig.mcpArtifactMaxGib() },
            { ProbeConfig.bridgeEnabled() }, { ProbeConfig.bridgeUrl() },
            { ProbeConfig.bridgeInstance() }, { ProbeConfig.bridgeToken() },
            { ProbeConfig.alertEnabled() }, { ProbeConfig.alertChannelLog() },
            { ProbeConfig.alertChannelInGame() }, { ProbeConfig.alertWebhookEnabled() },
            { ProbeConfig.alertWebhookUrl() }, { ProbeConfig.alertWebhookTimeoutMs() },
            { ProbeConfig.alertRules() }, { ProbeConfig.cpuEnabled() },
            { ProbeConfig.incisionEnabled() }, { ProbeConfig.cpuSamplePeriodTicks() },
            { ProbeConfig.cpuWindowRounds() }, { ProbeConfig.webEnabled() },
            { ProbeConfig.webHost() }, { ProbeConfig.webPort() },
            { ProbeConfig.webToken() }, { ProbeConfig.webAllowedIps() },
        )
        assertEquals(66, calls.size, "取值方法应为 66 个")
        calls.forEach { it() }
    }

    /**
     * 一次读取的记录。
     *
     * @property method `config.yml` 上的取值方法名。
     * @property key 配置键名。
     * @property default 传入的默认值(字符串读取为 null)。
     */
    private data class Read(val method: String, val key: String, val default: Any?) {

        /** 断言用的紧凑签名。 */
        fun signature(): String = "$method|$key|$default"
    }

    /** 只记录、不返回值的配置替身。 */
    private class RecordingConfiguration(values: Map<String, Any?> = emptyMap()) {

        /** 已发生的读取(按发生顺序)。 */
        val reads = ArrayList<Read>()

        /** 代理实例。 */
        val proxy: Configuration = Proxy.newProxyInstance(
            Configuration::class.java.classLoader,
            arrayOf(Configuration::class.java),
            InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() as? String
                val fallback = args?.getOrNull(1)
                if (key != null) {
                    reads.add(Read(method.name, key, fallback))
                }
                val value = values[key]
                when (method.name) {
                    "getLong" -> (value as? Number)?.toLong() ?: fallback ?: 0L
                    "getInt" -> (value as? Number)?.toInt() ?: fallback ?: 0
                    "getBoolean" -> value as? Boolean ?: fallback ?: false
                    "getDouble" -> (value as? Number)?.toDouble() ?: fallback ?: 0.0
                    "getString" -> value as? String
                    "getStringList" -> (value as? List<*>)?.map { it.toString() } ?: emptyList<String>()
                    "contains" -> values.containsKey(key)
                    else -> fallback
                }
            },
        ) as Configuration
    }

    private companion object {

        /** 告警规则段的键前缀。 */
        private const val ALERT_RULE_PREFIX = "alert.rules."

        /** 告警规则段自身(经子路径读取,不作为独立键被读)。 */
        private val ALERT_RULES = setOf("alert.rules")

        /**
         * 改造前的读取形状基线:`方法|键|默认值`。
         *
         * 这张表是"独立模式零回归"的判据——它逐字对应 FR-31 之前的 66 个取值方法。
         */
        private val EXPECTED_READS = listOf(
            "getLong|collect-period-ticks|100",
            "getInt|history-capacity|360",
            "getInt|startup-top-n|5",
            "getInt|aggregation.window|12",
            "getString|server-name|null",
            "getBoolean|debug|false",
            "getInt|history-retention|30",
            "getBoolean|history-file.enabled|true",
            "getInt|history-file.retention-days|7",
            "getInt|history-file.archive-days|30",
            "getInt|agent-stack-max-samples|10",
            "getInt|history-file.max-total-mb|200",
            "getLong|world.sample-period-ticks|600",
            "getBoolean|world.entity-types|true",
            "getLong|folia.observed-region-expire-seconds|60",
            "getBoolean|network-forensics.enabled|true",
            "getStringList|network-forensics.payload-packet-types|null",
            "getStringList|network-forensics.payload-channels|null",
            "getInt|network-forensics.max-payload-bytes|65536",
            "getInt|network-forensics.retention-days|60",
            "getLong|network-forensics.max-total-gib|4",
            "getBoolean|http-monitor.enabled|true",
            "getBoolean|http-monitor.log-to-console|true",
            "getBoolean|http-monitor.log-headers|false",
            "getBoolean|http-monitor.file-enabled|true",
            "getInt|http-monitor.recent-capacity|500",
            "getLong|http-monitor.drain-period-ticks|20",
            "getInt|http-monitor.file-retention-days|7",
            "getInt|http-monitor.file-archive-days|30",
            "getBoolean|metrics.enabled|false",
            "getString|metrics.host|null",
            "getInt|metrics.port|9940",
            "getString|metrics.token|null",
            "getStringList|metrics.allowed-ips|null",
            "getBoolean|mcp.enabled|false",
            "getString|mcp.host|null",
            "getInt|mcp.port|9942",
            "getString|mcp.secret|null",
            "getInt|mcp.task-max-concurrent|4",
            "getLong|mcp.task-timeout-minutes|30",
            "getInt|mcp.task-output-mebibytes|64",
            "getLong|mcp.task-retention-minutes|60",
            "getLong|mcp.artifact-retention-hours|24",
            "getInt|mcp.audit-max-file-mb|20",
            "getInt|mcp.audit-retention-days|30",
            "getLong|mcp.artifact-max-gib|10",
            "getBoolean|bridge.enabled|false",
            "getString|bridge.url|null",
            "getString|bridge.instance|null",
            "getString|bridge.token|null",
            "getBoolean|alert.enabled|false",
            "getBoolean|alert.channels.log|true",
            "getBoolean|alert.channels.in-game|true",
            "getBoolean|alert.channels.webhook.enabled|false",
            "getString|alert.channels.webhook.url|null",
            "getInt|alert.channels.webhook.timeout-ms|3000",
            "getBoolean|cpu.enabled|false",
            "getBoolean|incision.enabled|false",
            "getLong|cpu.sample-period-ticks|20",
            "getInt|cpu.window-rounds|60",
            "getBoolean|web.enabled|false",
            "getString|web.host|null",
            "getInt|web.port|9941",
            "getString|web.token|null",
            "getStringList|web.allowed-ips|null",
        )
    }
}
