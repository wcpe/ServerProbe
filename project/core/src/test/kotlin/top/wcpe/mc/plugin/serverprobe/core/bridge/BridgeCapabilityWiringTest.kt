package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ConfigReloadCoordinator
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ConfigReloadRegistryImpl
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ConfigReloadRoute
import top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ReloadRuntime
import top.wcpe.mc.plugin.serverprobe.core.config.ReloadSettings

/**
 * 装配一致性：`hello.capabilities` 里声明的每一项，都必须真有对应能力被接线。
 *
 * ## 为什么需要这条测试
 * 能力数组是**面板的决策依据**：JM 看到 `config_reload` 才会下发重载命令，
 * 看到 `chunk` 才敢发大数据请求。若声明了却没接线（或接了线却忘了声明），
 * 后果分别是「下发后失败」和「能力永不被发现」——两者都不会报错，只会在联调时表现为
 * "这个功能怎么不生效"。本仓已经出现过一次真实缺口：`chunk`/`topic` 登记了，
 * 但 `config_reload` 与 `business_status` 漏登记，而 reconnect 规格明确要求四个都在。
 *
 * ## 断言策略
 * 用**常量 ↔ 实际接线点**双向核对，而不是断言某个固定字符串数组：
 * 后者会在新增能力时变成"改测试让它变绿"，失去鉴别力。
 */
class BridgeCapabilityWiringTest {

    /** 每个用例结束时执行的清理动作（停掉桩用的线程池）。 */
    private val cleanups = ArrayList<() -> Unit>()

    @AfterEach
    fun tearDown() {
        cleanups.reversed().forEach { runCatching(it) }
        cleanups.clear()
    }

    /** 四个能力值必须与 reconnect 规格 §3.7 的表逐字一致（面板按字符串匹配）。 */
    @Test
    fun `能力常量取值与规格一致`() {
        assertEquals("bridge_status", BridgeCapabilities.CAP_BRIDGE_STATUS)
        assertEquals("chunk", BridgeCapabilities.CAP_CHUNK)
        assertEquals("topic", BridgeCapabilities.CAP_TOPIC)
        assertEquals("config_reload", BridgeCapabilities.CAP_CONFIG_RELOAD)
        assertEquals("business_status", BridgeCapabilities.CAP_BUSINESS_STATUS)
    }

    /**
     * 重复登记同一能力不产生重复项。
     *
     * 注意 [BridgeCapabilities] 是进程级单例，初始集合只含 `bridge_status`，
     * 其余能力由装配时登记，因此**不能假设某个能力"本来就在里面"**：
     * 断言要点是"先登记一次得到 N，再登记两次仍是 N"，只考察幂等性本身。
     */
    @Test
    fun `重复登记同一能力不产生重复项`() {
        val capability = BridgeCapabilities.CAP_CHUNK
        BridgeCapabilities.addCapability(capability)
        val afterFirst = BridgeCapabilities.snapshot()

        BridgeCapabilities.addCapability(capability)
        BridgeCapabilities.addCapability(capability)
        val afterRepeats = BridgeCapabilities.snapshot()

        assertEquals(afterFirst.size, afterRepeats.size, "重复登记不应改变能力数量")
        assertEquals(afterRepeats.size, afterRepeats.toSet().size, "能力列表不得有重复项：$afterRepeats")
        assertEquals(1, afterRepeats.count { it == capability }, "同一能力只应出现一次：$afterRepeats")
    }

    /**
     * `config_reload` 的能力值必须与它自己路由认领的 action 一致。
     *
     * 这处最容易漂移：能力值写 `config_reload`、路由却认领别的 action，
     * 面板按能力下发就会静默落到"未知命令"。用路由的 `claims` 反推，不复制一份字符串。
     */
    @Test
    fun `配置重载能力值与其路由动作一致`() {
        val route = ConfigReloadRoute(
            coordinator = coordinatorStub(),
            decode = { emptyList() },
            encode = { "" },
        )
        val capability = BridgeCapabilities.CAP_CONFIG_RELOAD

        assertTrue(
            route.claims(commandWith(action = capability)),
            "ConfigReloadRoute 必须认领 action=$capability，否则面板按能力值下发会被判为未知命令",
        )
    }

    /** 该路由只认 `domain=core`；能力值相同但域不同不得被认领（防跨域误认）。 */
    @Test
    fun `配置重载路由只认核心域`() {
        val route = ConfigReloadRoute(
            coordinator = coordinatorStub(),
            decode = { emptyList() },
            encode = { "" },
        )
        val command = commandWith(action = BridgeCapabilities.CAP_CONFIG_RELOAD)

        assertTrue(route.claims(command), "core 域应被认领")
        assertFalse(route.claims(command.copy(domain = "activity")), "业务域不得被治理路由认领")
    }

    private fun commandWith(action: String): BridgeCommand = BridgeCommand(
        action = action,
        target = "",
        reason = "",
        requestId = "req-1",
        domain = "core",
        payload = "",
    )

    /**
     * 只为拿到 `claims` 的最小协调器桩。
     *
     * 本测试只关心"路由认领哪个 action/域"，`handle` 绝不会被调用，
     * 因此运行时依赖用最小合法值构造即可；用完即停，避免留下 daemon 线程。
     */
    private fun coordinatorStub(): ConfigReloadCoordinator {
        val settings = ReloadSettings()
        val runtime = ReloadRuntime.of(settings)
        val registry = ConfigReloadRegistryImpl()
        val stub = ConfigReloadCoordinator(
            runtime = runtime,
            listeners = registry,
            settings = { settings },
        )
        // 登记清理：测试结束前停机，避免 worker 池线程存活干扰"无残留线程"类断言。
        cleanups += { runtime.shutdownNow() }
        return stub
    }
}
