package top.wcpe.mc.plugin.serverprobe.bukkit.http

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/** 外呼日志过期归档与轮转测试。 */
class HttpLogFilePrunerTest {

    private fun logFile(dir: File, day: LocalDate, body: String = "log"): File {
        val name = "http-${day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))}.log"
        val file = File(dir, name)
        file.writeText(body)
        return file
    }

    @Test
    fun `过期日志 gzip 归档且解压内容一致`() {
        val dir = Files.createTempDirectory("http-log").toFile()
        val today = LocalDate.now()
        val old = logFile(dir, today.minusDays(10), "外呼日志内容")

        HttpLogFilePruner.prune(dir, today, retentionDays = 7, archiveDays = 30)

        val gz = File(dir, old.name + ".gz")
        assertTrue(gz.exists(), "过期 .log 应 gzip 归档")
        assertFalse(old.exists(), "原始 .log 应删除")
        val restored = GZIPInputStream(gz.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(restored, "外呼日志内容")
    }

    @Test
    fun `归档超过归档保留期后删除`() {
        val dir = Files.createTempDirectory("http-log-expiry").toFile()
        val today = LocalDate.now()
        val old = logFile(dir, today.minusDays(10))
        val todayFile = logFile(dir, today)

        HttpLogFilePruner.prune(dir, today, retentionDays = 7, archiveDays = 30)
        assertTrue(File(dir, old.name + ".gz").exists())

        // 以 5 天归档保留期再清一次:10 天前的归档越界删除,今天的归档不受影响
        HttpLogFilePruner.prune(dir, today, retentionDays = 7, archiveDays = 5)

        assertFalse(File(dir, old.name + ".gz").exists(), "超过归档保留期的 gzip 应删除")
        assertTrue(todayFile.exists(), "当天日志必须保留")
    }

    private fun assertEquals(expected: String, actual: String) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual)
    }
}
