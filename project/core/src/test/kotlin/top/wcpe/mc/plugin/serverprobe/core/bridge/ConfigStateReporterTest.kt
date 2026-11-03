package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigState
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedConfigStore
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneIdentity
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode

/**
 * [ConfigStateReporter] 单测（协议规格 §3.4.3）。
 *
 * 重点锁三件事：
 * - **`connection` 四态**逐态可判定（`online` / `degraded` / `unauthorized` / `disconnected`）——
 *   这是排障时区分"网络不通"与"凭据被吊销"的关键字段；
 * - `cacheAgeSeconds` 是"距上次成功应用"的秒数，**无基线时为 null**（不伪造成 0）；
 * - `effective` 必须脱敏后再上报。
 */
class ConfigStateReporterTest {

    /** 收帧记录器。 */
    private val frames = mutableListOf<Map<String, Any?>>()

    @BeforeEach
    fun setUp() {
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
        frames.clear()
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity())
    }

    @AfterEach
    fun tearDown() {
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
        ControlPlaneRuntime.reset()
        frames.clear()
    }

    /** 四种连接态都要如实映射成协议规定的线上取值。 */
    @Test
    fun `connection 四态如实上报`() {
        val expected = linkedMapOf(
            BridgeConnectionState.ONLINE to "online",
            BridgeConnectionState.DEGRADED to "degraded",
            BridgeConnectionState.UNAUTHORIZED to "unauthorized",
            BridgeConnectionState.DISCONNECTED to "disconnected",
        )
        expected.forEach { (state, wire) ->
            frames.clear()
            reporter(connection = { state }).reportOnce()
            assertEquals(wire, payload()[ "connection"], "${state.name} 应上报为 $wire")
        }
    }

    /** 已生效过：cacheAgeSeconds = 距上次成功应用的秒数。 */
    @Test
    fun `cacheAge 反映距上次成功应用的秒数`() {
        ManagedConfigState.markApplied(configVersion = 7, appliedAtEpochSeconds = NOW - 42)

        reporter().reportOnce()

        assertEquals(7L, payload()["configVersion"])
        assertEquals(42L, payload()["cacheAgeSeconds"])
    }

    /** 从未成功应用（既无下发也无缓存）：cacheAgeSeconds 为 null，不伪装成 0。 */
    @Test
    fun `无基线时 cacheAge 为 null`() {
        reporter().reportOnce()

        assertEquals(0L, payload()["configVersion"], "无缓存时版本为 0")
        assertNull(payload()["cacheAgeSeconds"], "无基线时不得报 0（会被误读成刚刚生效）")
    }

    /** 上次应用时间在"未来"（时钟回拨）：夹到 0，不报负数。 */
    @Test
    fun `时钟回拨时 cacheAge 不为负`() {
        ManagedConfigState.markApplied(configVersion = 3, appliedAtEpochSeconds = NOW + 600)

        reporter().reportOnce()

        assertEquals(0L, payload()["cacheAgeSeconds"])
    }

    /** `mode` 取运行模式；`capabilities` 声明托管模式的三项能力。 */
    @Test
    fun `上报运行模式与能力位`() {
        reporter().reportOnce()

        assertEquals("managed", payload()["mode"])
        @Suppress("UNCHECKED_CAST")
        val capabilities = payload()["capabilities"] as List<String>
        assertTrue(capabilities.containsAll(listOf("config_push", "metric_push", "config_state")), "实际:$capabilities")
    }

    /** 独立模式下同一方法也要如实报 standalone（供对账分辨），且能力位不得虚报任何托管能力。 */
    @Test
    fun `独立模式上报 standalone`() {
        ControlPlaneRuntime.reset()

        reporter().reportOnce()

        assertEquals("standalone", payload()["mode"])
        @Suppress("UNCHECKED_CAST")
        val capabilities = payload()["capabilities"] as List<String>
        assertTrue(capabilities.isEmpty(), "独立模式不推指标、不声明 config_push/config_state，实际:$capabilities")
    }

    /** `effective` 必须脱敏：敏感键不得原文上行，普通键原样保留。 */
    @Test
    fun `effective 敏感键被脱敏`() {
        ManagedConfigStore.replace(
            linkedMapOf(
                "history-capacity" to 360,
                "alert.channels.webhook.url" to "https://example/hook?token=abc",
                "alert.rules" to linkedMapOf("tps-warn" to linkedMapOf("threshold" to 18)),
            ),
        )

        reporter().reportOnce()

        @Suppress("UNCHECKED_CAST")
        val effective = payload()["effective"] as Map<String, Any?>
        assertEquals(360, effective["history-capacity"], "普通键应原样上报")
        assertEquals(
            "https://example/hook?token=***",
            effective["alert.channels.webhook.url"],
            "地址里夹带的查询串密钥应被遮掉，但地址本身保留供对账",
        )
        @Suppress("UNCHECKED_CAST")
        val rules = effective["alert.rules"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        assertEquals(18, (rules["tps-warn"] as Map<String, Any?>)["threshold"], "嵌套的非敏感值应保留")
    }

    /** `lastError` 透传最近一次失败/被拒原因，供控制面定位。 */
    @Test
    fun `lastError 透传失败原因`() {
        ManagedConfigState.recordError("自检失败:取不到非空指标快照")

        reporter().reportOnce()

        assertEquals("自检失败:取不到非空指标快照", payload()["lastError"])
    }

    /** 上行结果原样返回（供调用方做失败去重告警）。 */
    @Test
    fun `返回上行结果`() {
        assertEquals(UplinkOutcome.Sent, reporter(outcome = UplinkOutcome.Sent).reportOnce())
        assertEquals(UplinkOutcome.NotConnected, reporter(outcome = UplinkOutcome.NotConnected).reportOnce())
    }

    /** 帧信封：`type=event` / `event=config_state` / 带 payload。 */
    @Test
    fun `帧信封符合协议`() {
        reporter().reportOnce()

        val frame = frames.single()
        assertEquals("event", frame["type"])
        assertEquals("config_state", frame["event"])
        assertEquals("instance-9", frame["instance"])
        assertTrue(frame["payload"] is Map<*, *>, "结构化字段应放在 payload 下")
    }

    // ── 辅助 ──

    /** 取最后一帧的 `payload`。 */
    @Suppress("UNCHECKED_CAST")
    private fun payload(): Map<String, Any?> =
        frames.last()["payload"] as? Map<String, Any?> ?: throw AssertionError("帧缺少 payload:${frames.last()}")

    /** 构造上报器：连接态、上行结果与时钟都可注入。 */
    private fun reporter(
        connection: () -> BridgeConnectionState = { BridgeConnectionState.ONLINE },
        outcome: UplinkOutcome = UplinkOutcome.Sent,
    ): ConfigStateReporter = ConfigStateReporter(
        instance = { "instance-9" },
        connection = connection,
        send = { frame -> frames += frame; outcome },
        clockSeconds = { NOW },
    )

    /** 一份最小可用身份（只为把运行模式置为托管）。 */
    private fun identity(): ControlPlaneIdentity = ControlPlaneIdentity(
        schemaVersion = 1,
        provider = "any",
        endpoint = "ws://cp.example/agent",
        instanceId = "instance-9",
        credential = "opaque",
        capabilities = emptyList(),
        createdAt = "2026-10-19T03:12:44Z",
    )

    private companion object {

        /** 固定时钟（epoch 秒）。 */
        private const val NOW = 1_792_379_564L
    }
}
