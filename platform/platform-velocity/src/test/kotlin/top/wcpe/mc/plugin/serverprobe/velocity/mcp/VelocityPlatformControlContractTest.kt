package top.wcpe.mc.plugin.serverprobe.velocity.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformCommandRequest
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControl
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControlRegistration
import java.util.concurrent.CompletableFuture

/** Velocity 控制台适配器的最小 API 契约。 */
class VelocityPlatformControlContractTest {

    @Test
    fun `Velocity适配器保持平台控制接口和注册接口`() {
        assertTrue(PlatformControl::class.java.isAssignableFrom(VelocityPlatformControl::class.java))
        assertEquals(PlatformControlRegistration::class.java, VelocityPlatformControl::class.java.getDeclaredField("registry").type)
        val execute = VelocityPlatformControl::class.java.getMethod("execute", PlatformCommandRequest::class.java)
        assertEquals(CompletableFuture::class.java, execute.returnType)
    }
}
