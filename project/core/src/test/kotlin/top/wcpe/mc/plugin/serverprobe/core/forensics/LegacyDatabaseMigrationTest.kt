package top.wcpe.mc.plugin.serverprobe.core.forensics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** 旧取证库一次性迁移到通用库名的迁移函数测试。 */
class LegacyDatabaseMigrationTest {

    @Test
    fun `旧库与 WAL SHM 一起迁移到通用库名`() {
        val dir = Files.createTempDirectory("migrate")
        Files.write(dir.resolve("network-forensics.sqlite"), byteArrayOf(1))
        Files.write(dir.resolve("network-forensics.sqlite-wal"), byteArrayOf(2))
        Files.write(dir.resolve("network-forensics.sqlite-shm"), byteArrayOf(3))

        val target = migrateLegacyDatabaseFile(dir, "network-forensics.sqlite", "serverprobe-store.sqlite")

        assertTrue(Files.isRegularFile(target))
        assertTrue(Files.isRegularFile(dir.resolve("serverprobe-store.sqlite-wal")))
        assertTrue(Files.isRegularFile(dir.resolve("serverprobe-store.sqlite-shm")))
        assertFalse(Files.isRegularFile(dir.resolve("network-forensics.sqlite")))
    }

    @Test
    fun `目标已存在时不覆盖不删除旧文件`() {
        val dir = Files.createTempDirectory("migrate-exists")
        Files.write(dir.resolve("network-forensics.sqlite"), byteArrayOf(1))
        Files.write(dir.resolve("serverprobe-store.sqlite"), byteArrayOf(9))

        val target = migrateLegacyDatabaseFile(dir, "network-forensics.sqlite", "serverprobe-store.sqlite")

        assertTrue(Files.readAllBytes(target).contentEquals(byteArrayOf(9)))
        assertTrue(Files.isRegularFile(dir.resolve("network-forensics.sqlite")))
    }

    @Test
    fun `无旧库时直接返回目标路径`() {
        val dir = Files.createTempDirectory("migrate-none")
        val target = migrateLegacyDatabaseFile(dir, "network-forensics.sqlite", "serverprobe-store.sqlite")
        assertTrue(!Files.isRegularFile(target))
    }
}
