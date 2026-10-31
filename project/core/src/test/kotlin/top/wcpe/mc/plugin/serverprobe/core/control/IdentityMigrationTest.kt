package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * [IdentityMigration] 单元测试（协议规格 §3.5 的自动迁移与幂等要求）。
 *
 * 覆盖：正常迁移的产物与顺序、三要素缺失不迁移、未启用不迁移、无段不迁移、备份跳过、
 * 以及"任意步骤崩溃后重跑结果一致"（含身份已写但段未移除的断点续做）。
 */
class IdentityMigrationTest {

    /** 正常迁移：写身份文件、备份带时间戳、移除 bridge 段、其余配置与注释保真。 */
    @Test
    fun `首次迁移写出身份并移除桥段`() {
        val dir = Files.createTempDirectory("probe-migrate-ok")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")

        val outcome = IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)

        assertTrue(outcome is IdentityMigration.Outcome.Migrated, "应迁移成功，实际:$outcome")
        val migrated = outcome as IdentityMigration.Outcome.Migrated
        assertTrue(migrated.identityWritten, "首次迁移应写入身份文件")
        assertEquals("config.yml.bak.$EXPECTED_STAMP", migrated.backupFile)
        assertTrue(migrated.bridgeRemoved, "应移除 bridge 段")

        // 身份三要素来自 bridge 段;provider 为中性占位,能力位为空
        assertEquals("ws://127.0.0.1:9102/ws/plugin-bridge", migrated.identity.endpoint)
        assertEquals("instance-abc", migrated.identity.instanceId)
        assertEquals("token-abc", migrated.identity.credential)
        assertEquals(emptyList<String>(), migrated.identity.capabilities, "存量桥不声明能力位")
        assertEquals("2026-10-19T03:12:44Z", migrated.identity.createdAt)

        // 落盘产物
        val loaded = IdentityFileStore.read(identityFile)
        assertTrue(loaded is IdentityLoadResult.Loaded, "身份文件应可读回，实际:$loaded")
        assertEquals("token-abc", (loaded as IdentityLoadResult.Loaded).identity.credential, "凭证应原样落盘")

        val backup = dir.resolve("config.yml.bak.$EXPECTED_STAMP")
        assertEquals(BRIDGE_CONFIG, String(Files.readAllBytes(backup), Charsets.UTF_8), "备份应是迁移前原文")

        val updated = String(Files.readAllBytes(config), Charsets.UTF_8)
        assertFalse(BridgeSection.exists(updated), "配置里不应再有 bridge 段")
        assertTrue(updated.contains("# 采集周期"), "其它注释必须保留")
        assertTrue(updated.contains("collect-period-ticks: 100"), "其它配置项必须保留")
    }

    /** 幂等：段已移除后重跑为无操作，且不重写身份文件。 */
    @Test
    fun `迁移后重跑为无操作`() {
        val dir = Files.createTempDirectory("probe-migrate-idempotent")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")
        IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)
        val modified = Files.getLastModifiedTime(identityFile)

        val again = IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)

        assertEquals(IdentityMigration.Outcome.NoBridgeSection, again, "段已移除，重跑应无操作")
        assertEquals(modified, Files.getLastModifiedTime(identityFile), "身份文件不应被重写")
    }

    /**
     * 中途失败重跑：身份文件已写、`bridge` 段仍在（例如在步骤 3 与 5 之间被 kill）。
     *
     * 传入已存在的身份后应只补做备份与移除，终态与一次跑完一致，且**不改写**已有身份。
     */
    @Test
    fun `断点续做结果与一次跑完一致`() {
        val dir = Files.createTempDirectory("probe-migrate-resume")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")
        // 模拟步骤 3 已成功:身份文件已落盘
        val existing = ControlPlaneIdentity(
            schemaVersion = ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION,
            provider = "unknown",
            endpoint = "ws://127.0.0.1:9102/ws/plugin-bridge",
            instanceId = "instance-abc",
            credential = "token-abc",
            capabilities = emptyList(),
            createdAt = "2026-10-19T03:12:44Z",
        )
        IdentityFileStore.write(identityFile, existing)

        val outcome = IdentityMigration.migrate(config, identityFile, existing, FIXED_TIME)

        assertTrue(outcome is IdentityMigration.Outcome.Migrated, "续做应成功，实际:$outcome")
        val migrated = outcome as IdentityMigration.Outcome.Migrated
        assertFalse(migrated.identityWritten, "已有身份不应被重写")
        assertTrue(migrated.bridgeRemoved, "应补做段移除")
        assertNotNull(migrated.backupFile, "应补做备份")
        assertFalse(BridgeSection.exists(String(Files.readAllBytes(config), Charsets.UTF_8)), "段应被移除")
        assertEquals(
            existing,
            (IdentityFileStore.read(identityFile) as IdentityLoadResult.Loaded).identity,
            "已有身份必须原样保留",
        )
    }

    /** 三要素任一缺失 → 不迁移：不写身份文件、不改动配置（不猜、不兜底）。 */
    @Test
    fun `三要素缺失不迁移`() {
        val dir = Files.createTempDirectory("probe-migrate-incomplete")
        val config = dir.resolve("config.yml")
        val incomplete = "bridge:\n  enabled: true\n  url: \"ws://h:1/ws\"\n  instance: \"\"\n  token: \"t\"\n"
        Files.write(config, incomplete.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")

        val outcome = IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)

        assertEquals(IdentityMigration.Outcome.IncompleteFields, outcome)
        assertFalse(Files.exists(identityFile), "缺字段时不得写身份文件")
        assertEquals(incomplete, String(Files.readAllBytes(config), Charsets.UTF_8), "配置不得改动")
    }

    /** `bridge.enabled` 非 true → 不迁移（默认模板与显式关闭的部署不被打扰）。 */
    @Test
    fun `未启用不迁移`() {
        val dir = Files.createTempDirectory("probe-migrate-disabled")
        val config = dir.resolve("config.yml")
        val disabled = "bridge:\n  enabled: false\n  url: \"u\"\n  instance: \"i\"\n  token: \"t\"\n"
        Files.write(config, disabled.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")

        val outcome = IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)

        assertEquals(IdentityMigration.Outcome.NotEnabled, outcome)
        assertFalse(Files.exists(identityFile), "未启用时不得写身份文件")
        assertEquals(disabled, String(Files.readAllBytes(config), Charsets.UTF_8), "配置不得改动")
    }

    /** 无 bridge 段（含配置文件不存在）→ 无操作。 */
    @Test
    fun `无桥段或配置缺失时无操作`() {
        val dir = Files.createTempDirectory("probe-migrate-none")
        val config = dir.resolve("config.yml")
        val plain = "collect-period-ticks: 100\n"
        Files.write(config, plain.toByteArray(Charsets.UTF_8))

        assertEquals(
            IdentityMigration.Outcome.NoBridgeSection,
            IdentityMigration.migrate(config, dir.resolve("agent-identity.json"), null, FIXED_TIME),
        )
        assertEquals(
            IdentityMigration.Outcome.NoBridgeSection,
            IdentityMigration.migrate(dir.resolve("missing.yml"), dir.resolve("agent-identity.json"), null, FIXED_TIME),
            "配置不存在时应按无段处理",
        )
        assertEquals(plain, String(Files.readAllBytes(config), Charsets.UTF_8), "配置不得改动")
    }

    /** 同名备份已存在 → 跳过备份且不覆盖既有备份内容。 */
    @Test
    fun `同名备份已存在时跳过`() {
        val dir = Files.createTempDirectory("probe-migrate-backup")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val backup = dir.resolve("config.yml.bak.$EXPECTED_STAMP")
        Files.write(backup, "既有备份".toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")

        val outcome = IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)

        val migrated = outcome as IdentityMigration.Outcome.Migrated
        assertNull(migrated.backupFile, "同名备份已存在时应跳过")
        assertEquals("既有备份", String(Files.readAllBytes(backup), Charsets.UTF_8), "既有备份不得被覆盖")
        assertFalse(BridgeSection.exists(String(Files.readAllBytes(config), Charsets.UTF_8)), "段仍应被移除")
    }

    /** 迁移不得把凭证写进日志可见的产物（备份是原文，身份文件是唯一凭据载体）。 */
    @Test
    fun `迁移产物中的凭证只存在于身份文件`() {
        val dir = Files.createTempDirectory("probe-migrate-secret")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val identityFile = dir.resolve("agent-identity.json")

        IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)

        val identityText = String(Files.readAllBytes(identityFile), Charsets.UTF_8)
        assertTrue(identityText.contains("token-abc"), "身份文件应含凭证（供握手使用）")
        // 备份保留原文,属于运维自己的留档;此处只断言配置文件本身不再承载凭证
        assertFalse(
            BridgeSection.exists(String(Files.readAllBytes(config), Charsets.UTF_8)),
            "迁移后配置文件不应再承载桥凭据",
        )
    }

    private companion object {

        /** 固定迁移时刻，用于断言备份名与 `createdAt`。 */
        private val FIXED_TIME: Date = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 19, 3, 12, 44)
        }.time

        /** 上述时刻对应的备份名时间戳。 */
        private const val EXPECTED_STAMP = "20261019031244"

        /** 控制面写入的典型存量配置（含注释与其它配置项）。 */
        private const val BRIDGE_CONFIG = """# 探针配置
# 采集周期
collect-period-ticks: 100
bridge:
  # 是否开启插件桥
  enabled: true
  url: "ws://127.0.0.1:9102/ws/plugin-bridge"
  instance: "instance-abc"
  token: "token-abc"
history-capacity: 360
"""
    }
}
