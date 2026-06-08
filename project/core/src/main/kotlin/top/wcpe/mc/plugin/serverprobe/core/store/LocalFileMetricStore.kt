package top.wcpe.mc.plugin.serverprobe.core.store

import taboolib.common.platform.function.getDataFolder
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
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
 * - `data/metrics.jsonl`:指标历史,每条快照序列化为单行 JSON 追加(JSON Lines)。
 *
 * 序列化统一走 TabooLib [Configuration]([Type.JSON_MINIMAL]):序列化得 [Configuration] 后以 `.toString()` 取 JSON 文本,
 * 反序列化经 `loadFromString` + `deserialize(ignoreConstructor = true)`。原子写入由 [AtomicJsonWriter] 保证。
 *
 * 作为 IOC [Service] 注入到编排层/平台监听器使用。**注意**:所有落盘方法均不另起线程,要求**调用方已在异步上下文**
 * (编排采集任务、启动监听的延迟异步任务皆为异步),以满足"主线程零阻塞"(规范 R7)。
 *
 * **测试说明**:序列化往返依赖 [Configuration] 的运行期实现(重定位后的 nightconfig),该实现不在裸单测类路径,
 * 故 JSON 往返/本类的序列化逻辑**不写可运行单测**,其正确性由 TabooLib 保证 + M1 末真机验证;
 * 本模块单测仅覆盖不依赖 [Configuration] 的纯逻辑([AtomicJsonWriter]、[InstanceId]、
 * [top.wcpe.mc.plugin.serverprobe.core.startup.StartupComparator])。
 */
@Service
class LocalFileMetricStore : MetricStore {

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
            val section = Configuration.loadFromString(text, Type.JSON_MINIMAL)
            Configuration.deserialize<StartupProfile>(section, ignoreConstructor = true)
        }.getOrElse {
            ProbeLogger.warn("读取最近启动画像失败,将按无基线处理:${it.message}")
            null
        }
    }

    override fun appendHistory(snapshot: MetricSnapshot) {
        runCatching {
            val line = serializeSnapshotLine(snapshot)
            val path = metricsHistoryPath()
            Files.createDirectories(path.parent)
            // 行式追加(JSON Lines):文件不存在则创建,存在则在末尾追加一行
            Files.write(
                path,
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }.onFailure {
            ProbeLogger.warn("追加指标历史失败:${it.message}")
        }
    }

    /**
     * 将启动画像序列化为缩进 JSON 文本。
     *
     * @param profile 启动画像。
     * @return JSON 文本。
     */
    private fun serializeProfile(profile: StartupProfile): String =
        Configuration.serialize(profile, Type.JSON_MINIMAL).toString()

    /**
     * 将指标快照序列化为单行 JSON 并补行尾换行(JSON Lines 的一行)。
     *
     * [Type.JSON_MINIMAL] 序列化结果本身可能含换行,这里压成单行以保证一条快照恰占一行,便于按行读取。
     *
     * @param snapshot 指标快照。
     * @return 末尾带换行的单行 JSON。
     */
    private fun serializeSnapshotLine(snapshot: MetricSnapshot): String {
        val json = Configuration.serialize(snapshot, Type.JSON_MINIMAL).toString()
        // 压平为单行:去除序列化产生的换行,确保 JSON Lines 每条记录占且仅占一行
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

    /** 指标历史文件 `data/metrics.jsonl`。 */
    private fun metricsHistoryPath(): Path = dataRoot().resolve(METRICS_HISTORY_FILE)

    private companion object {

        /** 数据子目录名(相对插件数据目录)。 */
        private const val DATA_DIR_NAME = "data"

        /** 启动画像子目录名。 */
        private const val STARTUP_DIR_NAME = "startup"

        /** 最新启动画像文件名。 */
        private const val LATEST_PROFILE_FILE = "latest.json"

        /** 指标历史文件名(JSON Lines)。 */
        private const val METRICS_HISTORY_FILE = "metrics.jsonl"

        /** JSON 文件后缀。 */
        private const val JSON_SUFFIX = ".json"
    }
}
