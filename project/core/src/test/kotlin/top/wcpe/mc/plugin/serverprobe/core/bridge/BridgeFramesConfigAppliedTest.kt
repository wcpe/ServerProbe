package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.ConfigPushOutcome

/**
 * `config_applied` 帧的两条不变式：
 *
 * 1. `effective` 必须过脱敏——它与 `config_state` 是同一份数据的两个出口，
 *    过去只遮了 `config_state`，等于把 webhook 的 `?token=` 明文发给控制面；
 * 2. `dedupKey` 必须区分**同一版本的不同结果**——否则“先被拒、修好后重试成功”的回执
 *    会被对端按 `(domain, dedupKey)` 幂等丢掉。
 */
class BridgeFramesConfigAppliedTest {

    @Test
    fun `effective 里的查询参数被脱敏`() {
        val outcome = outcome(
            result = ConfigPushOutcome.RESULT_APPLIED,
            effective = mapOf(
                "alert.channels.webhook.url" to "https://h/hook?token=abc123&k=1",
                "server-name" to "srv",
            ),
        )

        val frame = BridgeFrames.configApplied("inst", 1L, outcome)
        @Suppress("UNCHECKED_CAST")
        val payload = frame["payload"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val effective = payload["effective"] as Map<String, Any?>

        assertFalse(effective["alert.channels.webhook.url"].toString().contains("abc123"), "token 不得明文上行")
        assertTrue(effective["alert.channels.webhook.url"].toString().contains("token=***"))
        assertEquals("srv", effective["server-name"], "普通值保留，便于控制面对账")
    }

    @Test
    fun `同一版本不同结果的去重键不同`() {
        val failed = BridgeFrames.configApplied("inst", 1L, outcome(ConfigPushOutcome.RESULT_FAILED))
        val applied = BridgeFrames.configApplied("inst", 2L, outcome(ConfigPushOutcome.RESULT_APPLIED))

        assertNotEquals(
            failed["dedupKey"],
            applied["dedupKey"],
            "同版本先失败后成功的回执必须能分开，否则成功回执会被幂等丢掉",
        )
    }

    /** 同版本、同结果的重复投递仍应同键（保持幂等本意）。 */
    @Test
    fun `同版本同结果去重键一致`() {
        val first = BridgeFrames.configApplied("inst", 1L, outcome(ConfigPushOutcome.RESULT_APPLIED))
        val second = BridgeFrames.configApplied("inst", 2L, outcome(ConfigPushOutcome.RESULT_APPLIED))

        assertEquals(first["dedupKey"], second["dedupKey"])
    }

    private fun outcome(
        result: String,
        effective: Map<String, Any?> = mapOf("k" to "v"),
    ): ConfigPushOutcome = ConfigPushOutcome(
        result = result,
        configVersion = 7L,
        effective = effective,
        selfCheck = null,
        error = null,
        appliedAt = null,
    )
}
