package top.wcpe.mc.plugin.serverprobe.core.cpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.net.URLClassLoader

/**
 * [PluginClassLoaderRegistry] 归并逻辑单测(FR2.6)。
 *
 * 用临时 [URLClassLoader] 模拟插件 ClassLoader:注册后应能解析其可加载类的归属,
 * 不可加载的类归 null,且"无归属"结果被缓存(二次查询不再遍历)。
 */
class PluginClassLoaderRegistryTest {

    @Test
    fun `已注册的类可归并到插件`() {
        val registry = PluginClassLoaderRegistry()
        val loader = URLClassLoader(arrayOf<URL>(), javaClass.classLoader)
        registry.register("TestPlugin", loader)

        // javaClass.classLoader 可加载本测试类(JDK 引导类加载器可见链),故 TestPlugin 也能加载
        val owner = registry.ownerOf(PluginClassLoaderRegistryTest::class.java.name)
        assertEquals("TestPlugin", owner)
    }

    @Test
    fun `无法加载的类归 null`() {
        val registry = PluginClassLoaderRegistry()
        val loader = URLClassLoader(arrayOf<URL>(), javaClass.classLoader)
        registry.register("TestPlugin", loader)

        assertNull(registry.ownerOf("com.nonexistent.UnknownClass"))
        // 无归属结果应被缓存:再次查询仍为 null
        assertNull(registry.ownerOf("com.nonexistent.UnknownClass"))
    }

    @Test
    fun `未注册任何插件时全部归 null`() {
        val registry = PluginClassLoaderRegistry()
        assertNull(registry.ownerOf("java.lang.String"))
    }

    @Test
    fun `注销后类归属被清空`() {
        val registry = PluginClassLoaderRegistry()
        val loader = URLClassLoader(arrayOf<URL>(), javaClass.classLoader)
        registry.register("TestPlugin", loader)
        val className = PluginClassLoaderRegistryTest::class.java.name
        assertEquals("TestPlugin", registry.ownerOf(className))

        registry.unregister("TestPlugin")
        // 注销清空缓存,类不再归属任何插件
        assertNull(registry.ownerOf(className))
    }

    @Test
    fun `插件名集合正确`() {
        val registry = PluginClassLoaderRegistry()
        val loader = URLClassLoader(arrayOf<URL>(), javaClass.classLoader)
        registry.register("A", loader)
        registry.register("B", loader)
        assertEquals(setOf("A", "B"), registry.pluginNames())
        assertTrue(registry.pluginNames().isNotEmpty())
    }
}
