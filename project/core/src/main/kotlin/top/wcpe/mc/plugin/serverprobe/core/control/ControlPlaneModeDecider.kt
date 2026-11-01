package top.wcpe.mc.plugin.serverprobe.core.control

import java.nio.file.Path
import java.util.Date

/**
 * 启动三态判定的**纯决策**部分（协议规格 §3.5）。
 *
 * 与 [ControlPlaneModeService] 拆开的原因：判定是安全关键路径（身份文件损坏必须降级、
 * 迁移必须幂等），但服务本身绑着平台数据目录与后台线程，无法单测。本类只吃路径、只吐结论，
 * 不写日志、不起线程，因而可被单元测试逐条覆盖；服务只负责"调用 + 落日志 + 上报事件"。
 *
 * 判定顺序（与规格一致）：
 * 1. 身份文件合法 → 托管；顺带补做上次中断的迁移残留清理（幂等）。
 * 2. 身份文件存在但不可用 → 独立模式（半写防御，不再尝试迁移，避免把损坏文件当"不存在"再写一份）。
 * 3. 无身份文件 → 尝试自动迁移；可迁移则托管，否则独立模式。
 */
internal object ControlPlaneModeDecider {

    /** 判定结论。 */
    sealed class Decision {

        /**
         * 进入托管模式。
         *
         * @property identity 生效身份。
         * @property migration 本次实际完成的迁移（含残留清理）；null 表示无需迁移。
         */
        data class Managed(
            val identity: ControlPlaneIdentity,
            val migration: IdentityMigration.Outcome.Migrated?,
            /**
             * 身份已存在、但 `config.yml` 的 `bridge` 段指向别处时的保留说明（null 表示无此情况）。
             *
             * 本类不写日志（设计如此），需要告警的内容以数据形式交回调用方。
             */
            val residualMismatch: IdentityMigration.Outcome.ResidualMismatch? = null,
        ) : Decision()

        /**
         * 进入独立模式。
         *
         * @property reason 独立原因（决定日志级别与文案）。
         * @property detail 附加说明（**绝不含凭证**）。
         */
        data class Standalone(val reason: StandaloneReason, val detail: String = "") : Decision()
    }

    /** 独立模式的原因分类。 */
    enum class StandaloneReason {

        /** 既无身份文件、也无（启用的）bridge 段：独立部署的正常路径。 */
        NO_IDENTITY,

        /** 身份文件存在但不可用（截断/非对象/版本未知/字段缺失）。 */
        CORRUPT_IDENTITY,

        /** bridge 段已启用但三要素不齐：不猜、不迁移。 */
        INCOMPLETE_BRIDGE,

        /** config.yml 读不到。 */
        CONFIG_UNREADABLE,

        /** 迁移写盘失败（且身份文件未能落盘）。 */
        MIGRATION_FAILED,
    }

    /**
     * 执行三态判定。
     *
     * @param identityFile 身份文件路径。
     * @param configFile `config.yml` 路径。
     * @param timestamp 迁移时刻（注入以便单测固定备份名与 createdAt）。
     * @return 判定结论。
     */
    fun decide(identityFile: Path, configFile: Path, timestamp: Date = Date()): Decision =
        when (val load = IdentityFileStore.read(identityFile)) {
            is IdentityLoadResult.Loaded -> managedWithResidualCleanup(load.identity, configFile, identityFile, timestamp)
            is IdentityLoadResult.Absent -> decideByMigration(configFile, identityFile, timestamp)
            is IdentityLoadResult.Unusable -> Decision.Standalone(
                StandaloneReason.CORRUPT_IDENTITY,
                describe(load),
            )
        }

    /** 身份已合法：托管模式，并补做上次中断的迁移残留清理。 */
    private fun managedWithResidualCleanup(
        identity: ControlPlaneIdentity,
        configFile: Path,
        identityFile: Path,
        timestamp: Date,
    ): Decision {
        val outcome = runCatching { IdentityMigration.migrate(configFile, identityFile, identity, timestamp) }.getOrNull()
        return Decision.Managed(
            identity = identity,
            migration = outcome as? IdentityMigration.Outcome.Migrated,
            residualMismatch = outcome as? IdentityMigration.Outcome.ResidualMismatch,
        )
    }

    /** 无身份文件：尝试自动迁移。 */
    private fun decideByMigration(configFile: Path, identityFile: Path, timestamp: Date): Decision =
        when (val outcome = IdentityMigration.migrate(configFile, identityFile, null, timestamp)) {
            is IdentityMigration.Outcome.Migrated -> Decision.Managed(outcome.identity, outcome)
            is IdentityMigration.Outcome.IncompleteFields -> Decision.Standalone(StandaloneReason.INCOMPLETE_BRIDGE)
            is IdentityMigration.Outcome.ConfigUnreadable -> Decision.Standalone(
                StandaloneReason.CONFIG_UNREADABLE,
                outcome.detail,
            )
            is IdentityMigration.Outcome.NotEnabled,
            is IdentityMigration.Outcome.NoBridgeSection,
            -> Decision.Standalone(StandaloneReason.NO_IDENTITY)
            // 只有“身份已存在”的残留清理路径会碰到它；这里给个保守结局，不影响正常三态判定
            is IdentityMigration.Outcome.ResidualMismatch -> Decision.Standalone(StandaloneReason.NO_IDENTITY, outcome.detail)
            is IdentityMigration.Outcome.Failed -> afterFailedMigration(outcome, identityFile)
        }

    /** 迁移失败：身份文件若已落盘则仍可按托管运行（控制面可据此对账），否则独立模式。 */
    private fun afterFailedMigration(
        outcome: IdentityMigration.Outcome.Failed,
        identityFile: Path,
    ): Decision {
        if (!outcome.identityWritten) {
            return Decision.Standalone(StandaloneReason.MIGRATION_FAILED, "${outcome.stage}:${outcome.detail}")
        }
        val reloaded = IdentityFileStore.read(identityFile)
        return if (reloaded is IdentityLoadResult.Loaded) {
            Decision.Managed(reloaded.identity, null)
        } else {
            Decision.Standalone(StandaloneReason.MIGRATION_FAILED, "${outcome.stage}:${outcome.detail}")
        }
    }

    /** 把不可用原因翻译成可读文案（版本未知必须明确说出来，不能静默）。 */
    private fun describe(result: IdentityLoadResult.Unusable): String = when (result.reason) {
        IdentityUnusableReason.UNKNOWN_SCHEMA_VERSION -> "身份文件版本未知(${result.detail})"
        IdentityUnusableReason.MALFORMED -> "身份文件解析失败(${result.detail})"
        IdentityUnusableReason.MISSING_FIELD -> "身份文件字段不完整(${result.detail})"
    }
}
