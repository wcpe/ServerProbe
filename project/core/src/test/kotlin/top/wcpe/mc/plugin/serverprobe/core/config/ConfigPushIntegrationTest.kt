package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeKernel
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandRegistry
import top.wcpe.mc.plugin.serverprobe.core.bridge.GovernanceRoute
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneIdentity
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testConfiguration
import top.wcpe.mc.plugin.serverprobe.core.testsupport.testJsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * 托管配置链路的**跨层联动**测试：下行帧 → 分发 → 应用 → 内存/缓存/状态。
 *
 * 与 [ConfigPushApplierTest]（应用器语义）和 [ManagedConfigServiceTest]（模式装配）互补：
 * 这里走的是 `BridgeClient.handleCommand` 的**真实分发路径**，载荷是协议规格 §3.4.1 的嵌套对象形态，
 * 目的是把"帧格式 → 载荷解析 → 应用 → 缓存落盘 → 命令行回执"这条缝焊起来——
 * 单看每层都对、层间约定错位，正是这类功能最容易出的问题。
 *
 * 自检与缓存落盘都注入可控实现：自检用可控结果（本测试不验证采集器/桥的真实状态，
 * 那部分由 [ConfigPushApplierTest] 与真机覆盖），落盘写真实临时目录以验证文件确实产生。
 */
class ConfigPushIntegrationTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var registry: BridgeCommandRegistry

    private lateinit var applier: ConfigPushApplier

    private var selfCheck: SelfCheckResult = pass()

    private lateinit var cacheFile: Path

    @BeforeEach
    fun setUp() {
        ProbeConfig.conf = testConfiguration(mapOf("bridge.instance" to "instance-9"))
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity())
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
        cacheFile = dir.resolve(ManagedCacheStore.FILE_NAME)
        selfCheck = pass()
        applier = ConfigPushApplier(
            selfCheck = { selfCheck },
            writeCache = { snapshot -> ManagedCacheStore.write(cacheFile, snapshot) },
            clockMillis = { FIXED_EPOCH_MILLIS },
        )
        registry = BridgeCommandRegistry()
        registry.registerCoreAction("config_push") { command ->
            val payload = ConfigPushPayload.parse(command.payload)
            val outcome = applier.apply(payload)
            if (outcome.applied) {
                BridgeCommandResult.ok("ok")
            } else {
                BridgeCommandResult.fail(outcome.error ?: "failed")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        ProbeConfig.conf = testConfiguration()
        ControlPlaneRuntime.reset()
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
    }

    /** 规格 §3.4.1 的帧形态（`payload` 为对象）走完整链路：生效 + 落盘 + 状态推进。 */
    @Test
    fun `嵌套 payload 帧走通应用到落盘`() {
        val result = dispatch(
            mapOf(
                "configVersion" to 7L,
                "baseVersion" to 0L,
                "values" to mapOf("collect-period-ticks" to 100L, "history-capacity" to 360L),
                "removals" to emptyList<String>(),
                "reason" to "调整采集节奏",
                "operator" to "wxys233",
            ),
        )

        assertEquals(true, result.success, "应回成功回执，实际:${result.error}")
        assertEquals(100L, ManagedConfigStore.currentValues()["collect-period-ticks"])
        assertEquals(7L, ManagedConfigState.configVersion())
        assertTrue(Files.isRegularFile(cacheFile), "生效后必须落盘缓存")

        val cached = ManagedCacheStore.read(cacheFile)
        assertEquals(7L, cached?.configVersion, "缓存版本应与生效版本一致")
        assertEquals(100L, cached?.values?.get("collect-period-ticks"), "缓存值应与内存一致")
    }

    /** 帧里带 `removals`：删除同样要落到内存与缓存。 */
    @Test
    fun `removals 在完整链路中生效`() {
        dispatch(mapOf("configVersion" to 1L, "baseVersion" to 0L, "values" to mapOf("server-name" to "生存服")))

        dispatch(
            mapOf(
                "configVersion" to 2L,
                "baseVersion" to 1L,
                "removals" to listOf("server-name"),
            ),
        )

        assertNull(ManagedConfigStore.currentValues()["server-name"], "键应被删除")
        assertEquals(
            false,
            ManagedCacheStore.read(cacheFile)?.values?.containsKey("server-name"),
            "缓存里也不得残留被删的键",
        )
    }

    /** 自检失败：内存回退、版本不推进、**缓存不被污染**（保持上一版内容）。 */
    @Test
    fun `自检失败时缓存保持上一版`() {
        dispatch(mapOf("configVersion" to 1L, "baseVersion" to 0L, "values" to mapOf("history-capacity" to 360L)))
        val beforeCache = ManagedCacheStore.read(cacheFile)

        selfCheck = SelfCheckResult(collectorAlive = false, metricProducible = true, bridgeUsable = true)
        val result = dispatch(mapOf("configVersion" to 2L, "baseVersion" to 1L, "values" to mapOf("history-capacity" to 200L)))

        assertEquals(false, result.success, "自检失败应回失败回执")
        assertTrue(result.error.contains("自检失败"), "实际:${result.error}")
        assertEquals(360L, ManagedConfigStore.currentValues()["history-capacity"], "内存应回退上一版")
        assertEquals(1L, ManagedConfigState.configVersion(), "版本不得推进")

        val afterCache = ManagedCacheStore.read(cacheFile)
        assertEquals(beforeCache?.configVersion, afterCache?.configVersion, "缓存版本不得被失败的那一版覆盖")
        assertEquals(360L, afterCache?.values?.get("history-capacity"), "缓存内容不得被污染")
    }

    /** 陈旧的帧：拒绝且不改动任何状态（不降级）。 */
    @Test
    fun `陈旧帧被拒且状态不动`() {
        dispatch(mapOf("configVersion" to 5L, "baseVersion" to 0L, "values" to mapOf("history-capacity" to 360L)))

        val result = dispatch(mapOf("configVersion" to 4L, "baseVersion" to 5L, "values" to mapOf("history-capacity" to 100L)))

        assertEquals(false, result.success)
        assertTrue(result.error.startsWith("stale"), "实际:${result.error}")
        assertEquals(360L, ManagedConfigStore.currentValues()["history-capacity"])
        assertEquals(5L, ManagedConfigState.configVersion())
    }

    /**
     * 模拟重启：清空内存后从缓存装载，生效值与版本必须原样回来（fail-static 的实际兑现）。
     *
     * 这条是托管模式"控制面不在场也能秒起"的回归断言——缓存写坏了或装载丢了版本，
     * 真机上表现为"重启后配置悄悄退回 config.yml"，极难定位。
     */
    @Test
    fun `应用后重启能恢复同一份配置`() {
        dispatch(
            mapOf(
                "configVersion" to 9L,
                "baseVersion" to 0L,
                "values" to mapOf("collect-period-ticks" to 100L, "debug" to true),
            ),
        )
        val memoryBefore = ManagedConfigStore.currentValues()

        // 模拟重启：内存两代清空、版本归零，只剩磁盘缓存
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
        assertTrue(ManagedConfigStore.currentValues().isEmpty(), "重启后内存应为空")

        assertTrue(ManagedCacheStore.loadInto(cacheFile), "应能从缓存装载")

        assertEquals(memoryBefore, ManagedConfigStore.currentValues(), "装载后的取值必须与重启前一致")
        assertEquals(9L, ManagedConfigState.configVersion(), "版本必须随缓存恢复")
    }

    /** 走真实分发路径：帧对象 → 命令载体 → 治理路由（core 内置动作优先）→ 回执。 */
    private fun dispatch(payload: Map<String, Any?>): BridgeCommandResult {
        val frame = testJsonObject(
            mapOf(
                "type" to "command",
                "action" to "config_push",
                "requestId" to "req-integration",
                "payload" to payload,
            ),
        )
        val command = requireNotNull(BridgeKernel.toCommand(frame)) { "帧应能被解析为命令:$frame" }
        val route = GovernanceRoute(registry = { registry.handler }, coreAction = registry::dispatchCoreAction)
        return route.handle(command)
    }

    /** 一份最小可用身份（只为把运行模式置为托管）。 */
    private fun identity(): ControlPlaneIdentity = ControlPlaneIdentity(
        schemaVersion = 1,
        provider = "any",
        endpoint = "ws://cp.example/agent",
        instanceId = "instance-9",
        credential = "opaque",
        capabilities = emptyList(),
        createdAt = "2026-10-19T03:12:44Z",
    )

    private companion object {

        /** 固定时钟：2026-10-19T03:12:44Z。 */
        private const val FIXED_EPOCH_MILLIS = 1_792_379_564_000L

        /** 三项全通过的自检。 */
        private fun pass(): SelfCheckResult = SelfCheckResult(collectorAlive = true, metricProducible = true, bridgeUsable = true)
    }
}
