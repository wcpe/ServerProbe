package top.wcpe.mc.plugin.serverprobe.integration.allininventorysync

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/** AllinInventorySync API 缺席时的类加载回归测试。 */
class OptionalPluginClasspathTest {

    /** 生命周期监听器只依赖刷新与注销所需的最窄接口，便于 IoC 静态解析。 */
    @Test
    fun `生命周期监听器注入背包 Provider 生命周期接口`() {
        val field = AllinInventorySyncLifecycleListener::class.java.getDeclaredField("provider")

        assertEquals(InventoryProviderLifecycle::class.java, field.type)
        assertTrue(InventoryProviderLifecycle::class.java.isAssignableFrom(InventoryProvider::class.java))
    }

    /** 无 AllinInventorySync API 时，软依赖相关类仍应允许 IoC 读取方法列表。 */
    @Test
    fun `缺少 AllinInventorySync API 时软依赖类可被反射扫描`() {
        val urls = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { it.contains("allininventorysync-api", ignoreCase = true) }
            .map { File(it).toURI().toURL() }
            .toTypedArray()

        URLClassLoader(urls, null).use { loader ->
            val classes = listOf(
                "top.wcpe.mc.plugin.serverprobe.integration.allininventorysync.InventoryProvider",
                "top.wcpe.mc.plugin.serverprobe.integration.allininventorysync.BukkitInventoryEventListener",
                "top.wcpe.mc.plugin.serverprobe.integration.allininventorysync.AllinInventorySyncLifecycleListener",
            )
            for (className in classes) {
                val type = Class.forName(className, false, loader)
                assertDoesNotThrow({ type.declaredMethods.toList() }, "$className 不应暴露缺席的可选 API 方法描述符")
            }
        }
    }
}
