package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * [ControlPlaneModeDecider] 单元测试（协议规格 §3.5 的启动三态判定）。
 *
 * 覆盖：有合法身份 → 托管；无身份 + 可迁移桥段 → 迁移后托管；都没有 → 独立；
 * 身份损坏/版本未知 → 独立且**不擅自迁移、不覆盖损坏文件**（半写防御）；
 * 桥段启用但字段不齐 → 独立且不迁移。
 */
class ControlPlaneModeDeciderTest {

    /** 有合法身份文件 → 托管模式，且无需迁移（配置文件里已无 `bridge` 段）。 */
    @Test
    fun `合法身份直接进入托管模式`() {
        val dir = Files.createTempDirectory("probe-mode-managed")
        val identity = sample()
        IdentityFileStore.write(dir.resolve("agent-identity.json"), identity)
        Files.write(dir.resolve("config.yml"), "collect-period-ticks: 100\n".toByteArray(Charsets.UTF_8))

        val decision = ControlPlaneModeDecider.decide(dir.resolve("agent-identity.json"), dir.resolve("config.yml"), FIXED_TIME)

        assertTrue(decision is ControlPlaneModeDecider.Decision.Managed, "应判定为托管，实际:$decision")
        val managed = decision as ControlPlaneModeDecider.Decision.Managed
        assertEquals(identity, managed.identity)
        assertEquals(null, managed.migration, "配置无 bridge 段时不应有迁移")
    }

    /** 无身份文件 + 可迁移桥段 → 自动迁移后托管。 */
    @Test
    fun `无身份且有可迁移桥段时自动迁移并托管`() {
        val dir = Files.createTempDirectory("probe-mode-migrate")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")

        val decision = ControlPlaneModeDecider.decide(identityFile, config, FIXED_TIME)

        assertTrue(decision is ControlPlaneModeDecider.Decision.Managed, "迁移成功应进入托管，实际:$decision")
        val managed = decision as ControlPlaneModeDecider.Decision.Managed
        assertEquals("instance-abc", managed.identity.instanceId)
        assertTrue(managed.migration != null, "应带回迁移结果（供上报事件）")
        assertTrue(Files.exists(identityFile), "身份文件应已落盘")
        assertFalse(BridgeSection.exists(String(Files.readAllBytes(config), Charsets.UTF_8)), "配置里的段应被移除")
    }

    /** 身份存在但配置仍残留桥段（上次迁移中断）→ 托管 + 补做清理。 */
    @Test
    fun `托管模式补做残留迁移清理`() {
        val dir = Files.createTempDirectory("probe-mode-residual")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")
        IdentityFileStore.write(identityFile, sample())

        val decision = ControlPlaneModeDecider.decide(identityFile, config, FIXED_TIME)

        val managed = decision as ControlPlaneModeDecider.Decision.Managed
        assertTrue(managed.migration != null, "应补做残留清理")
        assertFalse(BridgeSection.exists(String(Files.readAllBytes(config), Charsets.UTF_8)), "残留段应被移除")
        assertEquals(sample(), managed.identity, "已有身份不得被改写")
    }

    /** 既无身份也无桥段 → 独立模式（与改造前完全一致）。 */
    @Test
    fun `无身份也无桥段时为独立模式`() {
        val dir = Files.createTempDirectory("probe-mode-standalone")

        val decision = ControlPlaneModeDecider.decide(
            dir.resolve("agent-identity.json"),
            dir.resolve("config.yml"),
            FIXED_TIME,
        )

        assertEquals(
            ControlPlaneModeDecider.Decision.Standalone(ControlPlaneModeDecider.StandaloneReason.NO_IDENTITY),
            decision,
        )
    }

    /** 截断的身份文件 → 独立模式 + 明确原因，且不擅自迁移、不覆盖损坏文件（半写防御）。 */
    @Test
    fun `截断身份文件降级独立且不迁移`() {
        val dir = Files.createTempDirectory("probe-mode-corrupt")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")
        val broken = """{"schemaVersion":1,"provider":"p","endpo"""
        Files.write(identityFile, broken.toByteArray(Charsets.UTF_8))

        val decision = ControlPlaneModeDecider.decide(identityFile, config, FIXED_TIME)

        val standalone = decision as ControlPlaneModeDecider.Decision.Standalone
        assertEquals(ControlPlaneModeDecider.StandaloneReason.CORRUPT_IDENTITY, standalone.reason)
        assertTrue(standalone.detail.contains("解析失败"), "应说明解析失败，实际:${standalone.detail}")
        assertEquals(broken, String(Files.readAllBytes(identityFile), Charsets.UTF_8), "损坏文件不得被覆盖")
        assertTrue(BridgeSection.exists(String(Files.readAllBytes(config), Charsets.UTF_8)), "损坏时不得擅自迁移")
    }

    /** 未知 schemaVersion → 独立模式，告警必须明确说"版本未知"（不允许静默）。 */
    @Test
    fun `未知版本降级独立并说明版本未知`() {
        val dir = Files.createTempDirectory("probe-mode-version")
        val identityFile = dir.resolve("agent-identity.json")
        Files.write(
            identityFile,
            """{"schemaVersion":9,"provider":"p","endpoint":"ws://h:1/ws","instanceId":"i","credential":"c"}"""
                .toByteArray(Charsets.UTF_8),
        )

        val decision = ControlPlaneModeDecider.decide(identityFile, dir.resolve("config.yml"), FIXED_TIME)

        val standalone = decision as ControlPlaneModeDecider.Decision.Standalone
        assertEquals(ControlPlaneModeDecider.StandaloneReason.CORRUPT_IDENTITY, standalone.reason)
        assertTrue(standalone.detail.contains("版本未知"), "必须明确说明版本未知，实际:${standalone.detail}")
    }

    /** 桥段启用但三要素不齐 → 独立模式 + WARN 语义（不迁移）。 */
    @Test
    fun `桥段字段不齐时保持独立且不迁移`() {
        val dir = Files.createTempDirectory("probe-mode-incomplete")
        val config = dir.resolve("config.yml")
        Files.write(config, "bridge:\n  enabled: true\n  url: \"ws://h:1/ws\"\n".toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")

        val decision = ControlPlaneModeDecider.decide(identityFile, config, FIXED_TIME)

        val standalone = decision as ControlPlaneModeDecider.Decision.Standalone
        assertEquals(ControlPlaneModeDecider.StandaloneReason.INCOMPLETE_BRIDGE, standalone.reason)
        assertFalse(Files.exists(identityFile), "不得写入身份文件")
    }

    /** 默认模板形态（bridge 段存在但 enabled=false、字段全空）→ 独立模式，不迁移也不告警为"字段不齐"。 */
    @Test
    fun `默认模板的空桥段视为独立部署`() {
        val dir = Files.createTempDirectory("probe-mode-default")
        val config = dir.resolve("config.yml")
        Files.write(
            config,
            "bridge:\n  enabled: false\n  url: \"\"\n  instance: \"\"\n  token: \"\"\n".toByteArray(Charsets.UTF_8),
        )
        val identityFile = dir.resolve("agent-identity.json")

        val decision = ControlPlaneModeDecider.decide(identityFile, config, FIXED_TIME)

        assertEquals(
            ControlPlaneModeDecider.Decision.Standalone(ControlPlaneModeDecider.StandaloneReason.NO_IDENTITY),
            decision,
        )
        assertFalse(Files.exists(identityFile), "不得写入身份文件")
    }

    /** 合法身份样本。 */
    private fun sample(): ControlPlaneIdentity = ControlPlaneIdentity(
        schemaVersion = ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION,
        provider = "sample-vendor",
        endpoint = "ws://127.0.0.1:31001/ws/plugin-bridge",
        instanceId = "0b6f-instance",
        credential = "opaque-credential",
        capabilities = listOf("config_push"),
        createdAt = "2026-10-19T03:12:44Z",
    )

    private companion object {

        /** 固定判定时刻。 */
        private val FIXED_TIME: Date = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 19, 3, 12, 44)
        }.time

        /** 可迁移的存量配置。 */
        private const val BRIDGE_CONFIG = """collect-period-ticks: 100
bridge:
  enabled: true
  url: "ws://127.0.0.1:9102/ws/plugin-bridge"
  instance: "instance-abc"
  token: "token-abc"
"""
    }
}
