package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 零监听闸门的 **fail-close / 回撤** 行为验收。
 *
 * 这两条是「托管模式零监听端口」承诺在非主路径上的保命线：
 * 1. 判定超时/抛异常时，**有托管意图（身份文件存在）就不能 bind**——否则慢盘启动会把
 *    metrics/web/mcp 端口开起来，而在那之后没有任何机制会去关掉它；
 * 2. 万一已经 bind 了之后模式才落定为托管，**必须回撤**（幂等），不能只靠启动时那一次判断。
 *
 * 同时守住独立模式：确实没有身份文件的部署，判定超时也必须照常 bind，行为与引入托管能力前一致。
 */
class LocalListenerFailCloseTest {

    @BeforeEach
    fun setUp() {
        ControlPlaneRuntime.reset()
        LocalListenerGate.clearBoundForTest()
    }

    @AfterEach
    fun tearDown() {
        ControlPlaneRuntime.reset()
        LocalListenerGate.clearBoundForTest()
    }

    /** 没有身份文件 = 确定是独立部署：判定超时只能按旧行为继续 bind。 */
    @Test
    fun `判定超时且无托管意图时照常 bind`() {
        val bound = AtomicInteger(0)

        LocalListenerLauncher(LocalListenerGate.Surface.WEB, awaitDecision = { false }, waitMs = 20)
            .launch { bound.incrementAndGet() }
            .join(JOIN_TIMEOUT_MS)

        assertEquals(1, bound.get(), "无身份文件时超时必须按独立模式继续，不能砸独立模式")
    }

    /** 有身份文件但判定没完成：宁可不提供服务，也不能开端口。 */
    @Test
    fun `判定超时且存在托管意图时不 bind`() {
        ControlPlaneRuntime.markIdentityPresent(true)
        val bound = AtomicInteger(0)

        LocalListenerLauncher(LocalListenerGate.Surface.WEB, awaitDecision = { false }, waitMs = 20)
            .launch { bound.incrementAndGet() }
            .join(JOIN_TIMEOUT_MS)

        assertEquals(0, bound.get(), "有托管意图时判定超时必须 fail-close")
    }

    /** 判定过程抛异常与超时同等对待：不能因为异常而 fail-open。 */
    @Test
    fun `判定抛异常且存在托管意图时不 bind`() {
        ControlPlaneRuntime.markIdentityPresent(true)
        val bound = AtomicInteger(0)

        LocalListenerLauncher(LocalListenerGate.Surface.METRICS, awaitDecision = { error("判定线程挂了") }, waitMs = 20)
            .launch { bound.incrementAndGet() }
            .join(JOIN_TIMEOUT_MS)

        assertEquals(0, bound.get(), "判定异常 + 有托管意图时同样 fail-close")
    }

    /** 已 bind 后模式才落定为托管：必须回撤，且重复发布不重复动作。 */
    @Test
    fun `托管模式落定时回撤已开启的监听面`() {
        val stopped = AtomicInteger(0)

        LocalListenerLauncher(LocalListenerGate.Surface.METRICS, awaitDecision = { true }, waitMs = 20)
            .launch { LocalListenerGate.registerBound(LocalListenerGate.Surface.METRICS) { stopped.incrementAndGet() } }
            .join(JOIN_TIMEOUT_MS)
        assertEquals(0, stopped.get(), "回撤只能在托管模式落定后发生")

        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity())
        assertEquals(1, stopped.get(), "模式落定为托管后必须回撤已开的监听面")

        ControlPlaneRuntime.publish(ProbeMode.MANAGED, identity())
        assertEquals(1, stopped.get(), "回撤必须幂等，不得重复动作")
    }

    /** 独立模式下正常停止不会误触发回撤。 */
    @Test
    fun `独立模式下发布不触发回撤`() {
        val stopped = AtomicInteger(0)

        LocalListenerLauncher(LocalListenerGate.Surface.WEB, awaitDecision = { true }, waitMs = 20)
            .launch { LocalListenerGate.registerBound(LocalListenerGate.Surface.WEB) { stopped.incrementAndGet() } }
            .join(JOIN_TIMEOUT_MS)

        ControlPlaneRuntime.publish(ProbeMode.STANDALONE, null)
        assertEquals(0, stopped.get(), "独立模式发布不得回撤监听面")
    }

    /** 构造一个最小可用身份（仅用于触发 publish(MANAGED) 的回撤路径）。 */
    private fun identity(): ControlPlaneIdentity = ControlPlaneIdentity(
        schemaVersion = 1,
        provider = "e2e",
        endpoint = "ws://127.0.0.1:1/ws/plugin-bridge",
        instanceId = "e2e-fixture",
        credential = "unit-test-credential",
        capabilities = emptyList(),
        createdAt = "2026-01-01T00:00:00Z",
    )

    private companion object {
        /** 等后台线程跑完的上限：本测试的 launcher 最多等 20ms，5s 足够。 */
        const val JOIN_TIMEOUT_MS = 5_000L
    }
}
