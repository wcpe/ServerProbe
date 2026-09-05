package top.wcpe.mc.plugin.serverprobe.core.forensics

import taboolib.common.platform.function.getDataFolder
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.file.Path

/**
 * FR11 取证服务生命周期门面。
 *
 * SQLite 驱动随发行 Jar 内置；原生库加载失败允许继续启用探针，仅本服务报告不可用。
 */
@Service
class PacketForensicsService {

    @Volatile
    private var store: PacketForensicsStore = UnavailablePacketForensicsStore("数据包取证尚未启动")

    /** 配置开启时启动单写 SQLite 存储；关闭时保持明确的只读不可用状态。 */
    @PostEnable
    fun start() {
        if (!ProbeConfig.networkForensicsEnabled()) {
            store = UnavailablePacketForensicsStore("数据包取证已由配置关闭")
            ProbeLogger.info("数据包取证未开启(network-forensics.enabled=false)，已跳过")
            return
        }
        store = SqlitePacketForensicsStore(
            databasePath = databasePath(),
            retentionDays = ProbeConfig.networkForensicsRetentionDays(),
            maxDatabaseBytes = ProbeConfig.networkForensicsMaxTotalBytes(),
        )
    }

    /** 平台 Netty 适配器后续调用此入口；当前核心只负责非阻塞入队语义。 */
    fun enqueue(observation: PacketForensicsObservation): Boolean = store.enqueue(observation)

    /** FR8 与 Web 后续复用的受限查询入口。 */
    fun query(query: NetworkPacketQuery): NetworkPacketPage = store.query(query)

    /** 暴露驱动降级和队列丢弃状态。 */
    fun status(): NetworkForensicsStatus = store.status()

    /** 插件卸载时关闭写线程，避免 SQLite 句柄与后台线程泄漏。 */
    @PreDestroy
    fun stop() {
        store.close()
        store = UnavailablePacketForensicsStore("数据包取证已停止")
    }

    /** 数据库固定在插件数据目录，避免运行期工作目录差异导致路径漂移。 */
    private fun databasePath(): Path {
        val directory = getDataFolder().toPath().resolve(DATA_DIRECTORY)
        return migrateLegacyDatabaseFile(directory, LEGACY_DATABASE_FILE, DATABASE_FILE)
    }

    private companion object {
        private const val DATA_DIRECTORY = "data"
        private const val DATABASE_FILE = "serverprobe-store.sqlite"
        private const val LEGACY_DATABASE_FILE = "network-forensics.sqlite"
    }
}

/** 关闭、禁用或驱动缺失时的空存储，实现 API 的明确降级语义。 */
private class UnavailablePacketForensicsStore(private val reason: String) : PacketForensicsStore {

    override fun enqueue(observation: PacketForensicsObservation): Boolean = false

    override fun query(query: NetworkPacketQuery): NetworkPacketPage = NetworkPacketPage.empty()

    override fun status(): NetworkForensicsStatus = NetworkForensicsStatus.builder()
        .available(false)
        .droppedRecords(0)
        .unavailableReason(reason)
        .build()

    override fun close() = Unit
}

/**
 * 旧取证库文件一次性迁移到通用库名（升版保数据）：主库连同 WAL/SHM 一起改名。
 * 目标已存在或迁移失败时保留旧文件不动（宁可孤儿文件,不可丢数据）。
 */
internal fun migrateLegacyDatabaseFile(directory: java.nio.file.Path, legacyName: String, targetName: String): java.nio.file.Path {
    val legacy = directory.resolve(legacyName)
    val target = directory.resolve(targetName)
    if (!java.nio.file.Files.isRegularFile(legacy) || java.nio.file.Files.exists(target)) {
        return target
    }
    runCatching {
        for (suffix in listOf("", "-wal", "-shm")) {
            val source = directory.resolve(legacyName + suffix)
            if (java.nio.file.Files.isRegularFile(source)) {
                java.nio.file.Files.move(source, directory.resolve(targetName + suffix))
            }
        }
    }.onFailure {
        java.util.logging.Logger.getLogger("ServerProbe").warning(
            "取证库迁移失败,本轮将新建通用库,旧文件保留:${it.message}",
        )
    }
    return target
}
