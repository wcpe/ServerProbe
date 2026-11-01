package top.wcpe.mc.plugin.serverprobe.core.control

import taboolib.common.platform.function.getDataFolder
import top.wcpe.mc.plugin.serverprobe.core.config.ManagedCacheStore
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 托管模式启动状态机（协议规格 §3.5 的"探针启动三态判定"）。
 *
 * ```
 * 读 agent-identity.json
 * ├─ 存在且合法 ──────────────→ MODE=managed
 * ├─ 存在但解析失败 ──────────→ MODE=standalone + WARN（半写防御，不再尝试迁移）
 * └─ 不存在
 *    ├─ config.yml 有可迁移的 bridge 段 → 自动迁移 → MODE=managed
 *    └─ 否则 ─────────────────→ MODE=standalone（与改造前行为一致）
 * ```
 *
 * ## 线程模型：判定全程在后台线程，主线程零 IO
 * 身份文件读取、`config.yml` 备份/写回都是磁盘 IO，按红线一律跑在本类自起的 daemon 线程
 * （`ServerProbe-ManagedMode`）。主线程上的 [PostEnable] 只做"取数据目录 + 起线程"。
 * 需要模式的组件（当前是 [top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient]）在**自己的后台线程**
 * 用 [awaitReady] 等判定结果，因此不会有任何主线程阻塞。
 *
 * ## 绝不成为事故源
 * [runBootstrap] 整体 try/catch：任何异常（含磁盘不可写、配置被占用）都只 WARN 并降级独立模式，
 * 且**一定**在 finally 里放行 [ready]，避免等待方被永久卡住。
 *
 * ## 厂商中立
 * 本类不认识任何厂商名：[ControlPlaneIdentity.provider] 只被当作数据记录，不参与任何分支。
 */
@Service
class ControlPlaneModeService {

    /** 判定完成信号：无论成功、失败还是降级，最终都必须放行。 */
    private val ready = CountDownLatch(1)

    /** 是否已启动判定线程（`@PostEnable` 幂等保护）。 */
    private val started = AtomicBoolean(false)

    /**
     * 启动期触发三态判定（`@PostEnable`）。
     *
     * 主线程只做数据目录解析与线程启动，IO 全部在后台线程完成。
     */
    @PostEnable
    fun bootstrap() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        // 平台 API 只在主线程触碰,后台线程只拿已解析好的路径
        val dataFolder = runCatching { getDataFolder().toPath() }.getOrNull()
        val worker = Thread({ runBootstrap(dataFolder) }, THREAD_NAME)
        worker.isDaemon = true
        worker.start()
    }

    /**
     * 等待模式判定完成（供桥的后台线程调用）。
     *
     * @param timeoutMs 最长等待毫秒数。
     * @return true 表示判定已完成（无论结论是什么）；false 表示等待超时。
     */
    fun awaitReady(timeoutMs: Long): Boolean = try {
        ready.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        // 等待被中断:恢复中断标志,按"未就绪"处理(调用方据此按独立模式继续,不阻塞)
        Thread.currentThread().interrupt()
        false
    }

    /** 当前运行模式；判定完成前恒为 [ProbeMode.STANDALONE]。 */
    fun mode(): ProbeMode = ControlPlaneRuntime.mode()

    /** 当前托管身份；独立模式下为 null。 */
    fun identity(): ControlPlaneIdentity? = ControlPlaneRuntime.identity()

    /**
     * 托管模式下的桥连接目标；独立模式返回 null（调用方回落到 `config.yml` 的 `bridge.*`）。
     *
     * @return 由身份文件派生的连接目标，或 null。
     */
    fun bridgeTarget(): BridgeTarget? {
        val identity = ControlPlaneRuntime.identity() ?: return null
        return BridgeTarget(identity.endpoint, identity.credential, identity.instanceId, identity.provider)
    }

    /** 判定主体（后台线程）。 */
    // 判定链路上的每个分支都要落日志并保证 finally 放行,异常一律降级:整体广捕是有意为之
    @Suppress("TooGenericExceptionCaught")
    private fun runBootstrap(dataFolder: Path?) {
        // “是否有身份文件”在判定链路最早期就能确认，先告诉监听面：
        // 它们超时等不到结论时靠这个信号决定 fail-open（确定独立部署）还是 fail-close（有托管意图）。
        var identityPresent = false
        try {
            if (dataFolder == null) {
                ProbeLogger.warn("无法解析插件数据目录,托管模式判定跳过,按独立模式运行")
                return
            }
            val identityPath = dataFolder.resolve(IDENTITY_FILE_NAME)
            identityPresent = runCatching { Files.isRegularFile(identityPath) }.getOrDefault(false)
            ControlPlaneRuntime.markIdentityPresent(identityPresent)
            apply(
                ControlPlaneModeDecider.decide(
                    identityPath,
                    dataFolder.resolve(CONFIG_FILE_NAME),
                    Date(),
                ),
                dataFolder,
            )
        } catch (e: Exception) {
            // 探针绝不能因托管判定失败而启动失败
            ProbeLogger.warn("托管模式判定异常,已按独立模式继续:${e.message}")
            resetToStandalone()
            // 判定异常时仍要保留“托管意图”，否则监听面会在超时路径上 fail-open
            ControlPlaneRuntime.markIdentityPresent(identityPresent)
        } finally {
            // 无论结论如何都必须放行:等待方(桥线程)据此继续,超时则按独立模式走
            ready.countDown()
        }
    }

    /** 落实判定结论:置模式 + 落日志 + 迁移事件入队。 */
    private fun apply(decision: ControlPlaneModeDecider.Decision, dataFolder: Path) {
        when (decision) {
            is ControlPlaneModeDecider.Decision.Managed -> applyManaged(decision, dataFolder)
            is ControlPlaneModeDecider.Decision.Standalone -> applyStandalone(decision)
        }
    }

    /** 托管模式:记录身份、按"是否有迁移"选择日志,并把迁移事件入队等桥补发。 */
    private fun applyManaged(decision: ControlPlaneModeDecider.Decision.Managed, dataFolder: Path) {
        val identity = decision.identity
        // 先装缓存再发布模式:发布之后配置读取即会走托管面,若此时缓存还没进内存,
        // "秒起、不等控制面"(协议规格 §3.5)就退化成了"先按 config.yml 跑一会儿再说"
        loadManagedCacheBeforePublish(dataFolder)
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity)
        decision.residualMismatch?.let { mismatch ->
            // 保留现状而不是替运维做决定：那多半是他们刚换发凭据、把新值写进了 config.yml
            ProbeLogger.warn("config.yml 的 bridge 段与已有身份不一致，已保留现状未改:${mismatch.detail}")
        }
        val migration = decision.migration
        if (migration == null) {
            ProbeLogger.info("已进入托管模式:provider=${identity.provider}, instance=${identity.instanceId}")
            return
        }
        ProbeLogger.info(
            "已从 config.yml 的 bridge 段自动迁移身份文件并进入托管模式:" +
                "instance=${identity.instanceId}, 备份=${migration.backupFile ?: "已存在"}",
        )
        enqueueMigrationEvent(identity, migration)
    }

    /**
     * 装载托管配置磁盘缓存（fail-static 的入口）。
     *
     * 放在模式发布之前是刻意的顺序约束：`ManagedConfigResolver` 一旦看到 MANAGED 就会优先取托管面，
     * 缓存必须先就位。装载失败（无缓存/损坏）只 WARN，由 `config.yml` 与代码默认值兜底——
     * 绝不因此让探针启动失败。
     *
     * @param dataFolder 插件数据目录（由主线程解析后传入，后台线程不再触碰平台 API）。
     */
    private fun loadManagedCacheBeforePublish(dataFolder: Path) {
        val loaded = runCatching { ManagedCacheStore.loadInto(dataFolder.resolve(ManagedCacheStore.FILE_NAME)) }
            .getOrElse { failure ->
                ProbeLogger.warn("装载托管配置缓存异常,已按无缓存继续:${failure.message}")
                false
            }
        if (!loaded) {
            ProbeLogger.info("未发现可用的托管配置缓存,将按 config.yml 与代码默认值起步")
        }
    }

    /** 独立模式:按原因分档记日志（身份损坏/字段不齐/配置不可读为 WARN，正常独立部署为 INFO）。 */
    private fun applyStandalone(decision: ControlPlaneModeDecider.Decision.Standalone) {
        ControlPlaneRuntime.publish(ProbeMode.STANDALONE, null)
        when (decision.reason) {
            ControlPlaneModeDecider.StandaloneReason.NO_IDENTITY ->
                ProbeLogger.info("未发现身份文件与可迁移的 bridge 段,按独立模式运行")
            ControlPlaneModeDecider.StandaloneReason.CORRUPT_IDENTITY ->
                ProbeLogger.warn("身份文件不可用(已按独立模式降级):${decision.detail}")
            ControlPlaneModeDecider.StandaloneReason.INCOMPLETE_BRIDGE ->
                ProbeLogger.warn(
                    "config.yml 的 bridge 段已启用但 url/instance/token 不完整,已跳过自动迁移并保持独立模式(不猜、不兜底)",
                )
            ControlPlaneModeDecider.StandaloneReason.CONFIG_UNREADABLE ->
                ProbeLogger.warn("读取 config.yml 失败,已跳过自动迁移并保持独立模式:${decision.detail}")
            ControlPlaneModeDecider.StandaloneReason.MIGRATION_FAILED ->
                ProbeLogger.warn("自动迁移失败,已保持独立模式:${decision.detail}")
        }
    }

    /** 迁移事件入队(此刻桥可能还没连上，等连上后由桥补发，不丢)。 */
    private fun enqueueMigrationEvent(identity: ControlPlaneIdentity, outcome: IdentityMigration.Outcome.Migrated) {
        ControlPlaneEventQueue.enqueue(
            EVENT_IDENTITY_MIGRATED,
            linkedMapOf(
                "provider" to identity.provider,
                "instanceId" to identity.instanceId,
                "schemaVersion" to identity.schemaVersion,
                "backupFile" to (outcome.backupFile ?: ""),
                "bridgeRemoved" to outcome.bridgeRemoved,
            ),
        )
    }

    /** 无判定结论时(异常/无数据目录)直接回到独立模式:身份清空,模式取保守值。 */
    private fun resetToStandalone() = ControlPlaneRuntime.reset()

    /** 后台线程名与文件名常量集中处。 */
    private companion object {

        /** 判定线程名（便于线上排查）。 */
        private const val THREAD_NAME = "ServerProbe-ManagedMode"

        /** 身份文件名（协议规格 §3.2）。 */
        private const val IDENTITY_FILE_NAME = "agent-identity.json"

        /** 主配置文件名。 */
        private const val CONFIG_FILE_NAME = "config.yml"

        /** 迁移事件名（帧内 `event`）。 */
        private const val EVENT_IDENTITY_MIGRATED = "identity_migrated"
    }
}

/**
 * 托管模式下反向 WS 的连接目标（由身份文件派生）。
 *
 * 覆写 [toString] 掩码 [credential]：凭证仅用于握手 query 参数，绝不出现在日志/异常信息里。
 *
 * @property endpoint 控制面 WS 地址。
 * @property credential 不透明凭证。
 * @property instanceId 实例标识（握手 `?instance=`）。
 * @property provider 控制面标识（仅用于日志展示，不参与分支判断）。
 */
data class BridgeTarget(
    val endpoint: String,
    val credential: String,
    val instanceId: String,
    val provider: String,
) {

    /** 掩码凭证的文本表示。 */
    override fun toString(): String =
        "BridgeTarget(endpoint=$endpoint, instanceId=$instanceId, provider=$provider, credential=<不透明凭证,已隐藏>)"
}
