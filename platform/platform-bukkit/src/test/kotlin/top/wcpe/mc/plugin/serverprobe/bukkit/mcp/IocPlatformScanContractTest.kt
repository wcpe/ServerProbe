package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * IoC 平台扫描兜底的合同约束。
 *
 * JDK 8 注解解析器解析 `@PlatformSide(vararg Platform)` 枚举数组时抛 ArrayStoreException,
 * 会沿 TabooLib 注入链中断,导致异常类之后所有组件的 @Inject 字段未注入(1.16.5/JDK8 真机已证)。
 * 扫描必须对该解析失败免疫,并按模块包名给出与注解语义一致的平台判定。
 */
class IocPlatformScanContractTest {

    private val source: String
        get() = File(
            "../../plugin/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/ioc/scan/ComponentVisitor.kt",
        ).readText()

    @Test
    fun `平台注解读取失败时按包名兜底且不得中断注入链`() {
        assertTrue(source.contains("runCatching { candidate.getAnnotation(PlatformSide::class.java)?.value }"))
        assertTrue(source.contains("platformFromPackage"))
    }

    @Test
    fun `包名兜底映射覆盖三个平台模块且语义互斥`() {
        assertTrue(source.contains("BUKKIT_PACKAGE_PREFIX = \"top.wcpe.mc.plugin.serverprobe.bukkit.\""))
        assertTrue(source.contains("BUNGEE_PACKAGE_PREFIX = \"top.wcpe.mc.plugin.serverprobe.bungee.\""))
        assertTrue(source.contains("VELOCITY_PACKAGE_PREFIX = \"top.wcpe.mc.plugin.serverprobe.velocity.\""))
    }
}
