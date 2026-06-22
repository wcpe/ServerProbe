package top.wcpe.mc.plugin.serverprobe.core.store

import taboolib.common.platform.function.getDataFolder
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 本地文件存储后端([MetricStore] 的默认且唯一内置实现,FR1.5、FR8.2)。
 *
 * 数据根目录为插件数据目录下的 `data/`,落盘布局:
 * - `data/startup/latest.json`:最近一次启动画像(供启动对比读取);
 * - `data/startup/<epochMs>.json`:历次启动画像归档,按 [ProbeConfig.historyRetention] 保留最近 N 份;
 * - `data/metrics/<serverId>/metrics-<yyyyMMdd>.jsonl`:指标历史,按实例分目录、按自然日滚动,
 *   每条快照序列化为单行 JSON 追加(JSON Lines),详见 [MetricHistoryFile]。
 *
 * 序列化/反序列化统一经 [Json] 适配器(ADR-14,默认后端 TabooLib Configuration `Type.JSON_MINIMAL`):
 * 序列化走 `Json.encode`、反序列化走 `Json.decode`。原子写入由 [AtomicJsonWriter] 保证。
 *
 * M2 FR8 扩面:覆盖 [readStartupProfiles](读 `data/startup/` 归档,由新到旧)与
 * [readHistory](按 [MetricHistoryFile.resolveRange] 定位日期范围内的 `metrics-*.jsonl` 逐行读取并按时间过滤);
 * 二者均为**读盘路径**,坏文件/坏行 `runCatching` 跳过并告警,绝不因单份损坏而整体失败。
 *
 * 作为 IOC [Service] 注入到编排层/平台监听器使用。**注意**:所有落盘方法(含上述读取方法)均不另起线程,
 * 要求**调用方已在异步上下文**(编排采集任务、启动监听的延迟异步任务皆为异步;只读 API 的历史读取由其 KDoc 约定调用方异步),
 * 以满足"主线程零阻塞"(规范 R7)。
 *
 * **测试说明**:序列化往返依赖 [Json] 默认后端的运行期实现(重定位后的 nightconfig),不在裸单测类路径,
 * 故 JSON 往返/本类的序列化逻辑**不写可运行单测**,其正确性由 TabooLib 保证 + 真机验证;
 * 本模块单测仅覆盖不依赖运行期 JSON 后端的纯逻辑([AtomicJsonWriter]、[InstanceId]、
 * [top.wcpe.mc.plugin.serverprobe.core.startup.StartupComparator])。
 */
@Service
class LocalFileMetricStore : MetricStore {

    /**
     * 上次执行历史文件清理时所属的自然日(`yyyyMMdd`);尚未清理过为 null。
     *
     * 用于把清理限制为**每天至多一次**:仅当当前自然日与之不同(即跨天)时才触发 [MetricHistoryFile.prune],
     * 从而避免在高频采集路径上每周期扫描目录。由异步采集线程读写,故用 `@Volatile` 保证可见性。
     */
    @Volatile
    private var lastPruneDay: String? = null

    override fun saveStartupProfile(profile: StartupProfile) {
        val json = serializeProfile(profile)
        // 1) 覆盖写最新画像(原子)
        AtomicJsonWriter.writeAtomic(latestProfilePath(), json)
        // 2) 追加一份归档(以生成时刻命名),随后按保留份数清理
        val archivePath = startupDir().resolve("${profile.createdAtMs}$JSON_SUFFIX")
        AtomicJsonWriter.writeAtomic(archivePath, json)
        pruneArchives()
    }

    override fun lastStartupProfile(): StartupProfile? {
        val text = AtomicJsonWriter.readText(latestProfilePath()) ?: return null
        return runCatching {
            Json.decode<StartupProfile>(text)
        }.getOrElse {
            ProbeLogger.warn("读取最近启动画像失败,将按无基线处理:${it.message}")
            null
        }
    }

    /**
     * 追加一条历史指标到"按实例分目录 + 按自然日滚动"的 JSON Lines 文件(FR3.2)。
     *
     * 流程:① 配置开关 [ProbeConfig.historyFileEnabled] 关闭则直接返回;② 解析当天目标文件
     * `data/metrics/<serverId>/metrics-<yyyyMMdd>.jsonl` 并建目录;③ 行式追加单行 JSON;
     * ④ **跨天清理**——当本次所属自然日与 [lastPruneDay] 不同时,触发一次 [MetricHistoryFile.prune]
     * (按保留天数 + 总体积上限清理旧文件,绝不删当天),并更新 [lastPruneDay];清理每天至多一次,不在高频路径扫目录。
     * 任何失败仅 [ProbeLogger.warn],不向外抛、不影响采集主流程。
     *
     * 本方法须由**已在异步上下文**的调用方驱动(编排采集任务为异步),以满足主线程零阻塞(规范 R7)。
     *
     * 与以下既有概念相互独立:启动画像归档保留份数([ProbeConfig.historyRetention],针对 `data/startup/`)、
     * 内存近期历史缓冲容量([ProbeConfig.historyCapacity],不落盘);三者配置与语义各不相干。
     *
     * @param snapshot 待追加的指标快照。
     */
    override fun appendHistory(snapshot: MetricSnapshot) {
        if (!ProbeConfig.historyFileEnabled()) {
            return
        }
        runCatching {
            val root = dataRoot()
            val serverId = snapshot.serverId
            val path = MetricHistoryFile.resolvePath(root, serverId, snapshot.timestampMs)
            Files.createDirectories(path.parent)
            // 行式追加(JSON Lines):文件不存在则创建,存在则在末尾追加一行
            Files.write(
                path,
                serializeSnapshotLine(snapshot).toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            pruneIfDayChanged(root, serverId, snapshot.timestampMs)
        }.onFailure {
            ProbeLogger.warn("追加指标历史失败:${it.message}")
        }
    }

    /**
     * 读取历史归档的若干份启动画像,**由新到旧**返回至多 [limit] 份(M2 FR8 扩面)。
     *
     * 流程:① [limit] 非正直接返回空;② 列出 `data/startup/` 下归档文件——经 [archiveEpoch] 仅识别文件名为
     * 纯数字 epoch 毫秒的 `.json`(自动排除 `latest.json` 及其它文件);③ 按 epoch 降序取前 [limit] 个;
     * ④ 逐份 `Configuration` 反序列化为 [StartupProfile],单份失败 `runCatching` 跳过并 [ProbeLogger.warn],不影响其余。
     *
     * **读盘方法**:遍历归档目录并逐份反序列化,开销随保留份数增长,调用方须在**异步上下文**调用(规范 R7)。
     *
     * **测试说明**:反序列化依赖 [Configuration] 的运行期实现(重定位后的 nightconfig,不在裸单测类路径),
     * 故本方法不写可运行单测,其正确性由 TabooLib 保证 + 真机验证;目录枚举/排序的纯逻辑由 [archiveEpoch] 等既有路径覆盖。
     *
     * @param limit 期望返回的最大份数;非正时返回空列表。
     * @return 历史启动画像列表(由新到旧);无归档或读取失败时为空列表。
     */
    override fun readStartupProfiles(limit: Int): List<StartupProfile> {
        if (limit <= 0) {
            return emptyList()
        }
        val files = startupDir().toFile().listFiles() ?: return emptyList()
        return files.asSequence()
            .mapNotNull { file -> archiveEpoch(file)?.let { epoch -> file to epoch } }
            .sortedByDescending { it.second }
            .take(limit)
            .mapNotNull { (file, _) -> readProfileFile(file) }
            .toList()
    }

    /**
     * 读取 `[sinceMs, untilMs]`(闭区间)范围内的历史指标快照,至多 [limit] 条(M2 FR8 扩面)。
     *
     * 流程:① [limit] 非正直接返回空;② 经 [MetricHistoryFile.resolveRange] 定位本实例在该日期范围内实际存在的
     * `metrics-*.jsonl` 文件(按日期升序);③ 逐文件逐行 `Configuration` 反序列化为 [MetricSnapshot],
     * 仅保留 [MetricSnapshot.timestampMs] 落在 `[sinceMs, untilMs]` 内者(文件按自然日分桶,边界日仍需逐条过滤),
     * 累计达 [limit] 即止;单行解析失败 `runCatching` 跳过并 [ProbeLogger.warn],不影响其余行。
     *
     * 实例口径:与采集写入一致,取当前实例 `serverId`([InstanceId.resolve] + 配置 `server-name`),
     * 即只读"本服务器"的历史(与最新快照/近期缓冲/最近启动画像的单实例语义一致),不跨实例混读。
     *
     * **读盘方法**:遍历并逐行解析历史文件,调用方须在**异步上下文**调用(规范 R7)。
     *
     * 返回内容仅取决于磁盘已有文件,与当前 `history-file.enabled` 开关无关(开关只控制是否继续写入新数据,
     * 不影响对历史文件的读取)。
     *
     * **测试说明**:逐行反序列化依赖 [Configuration] 运行期实现(不在裸单测类路径),故本方法不写可运行单测,
     * 真机验证;日期范围定位的纯逻辑见 [MetricHistoryFile.resolveRange] 的单测。
     *
     * @param sinceMs 范围下界(epoch 毫秒,含)。
     * @param untilMs 范围上界(epoch 毫秒,含)。
     * @param limit 期望返回的最大条数;非正时返回空列表。
     * @return 范围内历史快照列表(按文件→行的自然顺序,大体由旧到新);无数据时为空列表。
     */
    override fun readHistory(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot> {
        if (limit <= 0) {
            return emptyList()
        }
        val serverId = InstanceId.resolve(ProbeConfig.configuredServerName())
        val files = MetricHistoryFile.resolveRange(dataRoot(), serverId, sinceMs, untilMs)
        val result = ArrayList<MetricSnapshot>()
        for (file in files) {
            readSnapshotLines(file, sinceMs, untilMs, limit - result.size, result)
            if (result.size >= limit) {
                break
            }
        }
        return result
    }

    /**
     * 读取单个历史指标文件:逐行反序列化为 [MetricSnapshot],按时间范围过滤后追加到 [sink],至多再收 [remaining] 条。
     *
     * 整体读取以 `runCatching` 容错(读文件失败仅告警);单行解析失败逐行 `runCatching` 跳过并告警,不中断本文件其余行。
     * 空行直接跳过(JSON Lines 末尾换行的兜底)。
     *
     * @param file 历史指标文件(`metrics-<yyyyMMdd>.jsonl`)。
     * @param sinceMs 范围下界(epoch 毫秒,含)。
     * @param untilMs 范围上界(epoch 毫秒,含)。
     * @param remaining 本文件最多还可收取的条数(由上层按 `limit - 已收数` 传入)。
     * @param sink 收集结果的可变列表(原地追加)。
     */
    private fun readSnapshotLines(
        file: Path,
        sinceMs: Long,
        untilMs: Long,
        remaining: Int,
        sink: MutableList<MetricSnapshot>
    ) {
        if (remaining <= 0) {
            return
        }
        runCatching {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                var taken = 0
                while (taken < remaining) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) {
                        continue
                    }
                    val snapshot = deserializeSnapshotLine(line) ?: continue
                    // JSONL 按 timestampMs 递增追加(每采集周期一行):一旦成功解析的行已越过上界,
                    // 后续行只会更晚,本文件无需再扫;仅在"成功解析且 timestampMs > untilMs"时早停,
                    // 坏行(解析失败已 continue)与时钟回拨的早期行不会误触发 break
                    if (snapshot.timestampMs > untilMs) {
                        break
                    }
                    if (snapshot.timestampMs >= sinceMs) {
                        sink.add(snapshot)
                        taken++
                    }
                }
            }
        }.onFailure {
            ProbeLogger.warn("读取历史指标文件失败,已跳过:${it.message}")
        }
    }

    /**
     * 反序列化单份启动画像归档文件;失败时告警并返回 null(供上层 `mapNotNull` 跳过坏文件)。
     *
     * @param file 启动画像归档文件。
     * @return 解析得到的 [StartupProfile];失败时为 null。
     */
    private fun readProfileFile(file: File): StartupProfile? {
        val text = AtomicJsonWriter.readText(file.toPath()) ?: return null
        return runCatching {
            Json.decode<StartupProfile>(text)
        }.getOrElse {
            ProbeLogger.warn("读取启动画像归档失败,已跳过(${file.name}):${it.message}")
            null
        }
    }

    /**
     * 反序列化单行历史指标(JSON Lines 的一行)为 [MetricSnapshot];失败时告警并返回 null(供调用方跳过坏行)。
     *
     * @param line 单行 JSON 文本(已确保非空白)。
     * @return 解析得到的 [MetricSnapshot];失败时为 null。
     */
    private fun deserializeSnapshotLine(line: String): MetricSnapshot? =
        runCatching {
            Json.decode<MetricSnapshot>(line)
        }.getOrElse {
            ProbeLogger.warn("解析历史指标行失败,已跳过:${it.message}")
            null
        }

    /**
     * 跨天才清理:仅当本次快照所属自然日与上次清理日 [lastPruneDay] 不同时,触发一次按实例的历史文件清理并更新标记。
     *
     * 把清理收敛到每天至多一次,避免每个采集周期扫描目录(性能,规范第 17 条)。清理本身的容错由
     * [MetricHistoryFile.prune] 内部 `runCatching` 保证,异常仅经回调告警。
     *
     * @param root 数据根目录。
     * @param serverId 实例标识。
     * @param epochMs 本次快照时刻 epoch 毫秒。
     */
    private fun pruneIfDayChanged(root: Path, serverId: String, epochMs: Long) {
        val today = MetricHistoryFile.dayOf(epochMs)
        if (today == lastPruneDay) {
            return
        }
        MetricHistoryFile.prune(
            dataRoot = root,
            serverId = serverId,
            retentionDays = ProbeConfig.historyFileRetentionDays(),
            maxTotalMb = ProbeConfig.historyFileMaxTotalMb(),
            onError = { ProbeLogger.warn("清理历史指标文件失败:${it.message}") }
        )
        lastPruneDay = today
    }

    /**
     * 将启动画像序列化为缩进 JSON 文本。
     *
     * @param profile 启动画像。
     * @return JSON 文本。
     */
    private fun serializeProfile(profile: StartupProfile): String = Json.encode(profile)

    /**
     * 将指标快照序列化为单行 JSON 并补行尾换行(JSON Lines 的一行)。
     *
     * [Type.JSON_MINIMAL] **恒输出紧凑单行**:它不做缩进/换行美化,故正常情况下结果本就不含换行;
     * 即便某字段值内部含有换行符,nightconfig 序列化器也会将其转义为两字符序列 `\n`(反斜杠 + 字母 n),
     * 并不会写出真实换行字节。因此下方 `replace("\r"/"\n")` **仅为防御性兜底**(防范序列化实现未来变化或
     * 极端边界),不会误伤字段内容——被转义的 `\n` 是字面两字符,不在 `replace` 的匹配范围内。
     *
     * @param snapshot 指标快照。
     * @return 末尾带换行的单行 JSON。
     */
    private fun serializeSnapshotLine(snapshot: MetricSnapshot): String {
        val json = Json.encode(snapshot)
        // 防御性兜底:JSON_MINIMAL 恒单行、值内换行已被序列化器转义为 \n(字面两字符),
        // 此处去除真实换行字节仅为保证 JSON Lines 每条记录占且仅占一行,不会误伤字段内容
        val singleLine = json.replace("\r", "").replace("\n", "")
        return "$singleLine\n"
    }

    /**
     * 按 [ProbeConfig.historyRetention] 清理启动画像归档,仅保留最近 N 份(latest.json 不计入、不删除)。
     *
     * 以归档文件名(epoch 毫秒)数值降序排序,删除排在 N 之后的较旧归档。清理失败仅告警,不影响主流程。
     */
    private fun pruneArchives() {
        val keep = ProbeConfig.historyRetention()
        runCatching {
            val files = startupDir().toFile().listFiles() ?: return
            files.asSequence()
                .mapNotNull { file -> archiveEpoch(file)?.let { epoch -> file to epoch } }
                .sortedByDescending { it.second }
                .drop(keep)
                .forEach { it.first.delete() }
        }.onFailure {
            ProbeLogger.warn("清理启动画像归档失败:${it.message}")
        }
    }

    /**
     * 取归档文件名中的 epoch 毫秒;非归档文件(非普通文件、非 `.json` 后缀、或文件名非纯数字)返回 null。
     *
     * 借文件名(去后缀)是否为纯数字(epoch 毫秒)来识别归档,从而排除 `latest.json` 及其它文件,避免误删。
     *
     * @param file 待判断文件。
     * @return epoch 毫秒值;非归档文件时为 null。
     */
    private fun archiveEpoch(file: File): Long? {
        if (!file.isFile) {
            return null
        }
        val name = file.name
        if (!name.endsWith(JSON_SUFFIX)) {
            return null
        }
        return name.removeSuffix(JSON_SUFFIX).toLongOrNull()
    }

    /** 数据根目录 `data/`。 */
    private fun dataRoot(): Path = getDataFolder().toPath().resolve(DATA_DIR_NAME)

    /** 启动画像目录 `data/startup/`。 */
    private fun startupDir(): Path = dataRoot().resolve(STARTUP_DIR_NAME)

    /** 最新启动画像文件 `data/startup/latest.json`。 */
    private fun latestProfilePath(): Path = startupDir().resolve(LATEST_PROFILE_FILE)

    private companion object {

        /** 数据子目录名(相对插件数据目录)。 */
        private const val DATA_DIR_NAME = "data"

        /** 启动画像子目录名。 */
        private const val STARTUP_DIR_NAME = "startup"

        /** 最新启动画像文件名。 */
        private const val LATEST_PROFILE_FILE = "latest.json"

        /** JSON 文件后缀。 */
        private const val JSON_SUFFIX = ".json"
    }
}
