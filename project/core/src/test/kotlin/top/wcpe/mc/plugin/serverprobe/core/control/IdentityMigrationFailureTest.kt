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
 * 自动迁移的**故障注入**用例（协议规格 §3.5「任意步骤崩溃后重跑结果一致」）。
 *
 * 与 [IdentityMigrationTest] 的差别：这里刻意让某一步写盘失败，再验证两点——
 * 1. 失败不抛异常、不把 `config.yml` 写坏（探针绝不成为事故源）；
 * 2. 故障消失后重跑能收敛到与一次跑完相同的终态。
 */
class IdentityMigrationFailureTest {

    /** 身份文件不可写（父路径被同名普通文件占据）→ 迁移在第一步就失败，配置保持原样。 */
    @Test
    fun `身份文件写入失败时配置不被改动`() {
        val dir = Files.createTempDirectory("probe-migrate-fail-identity")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        // 用一个同名普通文件占住身份文件的父路径,制造"目录创建失败"
        Files.write(dir.resolve("blocked"), ByteArray(0))
        val identityFile = dir.resolve("blocked").resolve("agent-identity.json")

        val outcome = IdentityMigration.migrate(config, identityFile, null, FIXED_TIME)

        assertTrue(outcome is IdentityMigration.Outcome.Failed, "应报告失败而非抛出，实际:$outcome")
        val failed = outcome as IdentityMigration.Outcome.Failed
        assertFalse(failed.identityWritten, "身份未写入，调用方据此保持独立模式")
        assertEquals(BRIDGE_CONFIG, String(Files.readAllBytes(config), Charsets.UTF_8), "配置不得被改动")
    }

    /** 故障消除后重跑：迁移正常完成，终态与一次跑完一致（幂等收口）。 */
    @Test
    fun `故障消除后重跑收敛到同一终态`() {
        val dir = Files.createTempDirectory("probe-migrate-recover")
        val config = dir.resolve("config.yml")
        Files.write(config, BRIDGE_CONFIG.toByteArray(Charsets.UTF_8))
        val blocker = dir.resolve("blocked")
        Files.write(blocker, ByteArray(0))
        val identityFile = blocker.resolve("agent-identity.json")
        assertEquals(
            true,
            IdentityMigration.migrate(config, identityFile, null, FIXED_TIME) is IdentityMigration.Outcome.Failed,
            "首次应失败",
        )

        // 故障消除（占位文件删掉）后重跑
        Files.delete(blocker)
        val recovered = dir.resolve("agent-identity.json")
        val outcome = IdentityMigration.migrate(config, recovered, null, FIXED_TIME)

        assertTrue(outcome is IdentityMigration.Outcome.Migrated, "重跑应成功，实际:$outcome")
        assertFalse(BridgeSection.exists(String(Files.readAllBytes(config), Charsets.UTF_8)), "段应被移除")
        assertTrue(Files.exists(recovered), "身份文件应已落盘")
    }

    private companion object {

        /** 固定迁移时刻。 */
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
