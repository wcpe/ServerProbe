package top.wcpe.mc.plugin.serverprobe.core.control

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 存量部署的自动迁移：`config.yml` 的 `bridge` 段 → `agent-identity.json`（协议规格 §3.5）。
 *
 * ## 步骤（严格按规格顺序）
 * 1. 读 `config.yml` 的 `bridge` 段（url / instance / token）
 * 2. 任一缺失 → **不迁移**，保持独立模式（不猜、不兜底）
 * 3. 原子写 `agent-identity.json`（内容相同则不重写）
 * 4. 备份 `config.yml` → `config.yml.bak.<yyyyMMddHHmmss>`（同名已存在则跳过）
 * 5. 从 `config.yml` 移除 `bridge` 段并原子写回（无该段则跳过）
 * 6. 由调用方上报迁移事件（本类不碰网络）
 *
 * ## 幂等
 * 任意步骤崩溃后重跑结果一致：步骤 3 内容相同不重写；步骤 4 同名备份已存在则跳过；步骤 5 无段则跳过。
 * 另有"断点续做"：若身份文件已存在但 `config.yml` 仍留着 `bridge` 段（上次迁移在步骤 3 与 5 之间中断），
 * 传入 [existing] 后会跳过步骤 3、只补做 4/5，使终态与一次跑完完全一致。
 *
 * ## 为什么迁移触发要额外看 `enabled`
 * 本仓库随包的默认 `config.yml` **恒含** `bridge` 段（`enabled: false` + 三个空值）。若只按"段存在"触发，
 * 每台独立部署每次启动都会走“字段缺失 → WARN”分支，既刷日志又是行为噪声。故触发条件收紧为
 * **`enabled` 显式为 true 且三要素齐备**：真正的存量托管部署（控制面写的就是 `enabled: true`）照常迁移，
 * 默认模板与显式关闭桥的部署不受影响。此收紧已在交付报告中列为待确认取舍。
 */
internal object IdentityMigration {

    /** 迁移结论。 */
    sealed class Outcome {

        /** `config.yml` 无 `bridge` 段：无需迁移（独立模式的正常路径）。 */
        object NoBridgeSection : Outcome()

        /** `bridge` 段存在但未启用（默认模板或运维显式关闭）：不迁移。 */
        object NotEnabled : Outcome()

        /** `bridge` 段已启用但 url/instance/token 有缺失：不迁移（不猜、不兜底）。 */
        object IncompleteFields : Outcome()

        /** `config.yml` 读不到或结构异常：不迁移。 */
        data class ConfigUnreadable(val detail: String) : Outcome()

        /** 迁移写入失败；[identityWritten] 表明身份文件是否已先落盘（落盘则本次仍可按托管运行）。 */
        data class Failed(val stage: String, val detail: String, val identityWritten: Boolean) : Outcome()

        /**
         * 迁移完成（或已完成残留清理）。
         *
         * @property identity 迁移得到的身份。
         * @property identityWritten 本次是否实际写入身份文件（false 表示内容已相同/已存在而跳过）。
         * @property backupFile 本次生成的备份文件名；null 表示同名备份已存在或未做备份。
         * @property bridgeRemoved 本次是否实际从 `config.yml` 移除了 `bridge` 段。
         */
        data class Migrated(
            val identity: ControlPlaneIdentity,
            val identityWritten: Boolean,
            val backupFile: String?,
            val bridgeRemoved: Boolean,
        ) : Outcome()
    }

    /**
     * 执行迁移（幂等）。
     *
     * @param configFile `config.yml` 路径。
     * @param identityFile `agent-identity.json` 路径。
     * @param existing 已加载的合法身份；null 表示当前无身份文件（首次迁移）。
     * @param timestamp 迁移时刻（备份名与 `createdAt` 共用，便于单测注入）。
     * @return 迁移结论。
     */
    fun migrate(
        configFile: Path,
        identityFile: Path,
        existing: ControlPlaneIdentity?,
        timestamp: Date = Date(),
    ): Outcome {
        val configText = when (val read = readConfig(configFile)) {
            is ConfigRead.Missing -> return Outcome.NoBridgeSection
            is ConfigRead.Failed -> return Outcome.ConfigUnreadable(read.detail)
            is ConfigRead.Ok -> read.text
        }
        val config = ConfigFile(configFile, configText)
        val values = BridgeSection.read(config.text) ?: return Outcome.NoBridgeSection
        precheck(values)?.let { return it }
        return apply(config, identityFile, existing, values, timestamp)
    }

    /** 读取配置文本：区分"文件不存在（无段）"与"读失败（不可读）"，两者结论不同。 */
    private fun readConfig(configFile: Path): ConfigRead = runCatching {
        if (Files.isRegularFile(configFile)) {
            ConfigRead.Ok(String(Files.readAllBytes(configFile), Charsets.UTF_8))
        } else {
            ConfigRead.Missing
        }
    }.getOrElse { ConfigRead.Failed("读取失败:${it.javaClass.simpleName}") }

    /** 迁移前置校验：未启用、三要素不齐都不迁移（不猜、不兜底）。 */
    private fun precheck(values: BridgeSectionValues): Outcome? = when {
        !values.isEnabled() -> Outcome.NotEnabled
        !values.isComplete() -> Outcome.IncompleteFields
        else -> null
    }

    /**
     * 执行写入三步：身份文件 → 备份 → 移除段。
     *
     * 每步失败都只返回 [Outcome.Failed]（不抛），[Outcome.Failed.identityWritten] 用于让调用方判断
     * 身份是否已可托管——这保证"迁移半途失败"也不会把探针困在不可用状态。
     */
    private fun apply(
        config: ConfigFile,
        identityFile: Path,
        existing: ControlPlaneIdentity?,
        values: BridgeSectionValues,
        timestamp: Date,
    ): Outcome {
        val identity = existing ?: buildIdentity(values, timestamp)
        var identityWritten = false
        if (existing == null) {
            identityWritten = runCatching { IdentityFileStore.write(identityFile, identity) }
                .getOrElse { return Outcome.Failed(STAGE_IDENTITY, it.javaClass.simpleName, false) }
        }
        val backup = runCatching { backupConfig(config.path, timestamp) }
            .getOrElse { return Outcome.Failed(STAGE_BACKUP, it.javaClass.simpleName, identityWritten) }
        return runCatching { removeBridgeSection(config) }
            .fold(
                onSuccess = { removed -> Outcome.Migrated(identity, identityWritten, backup, removed) },
                onFailure = { Outcome.Failed(STAGE_REMOVE, it.javaClass.simpleName, identityWritten) },
            )
    }

    /** 配置路径 + 已读文本（避免在多个私有函数间反复传两个参数）。 */
    private class ConfigFile(val path: Path, val text: String)

    /** 配置读取结果。 */
    private sealed class ConfigRead {

        /** 读到文本。 */
        class Ok(val text: String) : ConfigRead()

        /** 配置不存在（等价"无 bridge 段"）。 */
        object Missing : ConfigRead()

        /** 读取失败。 */
        class Failed(val detail: String) : ConfigRead()
    }

    /** 由 `bridge` 段构造身份对象。 */
    private fun buildIdentity(values: BridgeSectionValues, timestamp: Date): ControlPlaneIdentity =
        ControlPlaneIdentity(
            schemaVersion = ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION,
            // 存量 bridge 段不含厂商标识,写入中性占位值;控制面下次签发身份时会覆盖为真实值
            provider = LEGACY_PROVIDER_PLACEHOLDER,
            endpoint = values.url.trim(),
            instanceId = values.instance.trim(),
            credential = values.token.trim(),
            // 存量桥只支持基础帧,不声明任何能力位(语义即"只用基础帧")
            capabilities = emptyList(),
            createdAt = formatUtc(timestamp, ISO_TIMESTAMP_PATTERN),
        )

    /**
     * 备份 `config.yml`：`config.yml.bak.<yyyyMMddHHmmss>`；同名备份已存在则跳过。
     *
     * @return 本次生成的备份文件名；跳过时返回 null。
     */
    private fun backupConfig(configFile: Path, timestamp: Date): String? {
        val backupName = configFile.fileName.toString() + BACKUP_INFIX + formatUtc(timestamp, BACKUP_TIMESTAMP_PATTERN)
        val parent = configFile.toAbsolutePath().parent ?: error("配置路径缺少父目录")
        val backupPath = parent.resolve(backupName)
        if (Files.exists(backupPath)) {
            return null
        }
        // 备份即"原样留档":直接复制当前字节,不经原子替换(备份文件不参与运行期读取)
        Files.copy(configFile, backupPath, StandardCopyOption.REPLACE_EXISTING)
        return backupName
    }

    /**
     * 移除 `bridge` 段并原子写回；写回后重新校验，校验不过则还原原文，避免把配置写坏。
     *
     * @return true 表示确实移除了段；false 表示无需改动。
     */
    private fun removeBridgeSection(config: ConfigFile): Boolean {
        if (!BridgeSection.exists(config.text)) {
            return false
        }
        val updated = BridgeSection.remove(config.text)
        if (BridgeSection.exists(updated)) {
            // 文本级移除未能彻底去掉段(形态超出支持范围):不写坏配置,交由人工处理
            error("bridge 段未能完整移除")
        }
        AtomicFileWrite.write(config.path, updated)
        val written = String(Files.readAllBytes(config.path), Charsets.UTF_8)
        if (written != updated) {
            // 写回后校验不一致:还原原文,宁可暂不迁移也不留下坏配置
            AtomicFileWrite.write(config.path, config.text)
            error("写回校验不一致,已还原")
        }
        return true
    }

    /** 以 UTC + 固定 Locale 格式化时间戳，避免机器时区/语言影响文件命名与落盘文本。 */
    private fun formatUtc(timestamp: Date, pattern: String): String =
        SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(timestamp)

    /** 迁移写入阶段标识：身份文件。 */
    private const val STAGE_IDENTITY = "写身份文件"

    /** 迁移写入阶段标识：配置备份。 */
    private const val STAGE_BACKUP = "备份配置"

    /** 迁移写入阶段标识：移除 bridge 段。 */
    private const val STAGE_REMOVE = "移除 bridge 段"

    /** 备份文件名中插入的标识。 */
    private const val BACKUP_INFIX = ".bak."

    /** 备份名时间戳格式：`yyyyMMddHHmmss`（协议规格 §3.5 指定的备份名格式）。 */
    private const val BACKUP_TIMESTAMP_PATTERN = "yyyyMMddHHmmss"

    /** `createdAt` 时间戳格式：ISO-8601 UTC（与协议规格 §3.2 的示例一致）。 */
    private const val ISO_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    /** 存量迁移写入的 provider 占位值（`bridge` 段不含厂商标识）。 */
    private const val LEGACY_PROVIDER_PLACEHOLDER = "unknown"
}
