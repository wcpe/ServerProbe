package top.wcpe.mc.plugin.serverprobe.bungee.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BungeeNettyForensicsIocContractTest {

    @Test
    fun `监听器仅注入 BungeeCord 取证生命周期契约`() {
        val field = BungeeNettyForensicsListener::class.java.getDeclaredField("forensics")

        assertEquals(
            "top.wcpe.mc.plugin.serverprobe.bungee.forensics.BungeeNettyForensicsLifecycle",
            field.type.name,
        )
    }

    /** 玩家已接入后端时再附着，避免 PostLogin 的初始管线在桥接阶段被替换。 */
    @Test
    fun `监听器在后端连接完成后附着网络取证`() {
        val source = File("src/main/kotlin/top/wcpe/mc/plugin/serverprobe/bungee/forensics/BungeeNettyForensicsListener.kt")
            .readText()

        assertTrue(source.contains("import net.md_5.bungee.api.event.ServerConnectedEvent"))
        assertTrue(source.contains("fun onServerConnected(event: ServerConnectedEvent)"))
        assertFalse(source.contains("PostLoginEvent"))
    }

    /** Bungee 的 FR11 机器人需要完成一次真实重连，验收窗口必须覆盖第二条连接的完整载荷。 */
    @Test
    fun `Bungee FR11 验收窗口覆盖重连后的完整流量`() {
        val harnessPath = "../../e2e/harness-network-bungee/src/main/java/top/wcpe/mc/plugin/serverprobe/" +
            "e2e/network/BungeeNetworkForensicsHarness.java"
        val source = File(harnessPath)
            .readText()

        assertTrue(source.contains("9952, 9953, 75"))
    }

    /** 后端通用 E2E harness 必须识别代理 FR11 场景，不能因未知场景在加载期失效。 */
    @Test
    fun `通用 E2E harness 识别 Bungee FR11 场景`() {
        val source = File("../../e2e/harness/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/e2e/ServerProbeE2eHarnessPlugin.kt")
            .readText()

        assertTrue(source.contains("FR11_NETWORK_FORENSICS_BUNGEE(\"fr11-network-forensics-bungee\")"))
    }
}
