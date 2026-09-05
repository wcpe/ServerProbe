package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 多版本矩阵冒烟场景的合同约束。
 *
 * 矩阵在 Paper 1.8.8–1.21.1 与 Spigot 1.8.8/1.16.5 上复用 `matrix-` 前缀场景做真实冒烟；
 * harness 必须能在旧版本服务器(缺少 ServerLoadEvent 与 Folia 调度 API)上被反射加载,
 * 否则整个矩阵对旧版本不可用。
 */
class E2eMatrixSmokeContractTest {

    private val harnessSource: String
        get() = File(
            "../../e2e/harness/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/e2e/ServerProbeE2eHarnessPlugin.kt",
        ).readText()

    @Test
    fun `matrix 前缀场景映射为 FR8 只读冒烟验收`() {
        assertTrue(harnessSource.contains("matrix-"))
        assertTrue(harnessSource.contains("MATRIX_SMOKE"))
    }

    @Test
    fun `主插件类不得在字段或方法签名中引用 Folia 专属类型`() {
        // 旧版本服务器反射 Bukkit 插件类时会急切解析成员类型;
        // io.papermc.paper.threadedregions.scheduler.ScheduledTask 只允许出现在嵌套匣类与注释之外的方法体里。
        val withoutComments = harnessSource
            .lineSequence()
            .filter { !it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(withoutComments.contains("class FoliaTaskBox"))
        assertTrue(
            !Regex("""private (var|val) folia\w*:\s*ScheduledTask""").containsMatchIn(withoutComments),
            "主类字段不得声明 ScheduledTask 类型",
        )
        assertTrue(
            !Regex("""\)\s*:\s*ScheduledTask""").containsMatchIn(withoutComments),
            "主类方法不得返回 ScheduledTask",
        )
        assertTrue(
            !Regex("""folia\w*:\s*ScheduledTask\??\s*=\s*null""").containsMatchIn(withoutComments),
            "主类不得直接持有 ScheduledTask 字段",
        )
    }

    @Test
    fun `ServerLoadEvent 监听按场景条件注册且旧版本类缺失时安全跳过`() {
        // org.bukkit.event.server.ServerLoadEvent 自 1.12 才存在;注册必须先确认场景需要且类可加载。
        assertTrue(harnessSource.contains("ServerLoadBridge"))
        assertTrue(harnessSource.contains("""Class.forName("org.bukkit.event.server.ServerLoadEvent""""))
    }

    @Test
    fun `harness 描述文件 api-version 不得高于矩阵最低支持版本`() {
        // 1.16.5-1.19.4 会拒绝 api-version 高于自身的插件；1.13 是 Bukkit 支持的最低 api-version。
        assertTrue(harnessSource.isNotEmpty())
        val descriptor = File(
            "../../e2e/harness/src/main/resources/plugin.yml",
        ).readText()
        assertTrue(descriptor.contains("api-version: '1.13'"))
        val fr11Descriptor = File(
            "../../e2e/harness-network-bukkit/src/main/resources/plugin.yml",
        ).readText()
        assertTrue(fr11Descriptor.contains("api-version: '1.13'"))
    }

    @Test
    fun `矩阵后端与场景在构建脚本中逐版本声明`() {
        val build = File("../../build-logic/src/main/kotlin/serverprobe.e2e-verification.gradle.kts").readText()
        val versions = listOf(
            "1.8.8", "1.12.2", "1.16.5", "1.17.1", "1.18.2", "1.19.4", "1.20.4", "1.21.1",
        )
        versions.forEach { version ->
            assertTrue(build.contains("""version = "$version""""), "构建脚本缺少矩阵版本 $version")
        }
        assertTrue(build.contains("""backend("matrix-spigot-1-8-8") {"""))
        assertTrue(build.contains("""backend("matrix-spigot-1-16-5") {"""))
        assertTrue(build.contains("""scenario("matrix-paper-1-8-8") {"""))
        assertTrue(build.contains("""scenario("matrix-paper-1-21-1") {"""))
    }
}
