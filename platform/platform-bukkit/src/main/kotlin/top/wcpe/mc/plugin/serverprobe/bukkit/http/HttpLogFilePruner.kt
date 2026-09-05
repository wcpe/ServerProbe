package top.wcpe.mc.plugin.serverprobe.bukkit.http

import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

/**
 * 外呼日志(`http-<yyyyMMdd>.log`)的过期归档与轮转。
 *
 * 三级模式与指标历史一致:保留窗口内为原始 .log → 过期后 gzip 归档为 `.log.gz` →
 * 归档超过归档保留期后删除。当天文件永远豁免。
 */
object HttpLogFilePruner {

    private const val FILE_PREFIX = "http-"
    private const val FILE_SUFFIX = ".log"
    private val DAY_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * 清理过期/归档外呼日志。
     *
     * @param dir 外呼日志目录。
     * @param retentionDays 原始 .log 保留天数。
     * @param archiveDays gzip 归档保留天数(0=过期直接删除不归档)。
     */
    fun prune(dir: File, today: LocalDate, retentionDays: Int, archiveDays: Int) {
        if (!dir.isDirectory) {
            return
        }
        val cutoff = today.minusDays((maxOf(retentionDays, 1) - 1).toLong())
        val logs = dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) } ?: return
        for (file in logs) {
            val day = dayOf(file.name, today)
            if (day.isBefore(cutoff) && !day.isEqual(today)) {
                if (archiveDays > 0) {
                    gzipArchive(file)
                } else {
                    file.delete()
                }
            }
        }
        pruneArchives(dir, today, archiveDays)
    }

    /** 删除超过归档保留期的 gzip 归档(archiveDays<=0 时不清理)。 */
    private fun pruneArchives(dir: File, today: LocalDate, archiveDays: Int) {
        if (archiveDays <= 0) {
            return
        }
        val archiveCutoff = today.minusDays(archiveDays.toLong())
        val archives = dir.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(".log.gz")
        } ?: return
        for (archive in archives) {
            val day = dayOf(archive.name.removeSuffix(".gz"), today)
            if (day.isBefore(archiveCutoff) && !day.isEqual(today)) {
                archive.delete()
            }
        }
    }

    private fun dayOf(name: String, fallback: LocalDate): LocalDate = runCatching {
        LocalDate.parse(name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX), DAY_FORMATTER)
    }.getOrDefault(fallback)

    private fun gzipArchive(file: File) {
        runCatching {
            val target = File(file.parentFile, file.name + ".gz")
            GZIPOutputStream(target.outputStream(), 8192).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            file.delete()
        }
    }
}
