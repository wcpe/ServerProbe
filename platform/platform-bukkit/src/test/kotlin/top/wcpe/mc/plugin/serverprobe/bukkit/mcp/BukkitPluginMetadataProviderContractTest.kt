package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpToolProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpToolProviderRegistry
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginClassResolver
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginClassResolverRegistry
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginMetadataProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginMetadataProviderRegistry
import java.io.File

/**
 * [BukkitPluginMetadataProvider] 的源码级契约测试(FR-15)。
 *
 * 仅做静态断言,不依赖真机 Bukkit 环境,也不触碰含 Bukkit 类型签名的方法反射
 * (compileOnly 的 Bukkit API 不进测试运行时,`getDeclaredMethod` 枚举签名会
 * NoClassDefFoundError,故沿用项目既有"读源文件断言"风格):
 * - 实现保持 [PluginMetadataProvider] 与 MCP 工具提供者契约;
 * - [PostConstruct] 阶段把元数据实现、类解析实现与工具面注册进 core 契约;
 * - 平台限定为 BUKKIT。
 */
class BukkitPluginMetadataProviderContractTest {

    /** 源文件路径(与既有契约测试同约定:以本测试文件所在目录为基准)。 */
    private val source: String = "src/main/kotlin/top/wcpe/mc/plugin/serverprobe/bukkit/mcp/BukkitPluginMetadataProvider.kt"

    @Test
    fun `Bukkit实现保持插件元数据与 MCP 工具提供者契约`() {
        assertTrue(PluginMetadataProvider::class.java.isAssignableFrom(BukkitPluginMetadataProvider::class.java))
        assertTrue(McpToolProvider::class.java.isAssignableFrom(BukkitPluginMetadataProvider::class.java))
        assertTrue(PluginClassResolver::class.java.isAssignableFrom(BukkitPluginMetadataProvider::class.java))
    }

    @Test
    fun `Bukkit实现注入的是 core 契约注册表而非具体实现`() {
        assertEquals(
            PluginMetadataProviderRegistry::class.java,
            BukkitPluginMetadataProvider::class.java.getDeclaredField("metadataRegistry").type,
        )
        assertEquals(
            PluginClassResolverRegistry::class.java,
            BukkitPluginMetadataProvider::class.java.getDeclaredField("classResolverRegistry").type,
        )
        assertEquals(
            McpToolProviderRegistry::class.java,
            BukkitPluginMetadataProvider::class.java.getDeclaredField("mcpToolProviderRegistry").type,
        )
    }

    @Test
    fun `源文件声明平台限定与生命周期注册`() {
        val text = File(source).readText()
        assertTrue(text.contains("@Service"), "应声明为 IOC Service")
        assertTrue(text.contains("@PlatformSide(Platform.BUKKIT)"), "应仅限 Bukkit 平台生效")
        assertTrue(text.contains("@PostConstruct"), "应在依赖注入完成后自注册")
        assertTrue(text.contains("@PreDestroy"), "应在卸载时撤销注册")
        assertTrue(text.contains("metadataRegistry.register(this)"), "应注册元数据实现")
        assertTrue(text.contains("classResolverRegistry.register(this)"), "应注册类解析实现")
        assertTrue(text.contains("mcpToolProviderRegistry.register(this)"), "应注册 MCP 工具面")
        assertTrue(text.contains("JavaPlugin::class.java.getDeclaredField(\"file\")"), "应经插件实例解析 jar 而非字符串拼路径")
    }
}
