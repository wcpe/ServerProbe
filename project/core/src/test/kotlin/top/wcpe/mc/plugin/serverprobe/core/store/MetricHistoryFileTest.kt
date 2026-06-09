package top.wcpe.mc.plugin.serverprobe.core.store

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [MetricHistoryFile] 单元测试。
 *
 * 该类为纯文件/时间逻辑(`java.time` + `java.nio.file`),不依赖 TabooLib 与平台,可直接验证。
 * 覆盖:① [MetricHistoryFile.resolvePath] 的"按实例分目录 + 按日滚动 + 文件名日期格式";
 * ② [MetricHistoryFile.dayOf] 跨天得到不同日期串;③ [MetricHistoryFile.prune] 删超保留天数、保当天、
 * 按体积上限从最旧删;④ 空目录/不存在目录的容错不抛;
 * ⑤ [MetricHistoryFile.resolveRange] 的日期范围定位(同天/跨天升序/跳过缺失天/排除范围外/空范围/目录不存在)。
 * 所有用例在 [Files.createTempDirectory] 隔离的临时目录内进行,互不干扰。
 */
class MetricHistoryFileTest {

    /** resolvePath 应落在 `metrics/<serverId>/metrics-<yyyyMMdd>.jsonl`,且日期取系统时区当天。 */
    @Test
    fun `resolvePath 按实例分目录且文件名日期正确`() {
        val root = Files.createTempDirectory("probe-history-resolve")
        val epoch = System.currentTimeMillis()

        val path = MetricHistoryFile.resolvePath(root, "srv-A", epoch)

        val expectedDay = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        assertEquals("metrics-$expectedDay.jsonl", path.fileName.toString(), "文件名应为 metrics-<当天>.jsonl")
        assertEquals("srv-A", path.parent.fileName.toString(), "应按实例分目录")
        assertEquals("metrics", path.parent.parent.fileName.toString(), "实例目录应位于 metrics/ 下")
        assertEquals(root, path.parent.parent.parent, "metrics/ 应直接位于 dataRoot 下")
    }

    /** 不同实例应解析到不同子目录,互不串扰。 */
    @Test
    fun `resolvePath 不同实例落不同目录`() {
        val root = Files.createTempDirectory("probe-history-multi")
        val epoch = System.currentTimeMillis()

        val a = MetricHistoryFile.resolvePath(root, "srv-A", epoch)
        val b = MetricHistoryFile.resolvePath(root, "srv-B", epoch)

        assertNotEquals(a.parent, b.parent, "不同实例应落在不同目录")
    }

    /** dayOf 对相隔一天以上的时刻应得到不同日期串。 */
    @Test
    fun `dayOf 跨天不同`() {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2L * 24 * 60 * 60 * 1000

        assertNotEquals(MetricHistoryFile.dayOf(now), MetricHistoryFile.dayOf(twoDaysAgo), "相隔两天应得到不同日期串")
    }

    /** prune 应删除超出保留天数的旧文件,但保留当天文件。 */
    @Test
    fun `prune 删超保留天数并保当天`() {
        val root = Files.createTempDirectory("probe-history-prune-days")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        // 当天文件、10 天前文件;保留天数 7 → 10 天前应删,当天应留
        val todayFile = writeHistoryFile(root, serverId, today, sizeBytes = 10)
        val oldFile = writeHistoryFile(root, serverId, today.minusDays(10), sizeBytes = 10)

        MetricHistoryFile.prune(root, serverId, retentionDays = 7, maxTotalMb = 1024)

        assertTrue(Files.exists(todayFile), "当天文件必须保留")
        assertFalse(Files.exists(oldFile), "超出保留天数的旧文件应被删除")
    }

    /** 保留天数边界:retentionDays=7 时,含当天在内最近 7 天保留,第 7 天前(6 天前)保留、更早删除。 */
    @Test
    fun `prune 保留天数边界含当天最近 N 天`() {
        val root = Files.createTempDirectory("probe-history-prune-edge")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        // 6 天前在保留窗口内(今天 + 之前 6 天 = 7 天),7 天前刚好越界
        val sixDaysAgo = writeHistoryFile(root, serverId, today.minusDays(6), sizeBytes = 10)
        val sevenDaysAgo = writeHistoryFile(root, serverId, today.minusDays(7), sizeBytes = 10)

        MetricHistoryFile.prune(root, serverId, retentionDays = 7, maxTotalMb = 1024)

        assertTrue(Files.exists(sixDaysAgo), "6 天前应在 7 天保留窗口内")
        assertFalse(Files.exists(sevenDaysAgo), "7 天前应越界被删")
    }

    /** prune 第二阶段:总体积超上限时从最旧文件起删除,直到达标,且当天文件豁免。 */
    @Test
    fun `prune 按体积上限从最旧删并保当天`() {
        val root = Files.createTempDirectory("probe-history-prune-size")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        // 三个文件均在保留天数内(retentionDays 给足),各约 1MB;上限 2MB → 需删 1 个,从最旧删
        val oldest = writeHistoryFile(root, serverId, today.minusDays(2), sizeBytes = ONE_MB)
        val middle = writeHistoryFile(root, serverId, today.minusDays(1), sizeBytes = ONE_MB)
        val todayFile = writeHistoryFile(root, serverId, today, sizeBytes = ONE_MB)

        MetricHistoryFile.prune(root, serverId, retentionDays = 30, maxTotalMb = 2)

        assertFalse(Files.exists(oldest), "超体积上限时应先删最旧文件")
        assertTrue(Files.exists(middle), "删到达标即止,次旧应保留")
        assertTrue(Files.exists(todayFile), "当天文件即便最'新'也不参与按体积删除,必须保留")
    }

    /** 体积上限只剩当天文件仍超标时,绝不删当天:宁可超额也保当天。 */
    @Test
    fun `prune 仅当天文件超体积也不删当天`() {
        val root = Files.createTempDirectory("probe-history-prune-today-only")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        // 仅一个当天文件,体积 2MB,上限 1MB → 仍不得删当天
        val todayFile = writeHistoryFile(root, serverId, today, sizeBytes = 2 * ONE_MB)

        MetricHistoryFile.prune(root, serverId, retentionDays = 7, maxTotalMb = 1)

        assertTrue(Files.exists(todayFile), "即使超体积,当天文件也绝不删除")
    }

    /** P1-1:maxTotalMb<=0 视为不限制体积,体积阶段被跳过——即使总体积很大也不因体积删除任何文件。 */
    @Test
    fun `prune maxTotalMb 为 0 不因体积删除任何文件`() {
        val root = Files.createTempDirectory("probe-history-prune-zero-cap")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        // 三个文件均在保留天数内(retentionDays 给足),总计约 3MB;maxTotalMb=0 应跳过体积清理,全部保留
        val oldest = writeHistoryFile(root, serverId, today.minusDays(2), sizeBytes = ONE_MB)
        val middle = writeHistoryFile(root, serverId, today.minusDays(1), sizeBytes = ONE_MB)
        val todayFile = writeHistoryFile(root, serverId, today, sizeBytes = ONE_MB)

        MetricHistoryFile.prune(root, serverId, retentionDays = 30, maxTotalMb = 0)

        assertTrue(Files.exists(oldest), "maxTotalMb=0 表示不限制体积,最旧文件不应被删")
        assertTrue(Files.exists(middle), "maxTotalMb=0 表示不限制体积,次旧文件不应被删")
        assertTrue(Files.exists(todayFile), "当天文件必须保留")
    }

    /** P1-3:含 `../`、`/`、`:` 等危险字符的 serverId 应被净化为单层安全目录名,不含路径分隔符、不逃逸出 metrics/。 */
    @Test
    fun `resolvePath serverId 危险字符被净化不逃逸`() {
        val root = Files.createTempDirectory("probe-history-sanitize")
        val epoch = System.currentTimeMillis()
        // 同时包含 ../ 回溯、正反斜杠、盘符冒号、空格
        val malicious = "../../etc:\\srv name"

        val path = MetricHistoryFile.resolvePath(root, malicious, epoch)

        val instanceDirName = path.parent.fileName.toString()
        // 目录段仅由白名单字符构成(其余替换为下划线),不含任何路径分隔符或盘符冒号
        assertTrue(instanceDirName.all { it.isLetterOrDigit() || it == '_' || it == '-' },
            "净化后的目录名应仅含 [A-Za-z0-9_-],实际为 $instanceDirName")
        assertFalse(instanceDirName.contains('/'), "净化后的目录名不应含正斜杠")
        assertFalse(instanceDirName.contains('\\'), "净化后的目录名不应含反斜杠")
        assertFalse(instanceDirName.contains(".."), "净化后的目录名不应含回溯序列")
        // 实例目录必须严格位于 metrics/ 下(metrics/ 又直接位于 dataRoot 下),未发生逃逸
        assertEquals("metrics", path.parent.parent.fileName.toString(), "实例目录应位于 metrics/ 下")
        assertEquals(root, path.parent.parent.parent, "metrics/ 应直接位于 dataRoot 下,未逃逸")
        // 规范化后仍以 dataRoot 为前缀,进一步确认没有越界
        assertTrue(path.normalize().startsWith(root.normalize()), "最终路径规范化后应仍在 dataRoot 之内")
    }

    /** P1-3:serverId 全为非法字符时净化结果回退为安全的非空目录名(default),仍不逃逸。 */
    @Test
    fun `resolvePath serverId 全非法字符回退为安全目录`() {
        val root = Files.createTempDirectory("probe-history-sanitize-fallback")
        val epoch = System.currentTimeMillis()

        val path = MetricHistoryFile.resolvePath(root, "///", epoch)

        val instanceDirName = path.parent.fileName.toString()
        assertTrue(instanceDirName.isNotEmpty(), "净化后目录名不应为空")
        assertTrue(instanceDirName.all { it.isLetterOrDigit() || it == '_' || it == '-' },
            "回退目录名应仅含安全字符,实际为 $instanceDirName")
        assertEquals("metrics", path.parent.parent.fileName.toString(), "实例目录应位于 metrics/ 下")
        assertEquals(root, path.parent.parent.parent, "metrics/ 应直接位于 dataRoot 下,未逃逸")
    }

    /** resolveRange:同一天范围应只返回当天那一个已存在文件。 */
    @Test
    fun `resolveRange 同天范围返回当天文件`() {
        val root = Files.createTempDirectory("probe-range-sameday")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        val todayFile = writeHistoryFile(root, serverId, today, sizeBytes = 10)

        val start = epochAtStartOfDay(today)
        val end = epochAtStartOfDay(today) + 23L * 60 * 60 * 1000

        val files = MetricHistoryFile.resolveRange(root, serverId, start, end)

        assertEquals(listOf(todayFile), files, "同天范围应只返回当天文件")
    }

    /** resolveRange:跨多天范围应按日期升序返回区间内**实际存在**的文件,缺失的天自动跳过。 */
    @Test
    fun `resolveRange 跨天按升序返回存在文件并跳过缺失天`() {
        val root = Files.createTempDirectory("probe-range-multiday")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        // 写 3 天前与 1 天前(2 天前缺失);范围覆盖 [3天前, 今天]
        val threeDaysAgo = writeHistoryFile(root, serverId, today.minusDays(3), sizeBytes = 10)
        val oneDayAgo = writeHistoryFile(root, serverId, today.minusDays(1), sizeBytes = 10)

        val start = epochAtStartOfDay(today.minusDays(3))
        val end = epochAtStartOfDay(today) + 12L * 60 * 60 * 1000

        val files = MetricHistoryFile.resolveRange(root, serverId, start, end)

        // 仅返回存在的两个,且按日期升序(3 天前在前、1 天前在后);缺失的 2 天前与无文件的今天不出现
        assertEquals(listOf(threeDaysAgo, oneDayAgo), files, "应按日期升序返回存在文件并跳过缺失天")
    }

    /** resolveRange:范围外的文件不应被包含。 */
    @Test
    fun `resolveRange 不含范围外文件`() {
        val root = Files.createTempDirectory("probe-range-outside")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        val inRange = writeHistoryFile(root, serverId, today.minusDays(1), sizeBytes = 10)
        // 10 天前在范围外
        writeHistoryFile(root, serverId, today.minusDays(10), sizeBytes = 10)

        val start = epochAtStartOfDay(today.minusDays(2))
        val end = epochAtStartOfDay(today) + 12L * 60 * 60 * 1000

        val files = MetricHistoryFile.resolveRange(root, serverId, start, end)

        assertEquals(listOf(inRange), files, "范围外文件不应被包含")
    }

    /** resolveRange:sinceMs > untilMs(空范围)应返回空列表。 */
    @Test
    fun `resolveRange 空范围返回空`() {
        val root = Files.createTempDirectory("probe-range-empty")
        val serverId = "srv"
        val today = LocalDate.now(ZoneId.systemDefault())
        writeHistoryFile(root, serverId, today, sizeBytes = 10)

        val start = epochAtStartOfDay(today) + 12L * 60 * 60 * 1000
        val end = start - 1 // since > until

        assertTrue(MetricHistoryFile.resolveRange(root, serverId, start, end).isEmpty(), "since>until 应返回空")
    }

    /** resolveRange:实例目录不存在时安静返回空列表,不抛异常。 */
    @Test
    fun `resolveRange 实例目录不存在返回空`() {
        val root = Files.createTempDirectory("probe-range-absent")
        val today = LocalDate.now(ZoneId.systemDefault())

        val start = epochAtStartOfDay(today.minusDays(2))
        val end = epochAtStartOfDay(today) + 12L * 60 * 60 * 1000

        assertTrue(MetricHistoryFile.resolveRange(root, "absent", start, end).isEmpty(), "目录不存在应返回空")
    }

    /** 目录不存在 / 空目录时清理应安静返回,不抛异常。 */
    @Test
    fun `prune 空目录或不存在目录容错不抛`() {
        val root = Files.createTempDirectory("probe-history-prune-empty")

        // 实例目录尚不存在
        assertDoesNotThrow { MetricHistoryFile.prune(root, "absent", retentionDays = 7, maxTotalMb = 200) }

        // 实例目录存在但为空
        Files.createDirectories(root.resolve("metrics").resolve("empty"))
        assertDoesNotThrow { MetricHistoryFile.prune(root, "empty", retentionDays = 7, maxTotalMb = 200) }
    }

    /**
     * 在 `metrics/<serverId>/` 下写一个指定日期、指定体积的历史文件。
     *
     * @param root 数据根目录。
     * @param serverId 实例标识。
     * @param day 文件名所用日期。
     * @param sizeBytes 文件字节数(用空格填充)。
     * @return 写出的文件路径。
     */
    private fun writeHistoryFile(root: Path, serverId: String, day: LocalDate, sizeBytes: Int): Path {
        val dir = root.resolve("metrics").resolve(serverId)
        Files.createDirectories(dir)
        val name = "metrics-${day.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.jsonl"
        val file = dir.resolve(name)
        Files.write(file, ByteArray(sizeBytes) { ' '.code.toByte() })
        return file
    }

    /**
     * 取给定自然日在系统时区零点的 epoch 毫秒(用于构造 resolveRange 的时间边界)。
     *
     * @param day 自然日。
     * @return 该日零点(系统时区)的 epoch 毫秒。
     */
    private fun epochAtStartOfDay(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        /** 1 MB 字节数,用于体积上限相关用例。 */
        private const val ONE_MB = 1024 * 1024
    }
}
