package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformCommandRequest
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControl
import top.wcpe.mc.plugin.serverprobe.core.mcp.PlatformControlRegistration
import java.util.concurrent.CompletableFuture

/** Bukkit、Paper 与 Folia 共用控制台适配器的最小 API 契约。 */
class BukkitPlatformControlContractTest {

    @Test
    fun `Bukkit适配器保持平台控制接口和注册接口`() {
        assertTrue(PlatformControl::class.java.isAssignableFrom(BukkitPlatformControl::class.java))
        val registryType = BukkitPlatformControl::class.java.getDeclaredField("registry").type
        assertEquals(PlatformControlRegistration::class.java, registryType)
        val execute = BukkitPlatformControl::class.java.getMethod("execute", PlatformCommandRequest::class.java)
        assertEquals(CompletableFuture::class.java, execute.returnType)
    }
}
