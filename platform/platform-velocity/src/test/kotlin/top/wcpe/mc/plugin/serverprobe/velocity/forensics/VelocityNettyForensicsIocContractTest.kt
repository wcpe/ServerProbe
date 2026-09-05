package top.wcpe.mc.plugin.serverprobe.velocity.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class VelocityNettyForensicsIocContractTest {

    @Test
    fun `监听器仅注入 Velocity 取证生命周期契约`() {
        val field = VelocityNettyForensicsListener::class.java.getDeclaredField("forensics")

        assertEquals(
            "top.wcpe.mc.plugin.serverprobe.velocity.forensics.VelocityNettyForensicsLifecycle",
            field.type.name,
        )
    }

    /** Velocity modern forwarding 必须使用独立 Paper 后端，不能复用仅支持 Bungee 转发的 Spigot 场景。 */
    @Test
    fun `Velocity FR11 拓扑使用独立 Paper modern forwarding 后端`() {
        val build = File("../../build.gradle.kts").readText()

        assertTrue(build.contains("backend(\"paper-fr11-velocity\") {\n        platform = paper"))
        assertTrue(build.contains("routesTo(\"paper-fr11-velocity\")"))
        assertTrue(build.contains("scenario(\"fr11-network-forensics-velocity\") {\n        backend = \"paper-fr11-velocity\""))
    }

    @Test
    fun `关闭网络取证时不得注册流量来源或附着连接`() {
        val source = File(
            "src/main/kotlin/top/wcpe/mc/plugin/serverprobe/velocity/forensics/VelocityNettyForensicsService.kt",
        ).readText()
        val disabledGuard = source.indexOf("if (!ProbeConfig.networkForensicsEnabled()) return")

        assertTrue(disabledGuard >= 0)
        assertTrue(disabledGuard < source.indexOf("ReflectiveNettyForensicsRegistry("))
        assertTrue(disabledGuard < source.indexOf("for (player in server<ProxyServer>().allPlayers) attach(player)"))
        assertTrue(disabledGuard < source.indexOf("traffic.register(this)"))
    }

    @Test
    fun `Velocity 取证诊断仅受全局 DEBUG 开关控制`() {
        val source = File(
            "src/main/kotlin/top/wcpe/mc/plugin/serverprobe/velocity/forensics/VelocityNettyForensicsService.kt",
        ).readText()

        assertTrue(source.contains("import top.wcpe.mc.plugin.serverprobe.core.forensics.NettyForensicsDebugLogger"))
        assertTrue(source.contains("debug = NettyForensicsDebugLogger({ ProbeLogger.debugEnabled }, ProbeLogger::debug)"))
    }

    @Test
    fun `Velocity FR11 验收等待完整机器人生命周期并按白名单包过滤`() {
        val harness = File(
            "../../e2e/harness-network-velocity/src/main/java/top/wcpe/mc/plugin/serverprobe/e2e/" +
                "network/VelocityNetworkForensicsHarness.java",
        ).readText()
        val support = File(
            "../../e2e/network-harness-common/src/main/java/top/wcpe/mc/plugin/serverprobe/e2e/network/NetworkForensicsE2eSupport.java",
        ).readText()

        assertTrue(harness.contains("\"velocity.PluginMessagePacket\", \"serverprobe:test\""))
        assertTrue(harness.contains("9954, 9955, 75"))
        assertTrue(support.contains(".packetType(expectedPacketType)"))
        assertTrue(support.contains("expectedChannel.equals(record.getChannel())"))
    }
}
