package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.util.logging.Logger

/**
 * 代理端托管场景的驱动器：在**代理进程内**扮模拟控制面，并把判定写成结果文件。
 *
 * ## 为什么在代理进程内
 * 托管探针的连接目标写在身份文件里，而代理的运行目录由测试框架在启动前铺开；
 * 把夹具做成外部进程只能靠起子进程 + 端口探测，既多一个运行时依赖（需要机器上有解释器），
 * 又把“断言”与“被测进程”拆成两个生命周期。夹具插件与探针同进程，端口与凭据直接读身份文件，
 * 启动顺序也由插件加载保证（夹具在 `onEnable` 先监听，探针随后才连）。
 *
 * ## 交卷规则
 * 证据表里以 [ManagedConfigFixture.ASSERT_PREFIX] 开头的键就是逐项断言；
 * 全部到齐且均为真才写 `status=PASS`，否则由 [ScenarioResultWriter] 汇总失败项。
 * 结果文件路径来自 mc-testkit 注入的 `MC_TESTKIT_E2E_RESULT_FILE`（代理进程同样会注入）。
 *
 * ## 失败取证
 * 代理与后端共用 `run-proxy`，下一条场景铺模板时会把上一条的日志覆盖掉；而托管链路的失败
 * （如 `mcpBridge` 偶发）往往只能从“探针启动那一刻读到的 `mcp.enabled`”看出来。因此本类：
 * - 在**夹具启动前**抓一份探针 `config.yml` 快照（`<场景>-probe-config-at-start.yml`），这是探针对齐的真实状态；
 * - 交卷时再抓一份当前快照与代理日志（`<场景>-probe-config.yml` / `<场景>-proxy.log`）；
 * - 把两份快照里的 `mcp.enabled` 与日志是否存在写进结果文件的 `diagnostics.*`。
 *
 * 取证全程包在 `runCatching` 里：取证失败只记一行日志，绝不改变断言结果。
 *
 * @param pluginsDirectory 代理的 `plugins` 目录（由平台插件给出自己的数据目录后取父目录）。
 * @param pluginDataDir 探针插件的数据目录名（Bungee 为 `ServerProbe`，Velocity 为 `serverprobe`）。
 * @param scenario 场景名（写进日志与失败文案，与结果文件名同源）。
 */
class ManagedProxyHarness(
    private val pluginsDirectory: File,
    private val pluginDataDir: String,
    private val scenario: String,
) : AutoCloseable {

    /** 结果文件：路径由 mc-testkit 的环境变量给出，缺了立即失败（不静默降级到别处写）。 */
    private val resultFile = File(
        System.getenv(RESULT_FILE_ENV)
            ?: error("缺少 mc-testkit 结果文件路径（$RESULT_FILE_ENV）：$scenario 无法交卷"),
    )

    /** 结果写入器。 */
    private val writer = ScenarioResultWriter(resultFile)

    /** 取证日志（只在取证自身出错时用，不污染断言）。 */
    private val logger: Logger = Logger.getLogger("ServerProbe-ManagedProxyHarness")

    /** 夹具启动前抓下的 `mcp.enabled`（探针将读到的值）；未抓到时为 [MISSING]。 */
    @Volatile
    private var mcpEnabledAtStart: String = MISSING


    /** 已启动的夹具；未启动成功时为 null（已写 FAIL）。 */
    private var fixture: ManagedConfigFixture? = null

    /**
     * 起夹具并开始等待证据。
     *
     * 监听在构造夹具时就已绑定（端口来自身份文件），因此必须在探针建立反向连接**之前**调用——
     * 平台插件的 `onEnable` 就是这个时机。
     *
     * 等待跑在独立守护线程上，绝不占服务器主线程；探针的反向连接一旦建立就会先发 hello。
     */
    fun start() {
        // 探针即将读到的就是这份文件：先抄一份，失败时才能区分“文件本身不对”与“探针读错”。
        mcpEnabledAtStart = runCatching {
            val probeConfig = probeConfigFile()
            copyToResults(probeConfig, "$scenario-probe-config-at-start.yml")
            mcpEnabledOf(probeConfig)
        }.getOrElse { error ->
            logger.warning("取证失败（启动前 config 快照）：${error.message}")
            UNREADABLE
        }
        val started = runCatching {
            ManagedConfigFixture.startFromIdentityFile(pluginsDirectory, pluginDataDir, scenario)
        }.getOrElse { error ->
            writer.fail("$scenario 夹具启动失败:${error.message}")
            return
        }
        fixture = started
        Thread({ awaitAndReport(started) }, "serverprobe-managed-proxy-await").apply {
            isDaemon = true
            start()
        }
    }

    /** 等待证据并交卷：证据表里的 `assert.*` 逐项转登成断言，其余作为附加详情。 */
    private fun awaitAndReport(fixture: ManagedConfigFixture) {
        runCatching { fixture.awaitEvidence(TIMEOUT_SECONDS) }
            .onSuccess { evidence ->
                val (assertions, details) = evidence.entries.partition { it.key.startsWith(ManagedConfigFixture.ASSERT_PREFIX) }
                assertions.forEach { (key, value) ->
                    val name = key.removePrefix(ManagedConfigFixture.ASSERT_PREFIX)
                    writer.assertTrue(name, value == ASSERT_PASSED, value)
                }
                writer.finish(PASS_MESSAGE, details.associate { it.key to it.value } + collectDiagnostics())
            }
            .onFailure { error -> writer.fail("$scenario 夹具验收失败:${error.message}", collectDiagnostics()) }
    }

    /**
     * 收尾取证：当前 `config.yml` 快照 + 代理日志 + 三条 `diagnostics.*` 判据。
     *
     * 取证自身出错不影响断言结果（只记一行日志）。
     *
     * @return 写进结果文件的附加详情。
     */
    private fun collectDiagnostics(): Map<String, String> = runCatching {
        val probeConfig = probeConfigFile()
        val snapshot = copyToResults(probeConfig, "$scenario-probe-config.yml")
        val logCount = copyProxyLogs()
        linkedMapOf(
            "diagnostics.probeConfig.exists" to probeConfig.isFile.toString(),
            "diagnostics.probeConfig.mcpEnabled" to mcpEnabledOf(probeConfig),
            "diagnostics.probeConfig.mcpEnabledAtStart" to mcpEnabledAtStart,
            "diagnostics.probeConfig.snapshot" to (snapshot?.name ?: "none"),
            "diagnostics.proxyLogExists" to (logCount > 0).toString(),
            "diagnostics.proxyLogCount" to logCount.toString(),
        )
    }.getOrElse { error ->
        logger.warning("取证失败（交卷时）：${error.message}")
        mapOf("diagnostics.error" to (error.message ?: error.javaClass.simpleName))
    }

    /** 探针插件的数据目录下的 `config.yml`（探针实际读取的那份）。 */
    private fun probeConfigFile(): File = File(File(pluginsDirectory, pluginDataDir), CONFIG_FILE_NAME)

    /** 把文件复制到结果目录（与结果文件同目录）；源文件不存在时返回 null。 */
    private fun copyToResults(source: File, name: String): File? {
        if (!source.isFile) return null
        val target = File(resultFile.absoluteFile.parentFile, name)
        source.copyTo(target, overwrite = true)
        return target
    }

    /** 把代理运行目录下的日志复制到结果目录（代理与后端共用该目录，不抄就会被下一条场景覆盖）。 */
    private fun copyProxyLogs(): Int {
        val runDirectory = pluginsDirectory.absoluteFile.parentFile ?: return 0
        val logs = runDirectory.listFiles { file -> file.isFile && file.name.endsWith(".log") }.orEmpty()
        logs.forEach { log -> copyToResults(log, "$scenario-proxy-" + log.name) }
        return logs.size
    }

    /**
     * 从 `config.yml` 里读 `mcp.enabled`（只识别 `mcp:` 块内第一个顶层 `enabled:`）。
     *
     * 手写而不用 YAML 库：fixture-core 不引入额外依赖，且只需要这一个键。
     *
     * @param file 探针配置文件。
     * @return `true` / `false` / [MISSING]（文件不存在）/ [UNREADABLE]（读不出）/ `absent`（块内无该键）。
     */
    private fun mcpEnabledOf(file: File): String {
        if (!file.isFile) return MISSING
        val lines = runCatching { file.readLines() }.getOrElse { return UNREADABLE }
        var inMcpSection = false
        for (raw in lines) {
            val line = raw.substringBefore("#")
            if (line.isBlank()) continue
            if (!line.first().isWhitespace()) {
                inMcpSection = line.trim().startsWith("$MCP_SECTION:")
                continue
            }
            if (!inMcpSection) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("enabled:")) return trimmed.substringAfter(':').trim().ifEmpty { "empty" }
        }
        return MAGIC_ABSENT
    }

    override fun close() {
        fixture?.close()
        fixture = null
    }

    private companion object {

        /** 结果文件环境变量（mc-testkit 注入，后端与代理都带）。 */
        const val RESULT_FILE_ENV = "MC_TESTKIT_E2E_RESULT_FILE"

        /** 断言通过标记：与 [ManagedConfigFixture] 登记证据时的取值一致。 */
        const val ASSERT_PASSED = "true"

        /** 交卷文案（与后端托管场景保持一致）。 */
        const val PASS_MESSAGE = "托管模式手工验收通过"

        /** 证据等待上限（秒）：配置下发与 MCP 探测各有重试预算，留足总时长。 */
        const val TIMEOUT_SECONDS = 120L

        /** 探针配置文件与它所在的节名。 */
        const val CONFIG_FILE_NAME = "config.yml"
        const val MCP_SECTION = "mcp"

        /** 取证取值：文件不存在 / 读不出 / 块内无该键。 */
        const val MISSING = "missing"
        const val UNREADABLE = "unreadable"
        const val MAGIC_ABSENT = "absent"
    }
}
