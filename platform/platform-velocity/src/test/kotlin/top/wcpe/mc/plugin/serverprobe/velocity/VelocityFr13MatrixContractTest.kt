package top.wcpe.mc.plugin.serverprobe.velocity

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class VelocityFr13MatrixContractTest {

    @Test
    fun `FR13 声明三个 Velocity 版本的独立双 Paper 矩阵`() {
        val build = File("../../build-logic/src/main/kotlin/serverprobe.e2e-verification.gradle.kts").readText().replace("\r\n", "\n")

        assertMatrix(build, "v31", "3.1.1", null)
        assertMatrix(build, "v35", "3.5.1", null)
        assertMatrix(build, "v41", "4.1.0", 25)
        assertTrue(build.contains("backend(\"paper-velocity-matrix-v31-a\") {\n        platform = paper\n        version = \"1.18.1\""))
        assertTrue(build.contains("backend(\"paper-velocity-matrix-v31-b\") {\n        platform = paper\n        version = \"1.18.1\""))
    }

    @Test
    fun `FR13 代理验收桩同时验证 RTT 路由和两名玩家延迟`() {
        val harness = File(
            "../../e2e/harness-velocity-matrix/src/main/java/top/wcpe/mc/plugin/serverprobe/e2e/velocity/VelocityMatrixHarness.java",
        ).readText()

        assertTrue(harness.contains("ServerProbeApi.INSTANCE.read()"))
        assertTrue(harness.contains("getBackends()"))
        assertTrue(harness.contains("getPlayerRoutes()"))
        assertTrue(harness.contains("getPlayerPings()"))
        assertTrue(harness.contains("MC_TESTKIT_E2E_RESULT_FILE"))
    }

    @Test
    fun `FR13 协议机器人只有切服角色发送服务器切换命令`() {
        val driver = File("../../e2e-bot/src/velocityMatrix.js").readText()

        assertTrue(driver.contains("resolveMatrixSwitchTarget"))
        assertTrue(driver.contains("switcher"))
    }

    private fun assertMatrix(build: String, suffix: String, version: String, javaVersion: Int?) {
        assertTrue(build.contains("backend(\"paper-velocity-matrix-$suffix-a\")"))
        assertTrue(build.contains("backend(\"paper-velocity-matrix-$suffix-b\")"))
        assertTrue(build.contains("proxy(\"velocity-matrix-$suffix\")"))
        assertTrue(build.contains("version = \"$version\""))
        assertTrue(build.contains("scenario(\"velocity-matrix-$suffix\")"))
        assertTrue(build.contains("backends(\"paper-velocity-matrix-$suffix-a\", \"paper-velocity-matrix-$suffix-b\")"))
        assertTrue(build.contains("via = \"velocity-matrix-$suffix\""))
        assertTrue(build.contains("bot(\"switcher\")"))
        assertTrue(build.contains("bot(\"observer\")"))
        if (javaVersion != null) assertTrue(build.contains("javaVersion = $javaVersion"))
    }
}
