package top.wcpe.mc.plugin.serverprobe.core.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 历史指标落盘的纯文件/时间逻辑(FR3.2)。
 *
 * 仅依赖 `java.time` 与 `java.nio.file`,**不依赖 TabooLib [taboolib.module.configuration.Configuration]
 * 或任何平台 API**,因此其路径解析、按日分桶、清理策略均可在裸单测类路径下直接验证。
 *
 * 落盘布局(按实例分目录 + 按自然日滚动):
 * `<dataRoot>/metrics/<serverId>/metrics-<yyyyMMdd>.jsonl`
 *
 * 与既有概念严格区分:
 * - `history-capacity`:内存环形缓冲(默认 360 份),供只读 API 回看,不落盘;
 * - `history-retention`:启动画像归档保留份数(默认 30 份),针对 `data/startup/`;
 * - 本类对应**独立**的 `history-file.*` 配置段,只管 `data/metrics/` 下的历史指标文件。
 *
 * 无状态纯逻辑,以 `object` 实现,不纳入 IOC 容器。
 */
object MetricHistoryFile {

    /**
     * 取给定时刻所属的自然日(系统默认时区),格式 `yyyyMMdd`。
     *
     * 按系统时区分桶,使同一运维日的快照落入同一文件,便于按天检索与清理。
     *
     * @param epochMs epoch 毫秒。
     * @return `yyyyMMdd` 形式的日期串。
     */
    fun dayOf(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY_FORMATTER)

    /**
     * 解析给定时刻、给定实例对应的历史指标文件路径。
     *
     * 路径形如 `<dataRoot>/metrics/<serverId>/metrics-<yyyyMMdd>.jsonl`:按实例分目录隔离多实例,
     * 按自然日滚动单文件。
     *
     * @param dataRoot 数据根目录(插件数据目录下的 `data/`)。
     * @param serverId 实例标识。
     * @param epochMs 采样时刻 epoch 毫秒,决定落入哪一天的文件。
     * @return 该实例当天的历史指标文件路径。
     */
    fun resolvePath(dataRoot: Path, serverId: String, epochMs: Long): Path =
        instanceDir(dataRoot, serverId).resolve("$FILE_PREFIX${dayOf(epochMs)}$FILE_SUFFIX")

    /**
     * 解析 `[sinceMs, untilMs]`(闭区间)所跨自然日范围内、**实际存在**的历史指标文件路径(M2 FR8 读取)。
     *
     * 按系统时区把时间范围映射为若干自然日,对每一天调用 [resolvePath] 得到候选文件,仅保留磁盘上确实存在的;
     * 结果按日期**升序**(最旧在前)返回,便于上层按时间顺序逐文件逐行读取。纯文件/时间逻辑,不依赖 [Configuration]。
     *
     * 边界:[sinceMs] > [untilMs] 时范围为空,返回空列表;实例目录不存在或当天无文件时对应日跳过。
     *
     * @param dataRoot 数据根目录。
     * @param serverId 实例标识。
     * @param sinceMs 范围下界(epoch 毫秒,含)。
     * @param untilMs 范围上界(epoch 毫秒,含)。
     * @return 范围内实际存在的历史指标文件路径(按日期升序);无则空列表。
     */
    fun resolveRange(dataRoot: Path, serverId: String, sinceMs: Long, untilMs: Long): List<Path> {
        if (sinceMs > untilMs) {
            return emptyList()
        }
        val zone = ZoneId.systemDefault()
        val startDay = Instant.ofEpochMilli(sinceMs).atZone(zone).toLocalDate()
        val endDay = Instant.ofEpochMilli(untilMs).atZone(zone).toLocalDate()
        val instanceDir = instanceDir(dataRoot, serverId)
        val result = ArrayList<Path>()
        var day = startDay
        while (!day.isAfter(endDay)) {
            val file = instanceDir.resolve("$FILE_PREFIX${day.format(DAY_FORMATTER)}$FILE_SUFFIX")
            if (Files.isRegularFile(file)) {
                result.add(file)
            }
            day = day.plusDays(1)
        }
        return result
    }

    /**
     * 清理某实例目录下过期/超额的历史指标文件,**绝不删除当天文件**。
     *
     * 分两阶段(均以系统时区"今天"为基准,当天文件始终豁免):
     * 1. **按保留天数**:保留含今天在内最近 `retentionDays` 天的文件;文件名日期(或回退到文件修改时间)
     *    早于 `今天 - (retentionDays - 1)` 的 `.jsonl` 删除(例:retentionDays=7 时保留今天及之前 6 天,共 7 天);
     * 2. **按总体积**:若实例目录下 `.jsonl` 总体积仍超过 `maxTotalMb`,从**最旧**的文件起逐个删除,直到达标。
     *
     * **`maxTotalMb` 非法值兜底**:`maxTotalMb <= 0` 视为"不限制体积"——直接跳过体积清理阶段(阶段二),
     * 仅执行按保留天数清理(阶段一)。该约定避免运维误配 `0`/负数时把除当天外的历史文件全部删空。
     *
     * 全程 `runCatching` 容错:任何文件系统异常都不会向外抛出;若传入 [onError] 则把异常交给它(用于告警),
     * 否则静默忽略。设计上由调用方在**跨天**时触发(非高频路径),避免每个采集周期扫描目录。
     *
     * @param dataRoot 数据根目录。
     * @param serverId 实例标识。
     * @param retentionDays 历史文件保留天数(按自然日;当天不计入删除)。
     * @param maxTotalMb 该实例历史文件总体积上限(MB);`<= 0` 表示不限制体积,跳过体积清理阶段。
     * @param onError 异常回调(可选);提供时把容错捕获到的异常交给它,便于上层告警。
     */
    fun prune(
        dataRoot: Path,
        serverId: String,
        retentionDays: Int,
        maxTotalMb: Int,
        onError: ((Throwable) -> Unit)? = null
    ) {
        runCatching {
            val dir = instanceDir(dataRoot, serverId)
            if (!Files.isDirectory(dir)) {
                return
            }
            val today = LocalDate.now(ZoneId.systemDefault())
            val files = listHistoryFiles(dir)
            // 保留含今天在内最近 retentionDays 天:截止日 = 今天 -(retentionDays - 1),早于它的删除。
            // 取 max(retentionDays,1) 兜底非法配置(<=1 时退化为仅保留当天),且当天文件再加一道豁免。
            val cutoff = today.minusDays((maxOf(retentionDays, 1) - 1).toLong())
            // 阶段一:按保留天数划分——过期文件直接删除,其余进入阶段二(当天文件永远豁免)
            val (expired, survivors) = files.partition { file ->
                val fileDay = dayOfFile(file, today)
                fileDay.isBefore(cutoff) && fileDay.isBefore(today)
            }
            expired.forEach { runCatching { Files.deleteIfExists(it) } }
            // 阶段二:maxTotalMb <= 0 视为不限制体积,跳过体积清理(兜底误配 0/负数导致历史被删空);
            // 否则在阶段一幸存者中,若总体积超限则从最旧删到达标(当天文件永远豁免)
            if (maxTotalMb > 0) {
                pruneBySize(survivors, today, maxTotalMb.toLong() * BYTES_PER_MB)
            }
        }.onFailure { error ->
            // 仅经回调上报,绝不向外抛(清理失败不应影响采集主流程)
            onError?.invoke(error)
        }
    }

    /**
     * 按总体积上限从最旧文件起删除,直到达标;当天文件始终保留、不参与删除。
     *
     * @param files 候选文件(阶段一幸存者)。
     * @param today 系统时区今天。
     * @param maxTotalBytes 总体积上限(字节)。
     */
    private fun pruneBySize(files: List<Path>, today: LocalDate, maxTotalBytes: Long) {
        var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        if (total <= maxTotalBytes) {
            return
        }
        // 按文件日期升序(最旧在前)逐个删除,跳过当天文件
        val oldestFirst = files.sortedBy { dayOfFile(it, today) }
        for (file in oldestFirst) {
            if (total <= maxTotalBytes) {
                break
            }
            if (dayOfFile(file, today).isEqual(today)) {
                continue
            }
            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                total -= size
            }
        }
    }

    /**
     * 列出实例目录下所有历史指标文件(`metrics-*.jsonl`)。
     *
     * @param dir 实例目录。
     * @return 历史指标文件列表(无序)。
     */
    private fun listHistoryFiles(dir: Path): List<Path> =
        Files.newDirectoryStream(dir, "$FILE_PREFIX*$FILE_SUFFIX").use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }

    /**
     * 取文件对应的自然日:优先解析文件名中的 `yyyyMMdd`,无法解析时回退到文件最后修改日期。
     *
     * 回退到修改时间可兜底人为改名或异常文件,避免漏删/误删。任何异常一律回退为 [fallback]。
     *
     * @param file 历史指标文件。
     * @param fallback 解析失败时的回退日期(通常传"今天",使无法判定的文件被当作当天而豁免删除)。
     * @return 该文件所属自然日。
     */
    private fun dayOfFile(file: Path, fallback: LocalDate): LocalDate {
        val name = file.fileName.toString()
        val datePart = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        runCatching { return LocalDate.parse(datePart, DAY_FORMATTER) }
        return runCatching {
            Files.getLastModifiedTime(file).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrDefault(fallback)
    }

    /**
     * 实例历史目录 `<dataRoot>/metrics/<serverId>/`。
     *
     * `serverId` 先经 [sanitizeServerId] 净化再作目录段:`server-name` 由运维在 config 自由填写,
     * 不净化则形如 `../` / 绝对路径的取值会让 [Path.resolve] 逃逸出 `metrics/` 目录。写入路径
     * ([resolvePath])与清理路径([prune])均经由本方法,故净化在此单一入口完成即对二者一致生效。
     *
     * @param dataRoot 数据根目录。
     * @param serverId 实例标识(未净化的原始值)。
     * @return 实例历史目录路径(目录段已净化为安全文件名)。
     */
    private fun instanceDir(dataRoot: Path, serverId: String): Path =
        dataRoot.resolve(METRICS_DIR_NAME).resolve(sanitizeServerId(serverId))

    /**
     * 把 `serverId` 净化为安全的单层目录名,防止路径逃逸(目录穿越)。
     *
     * 仅保留 `[A-Za-z0-9_-]`,其余字符(含 `/`、`\`、`.`、`:`、空格等)一律替换为 `_`;
     * 因此 `../`、绝对路径、盘符等都无法构成路径分隔符或回溯,结果恒为单层安全名。
     * 若净化后为空串(原值全是非法字符),回退为 [DEFAULT_SERVER_ID]。
     *
     * @param serverId 未净化的原始实例标识。
     * @return 仅含 `[A-Za-z0-9_-]` 的非空安全目录名。
     */
    private fun sanitizeServerId(serverId: String): String {
        val sanitized = serverId.map { ch -> if (ch in SAFE_SERVER_ID_CHARS) ch else '_' }.joinToString("")
        return sanitized.ifEmpty { DEFAULT_SERVER_ID }
    }

    /** 历史指标根子目录名(相对 `data/`)。 */
    private const val METRICS_DIR_NAME = "metrics"

    /** `serverId` 净化后允许保留的字符集合(白名单 `[A-Za-z0-9_-]`),其余字符替换为 `_`。 */
    private val SAFE_SERVER_ID_CHARS: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '-')).toSet()

    /** `serverId` 净化后为空串时的回退目录名。 */
    private const val DEFAULT_SERVER_ID = "default"

    /** 历史指标文件名前缀。 */
    private const val FILE_PREFIX = "metrics-"

    /** 历史指标文件后缀(JSON Lines)。 */
    private const val FILE_SUFFIX = ".jsonl"

    /** 每 MB 字节数。 */
    private const val BYTES_PER_MB = 1024L * 1024L

    /** 按日分桶的日期格式(`yyyyMMdd`)。 */
    private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
}
