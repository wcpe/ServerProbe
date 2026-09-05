package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BukkitNetworkForensicsE2eContractTest {

    @Test
    fun `FR11 分别声明 Spigot Paper 与 Folia 真实场景`() {
        val build = File("../../build.gradle.kts").readText()

        assertTrue(build.contains("backend(\"spigot-fr11\") {\n        platform = spigot"))
        assertTrue(build.contains("backend(\"paper-fr11\") {\n        platform = paper"))
        assertTrue(build.contains("backend(\"folia-fr11\") {\n        platform = folia"))
        assertTrue(build.contains("scenario(\"fr11-network-forensics-paper\") {\n        backend = \"paper-fr11\""))
        assertTrue(build.contains("scenario(\"fr11-network-forensics-folia\") {\n        backend = \"folia-fr11\""))
    }

    @Test
    fun `Folia 验收桩使用全局区域调度器而非 Bukkit 调度器`() {
        val harness = File(
            "../../e2e/harness-network-bukkit/src/main/java/top/wcpe/mc/plugin/serverprobe/e2e/network/BukkitNetworkForensicsHarness.java",
        ).readText()

        assertTrue(harness.contains("getGlobalRegionScheduler"))
        assertTrue(harness.contains("fr11-network-forensics-paper"))
        assertTrue(harness.contains("fr11-network-forensics-folia"))
    }

    @Test
    fun `验收桩描述文件必须声明 folia 支持标记以便在 Folia 加载`() {
        val descriptor = File(
            "../../e2e/harness-network-bukkit/src/main/resources/plugin.yml",
        ).readText()

        assertTrue(descriptor.contains("folia-supported: true"))
    }

    @Test
    fun `直连场景验收查询按白名单包类型与通道过滤，避免洪泛包挤出单页`() {
        val harness = File(
            "../../e2e/harness-network-bukkit/src/main/java/top/wcpe/mc/plugin/serverprobe/e2e/network/BukkitNetworkForensicsHarness.java",
        ).readText()

        assertTrue(harness.contains("\"bukkit.PacketPlayInCustomPayload\""))
        assertTrue(harness.contains("\"bukkit.ServerboundCustomPayloadPacket\""))
        assertTrue(harness.contains("\"serverprobe:test\""))
    }

    @Test
    fun `Folia 后端使用 Mojang 映射类名的专用白名单模板`() {
        val build = File("../../build.gradle.kts").readText()

        assertTrue(build.contains("backend(\"folia-fr11\") {\n        platform = folia"))
        assertTrue(build.contains("e2e/templates/fr11-folia"))

        val template = File("../../e2e/templates/fr11-folia/plugins/ServerProbe/config.yml").readText()

        assertTrue(template.contains("\"bukkit.ServerboundCustomPayloadPacket\""))
        assertTrue(template.contains("\"serverprobe:test\""))
    }

    @Test
    fun `验收结果写入 mc-testkit 契约的 status 键以便官方 ResultReader 判定`() {
        val support = File(
            "../../e2e/network-harness-common/src/main/java/top/wcpe/mc/plugin/serverprobe/e2e/network/NetworkForensicsE2eSupport.java",
        ).readText()

        assertTrue(support.contains("setProperty(\"status\""))
    }
}
