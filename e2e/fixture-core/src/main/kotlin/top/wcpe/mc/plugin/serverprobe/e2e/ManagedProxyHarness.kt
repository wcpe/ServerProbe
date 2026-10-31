package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File

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
 * @param pluginsDirectory 代理的 `plugins` 目录（由平台插件给出自己的数据目录后取父目录）。
 * @param pluginDataDir 探针插件的数据目录名（Bungee 为 `ServerProbe`，Velocity 为 `serverprobe`）。
 * @param scenario 场景名（写进日志与失败文案，与结果文件名同源）。
 */
class ManagedProxyHarness(
    private val pluginsDirectory: File,
    private val pluginDataDir: String,
    private val scenario: String,
) : AutoCloseable {

    /** 结果写入器：路径由 mc-testkit 的环境变量给出，缺了立即失败（不静默降级到别处写）。 */
    private val writer: ScenarioResultWriter = ScenarioResultWriter(
        File(
            System.getenv(RESULT_FILE_ENV)
                ?: error("缺少 mc-testkit 结果文件路径（$RESULT_FILE_ENV）：$scenario 无法交卷"),
        ),
    )

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
                writer.finish(PASS_MESSAGE, details.associate { it.key to it.value })
            }
            .onFailure { error -> writer.fail("$scenario 夹具验收失败:${error.message}") }
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
    }
}
